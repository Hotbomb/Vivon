package app.couchphone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Tells phones on the same Wi-Fi that a Couch Phone TV is here (DNS-SD, "_couchphone._tcp"), so
 * the Couch Remote app finds it by itself, with no address to type.
 */
object Announce {
    private const val TAG = "couchphone.announce"
    const val TYPE = "_couchphone._tcp"
    private var nsd: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized fun start(c: Context) {
        if (listener != null) return
        val m = c.applicationContext.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Couch Phone ${Store.tvName()}"
            serviceType = TYPE
            port = Store.port
            setAttribute("secure", Tls.port.toString())
            setAttribute("v", BuildConfig.VERSION_NAME)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "announced as ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "announce failed: $code"); synchronized(this@Announce) { listener = null } }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsd = m; listener = l }
            .onFailure { Log.w(TAG, "announce", it) }
    }
}
