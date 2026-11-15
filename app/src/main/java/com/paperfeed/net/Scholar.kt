package com.paperfeed.net

import com.paperfeed.data.Article
import com.paperfeed.data.Source
import java.io.IOException

/**
 * Google Scholar has no API, so this scrapes the "sorted by date" result page.
 * Scholar rate-limits automated requests aggressively; failures surface as a
 * source error instead of crashing the refresh.
 */
object Scholar {
    private val block = Regex("""<div class="gs_ri">(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)
    private val titleLink = Regex("""<h3 class="gs_rt"[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
    private val href = Regex("""<a[^>]+href="([^"]+)"""")
    private val meta = Regex("""<div class="gs_a"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val snippet = Regex("""<div class="gs_rs"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val daysAgo = Regex("""(\d+)\s+days?\s+ago""")

    suspend fun search(source: Source): List<Article> {
        val q = source.query.trim()
        val url = if (q.startsWith("http")) q
        else "https://scholar.google.com/scholar?hl=en&as_sdt=0,5&scisbd=1&q=${Http.enc(q)}"
        val html = Http.get(url, userAgent = Http.BROWSER_AGENT, headers = mapOf("Accept-Language" to "en"))
        if (html.contains("gs_captcha") || html.contains("unusual traffic")) {
            throw IOException("Google Scholar asked for a captcha; try again later")
        }
        val now = System.currentTimeMillis()
        return block.findAll(html).mapIndexedNotNull { index, m ->
            val body = m.groupValues[1]
            val h3 = titleLink.find(body)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val link = href.find(h3)?.groupValues?.get(1)?.replace("&amp;", "&")
            val title = Text.cleanTitle(h3.replace(Regex("""<span class="gs_ct[^"]*".*?</span>""", RegexOption.DOT_MATCHES_ALL), ""))
                .removePrefix("[HTML]").removePrefix("[PDF]").trim()
            if (title.isBlank()) return@mapIndexedNotNull null
            // "A Author, B Author - Journal, 2026 - publisher.com"
            val metaText = Text.cleanTitle(meta.find(body)?.groupValues?.get(1))
            val parts = metaText.split(" - ")
            val authors = parts.getOrNull(0).orEmpty().split(',').map { it.trim().trimEnd('…') }.filter { it.isNotBlank() }
            val venue = parts.getOrNull(1)?.substringBeforeLast(',')?.trim()?.ifBlank { null }
            val year = Regex("""\b(19|20)\d{2}\b""").find(parts.getOrNull(1).orEmpty())?.value?.toInt()
            val snip = Text.clean(snippet.find(body)?.groupValues?.get(1))
            val days = snip?.let { daysAgo.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            // Keep Scholar's ordering when no explicit age is given.
            val published = days?.let { now - it * 86_400_000L } ?: (now - index * 60_000L)
            Article(
                key = Text.keyFor(Text.findDoi(link), link, title),
                title = title,
                authors = authors.joinToString("\n"),
                journal = venue ?: "Google Scholar",
                sourceId = source.id,
                doi = Text.findDoi(link),
                url = link,
                abstract = snip?.replace(daysAgo, "")?.trimStart(' ', '-', '—')?.ifBlank { null },
                published = published,
                year = year ?: Text.yearOf(published),
            )
        }.toList()
    }
}
