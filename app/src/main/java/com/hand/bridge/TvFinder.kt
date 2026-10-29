package com.hand.bridge

import android.content.Context
import android.net.ConnectivityManager
import com.hand.bridge.atv.Scan
import com.hand.bridge.atv.TvProbe
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds TVs on the Wi-Fi by every means we have, in parallel: mDNS (remote service and Google Cast)
 * and a direct scan of the local network for the TV pairing port. Each candidate is checked by
 * reading its certificate, so only real Android TVs are reported.
 */
class TvFinder(
    private val ctx: Context,
    private val onFound: (Found) -> Unit,
    private val onStatus: (String) -> Unit
) {
    class Found(val name: String, val host: String, val mac: String?, val svc: String)

    private val tried = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "tv-finder").apply { isDaemon = true } }
    @Volatile private var stopped = false
    @Volatile private var count = 0
    private var nsd: TvDiscovery? = null

    fun start() {
        val net = localSubnet(ctx)
        if (net == null) {
            Trace.add("finder: not on Wi-Fi")
            onStatus("This phone isn't connected to Wi-Fi.")
            return
        }
        Trace.add("finder: start on ${net.first}/${net.second}")
        nsd = TvDiscovery(
            ctx,
            onFound = { h -> consider(h.host, h.friendly ?: h.svc, advertisesRemote = h.type == TvDiscovery.REMOTE) },
            onError = { onStatus(it) }
        ).also { it.start() }
        pool.execute {
            val hosts = Scan.hosts(net.first, net.second)
            Scan.openPort(hosts, PAIR_PORT, 600, 48, { stopped }) { h ->
                Trace.add("finder: $h accepts connections on $PAIR_PORT")
                consider(h, h, advertisesRemote = false)
            }
            Trace.add("finder: scan of ${hosts.size} addresses done")
            if (!stopped && count == 0) {
                onStatus("No TV found on ${net.first.substringBeforeLast('.')}.x. Is the TV on and on this Wi-Fi (not a guest network)? You can also enter its IP address.")
            }
        }
    }

    fun stop() {
        stopped = true
        nsd?.stop()
        pool.shutdownNow()
    }

    private fun consider(host: String, hint: String, advertisesRemote: Boolean) {
        if (stopped || !tried.add(host)) return
        try {
            pool.execute {
                val r = TvProbe.identify(Prefs.identity(ctx), host)
                if (stopped) return@execute
                when {
                    r != null -> emit(Found(r.info.name ?: hint, host, r.info.mac, hint))
                    advertisesRemote -> emit(Found(hint, host, null, hint)) // it says it is a TV remote even if the probe failed
                    else -> Trace.add("finder: $host is not a TV (no pairing certificate)")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun emit(f: Found) {
        count++
        Trace.add("finder: TV '${f.name}' at ${f.host} (${f.mac ?: "no MAC"})")
        onFound(f)
    }

    companion object {
        const val PAIR_PORT = 6467

        fun localSubnet(ctx: Context): Pair<String, Int>? {
            val cm = ctx.svc<ConnectivityManager>()
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
            return (la.address.hostAddress ?: return null) to la.prefixLength
        }

        /** The TV's IP may change (DHCP): look for it again by name, then by scanning for its MAC. */
        fun findHost(ctx: Context, tv: Tv, timeoutMs: Long = 8_000): String? {
            var host: String? = null
            val latch = CountDownLatch(1)
            val finder = TvFinder(ctx, { f ->
                if (f.mac != null && f.mac == tv.mac || f.name == tv.name || f.svc == tv.svc) { host = f.host; latch.countDown() }
            }, {})
            finder.start()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            finder.stop()
            return host
        }
    }
}
