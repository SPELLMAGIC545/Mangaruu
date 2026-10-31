package eu.kanade.tachiyomi.extension.ja.mangaruu

import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

class ReaderTest {
    @Test
    fun realManifestProducesOrderedExtensionlessImages() {
        val chapter = Jsoup.parse(fixture("chapter.html")).extractNextJs<ReaderData>()!!.chapter
        val config = fixture("config.json").parseAs<ReaderConfig>()
        assertEquals(
            "https://api.mangarawgo.site/57700/550083.json?t=1769428984000",
            config.manifestUrl(chapter).toString(),
        )
        val manifest = fixture("manifest.json").parseAs<ReaderManifest>()
        val urls = chapter.imageUrls(manifest)
        assertEquals(33, urls.size)
        assertEquals("https://lh3.googleusercontent.com/d/1C5TMhF24PRc0H3eLaX0uvhdRz6IWq1O9=s0", urls.first())
        assertEquals("https://lh3.googleusercontent.com/d/1fGxq4VwRtSxjTLGgQ5Zo8767ryF01fce=s0", urls[1])
        val reversed = ReaderManifest(encodeManifest(manifest.entries().reversed()))
        assertEquals(urls, chapter.imageUrls(reversed))
    }

    @Test
    fun malformedManifestFailsInsteadOfReturningRelatedCovers() {
        org.junit.Assert.assertThrows(Exception::class.java) {
            ReaderManifest("not a manifest").entries()
        }
    }

    @Test
    fun liveChapterDownloadsDecodableImages() {
        assumeTrue("Opt in with MANGARUU_LIVE_TEST=1", System.getenv("MANGARUU_LIVE_TEST") == "1")
        // Hosted CI cannot fetch the chapter HTML (HTTP 403). Its explicit captured
        // metadata mode still downloads and decodes every real CDN image below.
        val capturedMetadata = System.getenv("MANGARUU_CAPTURED_METADATA") == "1"
        val html = if (capturedMetadata) fixture("chapter.html") else download(CHAPTER_URL).decodeToString()
        val chapter = Jsoup.parse(html).extractNextJs<ReaderData>()!!.chapter
        val manifest = if (capturedMetadata) {
            println("Metadata: captured 2026-10-30; image downloads: LIVE (fresh chapter/API access is not validated in this mode)")
            fixture("manifest.json").parseAs<ReaderManifest>()
        } else {
            println("Metadata: LIVE chapter HTML, config and manifest; image downloads: LIVE")
            val config = download("https://mangaruu.com/config.json").decodeToString().parseAs<ReaderConfig>()
            download(config.manifestUrl(chapter).toString()).decodeToString().parseAs<ReaderManifest>()
        }
        val urls = chapter.imageUrls(manifest)
        assertEquals("The reported chapter has 33 manga pages", 33, urls.size)
        urls.forEachIndexed { index, url ->
            val bytes = download(url)
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            assertNotNull("Page ${index + 1} returned a non-image response: $url", image)
            assertTrue("Page ${index + 1} is a placeholder", image.width >= 500 && image.height >= 500)
            println("Verified manga page ${index + 1}/${urls.size}: ${image.width}x${image.height}, ${bytes.size} bytes")
        }
    }

    private fun fixture(name: String): String = File("test/resources/$name").readText()

    // Curl uses the environment's HTTPS proxy in both local and CI runs. No cookies,
    // Referer, Origin, or browser session are supplied to the image CDN.
    private fun download(url: String): ByteArray {
        val directory = File("build/live-reader").apply { mkdirs() }
        val file = File.createTempFile("mangaruu-live-", ".response", directory)
        try {
            val process = ProcessBuilder("curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "45", url, "-o", file.absolutePath)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            assertEquals("Download failed: $url", 0, process.waitFor())
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun encodeManifest(entries: List<ReaderImage>): String {
        val bytes = Json.encodeToString(entries).encodeToByteArray()
        val key = "/fuCkYou!!!".encodeToByteArray()
        return java.util.Base64.getEncoder().encodeToString(ByteArray(bytes.size) { (bytes[it].toInt() xor key[it % key.size].toInt()).toByte() })
    }

    companion object {
        private const val CHAPTER_URL = "https://mangaruu.com/c/zensei-eiyuu-2-kai-me-no-jinsei-ha-asobi-makuro-u-to-omoi-masu-3326-ch-1-id-141124"

        @JvmStatic
        @BeforeClass
        fun setUp() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }
}
