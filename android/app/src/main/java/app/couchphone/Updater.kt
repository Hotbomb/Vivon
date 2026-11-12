package app.couchphone

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates Couch Phone by itself: reads the newest release on GitHub, downloads its
 * couch-phone-*.apk and installs it over this one with root (`pm install -r`). Android then
 * restarts the app (BootReceiver on MY_PACKAGE_REPLACED), which opens the home on the TV again.
 *
 * Started from the TV's "Connect a remote" screen or the phone remote's TV sheet.
 */
object Updater {
    private const val TAG = "couchphone.update"
    private const val LATEST = "https://api.github.com/repos/Hotbomb/Vivon/releases/latest"

    // what is known: the newest release and what this phone is doing about it
    @Volatile private var latest: String? = null
    @Volatile private var apkUrl: String? = null
    @Volatile private var apkSize = 0L
    @Volatile private var checkedAt = 0L
    @Volatile private var checking = false
    @Volatile private var status = "idle"            // idle, downloading, installing, failed
    @Volatile private var progress = 0
    @Volatile private var error: String? = null

    /** "0.1.15" to [0, 1, 15], to compare versions number by number. */
    private fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private fun get(url: String, timeout: Int = 10_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout; instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "couch-phone-updater")
        }

    /** Asks GitHub (at most every 10 minutes, unless forced). */
    fun check(force: Boolean = false) {
        if (checking || (!force && System.currentTimeMillis() - checkedAt < 10 * 60_000)) return
        checking = true
        try {
            val c = get(LATEST)
            try {
                if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val assets = j.optJSONArray("assets")
                var url: String? = null; var size = 0L
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    if (a.optString("name").matches(Regex("couch-phone-.*\\.apk"))) { url = a.optString("browser_download_url"); size = a.optLong("size") }
                }
                latest = j.optString("tag_name").removePrefix("v").ifEmpty { null }
                apkUrl = url; apkSize = size
                checkedAt = System.currentTimeMillis()
                if (status == "failed") { status = "idle"; error = null }
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Log.w(TAG, "check", e); error = "Could not reach GitHub: ${e.message}"
        } finally { checking = false }
    }

    /** What the screens show; looks again in the background when the last look is old. */
    fun summary(): JSONObject {
        if (System.currentTimeMillis() - checkedAt > 10 * 60_000 && !checking) Thread { check() }.start()
        val l = latest
        val available = l != null && apkUrl != null && newer(l, BuildConfig.VERSION_NAME)
        return JSONObject()
            .put("current", BuildConfig.VERSION_NAME)
            .put("latest", l ?: JSONObject.NULL)
            .put("available", available)
            .put("checking", checking || (checkedAt == 0L && error == null))
            .put("status", status).put("progress", progress)
            .put("error", error ?: JSONObject.NULL)
    }

    /** Downloads and installs the newest release, in the background. */
    @Synchronized fun start(): JSONObject {
        if (status == "downloading" || status == "installing") return summary()
        check(force = true)
        val l = latest; val url = apkUrl
        if (l == null || url == null) throw ApiError(503, error ?: "No release found on GitHub.")
        if (!newer(l, BuildConfig.VERSION_NAME)) throw ApiError(409, "Couch Phone ${BuildConfig.VERSION_NAME} is already the newest.")
        if (Root.available == false) throw ApiError(503, "Updating needs root. Open Magisk and allow Couch Phone.")
        status = "downloading"; progress = 0; error = null
        Thread { run(l, url) }.start()
        return summary()
    }

    private fun run(version: String, url: String) {
        val file = File(App.instance.cacheDir, "update.apk")
        try {
            val c = get(url, 30_000)
            try {
                if (c.responseCode != 200) throw IllegalStateException("the download answered ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: apkSize
                c.inputStream.use { inp ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) progress = (done * 100 / total).toInt()
                        }
                    }
                }
            } finally { c.disconnect() }
            if (apkSize > 0 && file.length() != apkSize) throw IllegalStateException("the download was cut short")
            status = "installing"; progress = 100
            Log.i(TAG, "installing $version")
            // pm reads it from /data/local/tmp (the app's own cache is private). Android stops this app
            // when the install is done, then starts the new one.
            val tmp = "/data/local/tmp/couch-phone-update.apk"
            val out = Root.sh("cp '${file.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp 2>&1; rm -f $tmp", 180_000)
            if (out.contains("Success")) return   // Android stops this app in a moment, then starts the new one
            throw IllegalStateException(out.lines().lastOrNull { it.isNotBlank() }?.take(160) ?: "the install did not finish")
        } catch (e: Exception) {
            Log.w(TAG, "update", e)
            status = "failed"; error = "The update did not install: ${e.message}"
        } finally { file.delete() }
    }
}
