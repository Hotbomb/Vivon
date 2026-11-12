package app.couchphone

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The web server on the phone (port 5400), adapted from couch-tv's on-TV app (MIT). It serves the
 * phone remote, the TV home page and the API both use, with couch-tv's endpoint shapes so its
 * remote and home work unchanged.
 *
 * Only this phone and devices on the same Wi-Fi network get an answer (App.allowedClient).
 * Every /api call needs a token from pairing except /api/health and /api/pair*.
 */
class Server(private val ctx: Context, port: Int, private val secure: Boolean = false) : NanoWSD(port) {
    private val assets = ctx.assets

    override fun useGzipWhenAccepted(r: Response) = false   // gzip would hold back server-sent events

    override fun serve(session: IHTTPSession): Response {
        // Local Wi-Fi only: anything else (mobile data, VPNs, other networks) gets nothing.
        if (!App.allowedClient(session.remoteIpAddress)) {
            Log.w(TAG, "refused ${session.remoteIpAddress}: not on this Wi-Fi")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Couch Phone only answers devices on the same Wi-Fi.")
        }
        if (session.uri.startsWith("/api/ws") && session.headers["upgrade"]?.lowercase() == "websocket" && !authed(session))
            return json(401, err("pair this phone first"))
        return super.serve(session)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Socket(handshake)

    override fun serveHttp(s: IHTTPSession): Response = try {
        val uri = s.uri
        when {
            uri.startsWith("/api/") -> api(s)
            uri.startsWith("/ontv/") -> asset("www/ontv/" + uri.removePrefix("/ontv/"))
            // The secure remote's root certificate, for phones to trust (see Tls.kt). Public by design.
            uri == "/couch-phone.crt" -> newFixedLengthResponse(Response.Status.OK, "application/x-x509-ca-cert",
                java.io.ByteArrayInputStream(Tls.caDer()), Tls.caDer().size.toLong()).apply { addHeader("Content-Disposition", "attachment; filename=\"couch-phone.crt\"") }
            uri == "/couch-phone.mobileconfig" -> Tls.mobileconfig().toByteArray().let {
                newFixedLengthResponse(Response.Status.OK, "application/x-apple-aspen-config", java.io.ByteArrayInputStream(it), it.size.toLong())
                    .apply { addHeader("Content-Disposition", "attachment; filename=\"couch-phone.mobileconfig\"") } }
            // The Couch Remote Android app, built with this version and kept inside it (see build.gradle.kts).
            uri == "/couch-remote.apk" -> if (exists("remote-app/couch-remote.apk"))
                newChunkedResponse(Response.Status.OK, "application/vnd.android.package-archive", assets.open("remote-app/couch-remote.apk"))
                    .apply { addHeader("Content-Disposition", "attachment; filename=\"couch-remote.apk\"") }
                else newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "The Android app is not in this build.")
            uri == "/tv" -> redirect("/tv/")
            uri == "/tv/config.js" -> newFixedLengthResponse(Response.Status.OK, "application/javascript",
                "window.COUCH_CONFIG = " + Store.publicConfig() + ";\n").noCache()
            uri.startsWith("/tv/") -> staticOrIndex("www/tv", uri.removePrefix("/tv/"), inject = false)
            else -> staticOrIndex("www/remote", uri.removePrefix("/"), inject = true)
        }
    } catch (e: ApiError) {
        json(e.status, err(e.message ?: "error"))
    } catch (e: Exception) {
        Log.w(TAG, "request ${s.uri}", e)
        json(500, err(e.message ?: e.javaClass.simpleName))
    }

    // ---------------------------------------------------------------- auth

    private fun tokenOf(s: IHTTPSession): String? {
        s.headers["authorization"]?.let { if (it.startsWith("Bearer ", true)) return it.substring(7).trim() }
        s.headers["x-couch-token"]?.let { return it }
        s.parameters["token"]?.firstOrNull()?.let { return it }
        s.headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("couchtv_token=") }?.let { return it.substringAfter('=') }
        return null
    }

    private fun authed(s: IHTTPSession) = Auth.valid(tokenOf(s))

    /**
     * After a phone or setup tool saves settings, the app order or people, the home shows them at
     * once (#96). Saves from the home page itself (on this TV, so from 127.0.0.1) are left alone:
     * the page already has them, and refreshing it from its own save would only repeat work.
     */
    private fun refreshHomeAfter(s: IHTTPSession) {
        // The socket's own address: a "remote-addr" header sent by the client replaces NanoHTTPD's.
        val from = s.remoteIpAddress ?: return
        if (from != "127.0.0.1" && from != "::1" && !from.startsWith("0:0:0:0:0:0:0:1")) HomeActivity.refresh()
    }

    // ---------------------------------------------------------------- API

    private fun api(s: IHTTPSession): Response {
        val path = s.uri.removeSuffix("/")
        val m = s.method
        // Always read the body first: an unread body would be taken as the start of the next request.
        bodies[s] = readBody(s)
        if (m == Method.OPTIONS) return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        // open endpoints
        when (path) {
            "/api/health" -> {
                val a = authed(s)
                val o = JSONObject().put("ok", true).put("mode", "on-tv").put("app", "couch-phone").put("version", BuildConfig.VERSION_NAME).put("auth", if (a) "ok" else "required")
                if (a) o.put("tv_connected", true).put("root", Root.available == true).put("dex", Dex.tvDisplay() != null)
                return json(200, o)
            }
            "/api/pair/start" -> {
                if (m != Method.POST) throw ApiError(405, "POST only")
                Auth.newPin()
                App.showPin()
                return json(200, ok().put("digits", 6).put("expires_in", 180).put("note", "The code is on the TV now."))
            }
            "/api/pair" -> {
                if (m != Method.POST) throw ApiError(405, "POST only")
                val b = body(s)
                Thread.sleep(400)   // slows guessing a little more
                val token = Auth.pair(b.optString("pin"), b.optString("name").ifEmpty { null })
                    ?: return json(401, err(if (Auth.currentPin() == null) "That code has expired. Ask the TV for a new one." else "That code is not right. Check the TV."))
                Now.push()
                return json(200, ok().put("token", token))
            }
        }
        if (!authed(s)) return json(401, err("pair this phone first: open this page on your phone and type the code from the TV"))
        // An app's own icon, as a PNG (cached), for the remote's Watch page and the TV home.
        if (m == Method.GET && path.startsWith("/api/app-icon/")) return Icons.response(path.removePrefix("/api/app-icon/"))

        return when ("$m $path") {
            "GET /api/now" -> json(200, Now.payload())
            "GET /api/events" -> events()
            "GET /api/tvs" -> json(200, ok().put("default", Store.TV_ID).put("tvs", JSONArray().put(JSONObject().put("id", Store.TV_ID)
                .put("name", Store.tvName()).put("kind", "dex").put("paired", true).put("connected", true)
                .put("picture", false).put("power", "on"))))
            "GET /api/config" -> json(200, ok(Store.publicConfig()))
            "POST /api/key" -> {
                val b = body(s)
                val key = b.optString("key").ifEmpty { throw ApiError(400, "key is required") }
                json(200, ok().put("key", Tv.key(key, b.optBoolean("hold"), b.optBoolean("raw"))))
            }
            "POST /api/text" -> {
                val t = body(s).optString("text").ifEmpty { throw ApiError(400, "text is required") }
                Tv.text(t); json(200, ok())
            }
            "POST /api/shortcut" -> { Tv.shortcut(body(s).optString("name")); json(200, ok()) }
            "POST /api/open-url" -> { val u = Tv.openUrl(body(s).optString("url")); json(200, ok().put("url", u)) }
            "POST /api/pointer" -> { Pointer.handle(body(s)); json(200, ok()) }
            // The TV picture (DeX's resolution and text size) and the phone's own screen while on the TV.
            "GET /api/connect" -> json(200, connectInfo())
            // Website tiles: {"action":"add","name":"Cinejoy","url":"cinejoy.pk"}, {"action":"remove","id":"site:cinejoy"},
            // {"action":"set","id":"site:cinejoy","opens":"couch|browser"}
            "GET /api/sites" -> json(200, ok().put("sites", JSONArray(Sites.list().map { it.json().put("id", it.id) })))
            "POST /api/sites" -> {
                val b = body(s)
                when (b.optString("action")) {
                    "add" -> { val site = Sites.add(b.optString("name"), b.optString("url")); refreshHomeAfter(s); json(200, ok().put("site", site.json().put("id", site.id))) }
                    "remove" -> { Sites.remove(b.optString("id")); refreshHomeAfter(s); json(200, ok()) }
                    "set" -> { Sites.setOpens(b.optString("id"), b.optString("opens")); json(200, ok()) }
                    else -> throw ApiError(400, "action must be add or remove")
                }
            }
            // Updating Couch Phone itself from the newest release on GitHub (Updater.kt).
            "GET /api/update" -> json(200, ok(Updater.summary()))
            "POST /api/update" -> json(200, ok(Updater.start()))
            "GET /api/battery" -> json(200, ok(App.battery()))
            "GET /api/display" -> json(200, ok(TvScreen.describe()))
            "POST /api/display" -> { TvScreen.set(body(s)); json(200, ok(TvScreen.describe())) }
            // Where the secure remote is (air mouse and speak need https), and whether this page is on it.
            "GET /api/secure" -> json(200, ok().put("secure", secure).put("port", Tls.port)
                .put("url", App.lanAddress()?.let { "https://$it:${Tls.port}/" } ?: JSONObject.NULL)
                .put("android_cert", "/couch-phone.crt").put("ios_profile", "/couch-phone.mobileconfig"))
            "GET /api/bluetooth" -> json(200, ok(Bt.state()))
            "POST /api/bluetooth" -> json(200, ok(Bt.act(body(s))))
            "GET /api/wifi" -> json(200, ok(Wifi.state()))
            "POST /api/wifi" -> json(200, ok(Wifi.act(body(s))))
            "GET /api/apps" -> json(200, ok().put("apps", appList()))
            "POST /api/launch" -> launch(body(s).optString("app"))
            "POST /api/volume" -> volume(body(s))
            "GET /api/weather" -> json(200, ok(Weather.get() ?: throw ApiError(503, "No weather right now.")))
            "POST /api/home-apps" -> {
                val b = body(s)
                Store.writeJson("home-apps.json", JSONObject().put("apps", b.optJSONArray("apps") ?: JSONArray()).put("line", b.optInt("line", 10)))
                json(200, ok())
            }
            "GET /api/app-order" -> json(200, appOrder())
            // The remote's Watch page: its own order and hidden apps (dragged and edited on the phone).
            "GET /api/remote-apps" -> json(200, ok(Store.readJson("remote-apps.json") as? JSONObject ?: JSONObject().put("order", JSONArray()).put("hidden", JSONArray())).put("saved", Store.readJson("remote-apps.json") != null))
            "POST /api/remote-apps" -> {
                val b = body(s)
                fun pkgs(k: String) = JSONArray().also { out -> b.optJSONArray(k)?.let { a -> for (i in 0 until minOf(a.length(), 400)) a.optString(i).takeIf { Regex("[A-Za-z0-9_.]{1,200}").matches(it) }?.let { out.put(it) } } }
                val saved = JSONObject().put("order", pkgs("order")).put("hidden", pkgs("hidden"))
                Store.writeJson("remote-apps.json", saved)
                json(200, ok(saved).put("saved", true))
            }
            // Puts any installed app on the home's dock (the end of it), unhiding it if it was hidden.
            "POST /api/home-add" -> { homeAdd(body(s).optString("package")); refreshHomeAfter(s); json(200, appOrder()) }
            "POST /api/app-order" -> {
                val b = body(s)
                val order = b.optJSONArray("order") ?: throw ApiError(400, "order must be a list of app packages")
                if (!b.has("line")) throw ApiError(400, "line is required")
                Store.writeJson("app-order.json", JSONObject().put("order", order).put("line", b.optInt("line").coerceIn(0, 10)))
                refreshHomeAfter(s)
                json(200, appOrder())
            }
            "GET /api/who" -> json(200, ok().put("who", Now.who ?: JSONObject.NULL))
            "POST /api/who" -> { Now.who = body(s).optString("who").ifEmpty { null }; Now.push(); json(200, ok().put("who", Now.who ?: JSONObject.NULL)) }
            "GET /api/people" -> json(200, ok().put("people", Store.people()))
            "POST /api/people" -> { savePeople(body(s)); refreshHomeAfter(s); json(200, ok().put("people", Store.people())) }
            "GET /api/settings" -> json(200, settings())
            "POST /api/settings" -> { saveSettings(body(s)); refreshHomeAfter(s); json(200, settings()) }
            "GET /api/home" -> json(200, ok().put("on", App.homeEnabled(App.instance)).put("held", HomeRole.held(App.instance)).put("dex", JSONObject(Dex.describe())))
            "POST /api/home/open" -> { Thread { Tv.pauseBrowser(); Dex.openHome() }.start(); json(200, ok()) }
            "GET /api/root" -> json(200, ok(JSONObject(Root.status())).put("dex", JSONObject(Dex.describe())))
            "POST /api/phones/forget" -> { Auth.forgetAll(); json(200, ok().put("note", "Every phone must pair again.")) }
            // Not in couch-phone (yet): answer the way couch-tv's remote and home expect for "nothing here".
            "GET /api/recs" -> json(200, ok().put("updated", Now.iso()).put("continue", JSONArray()).put("picks", JSONArray()))
            "GET /api/continue" -> json(200, ok().put("items", JSONArray()))
            "GET /api/history" -> json(200, ok().put("items", JSONArray()))
            "GET /api/music" -> json(200, ok().put("music", JSONObject.NULL))
            "GET /api/picture" -> json(200, ok().put("mode", JSONObject.NULL).put("supported", false).put("can_set", false))
            "GET /api/screens" -> json(200, ok().put("screens", JSONArray()))
            "POST /api/watch-next" -> json(200, ok())
            // Power: the TV's picture off and on (TvScreen.setTvOff); the sleep timer turns it off later.
            "POST /api/power" -> {
                val st = body(s).optString("state", "toggle")
                TvScreen.setTvOff(when (st) { "off" -> true; "on" -> false; "toggle" -> !TvScreen.tvOff; else -> throw ApiError(400, "state must be on, off or toggle") })
                json(200, ok().put("power", if (TvScreen.tvOff) "off" else "on"))
            }
            "GET /api/sleep" -> json(200, ok().put("ends", if (TvScreen.sleepEnds > 0) Now.iso(TvScreen.sleepEnds) else JSONObject.NULL))
            "POST /api/sleep" -> { TvScreen.setSleep(body(s).optInt("minutes")); json(200, ok().put("ends", if (TvScreen.sleepEnds > 0) Now.iso(TvScreen.sleepEnds) else JSONObject.NULL)) }
            "POST /api/ask", "POST /api/play", "GET /api/find", "POST /api/picture" ->
                json(501, err("Not in Couch Phone."))
            else -> json(404, err("no such endpoint: $m $path"))
        }
    }

    /** Every app with a main screen, in the shape couch-tv's remote shows in its app grid. */
    private fun appList(): JSONArray {
        val pm = App.instance.packageManager
        val arr = JSONArray()
        Tv.launchables().map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
            .forEach { (pkg, name) -> arr.put(JSONObject().put("id", pkg).put("name", name).put("package", pkg).put("colour", "#2A2A30").put("installed", true)
                .put("icon", "/api/app-icon/$pkg")) }
        // website tiles (Sites.kt), listed like apps
        Sites.list().forEach { site -> arr.put(JSONObject().put("id", site.id).put("name", site.name).put("package", site.id).put("colour", "#2A2A30")
            .put("installed", true).put("icon", "/api/app-icon/${site.id}").put("kind", "site").put("url", site.url).put("pinned", site.pinned).put("opens", Sites.opens(site.slug))) }
        return arr
    }

    private fun launch(target: String): Response {
        if (target.isEmpty()) throw ApiError(400, "app is required")
        if (target == "home" || target == BuildConfig.APPLICATION_ID) {
            Tv.pauseBrowser()
            Dex.openHome()
            return json(200, ok().put("app", Now.describe(BuildConfig.APPLICATION_ID)))
        }
        if (target == "settings") return launch("com.android.settings")
        // a website tile: the site in Brave on the TV
        if (target.startsWith(Sites.PREFIX)) { Tv.pauseBrowser(); Sites.open(target); return json(200, ok()) }
        // Android's own Bluetooth and Wi-Fi screens (the TV home's fallback for anything its panels cannot do)
        val screen = mapOf("bluetooth" to "android.settings.BLUETOOTH_SETTINGS", "network" to "android.settings.WIFI_SETTINGS",
            "wifi" to "android.settings.WIFI_SETTINGS")[target]
        if (screen != null) {
            if (!Dex.openAction(screen)) throw ApiError(500, "Could not open the settings screen.")
            return json(200, ok().put("app", Now.describe("com.android.settings")))
        }
        if (!Regex("[A-Za-z0-9_.]+").matches(target) || !Tv.hasPackage(target)) throw ApiError(404, "$target is not installed")
        Tv.pauseBrowser(except = target)
        if (!Dex.launchPackage(target)) throw ApiError(404, "${Tv.label(target)} has no screen to open")
        Now.push()
        return json(200, ok().put("app", Now.describe(target)))
    }

    private fun volume(b: JSONObject): Response {
        if (b.has("level")) Tv.setLevel(b.optInt("level"))
        else {
            val code = mapOf("up" to android.view.KeyEvent.KEYCODE_VOLUME_UP, "down" to android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                "mute" to android.view.KeyEvent.KEYCODE_VOLUME_MUTE)[b.optString("action")]
                ?: throw ApiError(400, "action must be up, down or mute, or send level")
            val steps = if (code == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) 1 else b.optInt("steps", 1).coerceIn(1, 20)
            repeat(steps) { Tv.volumeKey(code); Thread.sleep(60) }
        }
        Now.push()
        return json(200, ok().put("volume", JSONObject(Tv.volume())))
    }

    /**
     * For the TV's "Connect a remote" screen: the remote's address with a pairing code in it (one
     * scan opens the remote already paired, see android/web/shim.js), and the Android app's address.
     */
    private fun connectInfo(): JSONObject {
        val ip = App.lanAddress() ?: throw ApiError(503, "This phone is not on Wi-Fi.")
        val base = "http://$ip:${Store.port}/"
        val pin = Auth.currentPin() ?: Auth.newPin()
        val app = exists("remote-app/couch-remote.apk")
        // The fingerprint of this TV's certificate authority: the Couch Remote app checks the one it
        // fetches against it, so nobody else on the Wi-Fi can slip in their own.
        val fp = java.security.MessageDigest.getInstance("SHA-256").digest(Tls.caDer()).joinToString("") { "%02x".format(it) }
        val o = ok().put("url", base).put("pin", pin).put("qr_remote", Qr.svg("${base}#cpin=$pin&cafp=$fp")).put("app", app)
        if (app) o.put("app_url", "${base}couch-remote.apk").put("qr_app", Qr.svg("${base}couch-remote.apk"))
        o.put("update", Updater.summary())
        return o
    }

    private fun homeAdd(pkg: String) {
        if (!Regex("[A-Za-z0-9_.]+").matches(pkg) || !Tv.hasPackage(pkg)) throw ApiError(404, "$pkg is not installed")
        // 1. not hidden any more
        val st = Store.readJson("home-settings.json") as? JSONObject ?: JSONObject()
        st.optJSONArray("hidden_apps")?.let { h ->
            val keep = JSONArray(); for (i in 0 until h.length()) if (h.optString(i) != pkg) keep.put(h.opt(i))
            if (keep.length() != h.length()) { st.put("hidden_apps", keep); Store.writeJson("home-settings.json", st); Store.putStr("home_settings_updated", Now.iso()) }
        }
        // 2. one of the apps the order knows about (the TV reports its list; a hidden app was left out of it)
        val known = Store.readJson("home-apps.json") as? JSONObject ?: JSONObject().put("apps", JSONArray()).put("line", 10)
        val apps = known.optJSONArray("apps") ?: JSONArray().also { known.put("apps", it) }
        if ((0 until apps.length()).none { apps.optJSONObject(it)?.optString("package") == pkg }) {
            apps.put(JSONObject().put("package", pkg).put("name", Tv.label(pkg)))
            Store.writeJson("home-apps.json", known)
        }
        // 3. on the dock: the end of it, or its last place when it is full (that app moves behind More apps)
        val cur = appOrder()
        val list = cur.getJSONArray("apps")
        val order = ArrayList<String>(); for (i in 0 until list.length()) list.getJSONObject(i).optString("package").let { if (it != pkg) order += it }
        var line = cur.optInt("line")
        val i0 = (0 until list.length()).indexOfFirst { list.getJSONObject(it).optString("package") == pkg }
        if (i0 in 0 until line) order.add(i0, pkg)   // already on the dock: it stays where it is
        else {
            line = line.coerceAtMost(order.size)
            if (line < 10) { order.add(line, pkg); line += 1 } else order.add(9, pkg)
        }
        Store.writeJson("app-order.json", JSONObject().put("order", JSONArray(order)).put("line", line.coerceIn(0, 10)))
    }

    private fun appOrder(): JSONObject {
        val known = (Store.readJson("home-apps.json") as? JSONObject) ?: JSONObject()
        val apps = known.optJSONArray("apps") ?: JSONArray()
        val saved = Store.readJson("app-order.json") as? JSONObject
        if (saved == null) return ok().put("apps", apps).put("line", minOf(known.optInt("line", 10), 10, apps.length())).put("saved", false).put("dock_max", 10)
        val by = (0 until apps.length()).associate { apps.getJSONObject(it).optString("package") to apps.getJSONObject(it) }
        val order = saved.optJSONArray("order") ?: JSONArray()
        val listed = JSONArray(); val inOrder = HashSet<String>()
        for (i in 0 until order.length()) { val p = order.optString(i); inOrder += p; by[p]?.let { listed.put(it) } }
        val line = (0 until minOf(saved.optInt("line"), order.length())).count { by.containsKey(order.optString(it)) }
        by.forEach { (p, a) -> if (p !in inOrder) listed.put(a) }
        return ok().put("apps", listed).put("line", minOf(line, 10)).put("saved", true).put("dock_max", 10)
    }

    private fun settings(): JSONObject = ok()
        .put("settings", Store.readJson("home-settings.json") as? JSONObject ?: JSONObject())
        .put("updated", Store.str("home_settings_updated") ?: JSONObject.NULL)
        .put("people", Store.people()).put("weather", Store.weather()).put("tv_name", Store.tvName())
        .put("phones", Auth.phoneCount()).put("port", Store.port)
        .put("root", JSONObject(Root.status())).put("dex", JSONObject(Dex.describe()))
        .put("open_on_dex", Store.bool("open_on_dex", true)).put("apps_full_screen", Store.bool("apps_full_screen", true))
        .put("hide_taskbar", Store.bool("hide_taskbar", true)).put("taskbar", Dex.taskbarNote ?: JSONObject.NULL)

    /** {"people": [...]} replaces the list; {"order": [ids]} only reorders it. */
    private fun savePeople(b: JSONObject) {
        val list = b.optJSONArray("people") ?: run {
            val order = b.optJSONArray("order") ?: throw ApiError(400, "people (a list) or order (a list of ids) is required")
            val now = Store.people()
            val by = (0 until now.length()).associate { now.getJSONObject(it).optString("id") to now.getJSONObject(it) }
            val out = JSONArray(); val seen = HashSet<String>()
            for (i in 0 until order.length()) by[order.optString(i)]?.let { if (seen.add(order.optString(i))) out.put(it) }
            by.forEach { (id, p) -> if (id !in seen) out.put(p) }
            out
        }
        val clean = JSONArray()
        for (i in 0 until minOf(list.length(), 12)) {
            val p = list.optJSONObject(i) ?: continue
            val name = p.optString("name").trim().take(40)
            if (name.isNotEmpty()) clean.put(JSONObject().put("id", p.optString("id").ifEmpty { "person${i + 1}" }.take(32)).put("name", name))
        }
        Store.putStr("people", clean.toString())
        Now.push()
    }

    private fun saveSettings(b: JSONObject) {
        b.optJSONObject("settings")?.let {
            if (it.toString().length > 32_000) throw ApiError(413, "settings are too big")
            Store.writeJson("home-settings.json", it)
            Store.putStr("home_settings_updated", Now.iso())
        }
        b.optJSONObject("weather")?.let { w ->
            if (w.has("lat") && w.has("lon")) Store.putStr("weather", JSONObject().put("lat", w.getDouble("lat")).put("lon", w.getDouble("lon"))
                .put("place", w.optString("place").take(40)).put("timezone", w.optString("timezone", "UTC").take(40)).toString())
            Weather.clear()
        }
        b.optString("tv_name").trim().takeIf { it.isNotEmpty() }?.let { Store.putStr("tv_name", it.take(30)) }
        if (b.has("open_on_dex")) Store.putBool("open_on_dex", b.optBoolean("open_on_dex"))
        if (b.has("apps_full_screen")) Store.putBool("apps_full_screen", b.optBoolean("apps_full_screen"))
        if (b.has("hide_taskbar")) { Store.putBool("hide_taskbar", b.optBoolean("hide_taskbar")); Thread { runCatching { Dex.applyTaskbar() } }.start() }
    }

    // ---------------------------------------------------------------- server-sent events

    private fun events(): Response {
        val q = LinkedBlockingQueue<String>(50)
        val fn: (String) -> Unit = { q.offer("event: now\ndata: $it\n\n") }
        q.offer("event: now\ndata: ${Now.payload()}\n\n")
        Now.listen(fn)
        val stream = object : InputStream() {
            private var buf = ByteArray(0); private var pos = 0; private var closed = false
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (closed) return -1
                if (pos >= buf.size) {
                    val msg = try { q.poll(20, TimeUnit.SECONDS) } catch (e: InterruptedException) { null } ?: ": keepalive\n\n"
                    buf = msg.toByteArray(); pos = 0
                }
                val n = minOf(len, buf.size - pos)
                System.arraycopy(buf, pos, b, off, n); pos += n
                return n
            }
            override fun close() { closed = true; Now.unlisten(fn) }
        }
        return newChunkedResponse(Response.Status.OK, "text/event-stream", stream).apply {
            addHeader("Cache-Control", "no-cache"); addHeader("X-Accel-Buffering", "no")
        }
    }

    // ---------------------------------------------------------------- WebSocket (/api/ws)

    /**
     * Low-latency channel. Sends {"event":"now", ...} on every change. Takes
     * {"key":"up"}, {"key":"ok","hold":true}, {"volume":"up"} or {"text":"bluey"}; answers {"ok":true} or an error.
     */
    private inner class Socket(h: IHTTPSession) : WebSocket(h) {
        private val fn: (String) -> Unit = { runCatching { send(JSONObject(it).put("event", "now").toString()) } }
        /** The game controller this remote plays with (Gamepad.kt), null for none yet. */
        @Volatile private var player: Gamepad.Player? = null
        @Volatile private var padRetryAt = 0L
        // A controller-only socket (?pad=1) does not need the now playing updates.
        private val padOnly = h.parameters["pad"]?.firstOrNull() == "1"
        private fun unplug() { player?.let { Gamepad.leave(it) }; player = null }
        override fun onOpen() { if (!padOnly) { Now.listen(fn); runCatching { send(Now.payload().put("event", "now").toString()) } } }
        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) { Now.unlisten(fn); unplug() }
        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
        override fun onException(e: IOException?) { Now.unlisten(fn); unplug() }
        override fun onMessage(frame: NanoWSD.WebSocketFrame) {
            val reply = try {
                val m = JSONObject(frame.textPayload)
                when {
                    m.has("key") -> { Tv.key(m.getString("key"), m.optBoolean("hold")); JSONObject().put("ok", true).put("key", m.getString("key")) }
                    m.has("volume") -> { volume(JSONObject().put("action", m.getString("volume"))); JSONObject().put("ok", true) }
                    m.has("text") -> { Tv.text(m.getString("text")); JSONObject().put("ok", true) }
                    // The air mouse sends many small moves: no answer for those, to keep the socket quiet.
                    m.has("pointer") -> { Pointer.handle(m.getJSONObject("pointer")); if (m.optBoolean("quiet")) return; JSONObject().put("ok", true) }
                    // The remote's Controller screen: the first message plugs a controller in (player 1-4),
                    // then each change of the sticks and buttons; {"pad": false} unplugs it.
                    m.has("pad") -> {
                        val st = m.optJSONObject("pad")
                        if (st == null) { unplug(); JSONObject().put("ok", true).put("player", 0) }
                        else {
                            val first = player == null
                            // a controller that did not start is not tried again on every stick move
                            if (first && System.currentTimeMillis() < padRetryAt) return
                            val p = player ?: try { Gamepad.join().also { player = it } }
                                catch (e: ApiError) { padRetryAt = System.currentTimeMillis() + 10_000; throw e }
                            Gamepad.state(p, st)
                            if (!first) return
                            JSONObject().put("ok", true).put("player", p.n)
                        }
                    }
                    else -> JSONObject().put("ok", false).put("error", "unknown message")
                }
            } catch (e: ApiError) { JSONObject().put("ok", false).put("error", e.message).put("status", e.status) }
            catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message) }
            runCatching { send(reply.toString()) }
        }
    }

    // ---------------------------------------------------------------- static files

    private fun staticOrIndex(root: String, rel: String, inject: Boolean): Response {
        val clean = URLDecoder.decode(rel, "UTF-8")
        if (clean.contains("..")) return json(400, err("bad path"))
        if (clean.isNotEmpty() && exists("$root/$clean")) return if (inject && clean == "index.html") index(root) else asset("$root/$clean")
        // single page apps: unknown paths get index.html (files with an extension get a 404)
        if (clean.substringAfterLast('/').contains('.')) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        return if (inject) index(root) else asset("$root/index.html")
    }

    /** The phone remote's index.html with the on-TV pairing script added first. */
    private fun index(root: String): Response {
        val html = assets.open("$root/index.html").bufferedReader().readText()
        val tag = "<script src=\"/ontv/shim.js\"></script>"
        val out = if (html.contains("<head>")) html.replaceFirst("<head>", "<head>$tag") else tag + html
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", out).noCache()
    }

    private fun exists(path: String) = try { assets.open(path).close(); true } catch (e: IOException) { false }

    private fun asset(path: String): Response = try {
        val stream = assets.open(path)
        newChunkedResponse(Response.Status.OK, mime(path), stream).noCache()
    } catch (e: IOException) { newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found") }

    private fun mime(p: String) = when (p.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"; "js", "mjs" -> "application/javascript"; "css" -> "text/css"
        "json" -> "application/json"; "webmanifest" -> "application/manifest+json"; "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; "woff2" -> "font/woff2"
        "ico" -> "image/x-icon"; "txt" -> "text/plain"; else -> "application/octet-stream"
    }

    private fun redirect(to: String) = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply { addHeader("Location", to) }

    // ---------------------------------------------------------------- helpers

    private val bodies = java.util.Collections.synchronizedMap(java.util.WeakHashMap<IHTTPSession, JSONObject>())

    private fun body(s: IHTTPSession): JSONObject = bodies[s] ?: JSONObject()

    private fun readBody(s: IHTTPSession): JSONObject {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return JSONObject()
        if (len > 64 * 1024) throw ApiError(413, "request too large")
        val buf = ByteArray(len)
        var read = 0
        while (read < len) { val n = s.inputStream.read(buf, read, len - read); if (n < 0) break; read += n }
        val text = String(buf, 0, read, Charsets.UTF_8).trim()
        if (text.isEmpty()) return JSONObject()
        return try { JSONObject(text) } catch (e: Exception) { throw ApiError(400, "body must be JSON") }
    }

    private fun ok(from: JSONObject? = null): JSONObject = (from ?: JSONObject()).put("ok", true)
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun json(status: Int, o: JSONObject): Response =
        newFixedLengthResponse(Status(status), "application/json", o.toString()).noStore()

    private fun Response.noCache() = apply { addHeader("Cache-Control", "no-cache") }
    private fun Response.noStore() = apply { addHeader("Cache-Control", "no-store") }

    private class Status(private val code: Int) : Response.IStatus {
        override fun getRequestStatus() = code
        override fun getDescription() = "$code " + when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            409 -> "Conflict"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 429 -> "Too Many Requests";501 -> "Not Implemented"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"; else -> "Status" }
    }

    companion object { private const val TAG = "couchphone.server" }
}
