package com.hand.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlin.math.abs

/**
 * Integration with the Air Gestures app (github.com/896kb/air-gestures-android), which owns the
 * Neural Band connection. It broadcasts dev.kb896.airgestures.GESTURE to every app with extras
 * "gesture" (e.g. index_tap, swipe_left, dial) and, for dials/sliders, a signed Int "steps".
 * Only gestures set to "Send to automation" in Air Gestures are broadcast.
 */
object AirGestures {
    const val PKG = "dev.kb896.airgestures"
    private val PKGS = setOf(PKG, "$PKG.dev")
    const val ACTION_GESTURE = "$PKG.GESTURE"
    const val ACTION_SET_PROFILE = "$PKG.SET_PROFILE"
    const val ACTION_STATUS = "$PKG.STATUS"

    /** Band status from Air Gestures STATUS broadcasts; null until the first one arrives. */
    @Volatile var bandConnected: Boolean? = null
    @Volatile var battery: Int = -1

    /** What has actually reached us from Air Gestures (for the Home screen and the report). */
    @Volatile var received = 0
        private set
    @Volatile var lastAt = 0L
        private set
    @Volatile var lastGesture = ""
        private set
    @Volatile var lastRejected: String? = null
        private set

    /** One message from Air Gestures, kept for the live monitor on Home. [note] says why it was ignored, if it was. */
    class Event(val at: Long, val gesture: String, val steps: Int, val note: String?)

    private val log = ArrayDeque<Event>()
    /** True while BridgeService is listening; then the screen doesn't record the same broadcast twice. */
    @Volatile var serviceListening = false

    fun events(): List<Event> = synchronized(log) { log.toList() }

    private fun record(i: Intent, note: String?) {
        val g = i.getStringExtra("gesture") ?: "(none)"
        val steps = i.getIntExtra("steps", 0)
        received++
        lastAt = System.currentTimeMillis()
        lastGesture = g
        lastRejected = note
        synchronized(log) {
            log.addFirst(Event(lastAt, g, steps, note))
            while (log.size > 30) log.removeLast()
        }
        Trace.add("air: gesture '$g'${if (steps != 0) " steps=$steps" else ""} -> ${note ?: "accepted"}")
    }

    /** Seen by the open app screen. Only shows it: nothing is sent to any device from here. */
    fun observe(ctx: Context, receiver: BroadcastReceiver, i: Intent) {
        if (serviceListening) return // the service already recorded this one
        record(i, accept(ctx, receiver))
    }

    fun installed(ctx: Context) = PKGS.any {
        runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess
    }

    /** Tell Air Gestures to use (or stop using) the profile named after this app; it then sends us STATUS. */
    fun announce(ctx: Context, on: Boolean) {
        if (!on) { bandConnected = null; battery = -1 } // STATUS stops; don't keep gating on a stale value
        PKGS.forEach {
            ctx.sendBroadcast(
                Intent(ACTION_SET_PROFILE).setPackage(it).putExtra("profile", if (on) ctx.packageName else "")
            )
        }
    }

    /**
     * The broadcast is unprotected, so any app can fake it. Reject it when Android tells us the
     * sender (Android 14+, only if the sender opts in to sharing identity) and it isn't Air Gestures,
     * and while Air Gestures itself reports the band as disconnected.
     */
    fun accept(ctx: Context, receiver: BroadcastReceiver): String? {
        if (!Prefs.airGestures(ctx)) return "Receive Air Gestures is switched off"
        if (Build.VERSION.SDK_INT >= 34) {
            val from = receiver.sentFromPackage
            if (from != null && from !in PKGS) return "sent by $from, not Air Gestures"
        }
        if (bandConnected == false) return "Air Gestures says the band is disconnected"
        return null
    }

    /** Entry point for every GESTURE broadcast reaching the service. */
    fun handleBroadcast(ctx: Context, receiver: BroadcastReceiver, i: Intent) {
        val reason = accept(ctx, receiver)
        record(i, reason)
        if (reason == null) dispatch(ctx, i)
    }

    /** A sentence for the Home screen. */
    fun statusText(ctx: Context): String = when {
        !installed(ctx) -> "Air Gestures isn't installed."
        received == 0 -> "Nothing received from Air Gestures yet. In Air Gestures set a gesture's action to “Send to automation”, then use its “Send a test” button."
        lastRejected != null -> "Air Gestures is sending, but the last gesture was ignored: $lastRejected."
        else -> "Air Gestures is connected ($received received)."
    }

    /** Sends ourselves a gesture the same way Air Gestures does, to prove the rest of the chain works. */
    fun simulate(ctx: Context, gesture: String) {
        ctx.sendBroadcast(Intent(ACTION_GESTURE).setPackage(ctx.packageName).putExtra("gesture", gesture))
    }

    /** Routes one GESTURE intent (from Air Gestures or the Tasker entry point) through the mapping table. */
    fun dispatch(ctx: Context, i: Intent) {
        val gesture = i.getStringExtra("gesture")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val base = "EVT_" + gesture.uppercase().replace(' ', '_').replace('-', '_')
        if (base == "EVT_TEST") { Bridge.lastInput = base; return }
        Trace.add("air: $base -> ${Prefs.mapping(ctx)[base] ?: "no action assigned"}")
        val steps = i.getIntExtra("steps", 0)
        if (steps == 0) {
            Bridge.handle(ctx, base, discrete = true)
            return
        }
        // Dial/slider: one mapped input per step, e.g. EVT_DIAL_UP x3. Capped so a burst can't flood the host.
        val name = base + if (steps > 0) "_UP" else "_DOWN"
        repeat(minOf(abs(steps), 20)) { Bridge.handle(ctx, name, discrete = true) }
    }
}

/** Receives Air Gestures STATUS (sent only to apps that announced themselves via SET_PROFILE). */
class AirStatusReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.hasExtra("connected")) AirGestures.bandConnected = i.getBooleanExtra("connected", false)
        if (i.hasExtra("battery")) AirGestures.battery = i.getIntExtra("battery", -1)
    }
}
