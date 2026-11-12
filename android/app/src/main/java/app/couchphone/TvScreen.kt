package app.couchphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The picture on the TV and the phone's own screen while DeX is on.
 *
 *  - Phone screen off: the phone's panel is powered off (RootInput.phonePanel) as soon as DeX is on
 *    the TV, and again whenever Android turns it back on. The phone stays awake, so the TV keeps going.
 *    Unplugging the TV turns the panel back on.
 *  - Resolution and text size: `wm size` and `wm density` on the TV's display (root). This is the
 *    size DeX draws at; the signal to the TV stays what the phone and TV agreed on (Android 12 has no
 *    way to change that). Saved, and put back each time DeX starts.
 */
object TvScreen {
    private const val TAG = "couchphone.screen"
    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())
    private val dm by lazy { ctx.getSystemService(DisplayManager::class.java) }

    fun init(c: Context) { ctx = c.applicationContext }

    // ---------------------------------------------------------------- the phone's own screen

    val phoneOffWanted get() = Store.bool("phone_screen_off", true)
    @Volatile private var panelOff = false

    /** Turns the phone's panel off while DeX is on the TV (if wanted), or back on. */
    fun applyPhone() {
        val off = phoneOffWanted && Dex.tvDisplay() != null && Root.available != false
        if (!off && !panelOff) return
        runCatching { RootInput.phonePanel(off); panelOff = off }.onFailure { Log.w(TAG, "phone panel", it) }
    }

    private val keepOff = object : Runnable {
        override fun run() {
            if (Dex.tvDisplay() == null) return
            Thread { applyPhone() }.start()
            main.postDelayed(this, 20_000)
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        // Android turns the panel back on when the phone wakes (the power key, a call): off again.
        override fun onReceive(c: Context, i: Intent) { main.postDelayed({ Thread { panelOff = false; applyPhone() }.start() }, 400) }
    }

    // ---------------------------------------------------------------- DeX starting and stopping

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {
            // DeX takes a few seconds to set up its desktop.
            main.postDelayed({ if (Dex.tvDisplay() == id) Thread { runCatching { restorePicture(id) }; applyPhone() }.start() }, 5000)
            main.removeCallbacks(keepOff); main.postDelayed(keepOff, 8000)
        }
        override fun onDisplayRemoved(id: Int) {
            main.removeCallbacks(keepOff)
            if (Dex.tvDisplay() == null) Thread { applyPhone() }.start()   // the TV is gone: phone screen back on
        }
        override fun onDisplayChanged(id: Int) {}
    }

    fun watch() {
        dm.registerDisplayListener(listener, main)
        ctx.registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        Dex.tvDisplay()?.let { id -> main.postDelayed({ Thread { runCatching { restorePicture(id) }; applyPhone() }.start() }, 4000); main.postDelayed(keepOff, 8000) }
    }

    // ---------------------------------------------------------------- resolution and text size

    private fun wm(what: String, d: Int): Pair<String?, String?> {
        val out = Root.sh("wm $what -d $d 2>&1", 8000)
        fun pick(label: String) = Regex("$label (?:size|density): *(\\S+)").find(out)?.groupValues?.get(1)
        return pick("Physical") to pick("Override")
    }

    /** What the TV shows now, and the choices. */
    fun describe(): JSONObject {
        val o = JSONObject().put("phone_screen_off", phoneOffWanted)
        val d = Dex.tvDisplay() ?: return o.put("tv", false)
        o.put("tv", true)
        dm.getDisplay(d)?.let { disp ->
            val m = disp.mode
            o.put("signal", JSONObject().put("w", m.physicalWidth).put("h", m.physicalHeight).put("fps", Math.round(m.refreshRate)))
            val modes = JSONArray()
            disp.supportedModes.map { Triple(it.physicalWidth, it.physicalHeight, Math.round(it.refreshRate)) }.distinct()
                .sortedByDescending { it.first * it.second }.forEach { (w, h, f) -> modes.put(JSONObject().put("w", w).put("h", h).put("fps", f)) }
            o.put("modes", modes)
        }
        if (Root.available == false) return o.put("root", false)
        runCatching {
            val (pSize, oSize) = wm("size", d)
            val (pDen, oDen) = wm("density", d)
            o.put("size", oSize ?: pSize).put("native_size", pSize).put("density", (oDen ?: pDen)?.toIntOrNull()).put("native_density", pDen?.toIntOrNull())
            val native = pSize?.split('x')?.mapNotNull { it.toIntOrNull() }
            // Sizes up to 2560 wide (the most DeX draws on a phone), never larger than twice the signal.
            val sizes = listOf("1280x720", "1600x900", "1920x1080", "2560x1440").filter { s ->
                val w = s.substringBefore('x').toInt(); native == null || native.size < 2 || w <= native[0] * 2
            }
            o.put("sizes", JSONArray(sizes))
        }.onFailure { o.put("error", it.message) }
        return o
    }

    private val SIZE = Regex("(\\d{3,4})x(\\d{3,4})")

    /** {"size": "1920x1080" | "reset", "density": 160 | "reset", "phone_screen_off": true} */
    fun set(b: JSONObject) {
        if (b.has("phone_screen_off")) {
            Store.putBool("phone_screen_off", b.optBoolean("phone_screen_off"))
            if (!b.optBoolean("phone_screen_off")) panelOff = true   // so applyPhone turns it on
            Thread { applyPhone() }.start()
        }
        if (!b.has("size") && !b.has("density")) return
        val d = Dex.tvDisplay() ?: throw ApiError(409, "DeX is not on the TV right now.")
        if (b.has("size")) {
            val s = b.optString("size")
            if (s == "reset") { Root.sh("wm size reset -d $d"); Store.putStr("tv_size", "") }
            else {
                val m = SIZE.matchEntire(s) ?: throw ApiError(400, "size must look like 1920x1080")
                if (m.groupValues[1].toInt() !in 640..3840 || m.groupValues[2].toInt() !in 360..2160) throw ApiError(400, "that size is out of range")
                Root.sh("wm size $s -d $d"); Store.putStr("tv_size", s)
            }
        }
        if (b.has("density")) {
            val v = b.opt("density")
            if (v == "reset") { Root.sh("wm density reset -d $d"); Store.putStr("tv_density", "") }
            else {
                val n = b.optInt("density"); if (n !in 80..480) throw ApiError(400, "density must be 80 to 480")
                Root.sh("wm density $n -d $d"); Store.putStr("tv_density", n.toString())
            }
        }
    }

    /** DeX starts with its own sizes each time: put the saved ones back. */
    private fun restorePicture(d: Int) {
        if (Root.available == false) return
        Store.str("tv_size")?.takeIf { SIZE.matches(it) }?.let { Root.shOrNull("wm size $it -d $d") }
        Store.str("tv_density")?.toIntOrNull()?.let { Root.shOrNull("wm density $it -d $d") }
    }
}
