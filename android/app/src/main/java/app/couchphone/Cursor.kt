package app.couchphone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Couch Phone's own mouse pointer on the TV, for the remote's touchpad and air mouse.
 *
 * An arrow drawn in an overlay window on the TV's display (it shows the moment the remote's
 * pointer button is pressed), and real mouse events sent where it points by the root helper
 * (root/Injector.java): hover, click, right click, drag and the wheel, as from a plugged-in mouse,
 * so Chrome and every other app work with it. It hides itself after 3 minutes unused.
 *
 * If the overlay cannot be shown (no "display over other apps" permission), Pointer.kt's virtual
 * mouse (uinput) takes over instead.
 */
object Cursor {
    private const val TAG = "couchphone.cursor"
    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())
    private val dm by lazy { ctx.getSystemService(DisplayManager::class.java) }

    private var wm: WindowManager? = null
    @Volatile private var root: FrameLayout? = null
    private var arrow: View? = null
    @Volatile private var display = -1
    @Volatile var x = 0f; private set
    @Volatile var y = 0f; private set
    @Volatile private var w = 1920; @Volatile private var h = 1080

    fun init(c: Context) { ctx = c.applicationContext }

    /** Whether this pointer can be used: root for the mouse events, the overlay permission for the arrow. */
    fun available(): Boolean = Root.available != false && Settings.canDrawOverlays(ctx)

    private val hide = Runnable { removeView() }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val done = CountDownLatch(1)
        main.post { try { block() } finally { done.countDown() } }
        done.await(3, TimeUnit.SECONDS)
    }

    private fun removeView() {
        val r = root ?: return
        runCatching { wm?.removeViewImmediate(r) }
        root = null; arrow = null; wm = null; display = -1
    }

    /** Shows the arrow on the TV (or the phone's screen without DeX). False if it cannot. */
    @Synchronized private fun ensure(): Boolean {
        val d = Dex.target()
        val disp = dm.getDisplay(d) ?: return false
        val size = Point().also { @Suppress("DEPRECATION") disp.getRealSize(it) }   // what apps see, after `wm size`
        // Already showing on that display at that size: nothing to wait for (the usual case, many times a second).
        if (root != null && display == d && size.x == w && size.y == h) return true
        var ok = true
        onMain {
            if (root != null && display == d) {
                if (size.x != w || size.y != h) { x = x * size.x / w; y = y * size.y / h; w = size.x; h = size.y; place() }
                return@onMain
            }
            removeView()
            try {
                val dc = ctx.createDisplayContext(disp)
                val wc = if (Build.VERSION.SDK_INT >= 30) dc.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else dc
                val m = wc.getSystemService(WindowManager::class.java)
                val fl = FrameLayout(wc)
                val a = Arrow(wc)
                val px = (17 * wc.resources.displayMetrics.density).toInt().coerceAtLeast(16)
                fl.addView(a, FrameLayout.LayoutParams(px, (px * 1.6f).toInt(), Gravity.TOP or Gravity.START))
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT)
                lp.gravity = Gravity.TOP or Gravity.START
                lp.title = "Couch Phone pointer"
                // Android 12 lets clicks through another app's overlay only up to 80% opacity.
                lp.alpha = 0.8f
                if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                m.addView(fl, lp)
                wm = m; root = fl; arrow = a; display = d
                if (size.x != w || size.y != h || x == 0f && y == 0f) { w = size.x; h = size.y; x = w / 2f; y = h / 2f }
                place()
            } catch (e: Exception) {
                Log.w(TAG, "overlay", e); ok = false; removeView()
            }
        }
        return ok && root != null
    }

    private fun place() {
        val a = arrow ?: return
        val tip = a.layoutParams.width / 13f * 0.7f   // the arrow's tip sits this far into its view
        a.translationX = x - tip; a.translationY = y - tip
    }

    private fun used() { main.removeCallbacks(hide); main.postDelayed(hide, 3 * 60_000L) }

    // ---------------------------------------------------------------- moving at the finger's pace
    //
    // Wi-Fi delivers the remote's moves in bursts (a phone's Wi-Fi saves power by sending in batches),
    // so drawing each burst when it lands makes the pointer jump to where the finger already is. The
    // remote stamps each move with its own clock ("t", ms); here every move is replayed at that pace,
    // a little behind the finger (as little as the Wi-Fi allows), so the pointer moves as the finger did: smooth, at its speed.
    // Clicks, scrolls and buttons wait in the same line, so they land where the pointer is by then.

    private const val MIN_BUFFER = 40L    // ms behind the finger, at the least (steady Wi-Fi)
    private const val MAX_BUFFER = 180L   // and at the most (Wi-Fi sending in bursts)
    private class Step(val due: Long, val seq: Long, val dx: Float, val dy: Float, val act: (() -> Unit)?)
    private val steps = java.util.concurrent.PriorityBlockingQueue<Step>(64, compareBy<Step>({ it.due }, { it.seq }))
    private val wake = Object()
    @Volatile private var seq = 0L
    @Volatile private var lastDue = 0L
    private var player: Thread? = null
    // (local time - remote time) over the last 2 seconds: the smallest is the least delayed delivery
    private val offsets = ArrayDeque<Pair<Long, Double>>()

    private fun now() = android.os.SystemClock.uptimeMillis()

    @Synchronized private fun dueFor(t: Double, now: Long): Long {
        val off = now - t
        offsets.addLast(now to off)
        while (offsets.isNotEmpty() && now - offsets.first().first > 2000) offsets.removeFirst()
        val min = offsets.minOf { it.second }
        // How unevenly the Wi-Fi delivers (90th percentile of the extra delay), plus a little: just
        // enough waiting that no move arrives after its turn, so the pointer never jumps. Good Wi-Fi:
        // about 45 ms behind the finger; Wi-Fi that sends in bursts: up to 180 ms, but smooth.
        val jitter = offsets.map { it.second - min }.sorted()
        val buffer = (jitter[(jitter.size * 9) / 10].toLong() + 15).coerceIn(MIN_BUFFER, MAX_BUFFER)
        return (t + min).toLong() + buffer
    }

    private fun add(due: Long, dx: Float, dy: Float, act: (() -> Unit)? = null) {
        val n = now()
        val d = due.coerceIn(n, n + 400)
        synchronized(this) { seq += 1; if (d > lastDue) lastDue = d; steps.add(Step(d, seq, dx, dy, act)) }
        startPlayer()
        synchronized(wake) { wake.notifyAll() }
    }

    /** After every move already in line: clicks and scrolls happen where the pointer will be. */
    private fun then(act: () -> Unit) = add(maxOf(now(), lastDue), 0f, 0f, act)

    @Synchronized private fun startPlayer() {
        if (player?.isAlive == true) return
        player = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            while (true) {
                try {
                    val head = steps.peek()
                    if (head == null) { synchronized(wake) { wake.wait(1000) }; continue }
                    val n = now()
                    if (head.due > n) { synchronized(wake) { wake.wait((head.due - n).coerceIn(1, 16)) }; continue }
                    var dx = 0f; var dy = 0f
                    while (true) {
                        val s = steps.peek() ?: break
                        if (s.due > n) break
                        steps.poll()
                        if (s.act != null) {
                            if (dx != 0f || dy != 0f) { moveBy(dx, dy); dx = 0f; dy = 0f }
                            runCatching { s.act.invoke() }.onFailure { Log.w(TAG, "pointer action", it) }
                        } else { dx += s.dx; dy += s.dy }
                    }
                    if (dx != 0f || dy != 0f) moveBy(dx, dy)
                    Thread.sleep(4)
                } catch (e: InterruptedException) { return@Thread } catch (e: Exception) { Log.w(TAG, "pointer", e) }
            }
        }.apply { isDaemon = true; name = "couch-pointer"; start() }
    }

    private fun moveBy(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, w - 1f); y = (y + dy).coerceIn(0f, h - 1f)
        main.post { place() }
        runCatching { RootInput.mouseMove(display, x, y) }
    }

    /**
     * One message from the remote: {"dx":4,"dy":-2,"t":123456.7,"scroll":30,"hscroll":0,"click":true,
     * "button":"left|right|middle","action":"click|down|up","show":true|false}
     */
    fun handle(b: JSONObject) {
        if (b.has("show") && !b.optBoolean("show")) { main.removeCallbacks(hide); onMain { removeView() }; return }
        if (!ensure()) throw ApiError(503, "The pointer cannot be drawn on the TV. Open Couch Phone on the phone once, and allow root in Magisk.")
        used()
        val k = speed()
        val dx = b.optDouble("dx", 0.0).toFloat() * k; val dy = b.optDouble("dy", 0.0).toFloat() * k
        if (dx != 0f || dy != 0f) {
            val n = now()
            val t = b.optDouble("t", Double.NaN)
            add(if (t.isNaN()) n else dueFor(t, n), dx, dy)
        }
        val sc = b.optDouble("scroll", 0.0).toFloat()
        val hs = b.optDouble("hscroll", 0.0).toFloat()
        if (sc != 0f || hs != 0f) {
            // Touchpad pixels to wheel clicks (about 40 a click), like a phone's screen: fingers up move
            // the page up (the wheel down), fingers left move it left (the wheel right).
            then { RootInput.mouseWheel(display, x, y, sc / 40f, -hs / 40f) }
        }
        if (b.optBoolean("click")) then { click(display, 1) }
        if (b.has("button")) {
            val bit = when (b.optString("button", "left")) { "right", "r" -> 2; "middle", "m" -> 4; "left", "l" -> 1
                else -> throw ApiError(400, "button must be left, right or middle") }
            when (b.optString("action", "click")) {
                "down" -> then { RootInput.mouseButton(display, x, y, bit, true) }
                "up" -> then { RootInput.mouseButton(display, x, y, bit, false) }
                else -> then { click(display, bit) }
            }
        }
    }

    /**
     * How far a finger's move goes on the TV: scaled to the screen's size (the remote's moves are
     * phone-sized; a 1080p TV is half as wide again as 1280, a 1440p one twice), and a little more
     * while a browser is in front, whose pages are big and far to cross.
     */
    @Volatile private var speedAt = 0L
    @Volatile private var speedNow = 1f
    private fun speed(): Float {
        val n = now()
        if (n - speedAt < 500) return speedNow
        val screen = (w / 1280f).coerceIn(1f, 2.4f)
        val front = runCatching { Tv.frontCached() }.getOrNull()
        val page = if (front in Tv.BROWSERS || BrowserActivity.inFront() != null) 1.25f else 1f
        speedNow = screen * page; speedAt = n
        return speedNow
    }

    private fun click(d: Int, bit: Int) {
        RootInput.mouseButton(d, x, y, bit, true)
        Thread.sleep(20)
        RootInput.mouseButton(d, x, y, bit, false)
    }

    // ---------------------------------------------------------------- the remote's arrows in a browser

    @Volatile private var lastNudge = 0L
    @Volatile private var lastStep = 0f

    /**
     * An arrow press while a browser is in front: the pointer glides that way (a held arrow repeats
     * and speeds up, as on a TV browser); at the edge of the screen the page scrolls instead.
     */
    fun nudge(dirX: Int, dirY: Int) {
        if (!ensure()) throw ApiError(503, "The pointer cannot be drawn on the TV.")
        used()
        val n = now()
        val base = h * 0.05f
        val step = if (n - lastNudge < 450) (lastStep * 1.3f).coerceAtMost(h * 0.18f) else base
        lastNudge = n; lastStep = step
        val atEdge = (dirX < 0 && x <= 1f) || (dirX > 0 && x >= w - 2f) || (dirY < 0 && y <= 1f) || (dirY > 0 && y >= h - 2f)
        if (atEdge) {
            // pushing past the edge scrolls the page that way
            then { RootInput.mouseWheel(display, x, y, -dirY * 3f, dirX * 3f) }
            return
        }
        // glide over 130 ms, in 8 ms steps, starting after anything still in line
        val start = maxOf(n, lastDue)
        val parts = 16
        for (i in 1..parts) add(start + i * 8L, dirX * step / parts, dirY * step / parts)
    }

    /** OK in a browser: a click where the pointer is (held: a right click). */
    fun clickHere(right: Boolean = false) {
        if (!ensure()) throw ApiError(503, "The pointer cannot be drawn on the TV.")
        used()
        then { click(display, if (right) 2 else 1) }
    }

    /** The arrow: white with a dark edge, its tip at the view's top left corner (the point that clicks). */
    private class Arrow(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 0, 0, 0); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private val path = Path()
        override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
            val u = nw / 13f
            edge.strokeWidth = u * 1.1f
            path.reset()
            // a classic arrow, 12 by 19 units
            val pts = floatArrayOf(0f, 0f, 0f, 16.5f, 4f, 12.8f, 6.8f, 19.2f, 9.6f, 18f, 6.9f, 11.7f, 12f, 11.7f)
            val o = u * 0.7f
            path.moveTo(o + pts[0] * u, o + pts[1] * u)
            for (i in 2 until pts.size step 2) path.lineTo(o + pts[i] * u, o + pts[i + 1] * u)
            path.close()
        }
        override fun onDraw(c: Canvas) { c.drawPath(path, edge); c.drawPath(path, fill) }
    }
}
