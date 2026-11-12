package app.couchphone

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this); Root.init(this); Tv.init(this); Dex.init(this); TvScreen.init(this); Cursor.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { resumed++ }
            override fun onActivityPaused(a: Activity) { resumed-- }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    companion object {
        lateinit var instance: App
        @Volatile private var resumed = 0
        private val main = Handler(Looper.getMainLooper())

        val inForeground get() = resumed > 0

        fun startService(c: Context) {
            runCatching { c.startForegroundService(Intent(c, BridgeService::class.java)) }
                .onFailure { Log.w("couchphone", "could not start the service: ${it.message}") }
        }

        /** Puts the pairing code on the TV: the setup screen, opened on the DeX display. */
        fun showPin() {
            val c = instance
            Thread {
                val d = Dex.target()
                val ok = Root.available == true && Root.shOrNull("am start --display $d -f 0x10000000 -n '${c.packageName}/.SetupActivity' --ez pin true 2>&1")
                    ?.contains("Error") == false
                if (!ok) main.post {
                    runCatching {
                        val o = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(d).toBundle()
                        c.startActivity(Intent(c, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pin", true), o)
                    }
                    Auth.currentPin()?.let { Toast.makeText(c, "Couch Phone code: ${it.take(3)} ${it.drop(3)}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        /** The Wi-Fi interface's address and prefix length, or null when the phone is not on Wi-Fi. */
        fun wifi(): Pair<Inet4Address, Int>? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("swlan")) }
                .flatMap { it.interfaceAddresses }
                .firstOrNull { it.address is Inet4Address }
                ?.let { (it.address as Inet4Address) to it.networkPrefixLength.toInt() }
        }.getOrNull()

        fun lanAddress(): String? = wifi()?.first?.hostAddress

        /** True for this phone itself and for devices on the same Wi-Fi network. Mobile data and anything else: false. */
        fun allowedClient(ip: String?): Boolean {
            if (ip.isNullOrEmpty()) return false
            val a = runCatching { InetAddress.getByName(ip.substringBefore('%')) }.getOrNull() ?: return false
            if (a.isLoopbackAddress) return true
            val v4 = when {
                a is Inet4Address -> a
                // IPv4 written as IPv6 (::ffff:192.168.1.5)
                a.address.size == 16 && a.address.take(10).all { it == 0.toByte() } && a.address[10] == 0xff.toByte() && a.address[11] == 0xff.toByte() ->
                    InetAddress.getByAddress(a.address.copyOfRange(12, 16)) as Inet4Address
                else -> return false
            }
            val (mine, prefix) = wifi() ?: return false
            if (!v4.isSiteLocalAddress) return false
            val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix))
            fun n(x: Inet4Address) = x.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
            return (n(v4) and mask) == (n(mine) and mask)
        }

        /** The phone's battery: {"level": 0-100, "charging": true}. */
        fun battery(): org.json.JSONObject {
            val i = instance.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return org.json.JSONObject().put("level", org.json.JSONObject.NULL)
            val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            return org.json.JSONObject().put("level", if (level < 0) org.json.JSONObject.NULL else Math.round(level * 100f / scale))
                .put("charging", charging).put("plugged", i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }

        fun homeEnabled(c: Context) = c.packageManager.getComponentEnabledSetting(ComponentName(c, "${c.packageName}.HomeAlias")) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        fun setHomeEnabled(c: Context, on: Boolean) = c.packageManager.setComponentEnabledSetting(ComponentName(c, "${c.packageName}.HomeAlias"),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

/** Keeps the web server and the DeX watch running. Started at boot, when the app opens, and after an update. */
class BridgeService : Service() {
    private var server: Server? = null
    private var secureServer: Server? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lowLatency: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bridge", "Couch Phone remote", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "bridge").setContentTitle("Couch Phone")
            .setContentText("Phone remote is on" + (App.lanAddress()?.let { " at http://$it:${Store.port}" } ?: ""))
            .setSmallIcon(R.drawable.icon).setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = runCatching { wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "couchphone").apply { acquire() } }.getOrNull()
        // Low latency Wi-Fi (no power-save batching), for the remote's pointer and controller.
        lowLatency = runCatching { wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "couchphone-low-latency").apply { acquire() } }.getOrNull()
        startServer()
        Now.start()
        Dex.watch()
        TvScreen.watch()
        Thread { rootSetup() }.start()
    }

    /**
     * With root, once per start: let this app open screens from the background, keep Samsung's
     * battery saver from putting it to sleep, and start the input helper so the first key is quick.
     */
    private fun rootSetup() {
        if (!Root.openShell()) return
        val p = packageName
        // MediaWatch: to see (and pause) the browser's player only, never other apps' music
        Root.shOrNull("cmd notification allow_listener $p/$p.MediaWatch 2>&1")
        Root.shOrNull("appops set $p SYSTEM_ALERT_WINDOW allow; dumpsys deviceidle whitelist +$p; " +
            "cmd appops set $p RUN_ANY_IN_BACKGROUND allow; cmd appops set $p RUN_IN_BACKGROUND allow")
        RootInput.warmUp()
        // Bluetooth: the permissions to list, find and pair devices (granted with root, no pop-ups).
        Bt.grantPermissions()
        Now.push()
    }

    private fun startServer() {
        if (server != null) return
        try {
            server = Server(this, Store.port).also { it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            Log.i("couchphone", "server on port ${Store.port}")
            Announce.start(this)
        } catch (e: Exception) { Log.e("couchphone", "server failed to start", e) }
        // The same remote on https (port + 1), for the air mouse and speaking. Making the keys takes
        // a moment, so it starts on its own thread.
        Thread {
            synchronized(this) {
                if (secureServer != null) return@Thread
                try {
                    secureServer = Server(this, Tls.port, secure = true).also {
                        it.makeSecure(Tls.serverSocketFactory(), null)
                        it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    }
                    Log.i("couchphone", "secure server on port ${Tls.port}")
                } catch (e: Exception) { Log.e("couchphone", "secure server failed to start", e) }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { startServer(); return START_STICKY }

    override fun onDestroy() {
        server?.stop(); server = null
        secureServer?.stop(); secureServer = null
        runCatching { wifiLock?.release() }; runCatching { lowLatency?.release() }
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { App.startService(c) }
}
