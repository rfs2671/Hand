package com.hand.bridge.atv

import java.net.InetAddress
import java.security.cert.X509Certificate

/** What the TV's own certificate tells us about it: its name and (usually) its MAC address. */
object TvInfo {
    class Info(val name: String?, val mac: String?)

    private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /** Subjects look like CN=atvremote/darcy/darcy/SHIELD Android TV/XX:XX:XX:XX:XX:XX */
    fun parse(cert: X509Certificate): Info {
        val dn = cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253)
        val cn = Regex("(?:^|,)CN=([^,]+)").find(dn)?.groupValues?.get(1) ?: return Info(null, null)
        val parts = cn.split('/')
        val mac = parts.lastOrNull()?.takeIf { MAC.matches(it) }?.uppercase()
        val name = if (parts.size > 1) parts[parts.size - 2].takeIf { it.isNotBlank() } else null
        return Info(name, mac)
    }
}

/** Wake-on-LAN "magic packet": 6 x 0xFF then the MAC 16 times, sent as UDP broadcast. */
object WakeOnLan {
    fun packet(mac: String): ByteArray {
        val m = mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        require(m.size == 6) { "bad MAC address" }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { m[it % 6] }
    }

    /** Sends to each broadcast address on UDP ports 9 and 7. Returns how many sends succeeded. */
    fun send(mac: String, broadcasts: List<InetAddress>): Int {
        val data = packet(mac)
        var ok = 0
        java.net.DatagramSocket().use { s ->
            s.broadcast = true
            for (addr in broadcasts) for (port in intArrayOf(9, 7)) {
                try { s.send(java.net.DatagramPacket(data, data.size, addr, port)); ok++ } catch (_: java.io.IOException) {}
            }
        }
        return ok
    }
}
