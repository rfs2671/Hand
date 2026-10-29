package com.hand.bridge.atv

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.SSLEngine
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.TimeZone

/** Our client certificate. The TV remembers it after pairing, so it must stay the same afterwards. */
class Identity(val cert: X509Certificate, val key: PrivateKey) {

    fun certBase64(): String = Base64.getEncoder().encodeToString(cert.encoded)
    fun keyBase64(): String = Base64.getEncoder().encodeToString(key.encoded)

    fun sslContext(trust: TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(FixedKeyManager(key, cert)), arrayOf(trust), SecureRandom()) }

    companion object {
        fun generate(commonName: String = "atvremote"): Identity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val tbs = Der.seq(
                Der.explicit(0, Der.int(BigInteger.valueOf(2))),                       // v3
                Der.int(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)),     // serial
                SIG_ALG,
                Der.name(commonName),                                                   // issuer
                Der.seq(Der.utcTime(Date(now - 60_000)), Der.utcTime(Date(now + 10L * 365 * 24 * 3600 * 1000))),
                Der.name(commonName),                                                   // subject
                pair.public.encoded,                                                    // SubjectPublicKeyInfo
                Der.explicit(3, Der.seq(Der.seq(                                        // basicConstraints: CA, pathlen 0
                    Der.oid("2.5.29.19"),
                    Der.tlv(0x04, Der.seq(Der.tlv(0x01, byteArrayOf(0xFF.toByte())), Der.int(BigInteger.ZERO)))
                )))
            )
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private); update(tbs); sign()
            }
            val der = Der.seq(tbs, SIG_ALG, Der.tlv(0x03, byteArrayOf(0) + sig))
            return Identity(parseCert(der), pair.private)
        }

        fun fromBase64(cert: String, key: String): Identity {
            val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(key)))
            return Identity(parseCert(Base64.getDecoder().decode(cert)), k)
        }

        fun parseCert(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        private val SIG_ALG = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.tlv(0x05, ByteArray(0)))
    }
}

/** Just enough ASN.1 DER to write a self-signed certificate. */
internal object Der {
    fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, content: ByteArray) = byteArrayOf(tag.toByte()) + len(content.size) + content
    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun explicit(n: Int, content: ByteArray) = tlv(0xA0 or n, content)

    fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = java.io.ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) {
            val chunks = ArrayList<Int>()
            var x = v
            do { chunks.add((x and 0x7F).toInt()); x = x shr 7 } while (x > 0)
            for (i in chunks.indices.reversed()) out.write(chunks[i] or if (i > 0) 0x80 else 0)
        }
        return tlv(0x06, out.toByteArray())
    }

    fun name(cn: String) = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, cn.toByteArray(Charsets.UTF_8)))))

    fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, f.format(d).toByteArray())
    }
}

/**
 * Android TVs use self-signed certificates, so the usual chain check can't apply. Instead we pin:
 * the first time we pair we remember the TV's certificate fingerprint, and afterwards refuse any
 * other certificate (a different device, or someone in the middle).
 */
class PinTrust(private val pinnedSha256: String?) : X509ExtendedTrustManager() {
    @Volatile var seen: X509Certificate? = null
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        seen = leaf
        if (pinnedSha256 != null && fingerprint(leaf) != pinnedSha256) {
            throw CertificateException("server certificate changed")
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun fingerprint(c: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Always offers our one certificate. The stock key manager only does so when the server's list of
 * accepted issuers contains ours, and TVs don't always send a list that does.
 */
internal class FixedKeyManager(private val key: PrivateKey, private val cert: X509Certificate) : X509ExtendedKeyManager() {
    private val alias = "client"
    override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(alias)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) = alias
    override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?) = arrayOf(cert)
    override fun getPrivateKey(alias: String?) = key
}
