package app.couchphone

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509ExtendedKeyManager

/**
 * The secure (https) address of the phone remote. Browsers only give a page the motion sensors
 * (air mouse) and the microphone (speak) on https, so the remote is also served on port 5401.
 *
 * There is no certificate authority on a home network, so the phone makes its own: one
 * "Couch Phone" root, made once and kept in the app, that a phone can trust (an iPhone profile, or
 * an Android CA certificate), and a server certificate for the phone's Wi-Fi address, made again
 * whenever that address changes. A phone that has not trusted the root still works after it taps
 * through the browser's warning once.
 *
 * Certificates are written here in DER by hand (Android has no public API to make them).
 * Original code for couch-phone.
 */
object Tls {
    const val PORT_OFFSET = 1   // https on the remote's port + 1 (5401)
    private const val CA_NAME = "Couch Phone home root"
    private val rnd = SecureRandom()

    private class Ca(val key: PrivateKey, val cert: X509Certificate)
    private var ca: Ca? = null
    private var leafKey: KeyPair? = null
    private var leaf: Pair<String, X509Certificate>? = null   // (ip, certificate)

    val port get() = Store.port + PORT_OFFSET

    // ---------------------------------------------------------------- the root, made once

    @Synchronized private fun root(): Ca {
        ca?.let { return it }
        val keyB64 = Store.str("tls_ca_key"); val certB64 = Store.str("tls_ca_cert")
        if (keyB64 != null && certB64 != null) runCatching {
            val c = Ca(rsaKey(keyB64), x509(Base64.decode(certB64, Base64.NO_WRAP)))
            if (c.cert.notAfter.time > System.currentTimeMillis() + 30L * 86_400_000) { ca = c; return c }
        }
        val kp = rsa()
        val now = System.currentTimeMillis()
        val name = name(CA_NAME)
        val ski = keyId(kp.public)
        val exts = listOf(
            ext(OID_BASIC, true, seq(bool(true))),
            ext(OID_KEY_USAGE, true, bits(0x06, 1)),          // keyCertSign, cRLSign
            ext(OID_SKI, false, octets(ski)),
        )
        val cert = sign(tbs(serial(), name, now - 86_400_000, now + 3650L * 86_400_000, name, kp.public, exts), kp.private)
        Store.putStr("tls_ca_key", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
        Store.putStr("tls_ca_cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
        return Ca(kp.private, cert).also { ca = it }
    }

    /** The root certificate (DER), for phones to trust. */
    fun caDer(): ByteArray = root().cert.encoded

    /** An iPhone/iPad configuration profile that installs the root. */
    fun mobileconfig(): String {
        val cert = Base64.encodeToString(caDer(), Base64.NO_WRAP)
        val id = "app.couchphone.root." + keyId(root().cert.publicKey).take(4).joinToString("") { "%02x".format(it) }
        fun uuid(seed: String) = UUID.nameUUIDFromBytes((id + seed).toByteArray()).toString().uppercase()
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>PayloadContent</key>
  <array>
    <dict>
      <key>PayloadCertificateFileName</key><string>couch-phone-root.cer</string>
      <key>PayloadContent</key><data>$cert</data>
      <key>PayloadDescription</key><string>Lets this phone open the Couch Phone remote securely.</string>
      <key>PayloadDisplayName</key><string>$CA_NAME</string>
      <key>PayloadIdentifier</key><string>$id.cert</string>
      <key>PayloadType</key><string>com.apple.security.root</string>
      <key>PayloadUUID</key><string>${uuid("cert")}</string>
      <key>PayloadVersion</key><integer>1</integer>
    </dict>
  </array>
  <key>PayloadDescription</key><string>Trust the Couch Phone TV box on your home Wi-Fi, so its remote can use motion and the microphone.</string>
  <key>PayloadDisplayName</key><string>Couch Phone remote</string>
  <key>PayloadIdentifier</key><string>$id</string>
  <key>PayloadRemovalDisallowed</key><false/>
  <key>PayloadType</key><string>Configuration</string>
  <key>PayloadUUID</key><string>${uuid("profile")}</string>
  <key>PayloadVersion</key><integer>1</integer>
</dict>
</plist>
"""
    }

    // ---------------------------------------------------------------- the server certificate

    @Synchronized private fun serverCert(): Pair<PrivateKey, Array<X509Certificate>> {
        val c = root()
        // The server key is made fresh each start; only the root (which phones trust) is kept.
        val kp = leafKey ?: rsa().also { leafKey = it }
        val ip = App.lanAddress() ?: "127.0.0.1"
        val now = System.currentTimeMillis()
        val have = leaf
        if (have == null || have.first != ip || have.second.notAfter.time < now + 86_400_000) {
            val san = mutableListOf(tagged(0x87, (java.net.InetAddress.getByName(ip) as Inet4Address).address), tagged(0x82, "localhost".toByteArray()))
            if (ip != "127.0.0.1") san += tagged(0x87, byteArrayOf(127, 0, 0, 1))
            val exts = listOf(
                ext(OID_BASIC, true, seq()),
                ext(OID_KEY_USAGE, true, bits(0xA0, 5)),        // digitalSignature, keyEncipherment
                ext(OID_EXT_KEY_USAGE, false, seq(oid(OID_SERVER_AUTH))),
                ext(OID_SAN, false, seq(*san.toTypedArray())),
                ext(OID_AKI, false, seq(tagged(0x80, keyId(c.cert.publicKey)))),
                ext(OID_SKI, false, octets(keyId(kp.public))),
            )
            // 397 days at most: what iPhones accept for a server certificate.
            val cert = sign(tbs(serial(), issuer = name(CA_NAME), from = now - 86_400_000, to = now + 396L * 86_400_000, subject = name("Couch Phone ($ip)"), key = kp.public, exts = exts), c.key)
            leaf = ip to cert
        }
        return kp.private to arrayOf(leaf!!.second, c.cert)
    }

    /** A key manager that always hands out a certificate for the phone's current address. */
    private object Keys : X509ExtendedKeyManager() {
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = "couch"
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = "couch"
        override fun getCertificateChain(alias: String?) = serverCert().second
        override fun getPrivateKey(alias: String?) = serverCert().first
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("couch")
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    fun serverSocketFactory(): SSLServerSocketFactory {
        serverCert()   // make the certificates now, not during the first visitor's handshake
        return SSLContext.getInstance("TLS").apply { init(arrayOf(Keys), null, rnd) }.serverSocketFactory
    }

    // ---------------------------------------------------------------- keys

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, rnd) }.generateKeyPair()

    private fun rsaKey(b64: String): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(b64, Base64.NO_WRAP)))

    private fun x509(der: ByteArray) = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    private fun keyId(k: PublicKey): ByteArray = MessageDigest.getInstance("SHA-1").digest(k.encoded)

    private fun serial(): ByteArray = BigInteger(64, rnd).add(BigInteger.ONE).toByteArray()

    // ---------------------------------------------------------------- DER

    private const val OID_CN = "2.5.4.3"
    private const val OID_O = "2.5.4.10"
    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
    private const val OID_BASIC = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val OID_SAN = "2.5.29.17"
    private const val OID_SKI = "2.5.29.14"
    private const val OID_AKI = "2.5.29.35"

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = body.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun cat(vararg parts: ByteArray) = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
    private fun seq(vararg parts: ByteArray) = tlv(0x30, cat(*parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, cat(*parts))
    private fun int(v: ByteArray) = tlv(0x02, v)
    private fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xff.toByte() else 0))
    private fun octets(v: ByteArray) = tlv(0x04, v)
    private fun utf8(s: String) = tlv(0x0c, s.toByteArray(Charsets.UTF_8))
    private fun tagged(tag: Int, v: ByteArray) = tlv(tag, v)
    private fun explicit(n: Int, v: ByteArray) = tlv(0xa0 + n, v)
    private fun bits(value: Int, unused: Int) = tlv(0x03, byteArrayOf(unused.toByte(), value.toByte()))
    private fun bitString(v: ByteArray) = tlv(0x03, byteArrayOf(0) + v)
    private fun nul() = byteArrayOf(0x05, 0x00)

    private fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) {
            val stack = ArrayList<Int>()
            var x = v
            stack.add((x and 0x7f).toInt()); x = x shr 7
            while (x > 0) { stack.add(((x and 0x7f) or 0x80).toInt()); x = x shr 7 }
            stack.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun time(ms: Long): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
        return if (year < 2050) tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(Date(ms)).toByteArray())
        else tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(Date(ms)).toByteArray())
    }

    private fun name(cn: String) = seq(
        set(seq(oid(OID_O), utf8("Couch Phone"))),
        set(seq(oid(OID_CN), utf8(cn))),
    )

    private fun ext(id: String, critical: Boolean, value: ByteArray) =
        if (critical) seq(oid(id), bool(true), octets(value)) else seq(oid(id), octets(value))

    private fun algo() = seq(oid(OID_SHA256_RSA), nul())

    private fun tbs(serial: ByteArray, issuer: ByteArray, from: Long, to: Long, subject: ByteArray, key: PublicKey, exts: List<ByteArray>) = seq(
        explicit(0, int(byteArrayOf(2))),   // version 3
        int(serial),
        algo(),
        issuer,
        seq(time(from), time(to)),
        subject,
        key.encoded,                        // SubjectPublicKeyInfo, already DER
        explicit(3, seq(*exts.toTypedArray())),
    )

    private fun sign(tbs: ByteArray, key: PrivateKey): X509Certificate {
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(tbs) }.sign()
        return x509(seq(tbs, algo(), bitString(sig)))
    }
}
