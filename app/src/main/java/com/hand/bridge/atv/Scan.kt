package com.hand.bridge.atv

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Finds TVs without relying on mDNS: look for hosts on the local network that accept connections on the pairing port. */
object Scan {
    /** Usable IPv4 addresses in the subnet of [ip]/[prefix] (never wider than /22), excluding [ip] itself. */
    fun hosts(ip: String, prefix: Int): List<String> {
        val parts = ip.split('.').map { it.toInt() }
        require(parts.size == 4 && parts.all { it in 0..255 }) { "not an IPv4 address" }
        val p = prefix.coerceIn(22, 30)
        val addr = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val mask = -1 shl (32 - p)
        val net = addr and mask
        val count = 1 shl (32 - p)
        return (1 until count - 1).map { net + it }.filter { it != addr }.map {
            "${(it ushr 24) and 255}.${(it ushr 16) and 255}.${(it ushr 8) and 255}.${it and 255}"
        }
    }

    fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) { false }

    /** Blocks until every host has been tried (or [stopped] returns true); calls [onOpen] for each host that answers. */
    fun openPort(
        hosts: List<String>, port: Int, timeoutMs: Int = 600, threads: Int = 48,
        stopped: () -> Boolean = { false }, onOpen: (String) -> Unit
    ) {
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "scan").apply { isDaemon = true } }
        try {
            pool.invokeAll(hosts.map { h -> Callable { if (!stopped() && isOpen(h, port, timeoutMs)) onOpen(h) } })
        } finally {
            pool.shutdownNow()
        }
    }
}
