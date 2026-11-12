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
    private var root: FrameLayout? = null
    private var arrow: View? = null
    @Volatile private var display = -1
    @Volatile var x = 0f; private set
    @Volatile var y = 0f; private set
    private var w = 1920; private var h = 1080

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
                val px = (26 * wc.resources.displayMetrics.density).toInt().coerceAtLeast(24)
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

    private var scrollRest = 0f

    /**
     * One message from the remote: {"dx":4,"dy":-2,"scroll":30,"hscroll":0,"click":true,
     * "button":"left|right|middle","action":"click|down|up","show":true|false}
     */
    fun handle(b: JSONObject) {
        if (b.has("show") && !b.optBoolean("show")) { main.removeCallbacks(hide); onMain { removeView() }; return }
        if (!ensure()) throw ApiError(503, "The pointer cannot be drawn on the TV. Open Couch Phone on the phone once, and allow root in Magisk.")
        used()
        val d = display
        val dx = b.optDouble("dx", 0.0).toFloat(); val dy = b.optDouble("dy", 0.0).toFloat()
        if (dx != 0f || dy != 0f) {
            x = (x + dx).coerceIn(0f, w - 1f); y = (y + dy).coerceIn(0f, h - 1f)
            main.post { place() }
            RootInput.mouseMove(d, x, y)
        }
        val sc = b.optDouble("scroll", 0.0).toFloat()
        val hs = b.optDouble("hscroll", 0.0).toFloat()
        if (sc != 0f || hs != 0f) {
            // Touchpad pixels to wheel clicks (about 40 a click), finger up the pad scrolls the page up.
            RootInput.mouseWheel(d, x, y, -sc / 40f, hs)
        }
        if (b.optBoolean("click")) click(d, 1)
        if (b.has("button")) {
            val bit = when (b.optString("button", "left")) { "right", "r" -> 2; "middle", "m" -> 4; "left", "l" -> 1
                else -> throw ApiError(400, "button must be left, right or middle") }
            when (b.optString("action", "click")) {
                "down" -> RootInput.mouseButton(d, x, y, bit, true)
                "up" -> RootInput.mouseButton(d, x, y, bit, false)
                else -> click(d, bit)
            }
        }
    }

    private fun click(d: Int, bit: Int) {
        RootInput.mouseButton(d, x, y, bit, true)
        Thread.sleep(20)
        RootInput.mouseButton(d, x, y, bit, false)
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
