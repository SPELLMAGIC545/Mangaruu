package eu.kanade.tachiyomi.extension.ja.mangaruu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Canva2ImageInterceptorTest {
    @Test
    fun restoresEveryRealCanva2PageThroughAndroidBitmapAndHttpInterceptor() {
        assumeTrue("Opt in with MANGARUU_LIVE_TEST=1", System.getenv("MANGARUU_LIVE_TEST") == "1")
        val chapter = Jsoup.parse(testFixture("canva2-chapter.html")).extractNextJs<ReaderData>()!!.chapter
        val urls = chapter.imageUrls(testFixture("canva2-manifest.json").parseAs())
        assertEquals(28, urls.size)
        val references = testFixture("canva2-reference.json").parseAs<Canva2Reference>().cases
        urls.forEachIndexed { index, url ->
            val raw = download(url.substringBefore('#'))
            val decoded = decodePixels(raw)
            val reference = references.single { it.chapterId == chapter.siteId.toString() && it.width == decoded.width && it.height == decoded.height }
            val actual = transformed(url, raw)
            assertEquals(decoded.width, actual.width)
            assertEquals(decoded.height, actual.height)
            assertArrayEquals("Restored page ${index + 1} differs from the site Wasm", reference.restoreByReference(decoded.pixels), actual.pixels)
            println("Verified canva2 page ${index + 1}/${urls.size}: ${actual.width}x${actual.height}; Android PNG equals site-Wasm reference")
        }
    }

    @Test
    fun preservesOrdinaryImagesWithoutInvokingBitmapDecoder() {
        val raw = byteArrayOf(1, 2, 3, 4)
        client(raw).newCall(Request.Builder().url("https://example.com/plain.jpg").build()).execute().use {
            assertArrayEquals(raw, it.body.bytes())
            assertEquals("image/jpeg", it.header("Content-Type"))
        }
    }

    @Test
    fun losslessAndroidPipelineMatchesWasmOnUnevenGrid() {
        val reference = testFixture("canva2-reference.json").parseAs<Canva2Reference>().cases.single { it.width == 65 && it.height == 81 }
        val pixels = IntArray(65 * 81) { i -> -0x1000000 or ((i and 255) shl 16) or (((i ushr 8) and 255) shl 8) or ((i ushr 16) and 255) }
        val bitmap = Bitmap.createBitmap(pixels, 65, 81, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        bitmap.recycle()
        val actual = transformed("https://example.com/uneven.png#mangaruu-canva2=895177", bytes)
        assertArrayEquals(reference.restoreByReference(pixels), actual.pixels)
    }

    private fun transformed(url: String, raw: ByteArray): Pixels = client(raw)
        .newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals("image/png", response.header("Content-Type"))
            assertNull("Original encoded Content-Length must not survive", response.header("Content-Length"))
            decodePixels(response.body.bytes())
        }

    private fun client(raw: ByteArray): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Canva2ImageInterceptor())
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "image/jpeg").header("Content-Length", raw.size.toString())
                .body(raw.toResponseBody()).build()
        }.build()

    private class Pixels(val width: Int, val height: Int, val pixels: IntArray)

    private fun decodePixels(bytes: ByteArray): Pixels {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Image response must decode", bitmap)
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            Pixels(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private fun download(url: String): ByteArray {
        val directory = File("build/live-canva2").apply { mkdirs() }
        val file = File.createTempFile("page-", ".response", directory)
        try {
            val process = ProcessBuilder("curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "45", url, "-o", file.absolutePath)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            assertEquals("Download failed: $url", 0, process.waitFor())
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }
}
