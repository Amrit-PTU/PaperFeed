package com.paperfeed.search

import android.content.Context
import com.paperfeed.data.AppDatabase
import com.paperfeed.data.Article
import com.paperfeed.data.ArticleVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Meaning search over papers. Every stored paper gets a vector in the background ([index]); a
 * search is embedded once and compared with the library and with fresh online results, and
 * both are ranked by one score so they can be merged into a single list.
 */
class SmartSearch(private val context: Context, private val db: AppDatabase) {
    private val dao get() = db.vectors()

    /** Vectors of every indexed paper, loaded once and kept up to date while indexing. */
    private val library = ConcurrentHashMap<String, FloatArray>()
    @Volatile private var loaded = false
    private val loadLock = Mutex()
    private val indexLock = Mutex()

    /** Vectors of online results, so the indexer can reuse them if the paper gets stored. */
    private val remote = ConcurrentHashMap<String, Pair<Boolean, FloatArray>>()

    val indexing = MutableStateFlow(false)
    val pending: Flow<Int> get() = dao.observePending()

    data class Hit(val article: Article, val score: Float, val inLibrary: Boolean)

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            dao.all().forEach { library[it.articleKey] = Embedder.fromBytes(it.vec) }
            loaded = true
        }
    }

    /** Embeds papers that have no vector yet (newest first). Returns how many were done. */
    suspend fun index(budgetMs: Long = 90_000): Int = withContext(Dispatchers.Default) {
        if (!indexLock.tryLock()) return@withContext 0
        var done = 0
        try {
            indexing.value = true
            ensureLoaded()
            dao.pruneOrphans()
            val emb = Embedder.get(context)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < budgetMs) {
                val batch = dao.needingVectors(24)
                if (batch.isEmpty()) break
                val rows = batch.map { s ->
                    val hasAbstract = !s.abstract.isNullOrBlank()
                    val v = remote[s.key]?.takeIf { it.first == hasAbstract }?.second
                        ?: emb.embed(Embedder.paperText(s.title, s.abstract), query = false)
                    library[s.key] = v
                    ArticleVector(s.key, Embedder.toBytes(v), hasAbstract)
                }
                dao.upsert(rows)
                done += rows.size
            }
        } finally {
            indexing.value = false
            indexLock.unlock()
        }
        done
    }

    suspend fun queryVector(query: String): FloatArray =
        withContext(Dispatchers.Default) { Embedder.get(context).embed(query, query = true) }

    /** Papers in the library closest in meaning to the search. */
    suspend fun searchLibrary(query: String, qv: FloatArray, limit: Int = 60): List<Hit> = withContext(Dispatchers.Default) {
        ensureLoaded()
        val nearest = library.entries.asSequence()
            .map { it.key to Embedder.cosine(qv, it.value) }
            .sortedByDescending { it.second }
            .take(limit * 3)
            .toList()
        if (nearest.isEmpty()) return@withContext emptyList()
        val cos = nearest.toMap()
        val terms = terms(query)
        nearest.map { it.first }.chunked(500).flatMap { dao.articles(it) }
            .map { a -> Hit(a, score(cos.getValue(a.key), a, terms), inLibrary = true) to cos.getValue(a.key) }
            .filter { (h, c) -> c >= LIBRARY_MIN || keywordFraction(h.article, terms) == 1f }
            .map { it.first }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** Ranks online results (already keyword-matched by the server) by meaning. */
    suspend fun rankOnline(query: String, qv: FloatArray, articles: List<Article>): List<Hit> = withContext(Dispatchers.Default) {
        ensureLoaded()
        val emb = Embedder.get(context)
        val terms = terms(query)
        articles.mapNotNull { a ->
            val hasAbstract = !a.abstract.isNullOrBlank()
            val v = library[a.key] ?: remote[a.key]?.takeIf { it.first == hasAbstract }?.second
                ?: emb.embed(Embedder.paperText(a.title, a.abstract), query = false).also { remote[a.key] = hasAbstract to it }
            val c = Embedder.cosine(qv, v)
            if (c < ONLINE_MIN) null else Hit(a, score(c, a, terms), inLibrary = false)
        }.sortedByDescending { it.score }
    }

    private fun score(cosine: Float, a: Article, terms: List<String>): Float {
        if (terms.isEmpty()) return cosine
        val title = a.title.lowercase()
        val titleFrac = terms.count { it in title }.toFloat() / terms.size
        return cosine + 0.05f * keywordFraction(a, terms) + 0.03f * titleFrac
    }

    private fun keywordFraction(a: Article, terms: List<String>): Float {
        if (terms.isEmpty()) return 0f
        val text = (a.title + " " + a.abstract.orEmpty()).lowercase()
        return terms.count { it in text }.toFloat() / terms.size
    }

    private fun terms(query: String): List<String> =
        query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }.distinct()

    companion object {
        /** bge-small cosines: related papers usually score 0.65+, unrelated ones 0.45–0.58. */
        const val LIBRARY_MIN = 0.62f
        const val ONLINE_MIN = 0.5f
        private val STOP = setOf("the", "and", "for", "with", "from", "into", "that", "this", "are", "via", "using", "about", "on")
    }
}
