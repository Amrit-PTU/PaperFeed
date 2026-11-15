package com.paperfeed.data

import com.paperfeed.net.Fetchers
import com.paperfeed.net.Text
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

class Repository(
    private val db: AppDatabase,
    val settings: SettingsStore,
) {
    val fetchers = Fetchers(contactEmail = { cachedEmail }, elsevierKey = { cachedElsevierKey })
    @Volatile private var cachedEmail: String? = null
    @Volatile private var cachedElsevierKey: String? = null

    private suspend fun loadApiSettings() {
        val s = settings.current()
        cachedEmail = s.contactEmail
        cachedElsevierKey = s.elsevierApiKey
    }

    private val articles get() = db.articles()
    private val sourcesDao get() = db.sources()
    private val listsDao get() = db.lists()
    private val notesDao get() = db.notes()
    private val refreshLock = Mutex()

    data class RefreshResult(val newForNotification: List<Article>, val totalNew: Int, val errors: List<String>)

    // ---------------- Feed ----------------

    fun feed(sourceId: Long?): Flow<List<Article>> = articles.feed(sourceId, 600)

    // ---------------- Endless feed (going back in time) ----------------

    /** Sources that returned nothing older: their archive is fully loaded (or unavailable). */
    private val exhausted = MutableStateFlow<Set<Long>>(emptySet())
    private val paging = java.util.concurrent.ConcurrentHashMap<Long, Fetchers.Paging>()
    val loadingOlder = MutableStateFlow(false)

    /**
     * The date down to which every (pageable) source is loaded. Showing only articles newer than
     * this keeps the merged feed strictly chronological with no gaps between journals.
     */
    private fun watermark(sourceId: Long?): Flow<Long> =
        combine(sourcesDao.observeAll(), articles.observeCoverage(), exhausted) { sources, coverage, done ->
            val oldest = coverage.associate { it.sourceId to it.oldest }
            sources.filter { s ->
                s.enabled && (sourceId == null || s.id == sourceId) &&
                    fetchers.canPageBack(s) && s.id !in done && oldest.containsKey(s.id)
            }.maxOfOrNull { oldest.getValue(it.id) } ?: 0L
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun endlessFeed(sourceId: Long?): Flow<List<Article>> =
        watermark(sourceId).flatMapLatest { since -> articles.feedSince(sourceId, since) }

    /**
     * Loads the next older page for the source(s) that limit how far back the feed reaches.
     * Returns the number of new articles, or -1 when every source's archive is exhausted.
     */
    suspend fun loadOlder(sourceId: Long?): Int {
        if (!loadingOlder.compareAndSet(false, true)) return 0
        try {
            loadApiSettings()
            val done = exhausted.value
            val candidates = sourcesDao.all().filter {
                it.enabled && (sourceId == null || it.id == sourceId) && fetchers.canPageBack(it) && it.id !in done
            }
            val oldest = candidates.associate { it.id to (articles.oldestFor(it.id) ?: System.currentTimeMillis()) }
            val mark = oldest.values.maxOrNull() ?: return -1
            // Bottleneck sources: their loaded range ends at the watermark (within a day).
            val targets = candidates.filter { oldest.getValue(it.id) >= mark - 86_400_000L }
            val gate = Semaphore(4)
            val added = coroutineScope {
                targets.map { s ->
                    async {
                        gate.withPermit {
                            var before = oldest.getValue(s.id)
                            var inserted = 0
                            if (s.type == SourceType.CROSSREF || s.type == SourceType.RSS) {
                                // Journal archives: walk Crossref pages until something new turns up.
                                for (attempt in 0 until 6) {
                                    val result = runCatching { fetchers.crossrefOlder(s, before, paging[s.id]) }.getOrNull()
                                    if (result == null) { exhausted.value = exhausted.value + s.id; break }
                                    val (page, next) = result
                                    paging[s.id] = next
                                    inserted = articles.insertIgnore(page.distinctBy { it.key }).count { it != -1L }
                                    if (next.done) { exhausted.value = exhausted.value + s.id; break }
                                    if (inserted > 0) break
                                }
                                return@withPermit inserted
                            }
                            // Other sources: step back a day at a time if a page held only articles we already have.
                            for (attempt in 0 until 4) {
                                val page = runCatching { fetchers.fetchOlder(s, before) }.getOrNull()
                                if (page == null) { exhausted.value = exhausted.value + s.id; break }
                                val fresh = page.distinctBy { it.key }
                                inserted = articles.insertIgnore(fresh).count { it != -1L }
                                if (inserted > 0) break
                                val pageOldest = fresh.minOfOrNull { it.published }
                                if (fresh.isEmpty() || pageOldest == null) { exhausted.value = exhausted.value + s.id; break }
                                before = minOf(before, pageOldest) - 86_400_000L
                                if (attempt == 3) exhausted.value = exhausted.value + s.id
                            }
                            inserted
                        }
                    }
                }.awaitAll().sum()
            }
            return added
        } finally {
            loadingOlder.value = false
        }
    }

    // ---------------- Archive search ----------------

    data class SearchCursor(val crossref: String? = null, val crossrefDone: Boolean = false, val arxivStart: Int = 0, val arxivDone: Boolean = false)

    /** Searches all enabled journals (whole archive, via Crossref) and arXiv sources. */
    suspend fun search(
        query: String, field: Fetchers.SearchField, newest: Boolean, cursor: SearchCursor,
    ): Pair<List<Article>, SearchCursor> = coroutineScope {
        loadApiSettings()
        val sources = sourcesDao.all().filter { it.enabled }
        val issnMap = buildMap {
            sources.forEach { s -> fetchers.archiveIssns(s).forEach { put(it, s) } }
        }
        val journals = async {
            if (cursor.crossrefDone) Fetchers.Page(emptyList(), null)
            else fetchers.searchJournals(query, field, newest, issnMap, cursor.crossref)
        }
        val arxivSource = sources.firstOrNull { it.type == SourceType.ARXIV }
        val arxiv = async {
            if (cursor.arxivDone || arxivSource == null) emptyList()
            // arXiv throttles busy clients; never let it hold up the journal results.
            else withTimeoutOrNull(12_000) {
                runCatching { fetchers.searchArxiv(query, field, newest, arxivSource, cursor.arxivStart) }.getOrNull()
            }
        }
        val j = journals.await()
        val arxivResult = arxiv.await()
        val a = arxivResult.orEmpty()
        val merged = (j.items + a).distinctBy { it.key }.let { if (newest) it.sortedByDescending { x -> x.published } else it }
        merged to SearchCursor(
            crossref = j.next, crossrefDone = j.next == null,
            // A timed-out arXiv page (null) is retried with the next page instead of ending arXiv results.
            arxivStart = cursor.arxivStart + a.size,
            arxivDone = arxivSource == null || cursor.arxivDone || (arxivResult != null && a.isEmpty()),
        )
    }

    suspend fun authorPage(name: String, cursor: String?): Fetchers.Page {
        loadApiSettings()
        return fetchers.authorWorksPage(name, cursor)
    }
    fun article(key: String): Flow<Article?> = articles.observe(key)
    suspend fun getArticle(key: String): Article? = articles.get(key)

    suspend fun refresh(onlySource: Long? = null): RefreshResult = refreshLock.withLock {
        loadApiSettings()
        val now = System.currentTimeMillis()
        val recentCutoff = now - 14L * 86_400_000L
        val sources = sourcesDao.all().filter { it.enabled && (onlySource == null || it.id == onlySource) }
        val gate = Semaphore(4)
        val results = coroutineScope {
            sources.map { source ->
                async {
                    gate.withPermit {
                        runCatching { fetchers.fetch(source) }.fold(
                            onSuccess = { fetched ->
                                val unique = fetched.distinctBy { it.key }
                                val existing = unique.map { it.key }.chunked(500)
                                    .flatMap { articles.existingKeys(it) }.toSet()
                                val fresh = unique.filter { it.key !in existing }
                                articles.insertIgnore(fresh)
                                sourcesDao.setStatus(source.id, now, null)
                                // The first fetch of a source only fills the feed; don't flood notifications.
                                val notify = if (source.lastFetched == 0L || !source.notify) emptyList()
                                else fresh.filter { it.published >= recentCutoff }
                                Triple(fresh.size, notify, null as String?)
                            },
                            onFailure = { e ->
                                val msg = e.message ?: e.javaClass.simpleName
                                sourcesDao.setStatus(source.id, source.lastFetched, msg)
                                Triple(0, emptyList(), "${source.name}: $msg")
                            },
                        )
                    }
                }
            }.awaitAll()
        }
        articles.prune(now - 120L * 86_400_000L)
        RefreshResult(
            newForNotification = results.flatMap { it.second }.sortedByDescending { it.published },
            totalNew = results.sumOf { it.first },
            errors = results.mapNotNull { it.third },
        )
    }

    /** Fills missing/truncated abstracts. Returns the updated articles. */
    suspend fun enrichAbstracts(targets: List<Article>, budgetMs: Long = 60_000): List<Article> {
        val updated = mutableListOf<Article>()
        withTimeoutOrNull(budgetMs) {
            for (a in targets) {
                val found = runCatching { fetchers.findAbstract(a) }.getOrNull()
                if (found != null && found.length > (a.abstract?.length ?: 0)) {
                    articles.setAbstract(a.key, found)
                    updated += a.copy(abstract = found)
                } else {
                    articles.markAbstractAttempt(a.key)
                }
            }
        }
        return updated
    }

    suspend fun enrichPending(limit: Int = 25) = enrichAbstracts(articles.missingAbstracts(limit))

    /** Saves an abstract found by the user (e.g. extracted from the publisher's page). */
    suspend fun saveAbstract(key: String, abstract: String) {
        val text = abstract
            .replace(Regex("^\\s*(Abstract|ABSTRACT|Summary)\\s*[:.]?\\s*"), "")
            .replace(Regex("[ \\t\\u00A0]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (text.length > 40) articles.setAbstract(key, text)
    }

    suspend fun bibtex(article: Article): String? {
        loadApiSettings()
        return article.doi?.let { runCatching { fetchers.bibtex(it) }.getOrNull() }
    }

    suspend fun ensureAbstract(key: String) {
        val a = articles.get(key) ?: return
        val missing = a.abstract.isNullOrBlank() || a.abstract.endsWith("…")
        if (missing) {
            loadApiSettings()
            enrichAbstracts(listOf(a), budgetMs = 30_000)
        }
    }

    suspend fun markRead(key: String) = articles.markRead(key)
    suspend fun setRating(key: String, rating: Int) = articles.setRating(key, rating.coerceIn(0, 3))

    /** Saves an article not in the DB yet (e.g. from an online author search). */
    suspend fun ensureStored(article: Article) {
        if (articles.get(article.key) == null) articles.insertIgnore(listOf(article))
    }

    // ---------------- Authors ----------------

    suspend fun localByAuthor(name: String): List<Article> {
        val exact = articles.byAuthorExact(name)
        if (exact.isNotEmpty()) return exact
        val family = Text.familyName(name)
        val first = Text.foldName(name).firstOrNull()
        return articles.byAuthorFamily(name.substringAfterLast(' ')).filter { a ->
            a.authorList.any { Text.familyName(it) == family && Text.foldName(it).firstOrNull() == first }
        }
    }


    // ---------------- Notes ----------------

    fun notes(): Flow<List<NoteWithArticle>> = notesDao.observeAll()
    fun notesFor(key: String): Flow<List<Note>> = notesDao.observeFor(key)
    fun notesCount(): Flow<Int> = notesDao.observeCount()
    suspend fun note(id: Long): Note? = notesDao.get(id)
    suspend fun addNote(key: String, text: String): Long = notesDao.insert(Note(articleKey = key, text = text))
    suspend fun updateNote(id: Long, text: String) = notesDao.updateText(id, text)
    suspend fun deleteNote(id: Long) = notesDao.delete(id)

    // ---------------- Sources ----------------

    fun sources(): Flow<List<Source>> = sourcesDao.observeAll()
    suspend fun addSource(source: Source): Long = sourcesDao.insert(source)
    suspend fun updateSource(source: Source) = sourcesDao.update(source)
    suspend fun deleteSource(id: Long) {
        articles.deleteUnkeptFromSource(id)
        sourcesDao.delete(id)
    }
    suspend fun restoreDefaultSources() {
        val existing = sourcesDao.all().map { it.type to it.query.trim() }.toSet()
        sourcesDao.insertAll(DefaultSources.all.filter { (it.type to it.query) !in existing })
    }

    // ---------------- Lists ----------------

    fun listsWithCounts(): Flow<List<ListWithCount>> = listsDao.observeWithCounts()
    fun lists(): Flow<List<ArticleList>> = listsDao.observeAll()
    suspend fun allLists(): List<ArticleList> = listsDao.all()
    fun list(id: Long): Flow<ArticleList?> = listsDao.observe(id)
    fun listArticles(id: Long, byRating: Boolean): Flow<List<Article>> = listsDao.articlesIn(id, byRating)
    fun listIdsFor(key: String): Flow<List<Long>> = listsDao.observeListIdsFor(key)
    suspend fun notificationList(): ArticleList? = listsDao.notificationList()

    suspend fun createList(name: String): Long =
        listsDao.insert(ArticleList(name = name.trim(), position = listsDao.nextPosition()))

    suspend fun renameList(list: ArticleList, name: String) = listsDao.update(list.copy(name = name.trim()))
    suspend fun deleteList(id: Long) { if (id != ArticleList.READ_LATER_ID) listsDao.deleteList(id) }
    suspend fun setNotificationList(id: Long) = listsDao.setNotificationList(id)

    suspend fun moveList(list: ArticleList, up: Boolean) {
        val all = listsDao.all().toMutableList()
        val i = all.indexOfFirst { it.id == list.id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in all.indices) return
        java.util.Collections.swap(all, i, j)
        listsDao.upsertAll(all.mapIndexed { idx, l -> l.copy(position = idx) })
    }

    suspend fun addToList(listId: Long, key: String) = listsDao.addEntry(ListEntry(listId, key))
    suspend fun removeFromList(listId: Long, key: String) = listsDao.removeEntry(listId, key)
    suspend fun toggleInList(listId: Long, key: String) {
        if (listId in listsDao.listIdsFor(key)) removeFromList(listId, key) else addToList(listId, key)
    }

    // ---------------- Backup ----------------

    suspend fun snapshot(): BackupData = BackupData(
        articles = articles.kept(),
        lists = listsDao.all(),
        entries = listsDao.allEntries(),
        sources = sourcesDao.all(),
        settings = settings.current(),
        notes = notesDao.all(),
    )

    suspend fun restore(data: BackupData) {
        articles.upsertAll(data.articles)
        listsDao.clearAllEntries()
        listsDao.clearUserLists()
        listsDao.upsertAll(data.lists)
        listsDao.addEntries(data.entries)
        notesDao.upsertAll(data.notes)
        if (data.sources.isNotEmpty()) {
            sourcesDao.clear()
            sourcesDao.insertAll(data.sources)
        }
        data.settings?.let { s ->
            settings.update { cur ->
                cur.copy(
                    themeId = s.themeId, customTheme = s.customTheme, refreshMinutes = s.refreshMinutes,
                    notificationsEnabled = s.notificationsEnabled, contactEmail = s.contactEmail,
                    textScale = s.textScale, feedScale = s.feedScale, readerScale = s.readerScale,
                    feedStyle = s.feedStyle, previewLines = s.previewLines, elsevierApiKey = s.elsevierApiKey,
                )
            }
        }
    }
}

data class BackupData(
    val articles: List<Article>,
    val lists: List<ArticleList>,
    val entries: List<ListEntry>,
    val sources: List<Source>,
    val settings: AppSettings?,
    val notes: List<Note> = emptyList(),
)
