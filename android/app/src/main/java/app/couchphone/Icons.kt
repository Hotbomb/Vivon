package app.couchphone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Apps' own icons as PNGs (192 px), drawn from the phone's launcher icons and kept in memory. */
object Icons {
    private const val SIZE = 192
    private val cache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {   // up to 8 MB of PNGs
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private fun png(pkg: String): ByteArray? {
        cache.get(pkg)?.let { return it }
        val pm = App.instance.packageManager
        val d = runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, SIZE, SIZE)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray().also { cache.put(pkg, it) }
    }

    fun response(raw: String): NanoHTTPD.Response {
        val pkg = raw.removeSuffix(".png")
        val bytes = (if (Regex("[A-Za-z0-9_.]{1,200}").matches(pkg)) png(pkg) else null)
            ?: return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "no icon")
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "private, max-age=86400") }
    }
}
