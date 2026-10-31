package eu.kanade.tachiyomi.extension.ja.mangaruu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException

internal class Canva2ImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val chapterId = request.url.fragment?.takeIf { it.startsWith(FRAGMENT_PREFIX) }
            ?.removePrefix(FRAGMENT_PREFIX) ?: return chain.proceed(request)
        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val bitmap = response.use { BitmapFactory.decodeStream(it.body.byteStream()) }
            ?: throw IOException("Mangaruu: could not decode canva2 image")
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        try {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            bitmap.recycle()
        }
        val restored = Canva2Descrambler.descramble(pixels, width, height, chapterId)
        val output = Buffer()
        val result = Bitmap.createBitmap(restored, width, height, Bitmap.Config.ARGB_8888)
        try {
            // Lossless output avoids adding JPEG artifacts to the restored text.
            if (!result.compress(Bitmap.CompressFormat.PNG, 100, output.outputStream())) {
                throw IOException("Mangaruu: could not encode restored canva2 image")
            }
        } finally {
            result.recycle()
        }
        return response.newBuilder()
            .removeHeader("Content-Length")
            .header("Content-Type", PNG.toString())
            .body(output.asResponseBody(PNG, output.size))
            .build()
    }

    companion object {
        private const val FRAGMENT_PREFIX = "mangaruu-canva2="
        private val PNG = "image/png".toMediaType()
    }
}
