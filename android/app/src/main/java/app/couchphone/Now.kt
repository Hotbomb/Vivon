package app.couchphone

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What is on the TV now, in the shape couch-tv's remote reads from GET /api/now, pushed to every listener. */
object Now {
    @Volatile private var whoNow: String? = null
    @Volatile private var whoRead = false
    var who: String?
        get() { if (!whoRead) { whoNow = Store.who(); whoRead = true }; return whoNow }
        set(v) { whoNow = v; whoRead = true; Store.saveWho(v) }
    @Volatile private var front: String? = null
    @Volatile private var lastSig = ""

    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val loop = Executors.newSingleThreadScheduledExecutor()
    private var started = false

    fun listen(fn: (String) -> Unit) = listeners.add(fn)
    fun unlisten(fn: (String) -> Unit) = listeners.remove(fn)

    fun iso(t: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(t))

    fun describe(pkg: String?): JSONObject? {
        if (pkg.isNullOrEmpty()) return null
        if (pkg == BuildConfig.APPLICATION_ID || pkg == "com.sec.android.app.desktoplauncher" || pkg == "com.sec.android.app.launcher")
            return JSONObject().put("id", "home").put("name", "Home").put("package", pkg)
        return JSONObject().put("id", pkg).put("name", Tv.label(pkg)).put("package", pkg)
    }

    fun payload(): JSONObject = JSONObject()
        .put("ok", true)
        .put("tv_connected", true)       // the server is on the phone that drives the TV: if it answers, it is there
        .put("power", if (TvScreen.tvOff) "off" else "on")
        .put("app", describe(front) ?: JSONObject.NULL)
        .put("volume", JSONObject(Tv.volume()))
        .put("playing", JSONObject.NULL)
        .put("sleep", JSONObject().put("ends", if (TvScreen.sleepEnds > 0) iso(TvScreen.sleepEnds) else JSONObject.NULL))
        .put("waking", false)
        .put("who", who ?: JSONObject.NULL)
        .put("picture", JSONObject.NULL)
        .put("mode", "on-tv")
        .put("dex", Dex.tvDisplay() != null)
        .put("root", Root.available == true)

    fun push() {
        val msg = payload().toString()
        listeners.forEach { runCatching { it(msg) } }
    }

    /** Every 5 s while someone is listening: which app is in front, and the volume. */
    @Synchronized fun start() {
        if (started) return
        started = true
        loop.scheduleWithFixedDelay({
            try {
                if (listeners.isEmpty()) return@scheduleWithFixedDelay
                front = Tv.frontPackage()
                val p = payload(); val sig = p.toString()
                if (sig != lastSig) { lastSig = sig; listeners.forEach { runCatching { it(sig) } } }
            } catch (_: Exception) {}
        }, 2, 5, TimeUnit.SECONDS)
    }
}
