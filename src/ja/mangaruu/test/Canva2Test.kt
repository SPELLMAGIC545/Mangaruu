package eu.kanade.tachiyomi.extension.ja.mangaruu

import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class Canva2Test {
    @Test
    fun matchesIndependentSiteWasmForSquareAndUnevenGrids() {
        val reference = testFixture("canva2-reference.json").parseAs<Canva2Reference>()
        reference.cases.forEach { case ->
            val pixels = IntArray(case.width * case.height) { i ->
                -0x1000000 or ((i and 255) shl 16) or (((i ushr 8) and 255) shl 8) or ((i ushr 16) and 255)
            }
            val restored = Canva2Descrambler.descramble(pixels, case.width, case.height, case.chapterId)
            val bytes = ByteBuffer.allocate(restored.size * 4).apply { restored.forEach(::putInt) }.array()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
            assertEquals("${case.width}x${case.height}, chapter ${case.chapterId}", case.sha256, hash)
        }
    }

    @Test
    fun marksAllCanva2PagesWithSiteIdUsedByTheBrowser() {
        val chapter = Jsoup.parse(testFixture("canva2-chapter.html")).extractNextJs<ReaderData>()!!.chapter
        val urls = chapter.imageUrls(testFixture("canva2-manifest.json").parseAs())
        assertEquals(28, urls.size)
        assertEquals(895177L, chapter.siteId)
        assertTrue(urls.all { it.endsWith("#mangaruu-canva2=895177") })
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }
}

internal fun testFixture(name: String): String = File("test/resources/$name").readText()

@Serializable
internal class Canva2Reference(val wasmSha256: String, val cases: List<Canva2ReferenceCase>)

@Serializable
internal class Canva2ReferenceCase(
    val width: Int,
    val height: Int,
    val chapterId: String,
    val sha256: String,
    val tiles: List<ReferenceTile>,
) {
    fun restoreByReference(pixels: IntArray): IntArray = IntArray(pixels.size).also { output ->
        tiles.forEach { tile ->
            for (y in 0 until tile.height) {
                for (x in 0 until tile.width) {
                    output[(tile.y + y) * width + tile.x + x] = pixels[tile.source + x * tile.dx + y * tile.dy]
                }
            }
        }
    }
}

@Serializable
internal class ReferenceTile(val x: Int, val y: Int, val width: Int, val height: Int, val source: Int, val dx: Int, val dy: Int)
