package app.couchphone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth for the TV home's Bluetooth panel: on and off, paired devices, finding new ones,
 * pairing, unpairing, connecting and disconnecting.
 *
 * Android's public Bluetooth API does most of it. Its permissions are granted with root at start,
 * so there are no pop-ups. Unpairing, connecting and disconnecting are hidden Android calls; when
 * the phone refuses one, the answer says so and the TV can open Android's own Bluetooth screen.
 */
@SuppressLint("MissingPermission")
object Bt {
    private const val TAG = "couchphone.bt"
    private val ctx get() = App.instance
    private val adapter: BluetoothAdapter? get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** Devices found by the last search: address to (name, class, signal). */
    private val found = ConcurrentHashMap<String, JSONObject>()
    @Volatile private var searchingUntil = 0L
    /** A pairing in progress that needs the person: the code to type or confirm. */
    @Volatile private var pairing: JSONObject? = null
    @Volatile private var lastNote: String? = null
    private var registered = false
    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()

    private val PERMS = if (Build.VERSION.SDK_INT >= 31)
        listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN")
    else listOf("android.permission.ACCESS_FINE_LOCATION")

    /** Grants the Bluetooth permissions with root (called once per start). */
    fun grantPermissions() {
        val p = ctx.packageName
        Root.shOrNull(PERMS.joinToString("; ") { "pm grant $p $it 2>/dev/null" })
        // Lets this app use the few hidden Bluetooth calls (unpair, connect) on new Android versions.
        if (Build.VERSION.SDK_INT >= 28) runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/") }
        listen()
    }

    private fun allowed() = PERMS.all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    private fun need() {
        if (adapter == null) throw ApiError(404, "This phone has no Bluetooth.")
        if (!allowed()) {
            grantPermissions()
            if (!allowed()) throw ApiError(403, "Couch Phone may not use Bluetooth yet. Open Couch Phone on the phone once, and allow root in Magisk.")
        }
    }

    // ---------------------------------------------------------------- events

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> dev?.let {
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    found[it.address] = describe(it).put("signal", if (rssi == Short.MIN_VALUE.toInt()) JSONObject.NULL else rssi)
                }
                BluetoothDevice.ACTION_NAME_CHANGED -> dev?.let { d -> found[d.address]?.put("name", d.name ?: d.address) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> searchingUntil = 0
                BluetoothDevice.ACTION_PAIRING_REQUEST -> dev?.let {
                    val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    val key = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                    pairing = JSONObject().put("address", it.address).put("name", it.name ?: it.address)
                        .put("kind", when (variant) {
                            BluetoothDevice.PAIRING_VARIANT_PIN -> "pin"                // type a PIN (often 0000) on the phone's dialog
                            BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "confirm"
                            5 -> "type_on_device"                                      // PAIRING_VARIANT_PASSKEY_NOTIFICATION: a keyboard
                            4 -> "type_on_device"                                      // PAIRING_VARIANT_DISPLAY_PIN
                            else -> "confirm"
                        })
                        .put("code", if (key >= 0) (if (variant == 4) key.toString() else String.format("%06d", key)) else JSONObject.NULL)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> dev?.let {
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (st != BluetoothDevice.BOND_BONDING && pairing?.optString("address") == it.address) pairing = null
                    if (st == BluetoothDevice.BOND_BONDED) { found.remove(it.address); lastNote = "${it.name ?: "The device"} is paired." }
                    if (st == BluetoothDevice.BOND_NONE && i.getIntExtra("android.bluetooth.device.extra.REASON", 0) != 0) lastNote = "Pairing with ${it.name ?: "the device"} did not work. Put it in pairing mode and try again."
                }
            }
        }
    }

    @Synchronized private fun listen() {
        if (registered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Android's pairing request is only sent to apps that hold BLUETOOTH_PRIVILEGED: then it is simply missed.
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, f)
        registered = true
        // Profile proxies, to know what is connected (and to connect and disconnect).
        for (p in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, HID_HOST, LE_AUDIO)) runCatching {
            adapter?.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy }
                override fun onServiceDisconnected(profile: Int) { proxies.remove(profile) }
            }, p)
        }
    }

    private const val HID_HOST = 4      // BluetoothProfile.HID_HOST (hidden): keyboards, mice, game controllers
    private const val LE_AUDIO = 22     // BluetoothProfile.LE_AUDIO (Android 13+)

    // ---------------------------------------------------------------- state

    private fun kind(d: BluetoothDevice): String {
        val c: BluetoothClass = runCatching { d.bluetoothClass }.getOrNull() ?: return "other"
        return when (c.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (c.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "speaker"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> "tv"
                else -> "headphones"
            }
            BluetoothClass.Device.Major.PERIPHERAL -> when {
                c.deviceClass and 0x40 != 0 && c.deviceClass and 0x80 != 0 -> "keyboard"
                c.deviceClass and 0x80 != 0 -> "mouse"
                c.deviceClass and 0x40 != 0 -> "keyboard"
                c.deviceClass and 0x0c != 0 -> "gamepad"
                else -> "gamepad"
            }
            BluetoothClass.Device.Major.PHONE -> "phone"
            BluetoothClass.Device.Major.COMPUTER -> "computer"
            BluetoothClass.Device.Major.WEARABLE -> "watch"
            else -> "other"
        }
    }

    private fun connected(d: BluetoothDevice): Boolean {
        runCatching { return BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as Boolean }
        return proxies.values.any { p -> runCatching { p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false) }
    }

    private fun describe(d: BluetoothDevice) = JSONObject()
        .put("address", d.address)
        .put("name", runCatching { d.alias ?: d.name }.getOrNull() ?: d.name ?: d.address)
        .put("kind", kind(d))

    fun state(): JSONObject {
        val a = adapter ?: return JSONObject().put("available", false).put("on", false)
        if (!allowed()) grantPermissions()
        val o = JSONObject().put("available", true).put("allowed", allowed()).put("on", a.isEnabled)
            .put("searching", a.isEnabled && (System.currentTimeMillis() < searchingUntil || runCatching { a.isDiscovering }.getOrDefault(false)))
            .put("pairing", pairing ?: JSONObject.NULL).put("note", lastNote ?: JSONObject.NULL)
        if (!allowed() || !a.isEnabled) return o.put("paired", JSONArray()).put("found", JSONArray())
        val bonded = runCatching { a.bondedDevices }.getOrNull().orEmpty()
        val paired = JSONArray()
        bonded.map { d -> describe(d).put("connected", connected(d)) }
            .sortedWith(compareBy({ !it.optBoolean("connected") }, { it.optString("name").lowercase() }))
            .forEach { paired.put(it) }
        val bondedAddr = bonded.map { it.address }.toSet()
        val list = JSONArray()
        found.values.filter { it.optString("address") !in bondedAddr }
            // named devices first, strongest signal first; unnamed ones (only an address) last
            .sortedWith(compareBy({ it.optString("name") == it.optString("address") }, { -(it.optInt("signal", -200)) }))
            .take(30).forEach { list.put(it) }
        return o.put("paired", paired).put("found", list)
    }

    // ---------------------------------------------------------------- actions

    /** {"action":"on|off|search|stop|pair|unpair|connect|disconnect", "address":"AA:BB:..."} */
    fun act(b: JSONObject): JSONObject {
        need()
        val a = adapter!!
        lastNote = null
        when (val action = b.optString("action")) {
            "on", "off" -> power(action == "on")
            "search" -> {
                if (!a.isEnabled) throw ApiError(409, "Turn Bluetooth on first.")
                found.clear()
                if (a.isDiscovering) a.cancelDiscovery()
                if (!a.startDiscovery()) throw ApiError(500, "The phone would not start searching.")
                searchingUntil = System.currentTimeMillis() + 15_000
            }
            "stop" -> { runCatching { a.cancelDiscovery() }; searchingUntil = 0 }
            else -> {
                val addr = b.optString("address").uppercase()
                if (!BluetoothAdapter.checkBluetoothAddress(addr)) throw ApiError(400, "address is required")
                val d = a.getRemoteDevice(addr)
                when (action) {
                    "pair" -> {
                        // Searching slows pairing down a lot: stop first.
                        runCatching { a.cancelDiscovery() }; searchingUntil = 0
                        if (d.bondState == BluetoothDevice.BOND_BONDED) throw ApiError(409, "${d.name ?: addr} is already paired.")
                        if (!d.createBond()) throw ApiError(500, "Pairing did not start. Put the device in pairing mode and try again.")
                        lastNote = "Pairing with ${d.name ?: addr}…"
                    }
                    "unpair" -> {
                        val ok = runCatching { d.javaClass.getMethod("removeBond").invoke(d) as Boolean }.getOrDefault(false)
                        if (!ok) throw ApiError(501, "This phone would not unpair ${d.name ?: addr} from here. Use Bluetooth settings.")
                        lastNote = "${d.name ?: addr} is unpaired."
                    }
                    "connect", "disconnect" -> {
                        val on = action == "connect"
                        if (!profileCall(d, if (on) "connect" else "disconnect"))
                            throw ApiError(501, "This phone would not ${action} ${d.name ?: addr} from here. Turn the device off and on, or use Bluetooth settings.")
                        lastNote = if (on) "Connecting ${d.name ?: addr}…" else "${d.name ?: addr} is disconnected."
                    }
                    "cancel" -> { runCatching { d.javaClass.getMethod("cancelBondProcess").invoke(d) }; pairing = null }
                    else -> throw ApiError(400, "action must be on, off, search, stop, pair, unpair, connect, disconnect or cancel")
                }
            }
        }
        return state()
    }

    private fun power(on: Boolean) {
        val a = adapter!!
        if (a.isEnabled == on) return
        // The app's own switch works up to Android 12; newer versions only allow it with root.
        @Suppress("DEPRECATION")
        val ok = runCatching { if (on) a.enable() else a.disable() }.getOrDefault(false)
        if (!ok) {
            val v = if (on) "enable" else "disable"
            Root.shOrNull("cmd bluetooth_manager $v 2>/dev/null || svc bluetooth $v")
        }
        // wait up to 5 s for it to switch
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until && a.isEnabled != on) Thread.sleep(150)
        if (a.isEnabled != on) throw ApiError(500, "Bluetooth did not switch ${if (on) "on" else "off"}.")
    }

    /** connect or disconnect on every profile the device uses (hidden calls; false if all refused). */
    private fun profileCall(d: BluetoothDevice, method: String): Boolean {
        var any = false
        val uuids = runCatching { d.uuids?.map { it.uuid.toString().lowercase() } }.getOrNull().orEmpty()
        for ((id, proxy) in proxies) {
            // Only profiles the device has, when it says which (A2DP sink 110b, headset 1108/111e, HID 1124, LE audio 184e).
            val wanted = when (id) {
                BluetoothProfile.A2DP -> uuids.isEmpty() || uuids.any { it.startsWith("0000110b") || it.startsWith("0000110d") }
                BluetoothProfile.HEADSET -> uuids.isEmpty() || uuids.any { it.startsWith("00001108") || it.startsWith("0000111e") }
                HID_HOST -> uuids.isEmpty() || uuids.any { it.startsWith("00001124") || it.startsWith("00001812") }
                else -> true
            }
            if (!wanted) continue
            val ok = runCatching { proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, d) as Boolean }
                .onFailure { Log.i(TAG, "$method on profile $id: ${it.cause ?: it}") }.getOrDefault(false)
            any = any || ok
        }
        return any
    }
}
