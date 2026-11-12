package app.couchphone

import android.util.Log
import org.json.JSONObject
import java.io.File
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

    /** One remote's controller. The remote keeps this handle, so a reused number never mixes two phones up. */
    class Player internal constructor(val n: Int) {
        internal var proc: Process? = null
        internal var input: OutputStreamWriter? = null
        @Volatile internal var lastLine = ""
        @Volatile internal var gone = false
    }

    // A slot is taken (reserved) before its helper starts, so starting one never holds up the others.
    private val players = arrayOfNulls<Player>(4)

    private fun binary(): File? = File(App.instance.applicationInfo.nativeLibraryDir, "libcouchmouse.so").takeIf { it.isFile }

    /** Starts the helper for player n (1 to 4): the process and its input. */
    private fun start(n: Int): Pair<Process, OutputStreamWriter> {
        val bin = binary() ?: throw ApiError(503, "This build has no controller helper.")
        val started = try { Root.startHelper(Root.nativeCommands(bin, "pad $n"), TAG) }
            catch (e: IllegalStateException) { throw ApiError(503, "The controller did not start: ${e.message}") }
        Log.i(TAG, "player $n plugged in")
        return started
    }

    /** Plugs in a controller for a new remote (the lowest free player number, 1 to 4). */
    fun join(): Player {
        val me = synchronized(this) {
            // a slot whose helper died is free again (its remote is told on its next message)
            players.forEachIndexed { i, p -> if (p != null && p.proc != null && p.proc?.isAlive != true) { p.gone = true; players[i] = null } }
            val i = players.indexOfFirst { it == null }
            if (i < 0) throw ApiError(409, "Four controllers are connected already.")
            Player(i + 1).also { players[i] = it }
        }
        try {
            val (proc, input) = start(me.n)
            synchronized(me) { me.proc = proc; me.input = input }
            return me
        } catch (e: Exception) {
            synchronized(this) { if (players[me.n - 1] === me) players[me.n - 1] = null }
            throw e
        }
    }

    /** Unplugs this remote's controller (it closed the Controller screen, or went away). */
    fun leave(p: Player) {
        synchronized(this) { if (players[p.n - 1] === p) players[p.n - 1] = null }
        p.gone = true
        synchronized(p) {
            runCatching { p.input?.write("s 0 0 0 0 0 0 0 0 0\n"); p.input?.flush() }
            runCatching { p.input?.close() }; runCatching { p.proc?.destroy() }
            p.input = null; p.proc = null
        }
        Log.i(TAG, "player ${p.n} unplugged")
    }

    /** {"lx","ly","rx","ry": -1..1, "lt","rt": 0..1, "hx","hy": -1, 0 or 1, "b": button bits} */
    fun state(p: Player, s: JSONObject) {
        if (p.gone) throw ApiError(409, "This controller stopped. Close it and open it again.")
        fun stick(k: String) = (s.optDouble(k, 0.0).coerceIn(-1.0, 1.0) * 32767).toInt()
        fun trig(k: String) = (s.optDouble(k, 0.0).coerceIn(0.0, 1.0) * 255).toInt()
        fun hat(k: String) = s.optInt(k).coerceIn(-1, 1)
        val line = "s ${stick("lx")} ${stick("ly")} ${stick("rx")} ${stick("ry")} ${trig("lt")} ${trig("rt")} ${hat("hx")} ${hat("hy")} ${s.optInt("b") and 0x7FF}"
        if (line == p.lastLine) return
        try {
            synchronized(p) { val w = p.input ?: throw IllegalStateException("unplugged"); w.write(line + "\n"); w.flush() }
            p.lastLine = line
        } catch (e: Exception) { leave(p); throw ApiError(503, "The controller stopped. Close it and open it again.") }
    }

}
