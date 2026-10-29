package com.hand.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Finds Google TV / Android TV devices on the Wi-Fi (they advertise _androidtvremote2._tcp). */
class TvDiscovery(ctx: Context, private val onFound: (Found) -> Unit) {
    class Found(val svc: String, val host: String, val port: Int)

    private val nsd = ctx.applicationContext.svc<NsdManager>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val seen = HashSet<String>()
    private var resolving = false
    @Volatile private var stopped = false
    private var started = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(queue) { if (seen.add(info.serviceName)) queue.addLast(info) }
            resolveNext()
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { started = false }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }

    fun start() {
        started = true
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun stop() {
        stopped = true
        if (started) runCatching { nsd.stopServiceDiscovery(discovery) }
        started = false
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
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) = done()
                override fun onServiceResolved(r: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= 34) {
                        (r.hostAddresses.firstOrNull { it is Inet4Address } ?: r.hostAddresses.firstOrNull())?.hostAddress
                    } else r.host?.hostAddress
                    if (host != null && !stopped) onFound(Found(r.serviceName, host.substringBefore('%'), r.port))
                    done()
                }
                private fun done() { synchronized(queue) { resolving = false }; resolveNext() }
            })
        } catch (e: Exception) {
            synchronized(queue) { resolving = false }
        }
    }

    companion object {
        const val SERVICE = "_androidtvremote2._tcp"

        /** Looks for a known TV by its advertised name (its IP may have changed). Blocks up to [timeoutMs]. */
        fun findHost(ctx: Context, tv: Tv, timeoutMs: Long = 4_000): String? {
            var host: String? = null
            val latch = CountDownLatch(1)
            val d = TvDiscovery(ctx) { if (it.svc == tv.svc) { host = it.host; latch.countDown() } }
            d.start()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            d.stop()
            return host
        }
    }
}
