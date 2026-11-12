package app.couchphone

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display

/**
 * The DeX screen: which display it is, and opening things on it.
 *
 * With DeX on an HDMI TV the phone has two displays: 0 (its own screen) and the TV. Samsung's DeX
 * desktop always owns the TV's home, so this app does not try to replace it; instead it opens its
 * own home full screen on the TV, and every key and app the remote sends goes to that display.
 */
object Dex {
    private const val TAG = "couchphone.dex"
    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())
    private val dm by lazy { ctx.getSystemService(DisplayManager::class.java) }

    /** The display the home page was last seen on, when it is not the phone's own screen. */
    @Volatile var homeDisplay: Int = -1

    fun init(c: Context) { ctx = c.applicationContext }

    /** Hidden Display.getType(): 1 built in, 2 HDMI/USB-C, 3 Wi-Fi (Smart View), 4 overlay, 5 virtual. */
    private fun typeOf(d: Display): Int = runCatching { Display::class.java.getMethod("getType").invoke(d) as Int }.getOrDefault(-1)

    /** The TV's display id, or null when DeX is not on a TV right now. */
    fun tvDisplay(): Int? {
        val all = dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRIVATE == 0 }
        if (homeDisplay > 0 && all.any { it.displayId == homeDisplay }) return homeDisplay
        val pick = all.firstOrNull { typeOf(it) == 2 } ?: all.firstOrNull { typeOf(it) == 3 }
            ?: all.filter { typeOf(it) != 5 }.maxByOrNull { val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") it.getRealMetrics(m); m.widthPixels * m.heightPixels }
        return pick?.displayId
    }

    /** Where keys and apps go: the TV when DeX is on, else the phone's own screen. */
    fun target(): Int = tvDisplay() ?: Display.DEFAULT_DISPLAY

    fun describe(): Map<String, Any?> {
        val list = dm.displays.map { mapOf("id" to it.displayId, "name" to it.name, "type" to typeOf(it)) }
        return mapOf("tv" to tvDisplay(), "displays" to list)
    }

    /** Opens the home page on the TV (or the phone, without DeX), full screen. */
    fun openHome() {
        val d = target()
        val comp = ComponentName(ctx, HomeActivity::class.java)
        if (!rootStart(comp, d)) plainStart(Intent().setComponent(comp), d)
    }

    /** Opens an app's main screen on the TV. Returns false if it has none. */
    fun launchPackage(pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg) ?: return false
        val d = target()
        val comp = i.component
        if (comp != null && rootStart(comp, d)) return true
        plainStart(i, d)
        return true
    }

    /** Opens a system screen by its action (Bluetooth or Wi-Fi settings) on the TV. */
    fun openAction(action: String): Boolean {
        val d = target()
        if (Root.available != false) {
            val out = Root.shOrNull("am start --display $d -f 0x10000000 -a $action 2>&1", 12_000)
            if (out != null && !out.contains("Error") && !out.contains("Exception")) return true
        }
        plainStart(Intent(action), d)
        return true
    }

    /**
     * Root start: `am start --display` puts it on the TV even while this app is in the background,
     * and windowing mode 1 (full screen) opens it maximised instead of in a small DeX window.
     */
    private fun rootStart(comp: ComponentName, display: Int): Boolean {
        if (Root.available == false) return false
        val full = if (Store.bool("apps_full_screen", true) || comp.packageName == ctx.packageName) "--windowingMode 1 " else ""
        val out = Root.shOrNull("am start --display $display $full-f 0x10200000 -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER -n '${comp.flattenToShortString()}' 2>&1", 12_000) ?: return false
        if (out.contains("Error") || out.contains("Exception")) {
            Log.w(TAG, "am start: ${out.trim().take(200)}")
            // Some builds refuse the windowing mode: try once more without it.
            if (full.isNotEmpty()) {
                val again = Root.shOrNull("am start --display $display -f 0x10200000 -n '${comp.flattenToShortString()}' 2>&1", 12_000) ?: return false
                return !again.contains("Error")
            }
            return false
        }
        return true
    }

    private fun plainStart(i: Intent, display: Int) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()
        main.post { runCatching { ctx.startActivity(i, opts) }.onFailure { Log.w(TAG, "start on $display", it) } }
    }

    // ---------------------------------------------------------------- DeX's taskbar

    /**
     * Apps full screen: DeX's own "Auto hide taskbar" switch (Samsung DeX settings, or right-click
     * the taskbar). The taskbar then stays out of the way until the pointer touches the bottom edge.
     * Samsung does not document the setting's name, so it is found by looking: a 0/1 setting whose
     * name says taskbar and auto hide. What was found is kept in `taskbarNote` for the settings API.
     */
    @Volatile var taskbarNote: String? = null
        private set
    private val autoHide = Regex("(?i)(auto_?hide.*task_?bar|task_?bar.*auto_?hid)")

    fun applyTaskbar() {
        if (Root.available == false) { taskbarNote = "needs root"; return }
        val want = if (Store.bool("hide_taskbar", true)) "1" else "0"
        val out = Root.shOrNull("for n in system secure global; do settings list \$n 2>/dev/null | grep -i -e taskbar -e task_bar | sed \"s/^/\$n /\"; done", 15_000)
            ?: run { taskbarNote = "could not read the settings"; return }
        val found = out.lines().mapNotNull { line ->
            val ns = line.substringBefore(' ', ""); val kv = line.substringAfter(' ', "")
            val key = kv.substringBefore('=', ""); val value = kv.substringAfter('=', "").trim()
            if (ns.isEmpty() || key.isEmpty() || !autoHide.containsMatchIn(key) || value !in setOf("0", "1")) null else Triple(ns, key, value)
        }
        if (found.isEmpty()) {
            taskbarNote = "DeX's auto hide switch was not found: turn it on in Settings, Samsung DeX, or right-click the taskbar"
            Log.i(TAG, "taskbar settings seen: ${out.trim().take(400)}"); return
        }
        for ((ns, key, value) in found) if (value != want) Root.shOrNull("settings put $ns $key $want")
        taskbarNote = (if (want == "1") "hides itself" else "always shown") + " (" + found.joinToString { "${it.first} ${it.second}" } + ")"
        Log.i(TAG, "taskbar: $taskbarNote")
    }

    // ---------------------------------------------------------------- DeX starting

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {
            Log.i(TAG, "display added: $id")
            homeDisplay = -1
            Thread { runCatching { applyTaskbar() } }.start()
            // Give DeX a few seconds to finish starting its desktop, then put the home on top.
            main.postDelayed({ if (Store.bool("open_on_dex", true) && tvDisplay() == id) Thread { openHome() }.start() }, 4000)
        }
        override fun onDisplayRemoved(id: Int) { if (homeDisplay == id) homeDisplay = -1 }
        override fun onDisplayChanged(id: Int) {}
    }

    fun watch() {
        dm.registerDisplayListener(listener, main)
        Thread { runCatching { applyTaskbar() } }.start()
        // Already in DeX (started at boot with the TV plugged in): open the home once.
        if (tvDisplay() != null && Store.bool("open_on_dex", true)) main.postDelayed({ Thread { openHome() }.start() }, 3000)
    }
}
