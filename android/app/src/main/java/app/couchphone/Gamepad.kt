package app.couchphone

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Game controllers from the remote: each phone that opens the remote's Controller screen becomes
 * a player (1 to 4), a real controller on the phone that drives the TV. The native helper
 * (android/native/couchmouse.c, run as "pad <n>" as root) makes it with Linux uinput and an Xbox
 * 360 controller's ids, so Android, Steam Link and games such as Terraria see an ordinary controller.
 *
 * The remote sends the controller's whole state over the WebSocket each time it changes.
 */
object Gamepad {
    private const val TAG = "couchphone.pad"

    private class Player(val n: Int, val proc: Process, val input: OutputStreamWriter) {
        @Volatile var lastUse = System.currentTimeMillis()
        @Volatile var lastLine = ""
    }

    private val players = arrayOfNulls<Player>(4)

    private fun binary(): File? = File(App.instance.applicationInfo.nativeLibraryDir, "libcouchmouse.so").takeIf { it.isFile }

    /** Starts player n's controller (1 to 4). */
    private fun start(n: Int): Player {
        val bin = binary() ?: throw ApiError(503, "This build has no controller helper.")
        if (!Root.openShell()) throw ApiError(503, "The controller needs root. Open Magisk and allow Couch Phone.")
        val tries = listOf("exec '${bin.absolutePath}' pad $n",
            "cp '${bin.absolutePath}' /data/local/tmp/couchmouse && chmod 755 /data/local/tmp/couchmouse && exec /data/local/tmp/couchmouse pad $n")
        var why = "no answer"
        for (cmd in tries) {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            val w = OutputStreamWriter(p.outputStream)
            w.write("$cmd; echo 'fail could not run the controller helper'\n"); w.flush()
            val r = BufferedReader(InputStreamReader(p.inputStream))
            var first: String? = null
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until) {
                if (r.ready()) { first = r.readLine(); if (first == "ready" || first?.startsWith("fail") == true) break }
                else if (!p.isAlive) break
                else Thread.sleep(10)
            }
            if (first == "ready") {
                Thread { try { while (true) { val l = r.readLine() ?: break; Log.i(TAG, "player $n: $l") } } catch (_: Exception) {} }
                    .apply { isDaemon = true }.start()
                Log.i(TAG, "player $n plugged in")
                return Player(n, p, w)
            }
            why = first ?: "no answer"
            p.destroy()
        }
        throw ApiError(503, "The controller did not start: $why")
    }

    /** A free player number for a new remote, or an error when four are playing already. */
    @Synchronized fun join(): Int {
        val i = players.indexOfFirst { it == null || !it.proc.isAlive }
        if (i < 0) throw ApiError(409, "Four controllers are connected already.")
        players[i] = start(i + 1)
        return i + 1
    }

    /** Unplugs player n's controller (the remote closed its Controller screen, or went away). */
    @Synchronized fun leave(n: Int) {
        val p = players.getOrNull(n - 1) ?: return
        runCatching { p.input.write("s 0 0 0 0 0 0 0 0 0\n"); p.input.flush() }
        runCatching { p.input.close() }; runCatching { p.proc.destroy() }
        players[n - 1] = null
        Log.i(TAG, "player $n unplugged")
    }

    /** {"lx","ly","rx","ry": -1..1, "lt","rt": 0..1, "hx","hy": -1, 0 or 1, "b": button bits} */
    fun state(n: Int, s: JSONObject) {
        val p = synchronized(this) { players.getOrNull(n - 1) } ?: throw ApiError(409, "This controller is not connected.")
        fun stick(k: String) = (s.optDouble(k, 0.0).coerceIn(-1.0, 1.0) * 32767).toInt()
        fun trig(k: String) = (s.optDouble(k, 0.0).coerceIn(0.0, 1.0) * 255).toInt()
        fun hat(k: String) = s.optInt(k).coerceIn(-1, 1)
        val line = "s ${stick("lx")} ${stick("ly")} ${stick("rx")} ${stick("ry")} ${trig("lt")} ${trig("rt")} ${hat("hx")} ${hat("hy")} ${s.optInt("b") and 0x7FF}"
        p.lastUse = System.currentTimeMillis()
        if (line == p.lastLine) return
        try { synchronized(p) { p.input.write(line + "\n"); p.input.flush() }; p.lastLine = line }
        catch (e: Exception) { leave(n); throw ApiError(503, "The controller stopped. Close and open it again.") }
    }
}
