package app.couchphone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small JSON files and settings kept on the phone. Nothing here leaves the phone. */
object Store {
    private lateinit var dir: File
    private lateinit var ctx: Context
    private val prefs by lazy { ctx.getSharedPreferences("couchphone", Context.MODE_PRIVATE) }

    fun init(c: Context) {
        ctx = c.applicationContext
        dir = File(ctx.filesDir, "data").apply { mkdirs() }
    }

    fun str(key: String, def: String? = null): String? = prefs.getString(key, def)
    fun putStr(key: String, v: String?) = prefs.edit().apply { if (v == null) remove(key) else putString(key, v) }.apply()
    fun bool(key: String, def: Boolean = false) = prefs.getBoolean(key, def)
    fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun int(key: String, def: Int) = prefs.getInt(key, def)

    @Synchronized fun readJson(name: String): Any? = try {
        val f = File(dir, name)
        if (!f.isFile) null else f.readText().trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
    } catch (e: Exception) { null }

    @Synchronized fun writeJson(name: String, v: Any) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(v.toString())
        tmp.renameTo(File(dir, name))
    }

    val port get() = int("port", 5400)

    fun people(): JSONArray = (str("people")?.let { runCatching { JSONArray(it) }.getOrNull() }) ?: JSONArray()

    /** Weather place for the home screen. Defaults to a public example place; set your own in Home settings or /setup. */
    fun weather(): JSONObject = (str("weather")?.let { runCatching { JSONObject(it) }.getOrNull() })
        ?: JSONObject().put("lat", 51.5072).put("lon", -0.1276).put("place", "London").put("timezone", "Europe/London")

    fun who(): String? = str("who")
    fun saveWho(v: String?) = putStr("who", v)

    /** The public config the home page and the phone remote read (the same shape as couch-tv's). */
    fun publicConfig(): JSONObject = JSONObject()
        .put("people", people())
        .put("weather", weather())
        .put("tvs", JSONArray().put(JSONObject().put("id", TV_ID).put("name", tvName()).put("kind", "dex")
            .put("quick", JSONArray(listOf("remote", "bluetooth", "network")))))
        .put("default_tv", TV_ID)
        .put("mode", "on-tv")
        .put("app", "couch-phone")   // the TV home opens its own Bluetooth and Wi-Fi screens (connect.js)
        .put("ask", JSONObject().put("enabled", false))

    fun tvName(): String = str("tv_name", "TV") ?: "TV"

    const val TV_ID = "this-tv"
}
