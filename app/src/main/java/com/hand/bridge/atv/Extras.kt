package com.hand.bridge.atv

import java.net.InetSocketAddress
import java.net.Socket

/** Keys that arrived while the TV link was being re-established. They're sent once it's back, unless they've gone stale. */
class PendingKeys(private val max: Int = 4, private val ttlMs: Long = 3_000) {
    private class K(val code: Int, val dir: Int, val at: Long)
    private val q = ArrayDeque<K>()

    @Synchronized
    fun add(code: Int, dir: Int = Remote.DIR_SHORT, now: Long = System.currentTimeMillis()) {
        prune(now)
        if (q.size >= max) q.removeFirst()
        q.addLast(K(code, dir, now))
    }

    /** Returns the keys still fresh, oldest first, and empties the queue. */
    @Synchronized
    fun drain(now: Long = System.currentTimeMillis()): List<Pair<Int, Int>> {
        prune(now)
        return q.map { it.code to it.dir }.also { q.clear() }
    }

    @Synchronized fun clear() = q.clear()

    private fun prune(now: Long) {
        while (q.isNotEmpty() && now - q.first().at > ttlMs) q.removeFirst()
    }
}

/** Measures the network round trip to the TV by timing TCP connections (what the phone's Wi-Fi really does). */
object Latency {
    class Stats(val sent: Int, val ok: Int, val minMs: Double, val avgMs: Double, val maxMs: Double)

    fun tcpRtt(host: String, port: Int, count: Int = 12, timeoutMs: Int = 1_000, gapMs: Long = 60): Stats {
        val times = ArrayList<Double>()
        repeat(count) {
            val t0 = System.nanoTime()
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                times += (System.nanoTime() - t0) / 1e6
            } catch (e: Exception) { /* a lost probe counts against "ok" */ }
            if (gapMs > 0) Thread.sleep(gapMs)
        }
        return if (times.isEmpty()) Stats(count, 0, 0.0, 0.0, 0.0)
        else Stats(count, times.size, times.min(), times.average(), times.max())
    }
}
