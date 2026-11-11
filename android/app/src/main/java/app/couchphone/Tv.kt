package app.couchphone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.PowerManager
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
    fun key(name: String, hold: Boolean = false): String {
        val code = Keys.resolve(name) ?: throw ApiError(400, "unknown key: $name")
        when (code) {
            // DeX's own Home key would show Samsung's desktop: open this home instead.
            KeyEvent.KEYCODE_HOME -> { Dex.openHome(); return "HOME" }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> { volumeKey(code); return KeyEvent.keyCodeToString(code) }
        }
        // Play, pause and skip go to whatever is playing, wherever it is.
        if (code in MEDIA) { media(code); return KeyEvent.keyCodeToString(code) }
        RootInput.key(code, Dex.target(), hold)
        return KeyEvent.keyCodeToString(code)
    }

    fun text(s: String) { RootInput.text(s, Dex.target()) }

    private val MEDIA = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

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
