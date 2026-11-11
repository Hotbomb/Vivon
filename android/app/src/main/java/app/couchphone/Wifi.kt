package app.couchphone

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wi-Fi for the TV home's Wi-Fi panel, through Android's own `cmd wifi` tool (Android 11 and
 * newer), run as root: on and off, nearby networks, the one in use, connecting with a password,
 * and forgetting a saved network.
 *
 * Careful: the phone remote talks to this phone over Wi-Fi. Switching Wi-Fi off, or moving to
 * another network, cuts the remote off until the phone is back on the same network as it.
 */
object Wifi {
    private fun sh(cmd: String, timeout: Long = 15_000) = Root.sh(cmd, timeout)

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun unquote(s: String) = s.trim().removeSurrounding("\"")

    data class Net(val ssid: String, val bssid: String, val freq: Int, val rssi: Int, val flags: String)

    private fun security(flags: String): String = when {
        flags.contains("EAP") -> "enterprise"
        flags.contains("SAE") && !flags.contains("PSK") -> "wpa3"
        flags.contains("PSK") -> "wpa2"
        flags.contains("WEP") -> "wep"
        flags.contains("OWE") -> "owe"
        else -> "open"
    }

    /** "    BSSID  Frequency  RSSI  Age(sec)  SSID  Flags" rows from `cmd wifi list-scan-results`. */
    private fun scanResults(): List<Net> {
        val row = Regex("""^\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\s+(\d+)\s+(-?\d+)\s+(\S+)\s+(.*?)\s*((?:\[[^\]]*\])*)\s*$""")
        return sh("cmd wifi list-scan-results 2>&1").lines().mapNotNull { l ->
            row.find(l)?.groupValues?.let { g -> Net(g[5].trim(), g[1].lowercase(), g[2].toIntOrNull() ?: 0, g[3].toIntOrNull() ?: -100, g[6]) }
        }
    }

    /** Saved networks: SSID to network id, from `cmd wifi list-networks`. */
    private fun saved(): Map<String, Int> {
        val row = Regex("""^\s*(\d+)\s+(.+?)\s+(\S+)\s*$""")
        return sh("cmd wifi list-networks 2>&1").lines().drop(1).mapNotNull { l ->
            row.find(l)?.groupValues?.let { g -> unquote(g[2]) to g[1].toInt() }
        }.toMap()
    }

    private fun status(): Pair<Boolean, String?> {
        val out = sh("cmd wifi status 2>&1")
        val on = out.contains("Wifi is enabled", ignoreCase = true)
        val ssid = Regex("""Wifi is connected to "(.*)"""").find(out)?.groupValues?.get(1)
            ?: Regex("""SSID: "(.*?)", BSSID""").find(out)?.groupValues?.get(1)?.takeIf { out.contains("COMPLETED") }
        return on to ssid
    }

    private fun bars(rssi: Int) = when { rssi >= -55 -> 4; rssi >= -66 -> 3; rssi >= -77 -> 2; rssi >= -88 -> 1; else -> 0 }

    fun state(): JSONObject {
        if (Root.available == false || !Root.openShell()) throw ApiError(503, "Wi-Fi settings need root. Open Magisk and allow Couch Phone.")
        val (on, current) = status()
        val o = JSONObject().put("on", on).put("current", current ?: JSONObject.NULL).put("address", App.lanAddress() ?: JSONObject.NULL)
        if (!on) return o.put("networks", JSONArray())
        val known = saved()
        // One row per network name (the strongest access point), hidden networks left out.
        val best = scanResults().filter { it.ssid.isNotEmpty() }.groupBy { it.ssid }.map { (_, v) -> v.maxBy { it.rssi } }
        val list = JSONArray()
        best.sortedWith(compareBy({ it.ssid != current }, { it.ssid !in known }, { -it.rssi })).take(40).forEach {
            list.put(JSONObject().put("ssid", it.ssid).put("bars", bars(it.rssi)).put("security", security(it.flags))
                .put("band", if (it.freq >= 5900) "6 GHz" else if (it.freq >= 4900) "5 GHz" else "2.4 GHz")
                .put("saved", it.ssid in known).put("current", it.ssid == current))
        }
        // Saved networks that are not around right now still show, so they can be forgotten.
        known.keys.filter { k -> best.none { it.ssid == k } }.sorted().forEach {
            list.put(JSONObject().put("ssid", it).put("bars", 0).put("saved", true).put("current", it == current).put("away", true))
        }
        return o.put("networks", list)
    }

    /** {"action":"on|off|scan|connect|forget", "ssid":"...", "password":"..."} */
    fun act(b: JSONObject): JSONObject {
        when (b.optString("action")) {
            "on", "off" -> {
                val en = b.optString("action") == "on"
                sh("cmd wifi set-wifi-enabled ${if (en) "enabled" else "disabled"} 2>&1 || svc wifi ${if (en) "enable" else "disable"}")
            }
            "scan" -> sh("cmd wifi start-scan 2>&1")
            "connect" -> {
                val ssid = b.optString("ssid").ifEmpty { throw ApiError(400, "ssid is required") }
                val pass = b.optString("password")
                val net = scanResults().filter { it.ssid == ssid }.maxByOrNull { it.rssi }
                val sec = net?.let { security(it.flags) } ?: (if (pass.isEmpty()) "open" else "wpa2")
                if (sec == "enterprise" || sec == "wep") throw ApiError(501, "$ssid needs a sign-in this panel cannot do. Use Wi-Fi settings.")
                if ((sec == "wpa2" || sec == "wpa3") && pass.isEmpty()) {
                    // A saved network: Android joins it by itself when it is in range; turning Wi-Fi off and on nudges it.
                    if (ssid in saved()) throw ApiError(409, "Type the password for $ssid, or wait: the phone joins saved networks by itself.")
                    throw ApiError(400, "$ssid needs a password.")
                }
                if (sec != "open" && sec != "owe" && pass.length < 8) throw ApiError(400, "A Wi-Fi password has at least 8 characters.")
                val out = sh("cmd wifi connect-network ${q(ssid)} $sec${if (pass.isNotEmpty() && sec != "open" && sec != "owe") " " + q(pass) else ""} 2>&1", 20_000)
                if (out.contains("Error", true) || out.contains("Exception") || out.contains("failed", true) || out.contains("Unknown command", true))
                    throw ApiError(500, "Could not join $ssid: ${out.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: "no answer"}")
            }
            "forget" -> {
                val ssid = b.optString("ssid").ifEmpty { throw ApiError(400, "ssid is required") }
                val id = saved()[ssid] ?: throw ApiError(404, "$ssid is not saved.")
                sh("cmd wifi forget-network $id 2>&1")
            }
            else -> throw ApiError(400, "action must be on, off, scan, connect or forget")
        }
        // Give the change a moment to show.
        Thread.sleep(if (b.optString("action") == "scan") 2500 else 1200)
        return state()
    }
}
