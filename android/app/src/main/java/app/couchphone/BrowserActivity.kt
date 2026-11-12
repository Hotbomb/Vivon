package app.couchphone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * The Couch TV browser: websites made for phones and computers, used with the TV remote.
 *
 *  - The arrows move a highlight between links, posters, buttons and players (browser-nav.js); OK
 *    clicks the highlighted thing for real (a mouse click through the root helper), Back goes back.
 *  - Pop-ups and "click anywhere" ad tabs never open (no new windows at all), and known ad
 *    addresses are not loaded.
 *  - Players: OK on a player clicks into it ("player mode": the arrows and OK go to the player
 *    until Back); full-screen video fills the TV; the remote's play/pause and skip reach players
 *    inside frames (browser-frame.js); leaving the browser pauses them.
 *
 * Opened by website tiles (Sites.kt) on the TV's display.
 */
class BrowserActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var bar: View
    private lateinit var toast: TextView
    private var full: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    /** Player mode: the arrows and OK go to the player itself, until Back. */
    private var player = false
    private var playerAt: Pair<Float, Float>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bar = View(this).apply { setBackgroundColor(Color.parseColor("#FFB52E")); pivotX = 0f; scaleX = 0f }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * resources.displayMetrics.density).toInt(), Gravity.TOP))
        toast = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; setPadding(36, 18, 36, 18); alpha = 0f
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(220, 20, 20, 24)); cornerRadius = 60f }
        }
        root.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = (40 * resources.displayMetrics.density).toInt() })
        setContentView(root)
        setUp()
        hideBars()
        current = java.lang.ref.WeakReference(this)
        load(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); load(i) }

    private fun load(i: Intent?) {
        val url = i?.dataString ?: return
        if (!url.startsWith("http")) return
        player = false
        web.loadUrl(url)
        say(getString(R.string.browser_hint), 4000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUp() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(true)   // so a new window can be refused (onCreateWindow)
        // A normal Chrome on Android, without WebView's "wv": some sites refuse built-in browsers.
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(Color.BLACK)
        web.isFocusable = true
        web.isFocusableInTouchMode = true

        // in every frame, players included: play, pause and skip from the remote
        val frameJs = asset("www/ontv/browser-frame.js")
        if (frameJs != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            runCatching { WebViewCompat.addDocumentStartJavaScript(web, frameJs, setOf("*")) }.onFailure { Log.w(TAG, "frame script", it) }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                // only web pages; app links (intent:, market:) and ad addresses are refused
                if (u.scheme != "http" && u.scheme != "https") return true
                if (req.isForMainFrame && Ads.blocked(u.host)) { say(getString(R.string.browser_blocked), 2000); return true }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                if (Ads.blocked(req.url.host)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { player = false; inject() }
            override fun onPageFinished(view: WebView, url: String) { inject() }
        }
        web.webChromeClient = object : WebChromeClient() {
            // Pop-ups and new tabs (the ads' favourite): never as a new window. A link the person picked
            // that opens the same website in a new tab ("Watch now" on many sites) opens here instead;
            // anything else (another site: an ad) is stopped.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!isUserGesture) { say(getString(R.string.browser_popup), 1800); return false }
                val catcher = WebView(this@BrowserActivity)
                catcher.webViewClient = object : WebViewClient() {
                    private var done = false
                    private fun take(u: Uri) {
                        if (done) return
                        done = true
                        if ((u.scheme == "http" || u.scheme == "https") && !Ads.blocked(u.host) && sameSite(u.host, Uri.parse(web.url ?: "").host)) web.loadUrl(u.toString())
                        else say(getString(R.string.browser_popup), 1800)
                        main.post { runCatching { catcher.destroy() } }
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean { take(req.url); return true }
                    override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) { take(Uri.parse(url)); v.stopLoading() }
                }
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = catcher
                resultMsg.sendToTarget()
                // nothing came within a few seconds (a script-only window): let it go
                main.postDelayed({ runCatching { catcher.destroy() } }, 5000)
                return true
            }
            override fun onProgressChanged(view: WebView, p: Int) {
                bar.animate().cancel()
                bar.scaleX = p / 100f
                bar.alpha = if (p >= 100) 0f else 1f
            }
            override fun onPermissionRequest(r: PermissionRequest) {
                // protected video (DRM) only; no camera or microphone for websites
                val ok = r.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (ok.isNotEmpty()) r.grant(ok.toTypedArray()) else r.deny()
            }
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                full?.let { root.removeView(it) }
                full = v; fullCallback = cb; player = true
                root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                hideBars()
            }
            override fun onHideCustomView() { leaveFull() }
        }
    }

    /** The same website: the last two parts of the name (cinejoy.pk, www.cinejoy.pk, play.cinejoy.pk). */
    private fun sameSite(a: String?, b: String?): Boolean {
        fun site(h: String?) = h?.lowercase()?.split('.')?.takeLast(2)?.joinToString(".")
        return a != null && b != null && site(a) == site(b)
    }

    private fun leaveFull() {
        full?.let { root.removeView(it) }
        full = null
        fullCallback?.onCustomViewHidden(); fullCallback = null
        player = false
        web.requestFocus()
    }

    private fun asset(path: String) = runCatching { assets.open(path).bufferedReader().readText() }.getOrNull()
    private val navJs by lazy { asset("www/ontv/browser-nav.js") ?: "" }

    /** The navigation script, in the top frame (again after each load; it keeps itself to one copy). */
    private fun inject() { if (navJs.isNotEmpty()) web.evaluateJavascript(navJs, null) }

    private fun say(text: String, ms: Long) {
        toast.text = text
        toast.animate().cancel(); toast.alpha = 1f
        toast.animate().alpha(0f).setStartDelay(ms).setDuration(400).start()
    }

    // ---------------------------------------------------------------- the remote's keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val arrow = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> null
        }
        // only the remote's OK picks; Enter belongs to the page (a search box submits with it)
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) back()
            return true
        }
        // A player has the keys: the arrows and Enter go to it (seek, volume); OK clicks it (pause).
        if (player) {
            if (ok) { if (e.action == KeyEvent.ACTION_UP) clickPlayer(); return true }
            return super.dispatchKeyEvent(e)
        }
        if (arrow == null && !ok) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (arrow != null) {
            web.evaluateJavascript("window.__couchNav ? __couchNav.move('$arrow') : 'none'") { r ->
                // a text box keeps left and right (the caret); with no script yet, the page gets the key
                if (r == "\"pass\"" || r == "\"none\"") resend(code)
            }
        } else {
            web.evaluateJavascript("window.__couchNav ? __couchNav.ok() : null") { r -> onOk(r) }
        }
        return true
    }

    private fun resend(code: Int) {
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun onOk(raw: String?) {
        val j = runCatching { org.json.JSONObject(org.json.JSONTokener(raw ?: "null").nextValue() as String) }.getOrNull()
        if (j == null) { resend(KeyEvent.KEYCODE_ENTER); return }
        if (j.optBoolean("none")) return
        val (x, y) = toScreen(j.optDouble("x").toFloat(), j.optDouble("y").toFloat())
        when (j.optString("kind")) {
            "player" -> { player = true; playerAt = Pair(x, y); click(x, y); say(getString(R.string.browser_player), 3500) }
            else -> click(x, y)
        }
    }

    /** Page pixels (CSS × device pixel ratio, from the script) to the TV screen's pixels. */
    private fun toScreen(px: Float, py: Float): Pair<Float, Float> {
        val at = IntArray(2); web.getLocationOnScreen(at)
        return Pair(at[0] + px, at[1] + py)
    }

    /** A real click (the root helper's mouse), so the page reacts as to a person; else a tap on the view. */
    private fun click(x: Float, y: Float) {
        val d = displayId()
        Thread {
            val ok = runCatching {
                RootInput.mouseMove(d, x, y)
                RootInput.mouseButton(d, x, y, 1, true); Thread.sleep(30); RootInput.mouseButton(d, x, y, 1, false)
            }.isSuccess
            if (!ok) main.post { tap(x, y) }
        }.start()
    }

    private fun tap(x: Float, y: Float) {
        val at = IntArray(2); web.getLocationOnScreen(at)
        val t = android.os.SystemClock.uptimeMillis()
        val lx = x - at[0]; val ly = y - at[1]
        web.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, lx, ly, 0))
        web.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, lx, ly, 0))
    }

    private fun clickPlayer() {
        val at = playerAt
        if (at != null && full == null) { click(at.first, at.second); return }
        // full screen, or not known: the middle of the screen (most players pause on a click there)
        val w = root.width.toFloat(); val h = root.height.toFloat()
        val loc = IntArray(2); root.getLocationOnScreen(loc)
        click(loc[0] + w / 2, loc[1] + h / 2)
    }

    @Suppress("DEPRECATION")
    private fun displayId() = windowManager.defaultDisplay.displayId

    private fun back() {
        when {
            full != null -> { fullCallback?.onCustomViewHidden(); leaveFull() }
            player -> { player = false; web.requestFocus(); say(getString(R.string.browser_player_left), 1500) }
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    /** The remote's play/pause and skip keys (Tv.kt sends them here while the browser is in front). */
    fun media(code: Int) {
        val (what, v) = when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play" to 0
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> "pause" to 0
            KeyEvent.KEYCODE_MEDIA_REWIND -> "seek" to -10
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "seek" to 10
            KeyEvent.KEYCODE_MEDIA_NEXT -> "seek" to 30
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "seek" to -30
            else -> "toggle" to 0
        }
        main.post { web.evaluateJavascript("window.__couchMedia && __couchMedia('$what', $v)", null) }
    }

    // ---------------------------------------------------------------- leaving and coming back

    override fun onResume() { super.onResume(); front = true; web.onResume(); hideBars() }

    // Leaving (Home, another app): the players pause. In DeX the browser stays "on screen" behind the
    // home, so nothing else would stop the sound.
    override fun onPause() {
        super.onPause(); front = false
        web.evaluateJavascript("window.__couchMedia && __couchMedia('pause')", null)
        web.onPause()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        runCatching { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    companion object {
        private const val TAG = "couchphone.browser"
        @Volatile private var current: java.lang.ref.WeakReference<BrowserActivity>? = null
        @Volatile var front = false
            private set

        /** The browser, while it is in front on the TV (for the remote's media keys). */
        fun inFront(): BrowserActivity? = current?.get()?.takeIf { front }

        /** Opens an address in the Couch browser on the TV. */
        fun open(url: String) = Dex.openInCouch(url)
    }
}

/** Ad and pop-up addresses the Couch browser never loads (a short list of the worst; AdAway does the rest). */
object Ads {
    private val HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adsterra.com", "adsterratools.com", "a-ads.com",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "onclickads.net", "onclkds.com", "onclickalgo.com",
        "mgid.com", "taboola.com", "outbrain.com", "revcontent.com", "adnxs.com", "criteo.com", "criteo.net", "pubmatic.com",
        "rubiconproject.com", "openx.net", "amazon-adsystem.com", "hilltopads.net", "hilltopads.com", "clickadu.com", "monetag.com",
        "highperformanceformat.com", "profitabledisplaynetwork.com", "effectivegatecpm.com", "displayvertising.com",
        "adcash.com", "admaven.com", "ad-maven.com", "bidvertiser.com", "clickaine.com", "zeroredirect1.com", "pushame.com",
        "realsrv.com", "tsyndicate.com", "dolohen.com", "acint.net", "adskeeper.com", "adskeeper.co.uk", "richpush.co",
        "rollerads.com", "evadav.com", "galaksion.com", "pushground.com", "adspyglass.com", "yllix.com", "ad.plus",
        "popmyads.com", "poptival.com", "adbetclickin.pink", "lijit.com", "smartadserver.com", "33across.com",
        "scorecardresearch.com", "quantserve.com", "zedo.com", "infolinks.com", "chitika.com", "media.net", "vidoomy.com",
        "dtscout.com"
    )

    fun blocked(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in HOSTS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
