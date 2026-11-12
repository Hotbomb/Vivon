package app.couchremote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Couch Remote: the Couch Phone remote as an Android app.
 *
 * First it finds the TV: by itself on the Wi-Fi (Couch Phone announces "_couchphone._tcp"), from
 * the QR code on the TV's "Connect a remote" screen, or from a typed address. Then it shows the
 * TV's own remote page (the same one the browser gets, so the two never differ) on the secure
 * address, trusting only the TV's own certificate. On top of the page it adds what a browser
 * cannot: speaking through Android's recognizer, the phone's volume buttons for the TV, and a
 * sideways full screen for the controller.
 */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("remote", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var host: String? = null

    // ---------------------------------------------------------------- start

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // If the app ever stops by itself, the next start shows why (to send to whoever looks after it).
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { prefs.edit().putString("crash", android.util.Log.getStackTraceString(e).take(4000)).commit() }
            before?.uncaughtException(t, e)
        }
        val crash = prefs.getString("crash", null)
        if (crash != null) { prefs.edit().remove("crash").remove("base").apply(); showCrash(crash); return }
        val saved = prefs.getString("base", null)
        try { if (saved != null) openRemote(saved, null) else showConnect(null) }
        catch (e: Exception) { prefs.edit().remove("base").apply(); showCrash(android.util.Log.getStackTraceString(e)) }
    }

    /** What went wrong last time, with a button to copy it. */
    private fun showCrash(trace: String) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(36), dp(24), dp(24)) }
        col.addView(text("Couch Remote stopped last time", 24f, INK, true))
        col.addView(text("Please send this to whoever set up Couch Phone. Then tap Continue.", 15f, INK_2))
        col.addView(text(trace, 12f, INK_2).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, dp(12)) })
        col.addView(button("Copy the details", solid = true) {
            runCatching {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Couch Remote", "Couch Remote ${BuildConfig.VERSION_NAME}\n$trace"))
            }
        })
        col.addView(button("Continue") { showConnect(null) })
        setContentView(ScrollView(this).apply { setBackgroundColor(GROUND); addView(col) })
    }

    // While the remote is on screen, this phone's Wi-Fi skips its power saving (which sends in
    // batches): the TV pointer and the controller then follow the finger without bunching.
    private var fast: WifiManager.WifiLock? = null
    override fun onResume() {
        super.onResume(); web?.onResume(); if (web == null) startDiscovery()
        if (fast == null && Build.VERSION.SDK_INT >= 29) fast = runCatching {
            applicationContext.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "couch-remote").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }
    override fun onPause() { super.onPause(); web?.onPause(); stopDiscovery(); runCatching { fast?.release() }; fast = null }
    override fun onDestroy() { stopDiscovery(); speech?.destroy(); web?.destroy(); super.onDestroy() }

    // ---------------------------------------------------------------- the connect screen

    private var list: LinearLayout? = null
    private var looking: TextView? = null
    private val found = LinkedHashMap<String, Pair<String, String>>()   // base address to (name, base)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(t: String, size: Float, colour: Int = INK, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(colour); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(t: String, solid: Boolean = false, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; setTextColor(if (solid) Color.BLACK else INK)
        background = GradientDrawable().apply { setColor(if (solid) AMBER else CARD); cornerRadius = dp(16).toFloat() }
        setOnClickListener { on() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(10) }
    }

    private fun showConnect(error: String?) {
        web?.let { w -> w.stopLoading(); w.destroy() }
        web = null; host = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(36), dp(24), dp(24)) }
        col.addView(text("Couch Remote", 32f, INK, true))
        col.addView(text("The remote for your Couch Phone TV.", 16f, INK_2))
        if (error != null) col.addView(text(error, 15f, AMBER).apply { setPadding(0, dp(14), 0, 0) })

        col.addView(text("Your TV", 14f, INK_2, true).apply { setPadding(0, dp(26), 0, dp(2)) })
        looking = text("Looking for your TV on this Wi-Fi…", 15f, INK_2)
        col.addView(looking)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        prefs.getString("last", null)?.let { last -> found[last] = Pair(prefs.getString("last_name", null) ?: hostOf(last), last) }
        drawFound()

        col.addView(button("Scan the QR code on the TV", solid = true) { scan() })
        col.addView(text("On the TV, press the phone button at the top of the home screen: \"Connect a remote\".", 14f, INK_2)
            .apply { setPadding(0, dp(8), 0, 0) })

        col.addView(text("Or type its address", 14f, INK_2, true).apply { setPadding(0, dp(26), 0, dp(2)) })
        val field = EditText(this).apply {
            hint = "192.168.1.20"; setHintTextColor(INK_3); setTextColor(INK); textSize = 18f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16).toFloat() }
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(6) }
        }
        val go = { normalise(field.text.toString())?.let { openRemote(it, null) } ?: run { field.error = "Type the address shown on the TV" } }
        field.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { go(); true } else false }
        col.addView(field)
        col.addView(button("Connect") { go() })
        setContentView(ScrollView(this).apply { setBackgroundColor(GROUND); addView(col) })
        landscape(false)   // back from the controller: upright, with the bars
        startDiscovery()
    }

    private fun drawFound() {
        val l = list ?: return
        l.removeAllViews()
        found.values.forEach { (name, base) -> l.addView(button("$name  ·  ${hostOf(base)}") { openRemote(base, null) }) }
        looking?.text = if (found.isEmpty()) "Looking for your TV on this Wi-Fi…" else "Tap your TV:"
    }

    private fun hostOf(base: String) = Uri.parse(base).host ?: base

    /** "192.168.1.20", "192.168.1.20:5400" or a whole address, to "http://192.168.1.20:5400/". */
    private fun normalise(typed: String): String? {
        var t = typed.trim()
        if (t.isEmpty()) return null
        if (!t.contains("://")) t = "http://$t"
        val u = Uri.parse(t)
        val h = u.host ?: return null
        val port = if (u.port > 0) u.port else 5400
        return "http://$h:$port/"
    }

    // ---------------------------------------------------------------- finding the TV on the Wi-Fi

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun startDiscovery() {
        if (discovery != null || web != null) return
        val m = getSystemService(NsdManager::class.java) ?: return
        multicast = runCatching {
            applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("couch-remote").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { looking?.text = "This phone could not look for TVs. Scan the QR code instead." } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { toResolve.addLast(info); resolveNext() } }
        }
        runCatching { m.discoverServices("_couchphone._tcp", NsdManager.PROTOCOL_DNS_SD, l); nsd = m; discovery = l }
            .onFailure { looking?.text = "This phone could not look for TVs. Scan the QR code instead." }
    }

    // One at a time: older Androids refuse a second resolve while one is running.
    private fun resolveNext() {
        if (resolving) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd?.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) { main.post { resolving = false; resolveNext() } }
            override fun onServiceResolved(i: NsdServiceInfo) {
                @Suppress("DEPRECATION") val addr = i.host?.hostAddress
                main.post {
                    resolving = false
                    if (addr != null && !addr.contains(':')) {
                        val base = "http://$addr:${i.port}/"
                        found[base] = Pair(i.serviceName.removePrefix("Couch Phone ").ifEmpty { "TV" }, base)
                        drawFound()
                    }
                    resolveNext()
                }
            }
        }) ?: run { resolving = false }
    }

    private fun stopDiscovery() {
        discovery?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discovery = null
        runCatching { multicast?.release() }; multicast = null
    }

    // ---------------------------------------------------------------- the QR code

    private fun scan() {
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("Scan the QR code on the TV")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }.initiateScan()
    }

    @Deprecated("Activity results, the classic way (no AndroidX here)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (r == null) { @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data); return }
        val text = r.contents ?: return
        val u = Uri.parse(text)
        val base = normalise(text)
        if (base == null || (u.scheme != "http" && u.scheme != "https")) { showConnect("That QR code is not a Couch Phone TV's."); return }
        val pin = Regex("cpin=(\\d{6})").find(u.fragment ?: "")?.groupValues?.get(1)
        val fp = Regex("cafp=([0-9a-f]{64})").find(u.fragment ?: "")?.groupValues?.get(1)
        openRemote(base, pin, fp)
    }

    // ---------------------------------------------------------------- the remote

    /**
     * Opens the TV's remote. It first fetches the TV's own certificate over the plain address, then
     * loads the secure one (port + 1), so the page may use the microphone and the phone's motion.
     */
    private fun openRemote(base: String, pin: String?, fingerprint: String? = null) {
        stopDiscovery()
        val u = Uri.parse(base)
        val h = u.host ?: return showConnect("That address is not right.")
        val port = if (u.port > 0) u.port else 5400
        setContentView(TextView(this).apply {
            text = "Connecting to $h…"; textSize = 17f; setTextColor(INK_2); gravity = Gravity.CENTER; setBackgroundColor(GROUND)
        })
        Thread {
            val ca = runCatching { fetch("http://$h:$port/couch-phone.crt") }.getOrNull()
            val reachable = ca != null || runCatching { fetch("http://$h:$port/api/health"); true }.getOrDefault(false)
            main.post {
                if (!reachable) { showConnect("Can't reach the TV at $h. Is this phone on the same Wi-Fi as the TV?"); return@post }
                // The TV's certificate authority: from the QR code's fingerprint when there is one,
                // else the first one seen for this address (it is never swapped silently after).
                val stored = prefs.getString("ca:$h", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                if (ca != null) {
                    val matches = fingerprint != null && sha256(ca) == fingerprint
                    when {
                        fingerprint != null && !matches -> { showConnect("The TV at $h did not prove it is the TV that showed the QR code. Scan it again."); return@post }
                        matches || stored == null -> prefs.edit().putString("ca:$h", Base64.encodeToString(ca, Base64.NO_WRAP)).apply()
                        stored != null && !stored.contentEquals(ca) -> {
                            showConnect("The TV at $h has a new security certificate (Couch Phone reinstalled?). " +
                                "Scan the QR code on the TV's \"Connect a remote\" screen to trust it.")
                            return@post
                        }
                    }
                }
                prefs.edit().putString("base", "http://$h:$port/").putString("last", "http://$h:$port/").apply()
                found[base]?.first?.let { prefs.edit().putString("last_name", it).apply() }
                val secure = prefs.getString("ca:$h", null) != null
                val page = (if (secure) "https://$h:${port + 1}/" else "http://$h:$port/") + (if (pin != null) "#cpin=$pin" else "")
                showWeb(h, page)
            }
        }.start()
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun showWeb(h: String, url: String) {
        host = h
        val w = WebView(this)
        w.setBackgroundColor(GROUND)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.userAgentString = w.settings.userAgentString + " CouchRemote/" + BuildConfig.VERSION_NAME
        w.addJavascriptInterface(Bridge(), "CouchApp")
        w.webViewClient = object : WebViewClient() {
            // Only the TV's own certificate, made by the TV's own authority (fetched above).
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                if (trusted(error.certificate)) handler.proceed() else handler.cancel()
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val path = u.path.orEmpty()
                if (u.host == host && (u.scheme == "http" || u.scheme == "https") && !path.endsWith(".apk") && !path.endsWith(".crt") && !path.endsWith(".mobileconfig")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) main.post { showConnect("Can't reach the TV at $h. Is this phone on the same Wi-Fi as the TV?") }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            // The microphone and the like only for the TV's own page.
            override fun onPermissionRequest(r: PermissionRequest) { if (r.origin.host == host) r.grant(r.resources) else r.deny() }
        }
        web = w
        setContentView(w)
        w.loadUrl(url)
    }

    private fun trusted(cert: SslCertificate?): Boolean {
        val h = host ?: return false
        val caB64 = prefs.getString("ca:$h", null) ?: return false
        return runCatching {
            val cf = CertificateFactory.getInstance("X.509")
            val ca = cf.generateCertificate(ByteArrayInputStream(Base64.decode(caB64, Base64.NO_WRAP))) as X509Certificate
            val leaf = if (Build.VERSION.SDK_INT >= 29) cert?.x509Certificate
                else SslCertificate.saveState(cert).getByteArray("x509-certificate")?.let { cf.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
            leaf ?: return@runCatching false
            leaf.verify(ca.publicKey)
            true
        }.getOrDefault(false)
    }

    private fun js(code: String) { main.post { web?.evaluateJavascript(code, null) } }

    // ---------------------------------------------------------------- what the page can ask for

    private inner class Bridge {
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun landscape(on: Boolean) { main.post { this@MainActivity.landscape(on) } }
        @JavascriptInterface fun startSpeech(lang: String) { main.post { this@MainActivity.startSpeech(lang) } }
        @JavascriptInterface fun stopSpeech() { main.post { speech?.stopListening() } }
        @JavascriptInterface fun cancelSpeech() { main.post { speech?.cancel() } }
        @JavascriptInterface fun disconnect() { main.post { prefs.edit().remove("base").apply(); showConnect(null) } }
    }

    /** The controller: sideways, full screen and kept awake; back to normal after. */
    private fun landscape(on: Boolean) = runCatching { landscapeNow(on) }

    private fun landscapeNow(on: Boolean) {
        // Before the first setContentView there is no window to change (it crashed here at start).
        if (window.peekDecorView() == null) return
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                if (on) { c.hide(WindowInsets.Type.systemBars()); c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
                else c.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) else 0
        }
    }

    // ---------------------------------------------------------------- speaking

    private var speech: SpeechRecognizer? = null
    private var pendingLang: String? = null

    private fun said(type: String, text: String = "", final: Boolean = false) =
        js("window.__couchSpeech && window.__couchSpeech(${JSONObject.quote(type)}, ${JSONObject.quote(text)}, $final)")

    private fun startSpeech(lang: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { said("error", "service-not-allowed"); said("end"); return }
        speech?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(b: Bundle) {
                b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { said("result", it, false) }
            }
            override fun onResults(b: Bundle) {
                b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { said("result", it, true) }
                said("end")
            }
            override fun onError(code: Int) {
                said("error", when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                    else -> "aborted"
                })
                said("end")
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (lang.isNotEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        speech = r
        r.startListening(i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != MIC) return
        val lang = pendingLang; pendingLang = null
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED && lang != null) startSpeech(lang)
        else { said("error", "not-allowed"); said("end") }
    }

    // ---------------------------------------------------------------- keys

    /** The phone's volume buttons turn the TV up and down while the remote is open. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (web != null && (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.action == KeyEvent.ACTION_DOWN) js("window.couchAppKey && window.couchAppKey('${if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP) "volume_up" else "volume_down"}')")
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Back, the classic way")
    override fun onBackPressed() {
        val w = web
        if (w != null) { if (w.canGoBack()) w.goBack() else moveTaskToBack(true); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        private const val MIC = 7
        private val GROUND = Color.parseColor("#0B0B0D")
        private val CARD = Color.parseColor("#1C1C21")
        private val INK = Color.parseColor("#F2F2F5")
        private val INK_2 = Color.parseColor("#A8A8B0")
        private val INK_3 = Color.parseColor("#6A6A72")
        private val AMBER = Color.parseColor("#FFB52E")
    }
}
