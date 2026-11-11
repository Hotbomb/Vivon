package app.couchphone

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * The TV's mouse pointer, for the remote's touchpad and air mouse.
 *
 * A small native helper (android/native/couchmouse.c, packed in the APK as libcouchmouse.so) runs
 * as root and makes a virtual mouse with Linux uinput. To Android and DeX it is a real mouse: the
 * pointer shows on the TV, and clicks, right clicks and the wheel work in every app.
 */
object Pointer {
    private const val TAG = "couchphone.pointer"
    private var proc: Process? = null
    private var input: OutputStreamWriter? = null
    @Volatile var lastError: String? = null
        private set

    val running get() = proc?.isAlive == true

    private fun binary(): File? = File(App.instance.applicationInfo.nativeLibraryDir, "libcouchmouse.so").takeIf { it.isFile }

    @Synchronized private fun open(): Boolean {
        if (proc?.isAlive == true) return true
        val bin = binary()
        if (bin == null) { lastError = "this build has no mouse helper"; return false }
        if (!Root.openShell()) { lastError = Root.lastError ?: "no root"; return false }
        // Straight from the app's library folder, or (if the phone will not run it from there) a copy
        // in /data/local/tmp, the usual place for root helpers.
        val direct = "exec '${bin.absolutePath}'"
        val copied = "cp '${bin.absolutePath}' /data/local/tmp/couchmouse && chmod 755 /data/local/tmp/couchmouse && exec /data/local/tmp/couchmouse"
        return start(direct) || start(copied)
    }

    private fun start(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            val w = OutputStreamWriter(p.outputStream)
            // If exec itself fails, the shell carries on to the echo, so the answer comes at once.
            w.write("$cmd; echo 'fail could not run the mouse helper'\n"); w.flush()
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var first: String? = null
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until) {
                if (r.ready()) { first = r.readLine(); if (first == "ready" || first?.startsWith("fail") == true) break }
                else if (!p.isAlive) break
                else Thread.sleep(10)
            }
            if (first != "ready") {
                lastError = "mouse helper did not start: ${first ?: "no answer"}"
                Log.w(TAG, lastError!!); p.destroy(); return false
            }
            proc = p; input = w; lastError = null
            Thread {
                try { while (true) { val l = r.readLine() ?: break; Log.i(TAG, "helper: $l") } } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            true
        } catch (e: Exception) { lastError = e.message; Log.w(TAG, "start", e); false }
    }

    @Volatile private var lastUse = 0L
    private var idleWatch: Thread? = null

    /** Unplugs the virtual mouse after 10 minutes unused, so DeX is left as it was. */
    @Synchronized private fun watchIdle() {
        if (idleWatch?.isAlive == true) return
        idleWatch = Thread {
            while (true) {
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { return@Thread }
                synchronized(this) {
                    if (proc != null && System.currentTimeMillis() - lastUse > 10 * 60_000) {
                        runCatching { input?.close() }; runCatching { proc?.destroy() }
                        proc = null; input = null
                        Log.i(TAG, "mouse unplugged after 10 minutes unused")
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized private fun send(line: String) {
        lastUse = System.currentTimeMillis()
        watchIdle()
        if (!open()) throw ApiError(503, "The TV pointer is not available: ${lastError ?: "no root"}")
        try { input!!.write(line + "\n"); input!!.flush() }
        catch (e: Exception) {
            runCatching { proc?.destroy() }; proc = null
            if (!open()) throw ApiError(503, "The TV pointer stopped working.")
            input!!.write(line + "\n"); input!!.flush()
        }
    }

    private fun btn(b: String) = when (b) { "left", "l" -> "l"; "right", "r" -> "r"; "middle", "m" -> "m"; else -> throw ApiError(400, "button must be left, right or middle") }

    fun move(dx: Int, dy: Int) { if (dx != 0 || dy != 0) send("m $dx $dy") }
    fun scroll(v: Int, h: Int = 0) { if (v != 0 || h != 0) send("w $v $h") }
    fun click(b: String = "left") = send("c ${btn(b)}")
    fun press(b: String, down: Boolean) = send("b ${btn(b)} ${if (down) 1 else 0}")

    /**
     * One message from the remote, the same shape as couch-tv's LG pointer plus buttons:
     * {"dx":4,"dy":-2,"scroll":3,"hscroll":0,"click":true,"button":"right","action":"click|down|up"}
     * Scroll is in screen pixels from the touchpad; about 40 make one wheel click.
     */
    private var scrollRest = 0
    fun handle(b: org.json.JSONObject) {
        move(b.optInt("dx"), b.optInt("dy"))
        val px = b.optInt("scroll") + scrollRest
        val clicks = px / 40
        scrollRest = px - clicks * 40
        // Finger up the pad means content up, like a phone: wheel down.
        scroll(-clicks, b.optInt("hscroll"))
        if (b.optBoolean("click")) click("left")
        if (b.has("button")) {
            val which = b.optString("button", "left")
            when (b.optString("action", "click")) {
                "down" -> press(which, true)
                "up" -> press(which, false)
                else -> click(which)
            }
        }
    }
}
