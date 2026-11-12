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
            .put("pinned", pinned).put("opens", opens(slug))
    }

    /** Where a website tile opens: "couch" (the Couch TV browser, the default) or "browser" (Brave, else the usual one). */
    fun opens(slug: String): String = if (Store.str("site_open_$slug") == "browser") "browser" else "couch"

    fun setOpens(id: String, how: String) {
        val s = find(id) ?: throw ApiError(404, "That website tile is gone.")
        if (how != "couch" && how != "browser") throw ApiError(400, "opens must be couch or browser")
        Store.putStr("site_open_${s.slug}", how)
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
    @Volatile private var pinRefreshing = false
    private const val FLAG_PINNED = 2L
    private const val FLAG_DISABLED = 64L

    /** The pinned shortcuts as last read; read again in the background at most every minute, never waited for. */
    private fun pinned(): List<Site> {
        val now = System.currentTimeMillis()
        if (now - pinAt > 60_000 && !pinRefreshing && Root.available == true) {
            pinRefreshing = true; pinAt = now
            Thread {
                try {
                    val fresh = runCatching { fromFiles() ?: fromDump() }.onFailure { Log.w(TAG, "pinned shortcuts", it) }.getOrNull()
                    if (fresh != null) {
                        val changed = fresh.map { it.id to it.url } != pinCache.map { it.id to it.url }
                        pinCache = fresh
                        if (changed) HomeActivity.refresh()   // the TV home lists them at once
                    }
                } finally { pinRefreshing = false }
            }.start()
        }
        return pinCache
    }

    private fun slugFor(pkg: String, id: String) =
        "pin-" + java.security.MessageDigest.getInstance("SHA-1").digest("$pkg/$id".toByteArray()).take(5).joinToString("") { "%02x".format(it) }

    private fun labelFor(host: String) = host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }

    private fun unescape(v: String) = v.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }.replace("&amp;", "&")

    /** The web address an intent (as Intent.toUri writes it) opens: its data, or a web app's URL extra. */
    private fun urlOf(intent: String): String? {
        if (intent.startsWith("http://") || intent.startsWith("https://")) return intent.substringBefore("#Intent;")
        Regex("(?:^|;)S\\.[A-Za-z0-9_.]*(?:webapp_url|url)=([^;]+)").find(intent)?.let { return android.net.Uri.decode(it.groupValues[1]).takeIf { u -> u.startsWith("http") } }
        return null
    }

    /**
     * The shortcut service's own files (Android 12 keeps them in binary XML; abx2xml turns them back
     * into text): the full address, and the web app URL of sites pinned as web apps, which the dump
     * leaves out. Null when they cannot be read.
     */
    private fun fromFiles(): List<Site>? {
        val dir = "/data/system_ce/0/shortcut_service/packages"
        val out = Root.runOnce("for f in $dir/*.xml; do echo \"@@FILE ${'$'}f\"; abx2xml \"${'$'}f\" - 2>/dev/null || cat \"${'$'}f\"; done", 15_000)
        if (out == null || !out.contains("<shortcut")) return null
        val found = ArrayList<Site>()
        for (part in out.split("@@FILE ").drop(1)) {
            val pkg = part.substringBefore('\n').trim().substringAfterLast('/').removeSuffix(".xml")
            val shortcut = Regex("<shortcut\\b([^>]*?)(?:/>|>(.*?)</shortcut>)", RegexOption.DOT_MATCHES_ALL)
            for (m in shortcut.findAll(part)) {
                val attrs = m.groupValues[1]; val body = m.groupValues[2]
                fun attr(n: String) = Regex("\\b$n=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.let { unescape(it) }
                val flags = attr("flags")?.toLongOrNull() ?: continue
                if (flags and FLAG_PINNED == 0L || flags and FLAG_DISABLED != 0L) continue
                val intents = Regex("intent(?:-base)?=\"([^\"]*)\"").findAll(attrs + " " + body).map { unescape(it.groupValues[1]) }
                val url = intents.mapNotNull { urlOf(it) }.firstOrNull() ?: continue
                val host = android.net.Uri.parse(url).host ?: continue
                val id = attr("id") ?: url
                val label = attr("title")?.takeIf { it.isNotBlank() } ?: labelFor(host)
                val slug = slugFor(pkg, id)
                if (found.none { it.slug == slug }) found += Site(slug, label.take(40), url, pkg, pinned = true, bitmapPath = attr("bitmap-path"))
            }
        }
        return found
    }

    /** The service's dump, when the files cannot be read (addresses are cut to the site there). */
    private fun fromDump(): List<Site>? {
        val dump = Root.runOnce("dumpsys shortcut 2>/dev/null", 15_000) ?: return null
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
                ?: labelFor(host)
            val bitmap = Regex("(?i)bitmapPath=(/[^,}\\s]+)").find(b)?.groupValues?.get(1)
            val slug = slugFor(pkg, id)
            if (found.none { it.slug == slug }) found += Site(slug, label.take(40), url, pkg, pinned = true, bitmapPath = bitmap)
        }
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
    fun add(nameIn: String, urlIn: String, browser: String? = null): Site {
        var u = urlIn.trim()
        if (u.isEmpty()) throw ApiError(400, "Type the website's address.")
        if (!u.contains("://")) u = "https://$u"
        val uri = android.net.Uri.parse(u)
        val host = uri.host ?: throw ApiError(400, "That address is not right.")
        if (uri.scheme != "http" && uri.scheme != "https") throw ApiError(400, "Only web addresses (http or https).")
        val name = nameIn.trim().ifEmpty { labelFor(host) }.take(40)
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "site" }.take(24)
        val s = synchronized(this) {
            val have = added()
            var slug = base; var n = 2
            while (have.any { it.slug == slug }) slug = "$base-${n++}"
            Site(slug, name, u, browser ?: BRAVE).also { save(have + it) }
        }
        // the icon now if it comes quickly, so the tile shows it straight away (else it follows)
        requestIcon(s)?.join(5000)
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
        if (opens(s.slug) == "couch") { BrowserActivity.open(s.url); return }
        val b = s.browser?.takeIf { Tv.hasPackage(it) } ?: BRAVE.takeIf { Tv.hasPackage(it) }
        if (b == null) Tv.openUrl(s.url) else Dex.openUrl(s.url, b)
    }

    // ---------------------------------------------------------------- icons

    private fun iconFile(slug: String) = File(File(App.instance.filesDir, "site-icons").apply { mkdirs() }, "$slug.png")

    private val iconBusy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val iconFailed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The site's icon as a 192 px PNG, or null (the tile then shows the name; the icon is fetched for next time). */
    fun icon(id: String): ByteArray? {
        val s = find(id) ?: return null
        val f = iconFile(s.slug)
        if (f.isFile) return f.readBytes()
        requestIcon(s)
        return null
    }

    /** Fetches the icon in the background: one fetch per site at a time, and after a failure not again for 10 minutes. */
    private fun requestIcon(s: Site): Thread? {
        if (System.currentTimeMillis() - (iconFailed[s.slug] ?: 0L) < 10 * 60_000) return null
        if (!iconBusy.add(s.slug)) return null
        return Thread {
            try {
                val ok = runCatching { fetchIcon(s) }.onFailure { Log.w(TAG, "icon for ${s.url}", it) }.getOrDefault(false)
                if (ok) iconFailed.remove(s.slug) else iconFailed[s.slug] = System.currentTimeMillis()
            } finally { iconBusy.remove(s.slug) }
        }.apply { isDaemon = true; start() }
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

    private fun fetchIcon(s: Site): Boolean {
        if (iconFile(s.slug).isFile) return true
        if (s.bitmapPath != null && Root.available == true) {
            val b64 = Root.runOnce("base64 -w 0 '${s.bitmapPath.replace("'", "")}'", 8000)?.trim().orEmpty()
            val raw = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
            val bmp = raw?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) { keep(s.slug, tile(bmp)); bmp.recycle(); return true }
        }
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
            keep(s.slug, tile(bmp))
            bmp.recycle()
            Log.i(TAG, "icon for ${s.name} from $u")
            return true
        }
        return false
    }

    /** Written whole, then put in place: a half-written icon is never read. */
    private fun keep(slug: String, png: ByteArray) {
        val f = iconFile(slug)
        val tmp = File(f.parentFile, "$slug.tmp")
        tmp.writeBytes(png)
        if (!tmp.renameTo(f)) { f.writeBytes(png); tmp.delete() }
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
