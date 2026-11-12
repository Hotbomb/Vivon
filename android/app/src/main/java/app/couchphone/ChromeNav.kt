package app.couchphone

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The remote's arrows and OK in Chrome, TV style: Chrome's own "spatial navigation" (the arrows move
 * between links, buttons and posters on any web page, OK opens the one picked), instead of only
 * scrolling. Chrome has it built in but hidden behind a start-up switch; with root this:
 *  - turns on chrome://flags "Enable command line on non-rooted devices" (Chrome's Local State),
 *  - writes /data/local/tmp/chrome-command-line with --enable-spatial-navigation,
 *  - restarts Chrome so it reads them.
 * Turning it off writes an empty command line (the flag itself is harmless).
 */
object ChromeNav {
    private const val TAG = "couchphone.chromenav"
    private const val PKG = "com.android.chrome"
    private const val STATE = "/data/data/$PKG/app_chrome/Local State"
    private const val CMDLINE = "/data/local/tmp/chrome-command-line"
    private const val FLAG = "enable-command-line-on-non-rooted-devices"

    fun on(): Boolean = Root.shOrNull("cat $CMDLINE 2>/dev/null")?.contains("--enable-spatial-navigation") == true

    fun set(enable: Boolean) {
        if (Root.available == false) throw ApiError(503, "This needs root. Open Magisk and allow Couch Phone.")
        if (!Tv.hasPackage(PKG)) throw ApiError(404, "Chrome is not installed on the TV phone.")
        Root.sh("am force-stop $PKG")
        if (enable) {
            // Chrome's flags live in its Local State (JSON); read and written back whole, as base64,
            // so the file keeps its owner and permissions.
            val b64 = Root.sh("[ -f '$STATE' ] && base64 -w 0 '$STATE'; echo", 15_000).trim()
            val state = if (b64.isEmpty()) JSONObject() else runCatching { JSONObject(String(Base64.decode(b64, Base64.DEFAULT))) }
                .getOrElse { throw ApiError(500, "Chrome's settings file could not be read.") }
            val browser = state.optJSONObject("browser") ?: JSONObject().also { state.put("browser", it) }
            val labs = browser.optJSONArray("enabled_labs_experiments") ?: JSONArray().also { browser.put("enabled_labs_experiments", it) }
            if ((0 until labs.length()).none { labs.optString(it).substringBefore('@') == FLAG }) {
                labs.put(FLAG)
                val out = Base64.encodeToString(state.toString().toByteArray(), Base64.NO_WRAP)
                if (b64.isEmpty()) throw ApiError(409, "Open Chrome on the TV once first, then try again.")
                Root.sh("echo '$out' | base64 -d > '$STATE'", 15_000)
            }
            Root.sh("echo '_ --enable-spatial-navigation' > $CMDLINE && chmod 644 $CMDLINE")
        } else {
            Root.sh("echo '_' > $CMDLINE && chmod 644 $CMDLINE")
        }
        Log.i(TAG, "spatial navigation ${if (enable) "on" else "off"}")
    }
}
