package com.hand.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import com.hand.bridge.atv.PendingKeys
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
    private fun set(s: TvState) { state = s; updateWifiLock(); changed() }

    @Volatile private var appCtx: Context? = null
    @Volatile private var lastUp = 0L
    private val pending = PendingKeys()
    private var wifiLock: WifiManager.WifiLock? = null

    // Android TV Remote v2 supports proper key-down / key-up semantics. Keep the last
    // slider direction held while events are coming in, then release if they stop.
    // Air Gestures batches sliders (currently 250 ms): the deadline must exceed that
    // interval, but should be short enough to stop scrolling promptly.
    private var heldKey: Int? = null
    private var endHold: Runnable? = null
    private var lastSliderCode: Int? = null
    private var lastSliderAt = 0L
    private const val HOLD_IDLE_MS = 340L

    @Synchronized private fun releaseHold() {
        endHold?.let { main.removeCallbacks(it) }
        endHold = null
        heldKey?.let { remote?.key(it, Remote.DIR_END_LONG) }
        heldKey = null
    }

    /**
     * Continuous D-pad navigation while a dial/slider moves.
     * Unlike a sequence of SHORT keys, this lets the TV apply its own key-repeat behavior.
     * It does NOT create touchpad coordinates or multi-touch on Android TV.
     */
    @Synchronized fun continuousKey(code: Int, steps: Int = 1, releaseAfterMs: Long = HOLD_IDLE_MS): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (heldKey != code) {
            releaseHold()
            // A quick flick should move immediately by its actual number of steps.
            // If another batch arrives shortly after, treat it as a hold instead.
            val continuing = lastSliderCode == code && now - lastSliderAt in 1L..440L
            lastSliderCode = code
            lastSliderAt = now
            if (!continuing) {
                val r = remote
                return if (r?.keys(code, steps.coerceIn(1, 6), 12L) == true) true
                       else hold(code, Remote.DIR_SHORT)
            }
            if (remote?.key(code, Remote.DIR_START_LONG) != true) {
                return hold(code, Remote.DIR_SHORT)
            }
            heldKey = code
        }
        lastSliderAt = now
        endHold?.let { main.removeCallbacks(it) }
        val toEnd = Runnable { releaseHold() }
        endHold = toEnd
        main.postDelayed(toEnd, releaseAfterMs.coerceIn(60L, 1000L))
        return true
    }


    /**
     * Wi-Fi cursor mode for the projector-side Remouse accessibility service.
     *
     * Remouse needs a real DPAD ACTION_DOWN followed by ACTION_UP so it can
     * continuously accelerate the cursor while held. Start on the FIRST motion
     * sample (do not wait for a second batch or send repeated short key taps).
     * New motion of the same direction refreshes the release deadline. Direction
     * changes end the previous key before starting the next one.
     *
     * The Android TV v2 connection only knows DPAD keys; the projector itself
     * must run an enabled key-intercepting cursor service to move a pointer.
     */
    @Synchronized fun cursorDirection(code: Int, releaseAfterMs: Long = 180L): Boolean {
        if (code != Keys.UP && code != Keys.DOWN &&
            code != Keys.LEFT && code != Keys.RIGHT) return false
        if (heldKey != code) {
            releaseHold()
            // No queued stale shorts: one key down, repeated updates are
            // deliberately coalesced until key up or direction change.
            if (remote?.key(code, Remote.DIR_START_LONG) != true) return false
            heldKey = code
        }
        lastSliderCode = null
        lastSliderAt = 0L
        endHold?.let { main.removeCallbacks(it) }
        val release = Runnable { releaseHold() }
        endHold = release
        main.postDelayed(release, releaseAfterMs.coerceIn(70L, 1000L))
        return true
    }

    /**
     * Physical on-screen directional button hold. The touch UI sends a short key
     * immediately, then begins a long key only after the finger stays down.
     * Always release explicitly on touch-up/cancel or when the view is removed.
     */
    @Synchronized fun beginRemoteHold(code: Int): Boolean {
        releaseHold()
        val ok = remote?.key(code, Remote.DIR_START_LONG) == true
        if (ok) heldKey = code
        return ok
    }

    @Synchronized fun endRemoteHold(code: Int) {
        if (heldKey == code) releaseHold()
    }

    /** Called on touch-pad lift to ensure the TV receives KEY_UP. */
    @Synchronized fun endPointerGesture() {
        releaseHold()
        lastSliderCode = null
        lastSliderAt = 0L
    }

    /** Latest in-app delay (key queued -> written to the socket), for the speed check. */
    val lastSendDelayMs: Double get() = remote?.lastSendDelayMs ?: -1.0

    /**
     * True when gestures belong to the TV: it is connected, or it was a moment ago and we are
     * reconnecting. During that gap keys are held briefly instead of falling through to phone actions.
     */
    fun isTarget(): Boolean {
        if (connected) return true
        if (tv == null || !wanted) return false
        if (state == TvState.NEEDS_PAIRING || state == TvState.UNREACHABLE) return false
        return System.currentTimeMillis() - lastUp < 120_000
    }

    /** Wi-Fi power saving adds 100-300 ms of jitter; hold the radio awake while a TV is connected. */
    fun refreshLocks(ctx: Context) { appCtx = ctx.applicationContext; updateWifiLock() }

    private fun updateWifiLock() {
        val ctx = appCtx ?: return
        val want = connected && Prefs.lowLatency(ctx)
        try {
            if (want && wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = ctx.svc<WifiManager>().createWifiLock(mode, "HandBridge").apply { setReferenceCounted(false); acquire() }
                Trace.add("wifi: low-latency lock on")
            } else if (!want && wifiLock != null) {
                wifiLock?.release()
                wifiLock = null
                Trace.add("wifi: low-latency lock off")
            }
        } catch (e: Exception) {
            Trace.add("wifi: lock failed ${e.javaClass.simpleName} ${e.message}")
        }
    }

    val connected get() = remote?.ready == true
    fun connectedTv(): Tv? = if (connected) tv else null

    /** Connects (with retries) in the background. [afterWake] waits longer: a TV takes a while to boot. */
    fun connect(ctx: Context, target: Tv, userInitiated: Boolean = true, afterWake: Boolean = false) {
        val app = ctx.applicationContext
        appCtx = app
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
        releaseHold()
        wanted = false
        io.execute { runCatching { dropConnection() }; set(TvState.IDLE) }
    }

    private fun dropConnection() {
        releaseHold()
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
                lastUp = System.currentTimeMillis()
                set(TvState.CONNECTED)
                // Gestures made while the link was being re-established.
                val held = pending.drain()
                if (held.isNotEmpty()) Trace.add("tv: sending ${held.size} held key(s)")
                held.forEach { (code, dir) -> r.key(code, dir) }
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
            lastUp = System.currentTimeMillis()
            Trace.add("tv: connection closed ($reason)")
            error = reason
            set(TvState.IDLE)
            // Reconnect straight away while the bridge is running (a standby TV makes this fail and back off).
            if (wanted && t.auto && Prefs.enabled(ctx)) main.postDelayed({ connect(ctx, t, userInitiated = false) }, 300)
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

    /**
     * Queues a key press. If the link just dropped, the key is held for a few seconds, a reconnect starts
     * at once, and the key is sent when the TV is back (otherwise the gesture would simply be lost).
     */
    fun key(code: Int, direction: Int = Remote.DIR_SHORT): Boolean {
        if (direction == Remote.DIR_SHORT) releaseHold()
        if (remote?.key(code, direction) == true) return true
        return hold(code, direction)
    }

    /** [count] presses spaced [intervalMs] apart so each step shows on the TV (a slide). */
    fun keys(code: Int, count: Int, intervalMs: Long): Boolean {
        releaseHold()
        if (remote?.keys(code, count, intervalMs) == true) return true
        repeat(minOf(count, 2)) { hold(code, Remote.DIR_SHORT) } // a couple catch the TV up; more would overshoot
        return isTarget()
    }

    private fun hold(code: Int, direction: Int): Boolean {
        if (!isTarget()) return false
        pending.add(code, direction)
        val ctx = appCtx
        val t = tv
        if (ctx != null && t != null) connect(ctx, t, userInitiated = false)
        return true
    }

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
