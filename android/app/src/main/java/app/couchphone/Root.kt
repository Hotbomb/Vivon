package app.couchphone

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Root access, through Magisk's `su`. Two long-lived root processes:
 *  - a shell for commands (am start, dumpsys, settings), kept open so each command is quick;
 *  - the input helper (root/Injector.java under app_process), for keys and typing with no delay.
 * Both are started on first use and again if they die.
 */
object Root {
    private const val TAG = "couchphone.root"
    private lateinit var ctx: Context

    @Volatile var available: Boolean? = null   // null: not asked yet
        private set
    @Volatile var lastError: String? = null
        internal set

    fun init(c: Context) { ctx = c.applicationContext }

    // ---------------------------------------------------------------- the command shell

    private var shell: Process? = null
    private var shellIn: OutputStreamWriter? = null
    private var shellOut: BufferedReader? = null

    @Synchronized fun openShell(): Boolean {
        if (shell?.isAlive == true) return true
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            shell = p
            shellIn = OutputStreamWriter(p.outputStream)
            shellOut = BufferedReader(InputStreamReader(p.inputStream))
            // The first command proves we really are root (Magisk may have said no).
            val id = runLocked("id", 20_000)
            val ok = id.contains("uid=0")
            available = ok
            if (!ok) { lastError = "su answered but not as root: ${id.take(80)}"; closeShell() }
            ok
        } catch (e: Exception) {
            available = false; lastError = "no root: ${e.message}"
            Log.w(TAG, "su failed", e); closeShell(); false
        }
    }

    private fun closeShell() {
        runCatching { shell?.destroy() }
        shell = null; shellIn = null; shellOut = null
    }

    /** Runs a command as root and returns its output (stdout and stderr together). */
    @Synchronized fun sh(cmd: String, timeoutMs: Long = 10_000): String {
        if (!openShell()) throw ApiError(503, "Root is not available. Open Magisk and allow Couch Phone.")
        return try { runLocked(cmd, timeoutMs) } catch (e: Exception) { closeShell(); throw ApiError(502, "root command failed: ${e.message}") }
    }

    fun shOrNull(cmd: String, timeoutMs: Long = 10_000): String? = runCatching { sh(cmd, timeoutMs) }.getOrNull()

    private fun runLocked(cmd: String, timeoutMs: Long): String {
        val mark = "__cp_end_${System.nanoTime()}__"
        val w = shellIn ?: throw IllegalStateException("no shell")
        val r = shellOut ?: throw IllegalStateException("no shell")
        w.write("$cmd\necho $mark\n"); w.flush()
        val out = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (System.currentTimeMillis() > end) throw IllegalStateException("timed out: $cmd")
            if (!r.ready()) {
                if (shell?.isAlive != true) throw IllegalStateException("root shell closed")
                Thread.sleep(5); continue
            }
            val line = r.readLine() ?: throw IllegalStateException("root shell closed")
            if (line == mark) break
            out.append(line).append('\n')
        }
        return out.toString()
    }

    fun status(): Map<String, Any?> = mapOf("root" to available, "input" to RootInput.running, "error" to lastError)

    @Suppress("unused") private fun waitFor(p: Process, ms: Long) = p.waitFor(ms, TimeUnit.MILLISECONDS)
}

/** The root input helper (root/Injector.java), its own lock so a slow root command never holds up a key. */
object RootInput {

    private var helper: Process? = null
    private var helperIn: OutputStreamWriter? = null
    private var helperErr: String? = null

    val running get() = helper?.isAlive == true

    @Synchronized private fun openHelper(): Boolean {
        if (helper?.isAlive == true) return true
        if (!Root.openShell()) return false
        return try {
            val apk = App.instance.applicationInfo.sourceDir
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            val w = OutputStreamWriter(p.outputStream)
            w.write("export CLASSPATH='$apk'\nexec app_process /system/bin app.couchphone.root.Injector\n"); w.flush()
            val r = BufferedReader(InputStreamReader(p.inputStream))
            // app_process may print warnings first: wait for "ready" (or "fail ...").
            var first: String? = null
            val until = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < until) {
                first = readLineWithin(r, p, until - System.currentTimeMillis()) ?: break
                if (first == "ready" || first.startsWith("fail")) break
            }
            if (first != "ready") {
                helperErr = "input helper did not start: ${first ?: "no answer"}"
                Root.lastError = helperErr; Log.w("couchphone.root", helperErr!!); p.destroy(); return false
            }
            helper = p; helperIn = w; helperErr = null
            // Keep reading what it says (errors, "skipped"), so its output pipe never fills up.
            Thread {
                try { while (true) { val l = r.readLine() ?: break; Log.i("couchphone.root", "helper: $l"); lastHelperLine = l } } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            true
        } catch (e: Exception) { helperErr = e.message; Log.w("couchphone.root", "helper", e); false }
    }

    @Volatile private var lastHelperLine: String? = null

    private fun readLineWithin(r: BufferedReader, p: Process, ms: Long): String? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (r.ready()) return r.readLine()
            if (!p.isAlive) return if (r.ready()) r.readLine() else null
            Thread.sleep(10)
        }
        return null
    }

    @Synchronized private fun helperSend(line: String) {
        if (!openHelper()) throw ApiError(503, "Root input is not available. ${helperErr ?: Root.lastError ?: "Open Magisk and allow Couch Phone."}")
        try { helperIn!!.write(line + "\n"); helperIn!!.flush() }
        catch (e: Exception) {
            // It died: start it again once and resend.
            runCatching { helper?.destroy() }; helper = null
            if (!openHelper()) throw ApiError(503, "Root input stopped working.")
            helperIn!!.write(line + "\n"); helperIn!!.flush()
        }
    }

    fun key(code: Int, display: Int, longPress: Boolean = false, meta: Int = 0) = helperSend("k $code $display ${if (longPress) 1 else 0} $meta")

    fun text(s: String, display: Int) =
        helperSend("t $display " + Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))

    /** The phone's own screen panel: off (true) or on. The TV is not affected. */
    fun phonePanel(off: Boolean) = helperSend("s ${if (off) 0 else 2}")

    /** Starts root early (asks Magisk once) so the first remote press is fast. */
    fun warmUp() { Thread { runCatching { synchronized(this) { openHelper() } } }.start() }
}
