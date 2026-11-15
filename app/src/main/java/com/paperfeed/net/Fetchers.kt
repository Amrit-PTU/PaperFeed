package com.paperfeed.net

import com.paperfeed.data.Article
import com.paperfeed.data.Source
import com.paperfeed.data.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/** Turns a configured [Source] into a list of articles. */
class Fetchers(
    private val contactEmail: () -> String?,
    private val elsevierKey: () -> String? = { null },
) {

    suspend fun fetch(source: Source): List<Article> = when (source.type) {
        SourceType.CROSSREF -> crossrefJournal(source)
        SourceType.RSS -> FeedParser.parse(Http.get(source.query.trim()), source.id, source.name)
        SourceType.ARXIV -> arxiv(source)
        SourceType.OPENALEX -> openAlexSearch(source)
        SourceType.SCHOLAR -> Scholar.search(source)
    }

    private fun mailto(prefix: Char = '&'): String =
        contactEmail()?.takeIf { it.contains('@') }?.let { "${prefix}mailto=${Http.enc(it)}" } ?: ""

    // ---------------- Crossref ----------------

    private val crossrefSelect =
        "DOI,title,author,created,published,issued,container-title,abstract,volume,issue,page,URL,type"

    private val junkTitle = Regex(
        "^(editorial board|issue editorial masthead|contents|table of contents|graphical abstract|" +
            "copyright|cover|front matter|back matter|masthead|information for authors)",
        RegexOption.IGNORE_CASE,
    )

    private suspend fun crossrefJournal(source: Source): List<Article> {
        val issn = source.query.trim()
        val url = "https://api.crossref.org/journals/${Http.enc(issn)}/works?sort=created&order=desc&rows=40" +
            "&filter=type:journal-article&select=$crossrefSelect" + mailto()
        val items = JSONObject(Http.get(url)).getJSONObject("message").getJSONArray("items")
        return (0 until items.length()).mapNotNull { crossrefItem(items.getJSONObject(it), source.id, source.name) }
    }

    fun crossrefItem(o: JSONObject, sourceId: Long, fallbackJournal: String): Article? {
        val title = Text.cleanTitle(o.optJSONArray("title")?.optString(0))
        if (title.isBlank() || junkTitle.containsMatchIn(title)) return null
        val doi = o.optString("DOI").takeIf { it.isNotBlank() }?.let(Text::normalizeDoi)
        val authors = o.optJSONArray("author")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.getJSONObject(i)
                val name = listOf(a.optString("given"), a.optString("family"))
                    .filter { it.isNotBlank() }.joinToString(" ")
                name.ifBlank { a.optString("name").ifBlank { null } }
            }
        }.orEmpty()
        val created = o.optJSONObject("created")?.optString("date-time")?.let(Text::parseDate)
        val issued = datePartsToMillis(o.optJSONObject("published") ?: o.optJSONObject("issued"))
        // The earlier of "registered with Crossref" and "published": new papers appear online before
        // their issue date, while back-catalogue papers were registered years after publication.
        val published = listOfNotNull(created, issued).minOrNull() ?: System.currentTimeMillis()
        return Article(
            key = Text.keyFor(doi, o.optString("URL"), title),
            title = title,
            authors = authors.joinToString("\n"),
            journal = o.optJSONArray("container-title")?.optString(0)?.ifBlank { null } ?: fallbackJournal,
            sourceId = sourceId,
            doi = doi,
            url = o.optString("URL").ifBlank { null },
            abstract = Text.clean(o.optString("abstract").ifBlank { null }),
            published = published,
            // Online year: a paper released in 2026 for a 2027 issue reads as 2026.
            year = Text.yearOf(published),
            volume = o.optString("volume").ifBlank { null },
            issue = o.optString("issue").ifBlank { null },
            pages = o.optString("page").ifBlank { null },
        )
    }

    private fun datePartsToMillis(o: JSONObject?): Long? {
        val parts = o?.optJSONArray("date-parts")?.optJSONArray(0) ?: return null
        val y = parts.optInt(0).takeIf { it > 0 } ?: return null
        val m = parts.optInt(1, 1).coerceIn(1, 12)
        val d = parts.optInt(2, 1).coerceIn(1, 28)
        return LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    // ---------------- arXiv ----------------

    private suspend fun arxiv(source: Source): List<Article> {
        val q = source.query.trim().let { if (it.contains(':')) it else "all:$it" }
        val url = "https://export.arxiv.org/api/query?search_query=${Http.enc(q)}" +
            "&sortBy=submittedDate&sortOrder=descending&max_results=50"
        return FeedParser.parse(Http.get(url), source.id, "arXiv")
    }

    // ---------------- OpenAlex ----------------

    private val openAlexSelect =
        "doi,display_name,publication_date,authorships,primary_location,abstract_inverted_index,biblio,id"

    private suspend fun openAlexSearch(source: Source): List<Article> {
        val url = "https://api.openalex.org/works?search=${Http.enc(source.query.trim())}" +
            "&sort=publication_date:desc&per-page=40&select=$openAlexSelect" + mailto()
        return openAlexList(Http.get(url), source.id)
    }

    fun openAlexList(json: String, sourceId: Long): List<Article> {
        val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { openAlexItem(results.getJSONObject(it), sourceId) }
    }

    private fun openAlexItem(o: JSONObject, sourceId: Long): Article? {
        val title = Text.cleanTitle(o.optString("display_name"))
        if (title.isBlank()) return null
        val doi = o.optString("doi").takeIf { it.isNotBlank() && it != "null" }?.let(Text::normalizeDoi)
        val authors = o.optJSONArray("authorships")?.let { arr ->
            (0 until arr.length()).mapNotNull {
                arr.getJSONObject(it).optJSONObject("author")?.optString("display_name")?.ifBlank { null }
            }
        }.orEmpty()
        val published = Text.parseDate(o.optString("publication_date")) ?: System.currentTimeMillis()
        val loc = o.optJSONObject("primary_location")
        val journal = loc?.optJSONObject("source")?.optString("display_name")?.ifBlank { null } ?: "OpenAlex"
        val landing = loc?.optString("landing_page_url")?.takeIf { it.isNotBlank() && it != "null" }
        val biblio = o.optJSONObject("biblio")
        return Article(
            key = Text.keyFor(doi, landing ?: o.optString("id"), title),
            title = title,
            authors = authors.joinToString("\n"),
            journal = journal,
            sourceId = sourceId,
            doi = doi,
            url = landing ?: o.optString("id"),
            abstract = invertedAbstract(o.optJSONObject("abstract_inverted_index")),
            published = published,
            year = Text.yearOf(published),
            volume = biblio?.optString("volume")?.takeIf { it.isNotBlank() && it != "null" },
            issue = biblio?.optString("issue")?.takeIf { it.isNotBlank() && it != "null" },
            pages = biblio?.optString("first_page")?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    fun invertedAbstract(index: JSONObject?): String? {
        if (index == null || index.length() == 0) return null
        val words = sortedMapOf<Int, String>()
        index.keys().forEach { word ->
            val positions = index.getJSONArray(word)
            for (i in 0 until positions.length()) words[positions.getInt(i)] = word
        }
        return Text.clean(words.values.joinToString(" "))
    }

    // ---------------- Abstract enrichment ----------------

    /** Best-effort lookup of a missing (or truncated) abstract. */
    suspend fun findAbstract(article: Article): String? {
        val doi = article.doi
        val key = elsevierKey()?.trim()?.ifBlank { null }
        if (doi != null && key != null && doi.startsWith("10.1016/")) {
            // Elsevier's free API key gives the abstract that its RSS/Crossref records leave out.
            runCatching {
                val o = JSONObject(
                    Http.get(
                        "https://api.elsevier.com/content/article/doi/$doi?httpAccept=application/json",
                        headers = mapOf("X-ELS-APIKey" to key),
                    )
                )
                val core = o.getJSONObject("full-text-retrieval-response").getJSONObject("coredata")
                Text.clean(core.optString("dc:description").ifBlank { null })
            }.getOrNull()?.let { return it }
        }
        if (doi != null) {
            runCatching {
                val o = JSONObject(Http.get("https://api.openalex.org/works/doi:${doi}?select=abstract_inverted_index" + mailto()))
                invertedAbstract(o.optJSONObject("abstract_inverted_index"))
            }.getOrNull()?.let { return it }
            runCatching {
                val o = JSONObject(Http.get("https://api.crossref.org/works/${Http.enc(doi)}" + mailto('?')))
                Text.clean(o.getJSONObject("message").optString("abstract").ifBlank { null })
            }.getOrNull()?.let { return it }
            runCatching {
                val o = JSONObject(Http.get("https://api.semanticscholar.org/graph/v1/paper/DOI:$doi?fields=abstract"))
                Text.clean(o.optString("abstract").takeIf { it.isNotBlank() && it != "null" })
            }.getOrNull()?.let { return it }
        }
        arxivByTitle(article.title)?.let { return it }
        // Fall back to a title match, which often finds the arXiv preprint.
        return runCatching {
            val o = JSONObject(
                Http.get("https://api.semanticscholar.org/graph/v1/paper/search/match?query=${Http.enc(article.title)}&fields=title,abstract")
            )
            val hit = o.optJSONArray("data")?.optJSONObject(0) ?: return@runCatching null
            val same = Text.foldName(hit.optString("title")) == Text.foldName(article.title)
            if (same) Text.clean(hit.optString("abstract").takeIf { it.isNotBlank() && it != "null" }) else null
        }.getOrNull()
    }

    /** Many journal papers have an arXiv preprint with the same title and an abstract. */
    private suspend fun arxivByTitle(title: String): String? = runCatching {
        val words = Regex("[A-Za-z]{4,}").findAll(title).map { it.value }.take(8).toList()
        if (words.size < 2) return@runCatching null
        val q = words.joinToString(" AND ") { "ti:$it" }
        val entries = FeedParser.parse(
            Http.get("https://export.arxiv.org/api/query?search_query=${Http.enc(q)}&max_results=5"), -1, "arXiv",
        )
        val target = Text.foldName(title).replace(" ", "")
        entries.firstOrNull { Text.foldName(it.title).replace(" ", "") == target }?.abstract
    }.getOrNull()

    /** BibTeX straight from the DOI registry (content negotiation), for Zotero / reference managers. */
    suspend fun bibtex(doi: String): String =
        Http.get("https://doi.org/$doi", headers = mapOf("Accept" to "application/x-bibtex; charset=utf-8")).trim()

    // ---------------- Author search ----------------

    /**
     * Finds an author's works on OpenAlex: resolves the name to the best matching author
     * profile (most works with the same family name) and lists that profile's works.
     */
    private val authorFilterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** OpenAlex filter selecting the best-matching author profile, or a raw byline match. */
    private suspend fun authorFilter(name: String): String = authorFilterCache.getOrPut(name) {
        val family = Text.familyName(name)
        val authors = JSONObject(
            Http.get("https://api.openalex.org/authors?search=${Http.enc(name)}&per-page=10&select=id,display_name,works_count" + mailto())
        ).optJSONArray("results") ?: JSONArray()
        val best = (0 until authors.length()).map { authors.getJSONObject(it) }
            .filter { Text.familyName(it.optString("display_name")) == family }
            .maxByOrNull { it.optInt("works_count") }
        if (best != null) "author.id:" + best.optString("id").substringAfterLast('/')
        else "raw_author_name.search:" + Http.enc("\"$name\"")
    }

    /** One page of an author's works, newest first. Pass the returned cursor to continue. */
    suspend fun authorWorksPage(name: String, cursor: String?): Page {
        val url = "https://api.openalex.org/works?filter=${authorFilter(name)}&sort=publication_date:desc" +
            "&per-page=100&cursor=${Http.enc(cursor ?: "*")}&select=$openAlexSelect" + mailto()
        val json = Http.get(url)
        val next = JSONObject(json).optJSONObject("meta")?.optString("next_cursor")?.takeIf { it.isNotBlank() && it != "null" }
        return Page(openAlexList(json, sourceId = -1).distinctBy { it.key }, next)
    }

    data class Page(val items: List<Article>, val next: String?)

    // ---------------- Going back in time ----------------

    /** ISSNs used to page back through a source's archive (RSS feeds only carry recent items). */
    private val knownIssns = mapOf(
        "feeds.aps.org/rss/recent/prc.xml" to "2469-9985,0556-2813",
        "feeds.aps.org/rss/recent/rmp.xml" to "0034-6861",
        "feeds.aps.org/rss/recent/prl.xml" to "0031-9007",
        "iopscience.iop.org/journal/rss/1674-1137" to "1674-1137",
        "iopscience.iop.org/journal/rss/0954-3899" to "0954-3899",
    )
    private val issnLookupCache = java.util.concurrent.ConcurrentHashMap<Long, List<String>>()

    suspend fun archiveIssns(source: Source): List<String> = when (source.type) {
        SourceType.CROSSREF -> source.query.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        SourceType.RSS -> {
            val url = source.query.substringAfter("://")
            knownIssns.entries.firstOrNull { url.startsWith(it.key) }?.value?.split(',')
                ?: issnLookupCache.getOrPut(source.id) {
                    // Unknown feed: find the journal on Crossref by its name.
                    runCatching {
                        val items = JSONObject(
                            Http.get("https://api.crossref.org/journals?query=${Http.enc(source.name)}&rows=3" + mailto())
                        ).getJSONObject("message").getJSONArray("items")
                        (0 until items.length()).map { items.getJSONObject(it) }
                            .firstOrNull { Text.foldName(it.optString("title")) == Text.foldName(source.name) }
                            ?.optJSONArray("ISSN")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                            .orEmpty()
                    }.getOrDefault(emptyList())
                }
        }
        else -> emptyList()
    }

    fun canPageBack(source: Source) = source.type != SourceType.SCHOLAR

    private fun day(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /**
     * Where a journal's archive walk stands. Recent years are paged by Crossref registration date
     * (matches when papers appeared online); once we reach publishers' bulk back-catalogue
     * registrations (all stamped with one date, e.g. Elsevier in July 2002) we switch to paging by
     * publication date. Pages within one cutoff are walked with [offset].
     */
    data class Paging(val byPubDate: Boolean, val cutoff: String, val offset: Int, val done: Boolean = false)

    private val backCatalogue = java.time.LocalDate.of(2002, 8, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    suspend fun crossrefOlder(source: Source, before: Long, state: Paging?): Pair<List<Article>, Paging> {
        val st = state ?: Paging(byPubDate = before < backCatalogue, cutoff = day(before), offset = 0)
        val (filterKey, sortKey) = if (st.byPubDate) "until-pub-date" to "published" else "until-created-date" to "created"
        var backfile = false
        var anyItems = false
        val articles = archiveIssns(source).flatMap { issn ->
            val url = "https://api.crossref.org/journals/${Http.enc(issn)}/works?sort=$sortKey&order=desc&rows=40&offset=${st.offset}" +
                "&filter=type:journal-article,$filterKey:${st.cutoff}&select=$crossrefSelect" + mailto()
            val items = JSONObject(Http.get(url)).getJSONObject("message").getJSONArray("items")
            (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                anyItems = true
                val created = o.optJSONObject("created")?.optString("date-time")?.let(Text::parseDate)
                crossrefItem(o, source.id, source.name)?.also { a ->
                    // Registered over a year after publication: a bulk back-catalogue deposit.
                    if (created != null && created - a.published > 365L * 86_400_000L) backfile = true
                }
            }
        }
        // Reached the bulk back-catalogue registrations: this page mixes decades, so drop it and
        // continue by publication date from where the feed currently ends.
        if (!st.byPubDate && (!anyItems || backfile)) return emptyList<Article>() to Paging(true, day(before), 0)
        val oldest = articles.minOfOrNull { it.published } ?: before
        val next = when {
            !anyItems -> st.copy(done = true)
            // Crossref allows offsets up to 10,000; restart from a later cutoff before that.
            st.offset + 40 >= 9_960 -> Paging(st.byPubDate, day(oldest), 0)
            else -> st.copy(offset = st.offset + 40)
        }
        return articles to next
    }

    /** Articles of [source] released on or before [before] (newest first, one page). */
    suspend fun fetchOlder(source: Source, before: Long): List<Article> = when (source.type) {
        SourceType.CROSSREF, SourceType.RSS -> crossrefOlder(source, before, null).first
        SourceType.ARXIV -> {
            val stamp = java.time.Instant.ofEpochMilli(before).atZone(ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
            val base = source.query.trim().let { if (it.contains(':')) it else "all:$it" }
            val q = "($base) AND submittedDate:[199101010000 TO $stamp]"
            FeedParser.parse(
                Http.get("https://export.arxiv.org/api/query?search_query=${Http.enc(q)}&sortBy=submittedDate&sortOrder=descending&max_results=50"),
                source.id, "arXiv",
            )
        }
        SourceType.OPENALEX -> openAlexList(
            Http.get(
                "https://api.openalex.org/works?search=${Http.enc(source.query.trim())}&filter=to_publication_date:${day(before)}" +
                    "&sort=publication_date:desc&per-page=40&select=$openAlexSelect" + mailto()
            ),
            source.id,
        )
        SourceType.SCHOLAR -> emptyList()
    }

    // ---------------- Search across the whole archive ----------------

    enum class SearchField { ANY, AUTHOR }

    /**
     * Searches every journal in [issnToSource] at once through Crossref (all years).
     * [newest] sorts by date instead of relevance. Use the returned cursor for the next page.
     */
    suspend fun searchJournals(
        query: String, field: SearchField, newest: Boolean,
        issnToSource: Map<String, Source>, cursor: String?,
    ): Page {
        if (issnToSource.isEmpty()) return Page(emptyList(), null)
        val param = if (field == SearchField.AUTHOR) "query.author" else "query.bibliographic"
        val filter = (issnToSource.keys.map { "issn:$it" } + "type:journal-article").joinToString(",")
        // Newest first sorts by publication date, which Crossref only pages with offsets (max 10,000);
        // best-match uses a deep-paging cursor.
        val offset = if (newest) cursor?.toIntOrNull() ?: 0 else 0
        val url = "https://api.crossref.org/works?$param=${Http.enc(query)}&filter=$filter&rows=30" +
            "&select=$crossrefSelect,ISSN" +
            (if (newest) "&sort=published&order=desc&offset=$offset" else "&cursor=${Http.enc(cursor ?: "*")}") + mailto()
        val msg = JSONObject(Http.get(url)).getJSONObject("message")
        val items = msg.getJSONArray("items")
        val list = (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val issns = o.optJSONArray("ISSN")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
            val src = issns.firstNotNullOfOrNull { issnToSource[it] }
            // sourceId -1: search hits live outside the feed so they don't distort its date coverage.
            crossrefItem(o, -1, src?.name ?: "")
        }
        val next = if (newest) (offset + 30).takeIf { items.length() == 30 && it < 10_000 }?.toString()
        else msg.optString("next-cursor").takeIf { it.isNotBlank() && items.length() > 0 }
        return Page(list, next)
    }

    /** arXiv part of a search; [start] is the result offset. */
    suspend fun searchArxiv(query: String, field: SearchField, newest: Boolean, source: Source, start: Int): List<Article> {
        val base = source.query.trim().let { if (it.contains(':')) it else "all:$it" }
        val terms = Regex("[\\p{L}\\p{N}-]{2,}").findAll(query).map { it.value }.toList()
        if (terms.isEmpty()) return emptyList()
        val q = when (field) {
            SearchField.AUTHOR -> terms.filter { it.length > 2 || it.none { c -> c == '.' } }.joinToString(" AND ") { "au:$it" }
            SearchField.ANY -> terms.joinToString(" AND ") { "all:$it" }
        }
        val sort = if (newest) "&sortBy=submittedDate&sortOrder=descending" else "&sortBy=relevance"
        return FeedParser.parse(
            Http.get("https://export.arxiv.org/api/query?search_query=${Http.enc("($base) AND ($q)")}$sort&start=$start&max_results=30"),
            -1, "arXiv",
        )
    }
}
