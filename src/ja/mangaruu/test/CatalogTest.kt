package eu.kanade.tachiyomi.extension.ja.mangaruu

import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogTest {
    private val config get() = testFixture("catalog-config.json").parseAs<CatalogConfig>()
    private val sample get() = testFixture("search-catalog.json").parseAs<CatalogPart>().list
    private fun filters() = catalogFilters(testFixture("genres.json").parseAs<JsonElement>())
    private fun page(html: String, config: CatalogConfig = this.config) = Jsoup.parse(html).extractNextJs<CatalogData>()!!.toPage(config)

    @Test
    fun paginationUsesRealPathsAndAllCoversExistWithoutRenderedImages() {
        assertEquals("https://mangaruu.com", catalogUrl("https://mangaruu.com", "", 1))
        assertEquals("https://mangaruu.com/page/2", catalogUrl("https://mangaruu.com", "", 2))
        assertEquals("https://mangaruu.com/on-going", catalogUrl("https://mangaruu.com", "/on-going", 1))
        assertEquals("https://mangaruu.com/on-going/page/2", catalogUrl("https://mangaruu.com", "/on-going", 2))
        assertEquals("https://mangaruu.com/on-going/page/3", catalogUrl("https://mangaruu.com", "/on-going", 3))
        val pages = (1..3).map { n ->
            val html = testFixture("latest-$n.html")
            assertTrue("No rendered images should be needed", Jsoup.parse(html).select("img").isEmpty())
            page(html).also { result ->
                assertEquals(30, result.mangas.size)
                assertTrue(result.hasNextPage)
                assertTrue(result.mangas.all { !it.thumbnailUrl.isNullOrBlank() })
            }
        }
        assertNotEquals(pages[0].mangas.map { it.url }, pages[1].mangas.map { it.url })
        assertNotEquals(pages[1].mangas.map { it.url }, pages[2].mangas.map { it.url })
        val reported = mapOf("異世界でスローライフを（願望）" to "6844_1754885797609.webp", "ひねくれ領主の幸福譚　性格が悪くても辺境開拓できますうぅ！" to "5219_1750062750277.webp", "魔王とはじめるRPG" to "52403_1759470720422.webp", "完璧すぎて可愛げがないと婚約破棄された聖女は隣国に売られる" to "6101_1750063331961.webp")
        reported.forEach { (title, image) -> assertEquals("https://i.mangaraw.lat/$image", pages[0].mangas.single { it.title == title }.thumbnailUrl) }
    }

    @Test
    fun lastPageStopsAndMissingSiteCoverDoesNotBecomeFakeUrl() {
        val last = page(testFixture("latest-last.html"))
        assertEquals(25, last.mangas.size)
        assertFalse(last.hasNextPage)
        assertNull(sample.single { it.slug.endsWith("-3751") }.toEntry(config).thumbnailUrl)
    }

    @Test
    fun keywordAndGenreInclusionExclusionApplyTogether() {
        val filters = filters()
        assertEquals(36, filters.filterIsInstance<GenreFilter>().single().state.size)
        val states = filters.filterIsInstance<GenreFilter>().single().state
        states.single { it.id == 4 }.state = Filter.TriState.STATE_INCLUDE
        assertEquals("ワンピース", sample.search("ワンピース", filters, 1, config).mangas.single().title)
        states.single { it.id == 17 }.state = Filter.TriState.STATE_EXCLUDE
        assertTrue(sample.search("ワンピース", filters, 1, config).mangas.isEmpty())
        states.forEach { it.state = Filter.TriState.STATE_IGNORE }
        states.single { it.id == 4 }.state = Filter.TriState.STATE_INCLUDE
        states.single { it.id == 24 }.state = Filter.TriState.STATE_INCLUDE
        assertTrue(sample.search("さわらないで小手指くん", filters, 1, config).mangas.isNotEmpty())
    }

    @Test
    fun statusFormatAndAuthorAlternativeNameSearchWork() {
        val filters = filters()
        assertEquals("ワンピース", sample.search("尾田", filters, 1, config).mangas.single().title)
        assertEquals("ワンピース", sample.search("ＯＮＥ　ＰＩＥＣＥ", filters, 1, config).mangas.single().title)
        filters.filterIsInstance<StatusFilter>().single().state = 2
        assertEquals("悪役令嬢のお気に入り 王子……邪魔っ", sample.search("悪役令嬢のお気に入り", filters, 1, config).mangas.single().title)
        assertTrue(sample.search("ワンピース", filters, 1, config).mangas.isEmpty())
        filters.filterIsInstance<StatusFilter>().single().state = 0
        filters.filterIsInstance<FormatFilter>().single().state = 2
        assertEquals("好都合セミフレンド", sample.search("好都合セミフレンド", filters, 1, config).mangas.single().title)
        assertTrue(sample.search("ワンピース", filters, 1, config).mangas.isEmpty())
    }

    @Test
    fun sortingAndSearchPaginationDoNotTruncateAtThirtyResults() {
        val filters = filters()
        filters.filterIsInstance<OrderFilter>().single().state = 1
        assertEquals("ワンピース", sample.search("", filters, 1, config).mangas.first().title)
        filters.filterIsInstance<OrderFilter>().single().state = 2
        assertEquals("リモートラブホール", sample.search("", filters, 1, config).mangas.first().title)
        filters.filterIsInstance<OrderFilter>().single().state = 0
        val pages = (1..4).map { sample.search("", filters, it, config) }
        assertEquals(listOf(30, 30, 30, 3), pages.map { it.mangas.size })
        assertEquals(listOf(true, true, true, false), pages.map { it.hasNextPage })
        assertEquals(93, pages.flatMap { it.mangas }.map { it.url }.distinct().size)
        assertTrue(catalogFilters(null).any { it is StatusFilter })
    }

    @Test
    fun liveCatalogPagesAndReportedWebpCoversDecode() {
        assumeTrue("Opt in with MANGARUU_LIVE_TEST=1", System.getenv("MANGARUU_LIVE_TEST") == "1")
        val captured = System.getenv("MANGARUU_CAPTURED_METADATA") == "1"
        println("Catalog metadata: ${if (captured) "CAPTURED; fresh catalog access is not validated" else "LIVE"}; cover downloads: LIVE")
        val config = (if (captured) testFixture("catalog-config.json") else download("https://mangaruu.com/config.json").decodeToString()).parseAs<CatalogConfig>()
        val pages = (1..3).map { n ->
            val html = if (captured) testFixture("latest-$n.html") else download(catalogUrl("https://mangaruu.com", "/on-going", n)).decodeToString()
            page(html, config).also { assertEquals(30, it.mangas.size); assertTrue(it.hasNextPage) }
        }
        assertNotEquals(pages[0].mangas.first().url, pages[1].mangas.first().url)
        assertNotEquals(pages[1].mangas.first().url, pages[2].mangas.first().url)
        val catalog = if (captured) sample else (1..3).flatMap { download("https://mangaruu.com/mangas_$it.json").decodeToString().parseAs<CatalogPart>().list }
        assertTrue(catalog.search("予習", filters(), 1, config).mangas.any { it.title == "ないしょの予習" })
        val covers = pages[0].mangas.filter { manga -> listOf("-3155", "-2999", "-1895", "-3000").any { manga.url.endsWith(it) } } + pages[1].mangas.take(1) + pages[2].mangas.take(1)
        assertEquals(6, covers.size)
        covers.forEachIndexed { index, manga ->
            val bytes = download(manga.thumbnailUrl!!)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("Cover must decode: ${manga.title}", bitmap)
            try {
                assertTrue(bitmap.width >= 200 && bitmap.height >= 200)
                println("Verified live cover ${index + 1}/6: ${manga.title}; ${bitmap.width}x${bitmap.height}")
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun download(url: String): ByteArray {
        val directory = File("build/live-catalog").apply { mkdirs() }
        val file = File.createTempFile("catalog-", ".response", directory)
        try {
            val process = ProcessBuilder("curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "45", url, "-o", file.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            assertEquals("Download failed: $url", 0, process.waitFor())
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() { Injekt.addSingleton(Json { ignoreUnknownKeys = true }) }
    }
}
