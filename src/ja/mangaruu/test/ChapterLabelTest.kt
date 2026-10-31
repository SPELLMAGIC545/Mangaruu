package eu.kanade.tachiyomi.extension.ja.mangaruu

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterLabelTest {
    @Test
    fun realFirstAndLatestLinksHaveNumericNamesAndDeduplicate() {
        val doc = Jsoup.parse(testFixture("chapter-links.html"))
        val links = doc.select("a[href]")
        assertTrue(links.any { it.text() == "最初から読む" })
        assertTrue(links.any { it.text() == "最新を読む" })
        val chapters = links.map { it.attr("href") to ChapterLabel.parse(it.text(), it.attr("href")) }
            .distinctBy { it.first }
            .sortedByDescending { it.second.number }
        assertEquals(59, chapters.size)
        assertEquals("59話", chapters.first().second.name)
        assertEquals("1話", chapters.last().second.name)
        assertTrue(chapters.none { it.second.name.contains("読む") || it.second.name.contains("ヶ月") })
    }

    @Test
    fun supportsDecimalUrlSegmentsAndPreservesSpecialChapterNames() {
        assertEquals("2.1話", ChapterLabel.parse("最新を読む", "/c/example-ch-2-1-id-123").name)
        assertEquals(2.1f, ChapterLabel.parse("最初から読む", "/c/example-ch-2.1").number)
        assertEquals("54話", ChapterLabel.parse("54話 5ヶ月前", "/c/example-ch-54-id-123").name)
        assertEquals("番外編", ChapterLabel.parse("番外編", "/c/example-special").name)
    }
}
