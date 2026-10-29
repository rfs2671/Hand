package com.hand.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import com.hand.bridge.atv.AtvRemote
import com.hand.bridge.atv.Keys
import com.hand.bridge.atv.NeedsPairing
import com.hand.bridge.atv.Remote
import com.hand.bridge.atv.WakeOnLan
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class TvState { IDLE, CONNECTING, CONNECTED, NEEDS_PAIRING, UNREACHABLE }

/**
 * Keeps one live Wi-Fi connection to the selected TV and sends it key presses, the same way the
 * Google TV phone app does. Reconnects by itself (and re-finds the TV if its IP address changes).
 */
object TvManager {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-io").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var remote: AtvRemote? = null
    @Volatile private var wanted = true      // false after the user taps Disconnect
    @Volatile private var lastAuto = 0L

    @Volatile var tv: Tv? = null
        private set
    @Volatile var state = TvState.IDLE
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var powered = true
        private set

    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private fun changed() = main.post { listeners.forEach { it() } }
    private fun set(s: TvState) { state = s; changed() }

    val connected get() = remote?.ready == true
    fun connectedTv(): Tv? = if (connected) tv else null

    /** Connects (with retries) in the background. [afterWake] waits longer: a TV takes a while to boot. */
    fun connect(ctx: Context, target: Tv, userInitiated: Boolean = true, afterWake: Boolean = false) {
        val app = ctx.applicationContext
        if (userInitiated) {
            wanted = true
            // Gestures only reach the TV while the bridge service runs (it owns the Air Gestures receiver).
            if (!Prefs.enabled(app)) { Trace.add("tv: starting the bridge"); BridgeService.start(app) }
        }
        if (!wanted || !busy.compareAndSet(false, true)) return
        io.execute {
            try {
                doConnect(app, target, afterWake)
            } catch (e: Throwable) {
                Trace.add("tv: connect crashed: ${e.javaClass.simpleName} ${e.message}")
                error = e.message ?: e.javaClass.simpleName
                set(TvState.UNREACHABLE)
            } finally {
                busy.set(false)
            }
        }
    }

    fun disconnect() {
        wanted = false
        io.execute { runCatching { dropConnection() }; set(TvState.IDLE) }
    }

    private fun dropConnection() {
        val r = remote
        remote = null
        runCatching { r?.close() }
    }

    private fun doConnect(ctx: Context, target: Tv, afterWake: Boolean) {
        if (connected && tv?.id == target.id) return
        dropConnection()
        tv = target
        error = null
        Trace.add("tv: connecting to ${target.name} at ${target.host}")
        set(TvState.CONNECTING)
        var host = target.host
        val delays = if (afterWake) longArrayOf(0, 5_000, 10_000, 15_000, 20_000, 30_000) else longArrayOf(0, 3_000, 8_000, 20_000)
        for ((i, wait) in delays.withIndex()) {
            if (!wanted) { set(TvState.IDLE); return }
            if (wait > 0) Thread.sleep(wait)
            try {
                val r = AtvRemote(Prefs.identity(ctx), host, target.pin, listenerFor(ctx, target))
                r.connect(8_000)
                remote = r
                val now = if (host != target.host) target.copy(host = host).also { Prefs.upsertTv(ctx, it) } else target
                tv = now
                Prefs.setLastTv(ctx, now.id)
                powered = true
                Trace.add("tv: connected to ${now.name}")
                set(TvState.CONNECTED)
                return
            } catch (e: NeedsPairing) {
                Trace.add("tv: needs pairing")
                error = "This phone needs to be paired with the TV again."
                set(TvState.NEEDS_PAIRING)
                return
            } catch (e: IOException) {
                error = e.message
                Trace.add("tv: connect attempt ${i + 1} failed: ${e.javaClass.simpleName} ${e.message}")
                // The TV may have a new IP address after a router restart: find it by name.
                if (i >= 1) TvFinder.findHost(ctx, target)?.let { host = it }
            }
        }
        Trace.add("tv: unreachable")
        set(TvState.UNREACHABLE)
    }

    private fun listenerFor(ctx: Context, t: Tv) = object : AtvRemote.Listener {
        override fun onPower(on: Boolean) { powered = on; changed() }
        override fun onClosed(reason: String) {
            remote = null
            Trace.add("tv: connection closed ($reason)")
            error = reason
            set(TvState.IDLE)
            // Standby drops the link too; try again shortly while the bridge is running.
            if (wanted && t.auto && Prefs.enabled(ctx)) main.postDelayed({ connect(ctx, t, userInitiated = false) }, 2_000)
        }
    }

    /** Connect to the preferred auto-connect TV if nothing is connected. Called on service start, screen on, Wi-Fi up. */
    fun reconnectAuto(ctx: Context) {
        if (connected || busy.get() || !wanted) return
        val now = System.currentTimeMillis()
        if (now - lastAuto < 15_000) return
        val list = Prefs.tvs(ctx).filter { it.auto }
        val pick = list.firstOrNull { it.id == Prefs.lastTv(ctx) } ?: list.firstOrNull() ?: return
        lastAuto = now
        connect(ctx, pick, userInitiated = false)
    }

    /** Returns false if not connected or the write failed. */
    fun key(code: Int, direction: Int = Remote.DIR_SHORT): Boolean = remote?.key(code, direction) ?: false

    /**
     * Power button. Connected: the TV's power key toggles it. Not connected (standby): try to wake it
     * over the network (Wake-on-LAN, which only works if the TV supports it and its MAC is known),
     * then connect once it's up.
     */
    fun power(ctx: Context): Boolean {
        if (connected) return key(Keys.POWER)
        val t = tv ?: Prefs.tvs(ctx).firstOrNull { it.id == Prefs.lastTv(ctx) } ?: Prefs.tvs(ctx).firstOrNull() ?: return false
        wake(ctx, t)
        return true
    }

    fun wake(ctx: Context, t: Tv) {
        val app = ctx.applicationContext
        io.execute {
            t.mac?.let { runCatching { WakeOnLan.send(it, broadcasts(app)) } }
            wanted = true
            main.post { connect(app, t, userInitiated = true, afterWake = true) }
        }
    }

    private fun broadcasts(ctx: Context): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        out += InetAddress.getByName("255.255.255.255")
        val cm = ctx.svc<ConnectivityManager>()
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.forEach { la ->
            val a = la.address
            if (a is Inet4Address && la.prefixLength in 8..30) {
                val b = a.address
                val mask = -1 shl (32 - la.prefixLength)
                val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
                val bc = ip or mask.inv()
                out += InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()))
            }
        }
        return out
    }
}
