package com.paperfeed

import com.paperfeed.data.DefaultSources
import com.paperfeed.net.Fetchers
import com.paperfeed.net.Text
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hits the real feeds; run with LIVE_TESTS=1 to check parsers still match publisher formats. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveFeedsTest {
    private val fetchers = Fetchers(contactEmail = { null })

    @Test
    fun defaultSourcesParse() = runBlocking {
        assumeTrue(System.getenv("LIVE_TESTS") != null)
        val failures = mutableListOf<String>()
        DefaultSources.all.forEachIndexed { i, s ->
            val src = s.copy(id = i + 1L)
            val r = runCatching { fetchers.fetch(src) }
            r.onFailure { failures += "${s.name}: $it" }
            r.onSuccess { list ->
                val withAuthors = list.count { it.authorList.isNotEmpty() }
                val withAbs = list.count { !it.abstract.isNullOrBlank() }
                val withDoi = list.count { it.doi != null }
                println("== ${s.name}: ${list.size} items, authors=$withAuthors abstracts=$withAbs doi=$withDoi")
                list.take(2).forEach { a ->
                    println("   [${a.journal}] ${a.title}")
                    println("     ${a.authorList.take(3)} y=${a.year} pub=${java.time.Instant.ofEpochMilli(a.published)} key=${a.key}")
                    println("     abs: ${a.abstract?.take(160)}")
                }
                if (list.isEmpty()) failures += "${s.name}: empty"
            }
        }
        // Enrichment for an Elsevier paper without an abstract.
        val plb = fetchers.fetch(DefaultSources.all[2].copy(id = 3)).first()
        println("== enrichment for ${plb.doi}: ${fetchers.findAbstract(plb)?.take(200)}")
        val works = fetchers.authorWorksPage("Luis M. Robledo", null).items
        println("== author works: ${works.size}; first: ${works.firstOrNull()?.title} ${works.firstOrNull()?.year}")
        println("== clean: " + Text.clean("<jats:p>The <sup>11</sup>Be nucleus &amp; H<sub>2</sub>O</jats:p><jats:p>Second.</jats:p>"))
        // Going back in time: one page from before 1 Jan 2020 for every source.
        val jan2020 = java.time.LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        DefaultSources.all.forEachIndexed { i, s ->
            val r = runCatching { fetchers.fetchOlder(s.copy(id = i + 1L), jan2020) }
            r.onFailure { failures += "older ${s.name}: $it" }
            r.onSuccess { list ->
                val newest = list.maxOfOrNull { it.published }?.let { java.time.Instant.ofEpochMilli(it).toString().take(10) }
                println("== older ${s.name}: ${list.size} items, newest $newest")
                if (list.isEmpty()) failures += "older ${s.name}: empty"
                if (list.any { it.published > jan2020 + 86_400_000L }) failures += "older ${s.name}: not before cutoff"
            }
        }
        // Walk Nuclear Physics A back across Elsevier's 2002 back-catalogue registration.
        val npa = DefaultSources.all[1].copy(id = 2)
        var st: com.paperfeed.net.Fetchers.Paging? = null
        val y2003 = java.time.LocalDate.of(2003, 1, 1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        var before = y2003
        repeat(4) {
            val (page, next) = fetchers.crossrefOlder(npa, before, st)
            st = next
            before = minOf(before, page.minOfOrNull { a -> a.published } ?: before)
            println("== NPA walk: ${page.size} items, years ${page.minOfOrNull { a -> a.year ?: 0 }}..${page.maxOfOrNull { a -> a.year ?: 0 }}, next=$next")
        }
        if (st?.byPubDate != true) failures += "NPA walk did not switch to publication-date paging: $st"

        // Archive search over all default journals.
        val issnMap = buildMap { DefaultSources.all.forEachIndexed { i, s -> fetchers.archiveIssns(s.copy(id = i + 1L)).forEach { put(it, s) } } }
        println("== ISSNs: ${issnMap.keys}")
        val byAuthor = fetchers.searchJournals("Robledo", com.paperfeed.net.Fetchers.SearchField.AUTHOR, true, issnMap, null)
        println("== search author Robledo: ${byAuthor.items.size}, next=${byAuthor.next != null}; ${byAuthor.items.take(3).map { it.year.toString() + " " + it.journal + ": " + it.title.take(50) }}")
        val page2 = fetchers.searchJournals("Robledo", com.paperfeed.net.Fetchers.SearchField.AUTHOR, true, issnMap, byAuthor.next)
        println("== page2: ${page2.items.size}; oldest ${page2.items.minOfOrNull { it.year ?: 0 }}")
        val byTitle = fetchers.searchJournals("superheavy fission barriers", com.paperfeed.net.Fetchers.SearchField.ANY, false, issnMap, null)
        println("== search title: ${byTitle.items.take(3).map { it.year.toString() + " " + it.journal + ": " + it.title.take(50) }}")
        val ax = fetchers.searchArxiv("Robledo", com.paperfeed.net.Fetchers.SearchField.AUTHOR, true, DefaultSources.all.last(), 0)
        println("== arXiv author: ${ax.size}; ${ax.take(2).map { it.title.take(50) }}")
        if (byAuthor.items.isEmpty() || byTitle.items.isEmpty()) failures += "search empty"
        println("FAILURES: $failures")
        assertTrue(failures.toString(), failures.isEmpty())
    }
}
