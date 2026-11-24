package com.paperfeed.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One publication. [key] is the normalized DOI when known, otherwise the article URL,
 * so the same paper coming from two sources collapses into a single row.
 */
@Entity(
    tableName = "articles",
    indices = [Index("published"), Index("sourceId")],
)
data class Article(
    @PrimaryKey val key: String,
    val title: String,
    /** Authors separated by '\n'. */
    val authors: String,
    val journal: String,
    val sourceId: Long,
    val doi: String? = null,
    val url: String? = null,
    val abstract: String? = null,
    /** Online/release date in epoch millis, used for "latest first" ordering. */
    val published: Long,
    val year: Int? = null,
    val volume: String? = null,
    val issue: String? = null,
    val pages: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
    val rating: Int = 0,
    val read: Boolean = false,
    /** Lookups of a missing abstract so far; retried with backoff while indexes catch up. */
    val abstractAttempts: Int = 0,
    val abstractCheckedAt: Long = 0,
) {
    val authorList: List<String> get() = authors.split('\n').filter { it.isNotBlank() }
    val link: String? get() = doi?.let { "https://doi.org/$it" } ?: url
}

enum class SourceType(val label: String, val hint: String) {
    CROSSREF("Journal (Crossref ISSN)", "ISSN, e.g. 2469-9985"),
    RSS("RSS / Atom feed", "Feed URL"),
    ARXIV("arXiv", "Query, e.g. cat:nucl-th OR cat:nucl-ex"),
    OPENALEX("OpenAlex search", "Keywords, e.g. neutron star mergers"),
    SCHOLAR("Google Scholar (best effort)", "Search query"),
}

@Entity(tableName = "sources")
data class Source(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: SourceType,
    val query: String,
    val enabled: Boolean = true,
    val notify: Boolean = true,
    val lastFetched: Long = 0,
    val lastError: String? = null,
)

@Entity(tableName = "lists")
data class ArticleList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
    /** Shown as a one-tap action button on new-article notifications. */
    val notificationAction: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val READ_LATER_ID = 1L
    }
}

@Entity(
    tableName = "list_entries",
    primaryKeys = ["listId", "articleKey"],
    indices = [Index("articleKey")],
)
data class ListEntry(
    val listId: Long,
    val articleKey: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/** A quick idea or note jotted down while reading an article. */
@Entity(
    tableName = "notes",
    indices = [Index("articleKey"), Index("updatedAt")],
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleKey: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A note together with the paper it belongs to (the paper may be missing after a partial restore). */
data class NoteWithArticle(
    @Embedded val note: Note,
    @androidx.room.Relation(parentColumn = "articleKey", entityColumn = "key")
    val article: Article?,
)

data class SourceCoverage(val sourceId: Long, val oldest: Long)

data class ListWithCount(
    @Embedded val list: ArticleList,
    val count: Int,
)

/** Meaning-search vector of a paper (bge-small, 384 floats, little endian). */
@Entity(tableName = "article_vectors")
data class ArticleVector(
    @PrimaryKey val articleKey: String,
    val vec: ByteArray,
    /** False when the paper had no abstract yet; it is re-embedded once one arrives. */
    val withAbstract: Boolean,
)

data class VectorSource(val key: String, val title: String, val abstract: String?)

class KeyVec(val articleKey: String, val vec: ByteArray)
