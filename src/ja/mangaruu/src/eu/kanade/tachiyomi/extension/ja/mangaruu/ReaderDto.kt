package eu.kanade.tachiyomi.extension.ja.mangaruu

import keiyoushi.utils.aesCtr
import keiyoushi.utils.aesDecrypt
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAs
import keiyoushi.utils.xorInPlace
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import kotlin.time.Instant

@Serializable
internal class ReaderData(val chapter: ReaderChapter)

@Serializable
internal class ReaderConfig(private val apiImage: String) {
    fun manifestUrl(chapter: ReaderChapter): HttpUrl = apiImage.toHttpUrl().newBuilder()
        .addPathSegment(chapter.manga.siteId.toString())
        .addPathSegment("${chapter.siteId}.json")
        .addQueryParameter("t", Instant.parse(chapter.updatedAt).toEpochMilliseconds().toString())
        .build()
}

@Serializable
internal class ReaderChapter(
    @SerialName("s_id") val siteId: Long,
    val manga: ReaderManga,
    @SerialName("updated_at") val updatedAt: String,
    private val uuid: String,
    private val mode: String,
    @SerialName("_d") private val imageHost: String,
) {
    fun imageUrls(manifest: ReaderManifest): List<String> {
        // Canva modes need pixel descrambling as well; don't return scrambled images.
        require(mode == "image") { "Mangaruu: unsupported reader mode '$mode'" }
        val key = uuid.decodeHex()
        val host = imageHost.toHttpUrl()
        return manifest.entries().sortedBy { it.order }.map { entry ->
            val encrypted = entry.d.decodeReaderBase64().xorInPlace(IMAGE_KEY.encodeToByteArray())
            val path = encrypted.copyOfRange(16, encrypted.size)
                .aesDecrypt(aesCtr(key, encrypted.copyOfRange(0, 16)))
                .decodeToString()
            // D is the site's normal alternative server; its paths are extensionless.
            host.resolve(path)!!.toString()
        }
    }
}

@Serializable
internal class ReaderManga(@SerialName("s_id") val siteId: Long)

@Serializable
internal class ReaderManifest(private val d: String) {
    fun entries(): List<ReaderImage> = d.decodeReaderBase64()
        .xorInPlace(MANIFEST_KEY.encodeToByteArray())
        .decodeToString()
        .parseAs()
}

@Serializable
internal class ReaderImage(val order: Int, val d: String)

private fun String.decodeReaderBase64(): ByteArray = decodeBase64()!!.toByteArray()

private const val MANIFEST_KEY = "/fuCkYou!!!"
private const val IMAGE_KEY = "202508055d0db38bae2e86cc41649f90"
