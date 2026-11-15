package com.paperfeed

import com.paperfeed.net.FeedParser
import com.paperfeed.net.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TextTest {
    @Test
    fun jatsParagraphsAndScripts() {
        assertEquals(
            "The ¹¹Be nucleus & H₂O\nSecond.",
            Text.clean("<jats:p>The <sup>11</sup>Be nucleus &amp; H<sub>2</sub>O</jats:p><jats:p>Second.</jats:p>"),
        )
    }

    @Test
    fun doubleEscapedHtml() {
        assertEquals("Para one.\nPara two & more", Text.clean("&lt;p&gt;Para one.&lt;/p&gt;&lt;p&gt;Para two &amp;amp; more&lt;/p&gt;"))
    }

    @Test
    fun inlineTex() {
        assertEquals("α decay of ²⁰⁸Pb, N=126", Text.clean("\$\\alpha\$ decay of \$^{208}\\mathrm{Pb}\$, \$N=126\$"))
    }

    @Test
    fun texFontOutsideMath() {
        assertEquals("The TAURUS code", Text.clean("The \\texttt{TAURUS} code"))
    }

    @Test
    fun doiNormalization() {
        assertEquals("10.1103/sjy1-tshn", Text.findDoi("http://link.aps.org/doi/10.1103/SJY1-TSHN"))
        assertEquals("10.1080/00223131.2026.2664459", Text.findDoi("https://www.tandfonline.com/doi/full/10.1080/00223131.2026.2664459?af=R"))
    }

    @Test
    fun authorSplitting() {
        assertEquals(listOf("A. One", "B. Two", "C. Three"), Text.splitAuthors("A. One, B. Two, and C. Three"))
        assertEquals(listOf("Zsolt Podolyák", "Callum J. Wilson"), Text.splitAuthors("Zsolt Podolyák and Callum J. Wilson"))
    }

    @Test
    fun apsStyleRdfItem() {
        val xml = """<?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:dc="http://purl.org/dc/elements/1.1/"
                     xmlns:prism="http://prismstandard.org/namespaces/basic/2.0/" xmlns="http://purl.org/rss/1.0/">
              <item rdf:about="http://link.aps.org/doi/10.1103/abcd">
                <title>Pairing in ${'$'}N=Z${'$'} nuclei</title>
                <link>http://link.aps.org/doi/10.1103/abcd</link>
                <description>Author(s): A. One and B. Two&lt;br/&gt;&lt;p&gt;We study pairing correlations in a framework that is long enough to count…&lt;/p&gt;&lt;br/&gt;[Phys. Rev. C 114, 044301] Published Thu Oct 01, 2026</description>
                <dc:creator>A. One and B. Two</dc:creator>
                <dc:date>2026-10-01T10:00:00+00:00</dc:date>
                <prism:doi>10.1103/abcd</prism:doi>
                <prism:publicationName>Physical Review C</prism:publicationName>
              </item>
            </rdf:RDF>"""
        val a = FeedParser.parse(xml, 1, "PRC").single()
        assertEquals("Pairing in N=Z nuclei", a.title)
        assertEquals(listOf("A. One", "B. Two"), a.authorList)
        assertEquals("We study pairing correlations in a framework that is long enough to count…", a.abstract)
        assertEquals("doi:10.1103/abcd", a.key)
        assertEquals("Physical Review C", a.journal)
        assertEquals(2026, a.year)
    }

    @Test
    fun atomPrefersPublishedOverUpdated() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
            <id>http://arxiv.org/abs/1901.00001v3</id><updated>2023-03-21T10:00:00Z</updated>
            <published>2019-01-02T10:00:00Z</published><title>Old paper</title>
            <summary>An abstract that is long enough to count as an abstract for the parser.</summary>
            <author><name>A. Author</name></author></entry></feed>"""
        val a = FeedParser.parse(xml, 1, "arXiv").single()
        assertEquals(2019, a.year)
        assertEquals("arxiv:1901.00001", a.key)
    }

    @Test
    fun sciencedirectMetadataIsNotAnAbstract() {
        val xml = """<rss version="2.0"><channel><item><title><![CDATA[Some result]]></title>
            <description><![CDATA[<p>Publication date: November 2026</p><p><b>Source:</b> Physics Letters B, Volume 882</p><p>Author(s): Ginestra Bianconi, X. Y</p>]]></description>
            <link>https://www.sciencedirect.com/science/article/pii/S0370269326008646?dgcid=rss_sd_all</link></item></channel></rss>"""
        val a = FeedParser.parse(xml, 1, "Physics Letters B").single()
        assertNull(a.abstract)
        assertEquals(listOf("Ginestra Bianconi", "X. Y"), a.authorList)
        assertEquals("Physics Letters B", a.journal)
    }
}
