package com.paperfeed.net

import android.util.Xml
import com.paperfeed.data.Article
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Tolerant parser for RSS 0.9x/1.0 (RDF), RSS 2.0 and Atom, including the
 * Dublin Core / PRISM extensions used by publishers and the arXiv Atom API.
 */
object FeedParser {

    private class Item {
        var title: String? = null
        var link: String? = null
        var atomId: String? = null
        var description: String? = null
        var content: String? = null
        val authors = mutableListOf<String>()
        var authorsLine: String? = null
        var date: String? = null
        /** Revision/cover date: used only when no publication date is present. */
        var fallbackDate: String? = null
        var doi: String? = null
        var journal: String? = null
        var volume: String? = null
        var issue: String? = null
        var page: String? = null
        var arxivCategory: String? = null
        var journalRef: String? = null
    }

    fun parse(xml: String, sourceId: Long, fallbackJournal: String): List<Article> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')))

        val items = mutableListOf<Item>()
        var current: Item? = null
        var inAuthor = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    val local = name.substringAfter(':')
                    if (local == "item" || local == "entry") {
                        current = Item()
                    } else if (current != null) {
                        val it = current
                        when {
                            local == "author" && !name.startsWith("dc:") -> inAuthor = true
                            inAuthor && local == "name" -> it.authors += readText(parser)
                            name == "title" || name == "dc:title" ->
                                if (it.title == null) it.title = readText(parser) else readText(parser)
                            name == "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                val rel = parser.getAttributeValue(null, "rel")
                                if (href != null) {
                                    if ((rel == null || rel == "alternate") && it.link == null) it.link = href
                                } else {
                                    val t = readText(parser)
                                    if (t.isNotBlank() && it.link == null) it.link = t.trim()
                                }
                            }
                            name == "id" -> it.atomId = readText(parser).trim()
                            name == "guid" -> readText(parser).trim().let { g -> if (it.link == null && g.startsWith("http")) it.link = g }
                            name == "description" || name == "summary" -> it.description = readText(parser)
                            name == "content:encoded" || name == "content" -> it.content = readText(parser)
                            name == "dc:creator" || name == "iop:authors" -> {
                                val t = readText(parser).trim()
                                if (t.isNotEmpty()) {
                                    if (name == "iop:authors") it.authorsLine = t else it.authors += t
                                }
                            }
                            name == "dc:date" || name == "pubDate" || name == "published" ||
                                name == "prism:publicationDate" -> {
                                val t = readText(parser)
                                if (it.date == null) it.date = t
                            }
                            name == "updated" || name == "prism:coverDate" -> {
                                val t = readText(parser)
                                if (it.fallbackDate == null) it.fallbackDate = t
                            }
                            name == "prism:doi" || name == "arxiv:doi" -> it.doi = readText(parser).trim()
                            name == "dc:identifier" -> readText(parser).let { t -> if (it.doi == null && t.contains("10.")) it.doi = t }
                            name == "prism:publicationName" -> it.journal = readText(parser).trim()
                            name == "dc:source" -> readText(parser).trim().let { t -> if (it.journal == null && t.isNotEmpty()) it.journal = t.substringBefore(" (").replace(Regex("\\s+\\d+.*$"), "") }
                            name == "prism:volume" -> it.volume = readText(parser).trim()
                            name == "prism:number" -> it.issue = readText(parser).trim()
                            name == "prism:startingPage" -> it.page = readText(parser).trim()
                            name == "arxiv:primary_category" -> it.arxivCategory = parser.getAttributeValue(null, "term")
                            name == "arxiv:journal_ref" -> it.journalRef = readText(parser).trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val local = parser.name.substringAfter(':')
                    if (local == "author" && !parser.name.startsWith("dc:")) inAuthor = false
                    if ((local == "item" || local == "entry") && current != null) {
                        items += current
                        current = null
                    }
                }
            }
            event = parser.next()
        }
        return items.mapNotNull { toArticle(it, sourceId, fallbackJournal) }
    }

    private fun readText(parser: XmlPullParser): String {
        // Collect text (including CDATA) until the matching end tag, flattening children.
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> sb.append(parser.text ?: "")
                XmlPullParser.START_TAG -> {
                    depth++
                    sb.append('<').append(parser.name).append('>')
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth > 0) sb.append("</").append(parser.name).append('>')
                }
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    private val metaLine = Regex("^(Publication date|Source|Volume \\d+|Author\\(s\\)).*", RegexOption.IGNORE_CASE)

    private fun toArticle(it: Item, sourceId: Long, fallbackJournal: String): Article? {
        val title = Text.cleanTitle(it.title)
        if (title.isBlank()) return null
        val isArxiv = it.arxivCategory != null || it.atomId?.contains("arxiv.org/abs/") == true
        val link = if (isArxiv) it.atomId else (it.link ?: it.atomId?.takeIf { id -> id.startsWith("http") })

        var abstract = Text.clean(it.description ?: it.content)
        // dc:creator carries the full list; iop:authors is often abbreviated to "X et al".
        var authors: List<String> = when {
            it.authors.size == 1 -> Text.splitAuthors(Text.cleanTitle(it.authors[0]))
            it.authors.isNotEmpty() -> it.authors.map { a -> Text.cleanTitle(a) }
            it.authorsLine != null -> Text.splitAuthors(Text.cleanTitle(it.authorsLine).removeSuffix("et al").trim())
            else -> emptyList()
        }

        // Publisher feeds often pack metadata into the description; pull authors out and drop it.
        abstract?.let { text ->
            val lines = text.lines()
            val authorLine = lines.firstOrNull { l -> l.startsWith("Author(s):") }
            if (authorLine != null && authors.isEmpty()) {
                authors = Text.splitAuthors(authorLine.removePrefix("Author(s):"))
            }
            val kept = lines.filterNot { l -> metaLine.matches(l.trim()) }
                .joinToString("\n")
                .replace(Regex("\\[[^\\]]+]\\s*Published\\s.*$", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("Author\\(s\\):[^\\n]*\\n?"), "")
                .trim()
            abstract = kept.takeIf { k -> k.length > 40 }
        }

        val doi = Text.findDoi(it.doi, link)
        val published = Text.parseDate(it.date) ?: Text.parseDate(it.fallbackDate) ?: System.currentTimeMillis()
        val journal = when {
            isArxiv -> "arXiv" + (it.arxivCategory?.let { c -> " [$c]" } ?: "")
            else -> it.journal?.ifBlank { null } ?: fallbackJournal
        }
        return Article(
            key = if (isArxiv && it.doi == null) {
                "arxiv:" + (link ?: title).substringAfterLast("/abs/").replace(Regex("v\\d+$"), "")
            } else Text.keyFor(doi, link, title),
            title = title,
            authors = authors.joinToString("\n"),
            journal = journal,
            sourceId = sourceId,
            doi = doi,
            url = link,
            abstract = abstract,
            published = published,
            year = Text.yearOf(published),
            volume = it.volume ?: it.journalRef,
            issue = it.issue,
            pages = it.page,
        )
    }
}
