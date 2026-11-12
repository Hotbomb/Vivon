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

    /** pinned: a shortcut pinned from a browser (Brave's "Add to Home screen"), found by itself. */
    data class Site(val slug: String, val name: String, val url: String, val browser: String?,
                    val pinned: Boolean = false, val bitmapPath: String? = null) {
        val id get() = PREFIX + slug
        fun json(): JSONObject = JSONObject().put("slug", slug).put("name", name).put("url", url).put("browser", browser ?: JSONObject.NULL)
            .put("pinned", pinned)
    }

    /** Every website tile: the ones added here, then shortcuts pinned from browsers. */
    fun list(): List<Site> {
        val own = added()
        val hosts = own.mapNotNull { android.net.Uri.parse(it.url).host?.removePrefix("www.") }.toSet()
        return own + pinned().filter { android.net.Uri.parse(it.url).host?.removePrefix("www.") !in hosts }
    }

    // ---------------------------------------------------------------- shortcuts pinned from browsers
    //
    // Brave (like most browsers) cannot install a site as an app: "Add to Home screen" pins a
    // shortcut, which Android gives to the phone's own home screen, not to Couch Phone. With root,
    // Android's shortcut service lists them all (`dumpsys shortcut`): the pinned ones that open a web
    // address become website tiles, opening in the browser that pinned them.

    @Volatile private var pinCache: List<Site> = emptyList()
    @Volatile private var pinAt = 0L
    private const val FLAG_PINNED = 2L
    private const val FLAG_DISABLED = 64L

    private fun pinned(): List<Site> {
        val now = System.currentTimeMillis()
        if (now - pinAt < 60_000 || Root.available != true) return pinCache
        pinAt = now
        val dump = Root.shOrNull("dumpsys shortcut 2>/dev/null", 15_000) ?: return pinCache
        val found = ArrayList<Site>()
        for (block in dump.split("ShortcutInfo {").drop(1)) {
            val b = block.substringBefore("\n    Package:").substringBefore("\n  User:")
            val flags = Regex("flags=0x([0-9a-fA-F]+)").find(b)?.groupValues?.get(1)?.toLongOrNull(16) ?: continue
            if (flags and FLAG_PINNED == 0L || flags and FLAG_DISABLED != 0L) continue
            val pkg = Regex("packageName=([A-Za-z0-9_.]+)").find(b)?.groupValues?.get(1) ?: continue
            val url = Regex("dat=(https?://[^\\s,}\\]]+)").find(b)?.groupValues?.get(1)?.removeSuffix("...") ?: continue
            val host = android.net.Uri.parse(url).host ?: continue
            val id = Regex("\\bid=([^,]+)").find(b)?.groupValues?.get(1)?.trim() ?: url
            val label = Regex("shortLabel=(.*?), [A-Za-z]+=").find(b)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                ?: host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
            val bitmap = Regex("(?i)bitmapPath=(/[^,}\\s]+)").find(b)?.groupValues?.get(1)
            val slug = "pin-" + java.security.MessageDigest.getInstance("SHA-1").digest("$pkg/$id".toByteArray()).take(5).joinToString("") { "%02x".format(it) }
            if (found.none { it.slug == slug }) found += Site(slug, label.take(40), url, pkg, pinned = true, bitmapPath = bitmap)
        }
        pinCache = found
        return found
    }

    /** The websites added from the remote. */
    @Synchronized private fun added(): List<Site> {
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
        val have = added()
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
        if (s.pinned) throw ApiError(409, "${s.name} is a shortcut pinned in the browser: remove it there (or take it off this page).")
        save(added().filter { it.slug != s.slug })
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
        if (s.bitmapPath != null && Root.available == true) runCatching {
            val b64 = Root.sh("base64 -w 0 '${s.bitmapPath}'; echo", 8000).trim()
            val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            if (bmp != null) { f.writeBytes(tile(bmp)); bmp.recycle(); return f.readBytes() }
        }
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
