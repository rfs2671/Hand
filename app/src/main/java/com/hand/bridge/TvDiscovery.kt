package com.hand.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address

/**
 * mDNS search. Google TV / Android TV advertise _androidtvremote2._tcp; most also advertise
 * _googlecast._tcp (what the Google Home app lists), which we use as a second hint.
 */
class TvDiscovery(
    ctx: Context,
    private val types: List<String> = listOf(REMOTE, CAST),
    private val onFound: (Found) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    class Found(val svc: String, val host: String, val port: Int, val type: String, val friendly: String?)

    private val nsd = ctx.applicationContext.svc<NsdManager>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val seen = HashSet<String>()
    private val listeners = HashMap<String, NsdManager.DiscoveryListener>()
    private var resolving = false
    @Volatile private var stopped = false

    private fun listenerFor(type: String) = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            Trace.add("mDNS $type: found '${info.serviceName}'")
            synchronized(queue) { if (seen.add(type + "|" + info.serviceName)) queue.addLast(info) }
            resolveNext()
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(t: String) { Trace.add("mDNS $type: searching") }
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, code: Int) {
            Trace.add("mDNS $type: start failed ($code)")
            onError("Wi-Fi search ($type) failed with code $code")
        }
        override fun onStopDiscoveryFailed(t: String, code: Int) {}
    }

    fun start() {
        for (t in types) {
            val l = listenerFor(t)
            listeners[t] = l
            try { nsd.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, l) }
            catch (e: Exception) { Trace.add("mDNS $t: ${e.javaClass.simpleName} ${e.message}"); onError("Wi-Fi search failed: ${e.message}") }
        }
    }

    fun stop() {
        stopped = true
        listeners.values.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
    }

    // NsdManager can only resolve one service at a time.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val info = synchronized(queue) {
            if (resolving || stopped) return
            queue.removeFirstOrNull()?.also { resolving = true } ?: return
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                    Trace.add("mDNS: resolve '${i.serviceName}' failed ($code)")
                    done()
                }
                override fun onServiceResolved(r: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= 34) {
                        (r.hostAddresses.firstOrNull { it is Inet4Address } ?: r.hostAddresses.firstOrNull())?.hostAddress
                    } else r.host?.hostAddress
                    val type = if (r.serviceType?.contains("googlecast") == true) CAST else REMOTE
                    val friendly = r.attributes?.get("fn")?.toString(Charsets.UTF_8)
                    Trace.add("mDNS: '${r.serviceName}' is at $host:${r.port} ($type${friendly?.let { ", $it" } ?: ""})")
                    if (host != null && !stopped) onFound(Found(r.serviceName, host.substringBefore('%'), r.port, type, friendly))
                    done()
                }
                private fun done() { synchronized(queue) { resolving = false }; resolveNext() }
            })
        } catch (e: Exception) {
            Trace.add("mDNS: resolve error ${e.message}")
            synchronized(queue) { resolving = false }
        }
    }

    companion object {
        const val REMOTE = "_androidtvremote2._tcp"
        const val CAST = "_googlecast._tcp"
    }
}
