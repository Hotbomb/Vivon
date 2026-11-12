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
    // Asked on every key, pointer move and launch: kept for a second (a display coming or going clears it).
    @Volatile private var tvAt = 0L
    @Volatile private var tvCached: Int? = null
    fun tvDisplay(): Int? {
        val n = android.os.SystemClock.uptimeMillis()
        if (n - tvAt < 1000) return tvCached
        return findTv().also { tvCached = it; tvAt = n }
    }

    private fun findTv(): Int? {
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
        // Couch Phone's own windows still on the phone's screen would pull the home there: off to the TV.
        if (d != Display.DEFAULT_DISPLAY) SetupActivity.closeOnPhone()
        if (fastStart(Intent().setComponent(comp), d, full = true)) {
            // a Couch Phone window left on the phone's screen would hold the home there: checked after
            if (d != Display.DEFAULT_DISPLAY && Root.available == true) Thread { Thread.sleep(600); moveToTv(ctx.packageName, d) }.start()
            return
        }
        if (d != Display.DEFAULT_DISPLAY && Root.available == true) moveToTv(ctx.packageName, d)
        if (!rootStart(comp, d)) plainStart(Intent().setComponent(comp), d)
    }

    // ---------------------------------------------------------------- starting fast
    //
    // `am start` through root is a new Java process each time (a few hundred ms, then more to check
    // where the app went). Couch Phone starts apps itself instead: on the TV's display and maximised
    // (ActivityOptions' hidden windowing mode), allowed from the background because it may draw over
    // other apps. The root way stays as the fallback.

    private val windowingMode: java.lang.reflect.Method? by lazy {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions;")
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
        }.onFailure { Log.w(TAG, "no launch windowing mode", it) }.getOrNull()
    }

    /** Starts an activity itself on the display, maximised when asked. False if it could not (then use root). */
    private fun fastStart(i: Intent, display: Int, full: Boolean): Boolean {
        // without "display over other apps", Android drops background starts without a word
        if (!android.provider.Settings.canDrawOverlays(ctx)) return false
        return runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(display)
            if (full) (windowingMode ?: return false).invoke(opts, 1)   // WINDOWING_MODE_FULLSCREEN
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(i, opts.toBundle())
            true
        }.onFailure { Log.w(TAG, "fast start", it) }.getOrDefault(false)
    }

    /**
     * Opens an app's main screen on the TV. Returns false if it has none.
     *
     * An app already open on the phone's own screen would come back there (Android brings back its
     * task where it is), so it is moved to the TV first, and checked once more after it opens.
     */
    fun launchPackage(pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg) ?: return false
        val d = target()
        val comp = i.component
        val full = Store.bool("apps_full_screen", true)
        // Started at once; where it went is checked after, in the background, so the remote never waits.
        val started = fastStart(Intent(i), d, full) || (comp != null && rootStart(comp, d))
        if (!started) { plainStart(i, d); return true }
        if (d != Display.DEFAULT_DISPLAY && Root.available == true) Thread {
            Thread.sleep(700)
            var t = tasksOf(pkg)
            if (t.isEmpty() || t.any { it.second == d }) return@Thread
            // It came back on the phone's screen (Android reopens an app where it was): to the TV.
            moveToTv(pkg, d, t)
            Thread.sleep(500); t = tasksOf(pkg)
            if (t.isNotEmpty() && t.none { it.second == d }) {
                // still there: close it there (like swiping it away in Recents) and open it on the TV
                t.forEach { Root.shOrNull("am stack remove ${it.first} 2>&1") }
                if (!fastStart(Intent(i), d, full) && comp != null) rootStart(comp, d)
                Thread.sleep(800)
                if (tasksOf(pkg).let { x -> x.isNotEmpty() && x.none { it.second == d } }) Log.w(TAG, "$pkg stays on the phone's screen: DeX will not show it on the TV")
            }
        }.start()
        return true
    }

    /** The app's root tasks as (task id, display id), from `am stack list` (root). */
    private fun tasksOf(pkg: String): List<Pair<Int, Int>> {
        val out = Root.shOrNull("am stack list 2>&1", 6000) ?: return emptyList()
        val res = ArrayList<Pair<Int, Int>>()
        var root = -1; var disp = -1
        val head = Regex("(?:RootTask|Stack) id=(\\d+).*?displayId=(\\d+)")
        val task = Regex("taskId=\\d+: ([^/\\s]+)/")
        for (line in out.lines()) {
            head.find(line)?.let { root = it.groupValues[1].toInt(); disp = it.groupValues[2].toInt(); return@let }
            val t = task.find(line) ?: continue
            if (t.groupValues[1] == pkg && root >= 0 && res.none { it.first == root }) res += root to disp
        }
        if (root >= 0) return res
        // No `am stack` on this build: the activity dump instead ("Display #0", then "* Task{… #12 … A=10189:com.app …").
        val dump = Root.shOrNull("dumpsys activity activities 2>&1 | grep -E '^Display #|^  \\* Task\\{'", 6000) ?: return res
        val dRe = Regex("^Display #(\\d+)"); val tRe = Regex("Task\\{\\S+ #(\\d+) .*?[AI]=\\d+:([^\\s}]+)")
        for (line in dump.lines()) {
            dRe.find(line)?.let { disp = it.groupValues[1].toInt(); return@let }
            val t = tRe.find(line) ?: continue
            if (t.groupValues[2] == pkg && disp >= 0) res += t.groupValues[1].toInt() to disp
        }
        return res
    }

    /** Moves the app's tasks that are on another display to the TV. True if any were elsewhere. */
    private fun moveToTv(pkg: String, d: Int, known: List<Pair<Int, Int>>? = null): Boolean {
        val away = (known ?: tasksOf(pkg)).filter { it.second != d }
        away.forEach { (id, _) -> Root.shOrNull("am display move-stack $id $d 2>&1")?.let { if (it.isNotBlank()) Log.i(TAG, "move $pkg: ${it.trim().take(160)}") } }
        return away.isNotEmpty()
    }

    /** Opens a web address on the TV, in `browser` if given (its window moved to the TV first). */
    fun openUrl(url: String, browser: String?) {
        val d = target()
        val fast = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply { if (browser != null) setPackage(browser) }
        if (fastStart(fast, d, Store.bool("apps_full_screen", true))) {
            if (browser != null && d != Display.DEFAULT_DISPLAY && Root.available == true) Thread { Thread.sleep(700); moveToTv(browser, d) }.start()
            return
        }
        if (Root.available == true) {
            if (browser != null && d != Display.DEFAULT_DISPLAY) moveToTv(browser, d)
            val q = "'" + url.replace("'", "'\\''") + "'"
            val out = Root.shOrNull("am start --display $d -f 0x10000000 -a android.intent.action.VIEW -d $q ${browser ?: ""} 2>&1", 12_000)
            if (out != null && !out.contains("Error") && !out.contains("Exception")) return
        }
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        if (browser != null) i.setPackage(browser)
        plainStart(i, d)
    }

    /** Opens an address in the Couch TV browser (BrowserActivity) on the TV. */
    fun openInCouch(url: String) {
        val d = target()
        val comp = ComponentName(ctx, BrowserActivity::class.java)
        if (fastStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setComponent(comp), d, full = true)) return
        if (Root.available == true) {
            val q = "'" + url.replace("'", "'\\''") + "'"
            val out = Root.shOrNull("am start --display $d --windowingMode 1 -f 0x10000000 -n '${comp.flattenToShortString()}' " +
                "-a android.intent.action.VIEW -d $q 2>&1", 12_000)
            if (out != null && !out.contains("Error") && !out.contains("Exception")) return
        }
        plainStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setComponent(comp), d)
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
            tvAt = 0L
            Log.i(TAG, "display added: $id")
            homeDisplay = -1
            SetupActivity.closeOnPhone()
            Thread { runCatching { applyTaskbar() } }.start()
            // Give DeX a few seconds to finish starting its desktop, then put the home on top.
            main.postDelayed({ if (Store.bool("open_on_dex", true) && tvDisplay() == id) Thread { openHome() }.start() }, 4000)
        }
        override fun onDisplayRemoved(id: Int) { tvAt = 0L; if (homeDisplay == id) homeDisplay = -1 }
        override fun onDisplayChanged(id: Int) {}
    }

    fun watch() {
        dm.registerDisplayListener(listener, main)
        Thread { runCatching { applyTaskbar() } }.start()
        // Already in DeX (started at boot with the TV plugged in): open the home once.
        if (tvDisplay() != null && Store.bool("open_on_dex", true)) main.postDelayed({ Thread { openHome() }.start() }, 3000)
    }
}
