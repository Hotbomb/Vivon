package app.couchphone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.PowerManager
import android.view.KeyCharacterMap
import android.view.KeyEvent

class ApiError(val status: Int, message: String) : Exception(message)

/** Everything the remote does: keys and typing through the root helper, volume and media through Android. */
object Tv {
    private lateinit var ctx: Context
    private val audio by lazy { ctx.getSystemService(AudioManager::class.java) }
    private val power by lazy { ctx.getSystemService(PowerManager::class.java) }

    fun init(c: Context) { ctx = c.applicationContext }

    val isOn: Boolean get() = power.isInteractive

    // ---------- keys and text ----------
    fun key(name: String, hold: Boolean = false, raw: Boolean = false): String {
        val code = Keys.resolve(name) ?: throw ApiError(400, "unknown key: $name")
        // A browser in front: the arrows glide the pointer and OK clicks, as on a TV browser, so any
        // website works with the remote's arrows. (raw: the Keyboard's own arrows still move the caret.)
        if (!raw && browserPointer()) when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { Cursor.nudge(0, -1); return "POINTER_UP" }
            KeyEvent.KEYCODE_DPAD_DOWN -> { Cursor.nudge(0, 1); return "POINTER_DOWN" }
            KeyEvent.KEYCODE_DPAD_LEFT -> { Cursor.nudge(-1, 0); return "POINTER_LEFT" }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { Cursor.nudge(1, 0); return "POINTER_RIGHT" }
            KeyEvent.KEYCODE_DPAD_CENTER -> { Cursor.clickHere(right = hold); return "POINTER_CLICK" }
        }
        when (code) {
            // DeX's own Home key would show Samsung's desktop: open this home instead.
            KeyEvent.KEYCODE_HOME -> { pauseBrowser(); Dex.openHome(); return "HOME" }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> { volumeKey(code); return KeyEvent.keyCodeToString(code) }
        }
        // Play, pause and skip go to whatever is playing, wherever it is.
        if (code in MEDIA) { media(code); return KeyEvent.keyCodeToString(code) }
        RootInput.key(code, Dex.target(), hold)
        return KeyEvent.keyCodeToString(code)
    }

    /**
     * Browser shortcuts (Chrome's own keyboard shortcuts, which Samsung Internet and Firefox share),
     * sent to the app in front on the TV.
     */
    private val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val SHORTCUTS = mapOf(
        "back" to (KeyEvent.KEYCODE_DPAD_LEFT to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)),
        "forward" to (KeyEvent.KEYCODE_DPAD_RIGHT to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)),
        "reload" to (KeyEvent.KEYCODE_R to CTRL),
        "address" to (KeyEvent.KEYCODE_L to CTRL),
        "new_tab" to (KeyEvent.KEYCODE_T to CTRL),
        "close_tab" to (KeyEvent.KEYCODE_W to CTRL),
        "next_tab" to (KeyEvent.KEYCODE_TAB to CTRL),
        "prev_tab" to (KeyEvent.KEYCODE_TAB to (CTRL or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)),
        "reopen_tab" to (KeyEvent.KEYCODE_T to (CTRL or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)),
        "zoom_in" to (KeyEvent.KEYCODE_EQUALS to CTRL),
        "zoom_out" to (KeyEvent.KEYCODE_MINUS to CTRL),
        "zoom_reset" to (KeyEvent.KEYCODE_0 to CTRL),
        "find" to (KeyEvent.KEYCODE_F to CTRL),
        "page_down" to (KeyEvent.KEYCODE_SPACE to 0),
        "page_up" to (KeyEvent.KEYCODE_SPACE to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)),
        "fullscreen" to (KeyEvent.KEYCODE_F to 0),   // YouTube and most web players: F for full screen
    )

    fun shortcut(name: String) {
        val (code, meta) = SHORTCUTS[name] ?: throw ApiError(400, "unknown shortcut: $name. Known: ${SHORTCUTS.keys.joinToString()}")
        RootInput.key(code, Dex.target(), false, meta)
    }

    /**
     * Opens a web address in the browser on the TV (Chrome when it is installed). Words that are
     * not an address are searched for. Returns the address opened.
     */
    fun openUrl(raw: String): String {
        val t = raw.trim().take(4000)
        if (t.isEmpty()) throw ApiError(400, "url is required")
        // the first web address in what was shared ("Look at this https://...")
        val found = Regex("https?://\\S+").find(t)?.value
        val url = when {
            found != null -> found
            !t.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/\\S*)?$").matches(t) -> "https://$t"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
        val scheme = android.net.Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw ApiError(400, "Only web addresses (http or https) can be opened.")
        Dex.openUrl(url, OPEN_IN.firstOrNull { hasPackage(it) })
        return url
    }
    private val OPEN_IN = listOf("com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser", "org.mozilla.firefox")

    /**
     * Types text on the TV. Plain letters, numbers and symbols are typed key by key. Text with
     * anything the virtual keyboard has no key for (accents, emoji, other alphabets) is put on the
     * clipboard and pasted with Ctrl+V, which works in apps, browsers and the home screen alike.
     */
    fun text(s: String) {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val typeable = s.codePoints().allMatch { cp -> map.getEvents(Character.toChars(cp)) != null }
        if (typeable) { RootInput.text(s, Dex.target()); return }
        if (!copy(s)) throw ApiError(500, "Could not put the text on the clipboard.")
        RootInput.key(KeyEvent.KEYCODE_V, Dex.target(), false, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
    }

    /** Sets the phone's clipboard (on the main thread, as Android wants) and waits for it. */
    private fun copy(s: String): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            ok = runCatching {
                ctx.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Couch Phone", s))
            }.isSuccess
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    private val MEDIA = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

    /**
     * Leaving a browser (Home, or another app from the remote): pause what it plays. In DeX the
     * browser stays "on screen" behind the home, so Chrome never pauses by itself and the sound goes
     * on. Only browsers: music apps keep playing behind the home, as they should.
     */
    fun pauseBrowser(except: String? = null) {
        val front = runCatching { frontCached() }.getOrNull() ?: return
        if (front !in BROWSERS || front == except) return
        media(KeyEvent.KEYCODE_MEDIA_PAUSE)
        // and the same through the media sessions directly, in case the key went elsewhere
        if (Root.available == true) Thread { Root.shOrNull("cmd media_session dispatch pause 2>&1", 4000) }.start()
        frontAt = 0L   // the app in front is about to change
    }

    private fun media(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---------- apps ----------
    /** Every app with a main screen (phone apps and TV apps alike), not this one. */
    fun launchables(): List<android.content.pm.ResolveInfo> {
        val pm = ctx.packageManager
        val phone = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val tv = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        return (phone + tv).distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != ctx.packageName }
    }

    fun label(pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun hasPackage(pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    val BROWSERS = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.sec.android.app.sbrowser",
        "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.kiwibrowser.browser")

    @Volatile private var frontAt = 0L
    @Volatile private var frontNow: String? = null
    /** The app in front, looked up at most every 2.5 seconds (each look is a root command). */
    fun frontCached(): String? {
        val n = System.currentTimeMillis()
        if (n - frontAt > 2500) { frontNow = frontPackage(); frontAt = n }
        return frontNow
    }

    /** Arrows as a pointer: a browser is in front and "Arrows in browsers" is Pointer (the default). */
    private fun browserPointer(): Boolean =
        Store.str("browser_dpad", "pointer") == "pointer" && Dex.tvDisplay() != null && frontCached() in BROWSERS && Cursor.available()

    /** The app in front on the TV, from the activity manager (root). Null when unknown. */
    fun frontPackage(): String? {
        if (Root.available != true) return null
        val out = Root.shOrNull("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -5", 6000) ?: return null
        return Regex("ActivityRecord\\{\\S+ u0 ([^/\\s]+)/").find(out)?.groupValues?.get(1)
    }

    // ---------- volume ----------
    fun volume(): Map<String, Any?> {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val known = !audio.isVolumeFixed && max > 0
        val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return mapOf("level" to if (known) Math.round(level * 100f / max) else null,
            "max" to 100, "muted" to audio.isStreamMute(AudioManager.STREAM_MUSIC), "known" to known)
    }

    /** Media volume, which is what the TV plays through over HDMI in DeX. */
    fun volumeKey(code: Int) {
        val dir = when (code) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    fun setLevel(level: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level.coerceIn(0, 100) * max / 100f), AudioManager.FLAG_SHOW_UI)
    }
}

/** Friendly key names (the same ones couch-tv's remote sends) to Android key codes. */
object Keys {
    private val FRIENDLY = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN, "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT, "ok" to KeyEvent.KEYCODE_DPAD_CENTER, "select" to KeyEvent.KEYCODE_DPAD_CENTER,
        "enter" to KeyEvent.KEYCODE_ENTER, "back" to KeyEvent.KEYCODE_BACK, "home" to KeyEvent.KEYCODE_HOME,
        "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "play" to KeyEvent.KEYCODE_MEDIA_PLAY, "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "stop" to KeyEvent.KEYCODE_MEDIA_STOP, "next" to KeyEvent.KEYCODE_MEDIA_NEXT, "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND, "fast_forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "volume_up" to KeyEvent.KEYCODE_VOLUME_UP, "volume_down" to KeyEvent.KEYCODE_VOLUME_DOWN, "mute" to KeyEvent.KEYCODE_VOLUME_MUTE,
        "menu" to KeyEvent.KEYCODE_MENU, "search" to KeyEvent.KEYCODE_SEARCH, "captions" to KeyEvent.KEYCODE_CAPTIONS,
        "backspace" to KeyEvent.KEYCODE_DEL, "delete" to KeyEvent.KEYCODE_DEL, "space" to KeyEvent.KEYCODE_SPACE,
        "tab" to KeyEvent.KEYCODE_TAB, "escape" to KeyEvent.KEYCODE_ESCAPE, "page_up" to KeyEvent.KEYCODE_PAGE_UP,
        "page_down" to KeyEvent.KEYCODE_PAGE_DOWN, "app_switch" to KeyEvent.KEYCODE_APP_SWITCH,
    ) + (0..9).associate { "$it" to (KeyEvent.KEYCODE_0 + it) }

    /** "up", "DPAD_UP" or "KEYCODE_DPAD_UP" to a key code. Null when unknown. */
    fun resolve(name: String?): Int? {
        val k = name?.trim().orEmpty()
        if (k.isEmpty()) return null
        FRIENDLY[k.lowercase()]?.let { return it }
        val raw = k.uppercase().removePrefix("KEYCODE_")
        if (!Regex("[A-Z0-9_]+").matches(raw)) return null
        val code = KeyEvent.keyCodeFromString("KEYCODE_$raw")
        // Never power or sleep the phone from the remote by accident.
        return code.takeIf { it != KeyEvent.KEYCODE_UNKNOWN && it != KeyEvent.KEYCODE_POWER && it != KeyEvent.KEYCODE_SLEEP }
    }
}
