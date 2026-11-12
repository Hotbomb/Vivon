package app.couchphone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Website tiles: a website kept like an app, on the TV home and the remote's Watch page, that opens
 * in Brave (with its ad blocking) on the TV. Each one is "site:<slug>" wherever apps are listed.
 * Its icon is the site's own (apple-touch-icon, icon, or favicon), fetched once and kept.
 */
object Sites {
    private const val TAG = "couchphone.sites"
    const val PREFIX = "site:"
    private const val BRAVE = "com.brave.browser"

    data class Site(val slug: String, val name: String, val url: String, val browser: String?) {
        val id get() = PREFIX + slug
        fun json(): JSONObject = JSONObject().put("slug", slug).put("name", name).put("url", url).put("browser", browser ?: JSONObject.NULL)
    }

    @Synchronized fun list(): List<Site> {
        val a = Store.str("sites")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Site(o.optString("slug"), o.optString("name"), o.optString("url"), o.optString("browser").ifEmpty { null })
                .takeIf { it.slug.isNotEmpty() && it.url.startsWith("http") }
        }
    }

    @Synchronized private fun save(l: List<Site>) = Store.putStr("sites", JSONArray(l.map { it.json() }).toString())

    fun find(id: String): Site? = list().firstOrNull { it.id == id || it.slug == id }

    /** Adds a website. The address may be typed without https://; the name defaults to the site's. */
    @Synchronized fun add(nameIn: String, urlIn: String, browser: String? = null): Site {
        var u = urlIn.trim()
        if (u.isEmpty()) throw ApiError(400, "Type the website's address.")
        if (!u.contains("://")) u = "https://$u"
        val uri = android.net.Uri.parse(u)
        val host = uri.host ?: throw ApiError(400, "That address is not right.")
        if (uri.scheme != "http" && uri.scheme != "https") throw ApiError(400, "Only web addresses (http or https).")
        val name = nameIn.trim().ifEmpty { host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() } }.take(40)
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "site" }.take(24)
        val have = list()
        var slug = base; var n = 2
        while (have.any { it.slug == slug }) slug = "$base-${n++}"
        val s = Site(slug, name, u, browser ?: BRAVE)
        save(have + s)
        // the icon now, so the tile shows it straight away (a few seconds at most; the name if none)
        runCatching { fetchIcon(s) }.onFailure { Log.w(TAG, "icon for ${s.url}", it) }
        return s
    }

    @Synchronized fun remove(id: String) {
        val s = find(id) ?: return
        save(list().filter { it.slug != s.slug })
        iconFile(s.slug).delete()
    }

    /** Opens the website on the TV, in its browser (Brave), else the usual one. */
    fun open(id: String) {
        val s = find(id) ?: throw ApiError(404, "That website tile is gone.")
        val b = s.browser?.takeIf { Tv.hasPackage(it) } ?: BRAVE.takeIf { Tv.hasPackage(it) }
        if (b == null) Tv.openUrl(s.url) else Dex.openUrl(s.url, b)
    }

    // ---------------------------------------------------------------- icons

    private fun iconFile(slug: String) = File(File(App.instance.filesDir, "site-icons").apply { mkdirs() }, "$slug.png")

    /** The site's icon as a 192 px PNG, or null (the tile then shows the name). */
    fun icon(id: String): ByteArray? {
        val s = find(id) ?: return null
        val f = iconFile(s.slug)
        if (f.isFile) return f.readBytes()
        Thread { runCatching { fetchIcon(s) } }.start()   // not fetched yet (or failed): try again for next time
        return null
    }

    private fun get(url: String, max: Int): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
        return try {
            if (c.responseCode !in 200..299) null
            else c.inputStream.use { inp ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                while (out.size() < max) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    @Synchronized private fun fetchIcon(s: Site) {
        if (iconFile(s.slug).isFile) return
        val page = runCatching { get(s.url, 512 * 1024)?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
        val base = URL(s.url)
        // <link rel="apple-touch-icon" href> first (big, square), then <link rel="icon">, then the usual files
        val links = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(page).map { it.value }.toList()
        fun href(tag: String) = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
        fun rel(tag: String) = Regex("rel\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase().orEmpty()
        fun size(tag: String) = Regex("sizes\\s*=\\s*[\"'](\\d+)x", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val candidates = (links.filter { rel(it).contains("apple-touch-icon") }.sortedByDescending { size(it) } +
            links.filter { rel(it).split(' ').contains("icon") }.sortedByDescending { size(it) })
            .mapNotNull { href(it) }.mapNotNull { runCatching { URL(base, it).toString() }.getOrNull() } +
            listOf(URL(base, "/apple-touch-icon.png").toString(), URL(base, "/favicon.ico").toString())
        for (u in candidates.distinct()) {
            val bytes = runCatching { get(u, 2 * 1024 * 1024) }.getOrNull() ?: continue
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            iconFile(s.slug).writeBytes(tile(bmp))
            bmp.recycle()
            Log.i(TAG, "icon for ${s.name} from $u")
            return
        }
    }

    /** The picture on a rounded white tile (most site icons are made for a light background). */
    private fun tile(src: Bitmap): ByteArray {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val big = src.width >= 120
        if (!big) { p.color = Color.WHITE; c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 42f, 42f, p) }
        val pad = if (big) 0f else 36f
        val scale = minOf((size - 2 * pad) / src.width, (size - 2 * pad) / src.height)
        val w = src.width * scale; val h = src.height * scale
        c.drawBitmap(src, null, RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), p)
        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        out.recycle()
        return bytes.toByteArray()
    }
}
