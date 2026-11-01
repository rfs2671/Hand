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
    @Volatile var lastRoute: String = "No wristband event has reached Hand Bridge yet"
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
        val reason = accept(ctx, receiver)
        record(i, reason)
        // When the foreground service is starting or Android killed it, the activity is
        // temporarily the only receiver. Route once here rather than silently displaying
        // a gesture that does nothing. Once the service registers it takes sole ownership.
        if (reason == null && Prefs.enabled(ctx)) dispatch(ctx, i)
        else if (reason == null) keyOf(i)?.let {
            GestureTests.note(it, Bridge.resolve(ctx, it), sent = null, fromBand = true)
        }
    }

    /** True while the Test tab is open. */
    @Volatile var testing = false
    /** On the Test tab: also carry out each gesture's action on the connected device (otherwise only recognise it). */
    @Volatile var sendWhileTesting = true

    /** The mapping key for a gesture message (a dial/slide gets _UP/_DOWN from its steps), or null for "test"/empty. */
    private fun keyOf(i: Intent): String? {
        val gesture = i.getStringExtra("gesture")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val base = "EVT_" + gesture.uppercase().replace(' ', '_').replace('-', '_')
        if (base == "EVT_TEST") return null
        val steps = i.getIntExtra("steps", 0)
        return if (steps == 0) base else Actions.stepKey(base, steps)
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
        // STATUS may be stale after a band reconnect or a profile switch.
        // An actual automation event is more authoritative than an earlier disconnected status.
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

    /** Internal synthetic check: doesn't pretend a band event arrived. */
    fun simulate(ctx: Context, gesture: String) {
        val i = Intent(ACTION_GESTURE).putExtra("gesture", gesture)
        dispatch(ctx, i)
        Trace.add("air: locally simulated $gesture (not from band)")
    }

    fun deliveryStatus(ctx: Context): String {
        val latest = lastAt
        return when {
            !Prefs.airGestures(ctx) -> "OFF: Receive Air Gestures is disabled in Hand Bridge."
            !Prefs.enabled(ctx) -> "OFF: Hand Bridge service is disabled; turn it on from Home."
            !serviceListening && latest == 0L -> "Bridge service not listening yet. Open Home and enable the bridge."
            latest == 0L -> "NO wristband broadcasts received. In Air Gestures set each gesture to Send to automation in the ACTIVE profile."
            lastRejected != null -> "Wristband event ignored: $lastRejected"
            System.currentTimeMillis() - latest > 30_000L ->
                "No event in the last 30 seconds. Last: $lastGesture. Perform an index tap and check whether this changes."
            else -> "Received $received event(s). Last: $lastGesture. Route: $lastRoute"
        }
    }

    /** Routes one GESTURE intent (from Air Gestures or the Tasker entry point) through the mapping table. */
    fun dispatch(ctx: Context, i: Intent) {
        val gesture = i.getStringExtra("gesture")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val base = "EVT_" + gesture.uppercase().replace(' ', '_').replace('-', '_')
        if (base == "EVT_TEST") { Bridge.lastInput = base; lastRoute = "Air Gestures test broadcast received"; return }
        val steps = i.getIntExtra("steps", 0)
        // A dial/slide becomes one mapped input per step, e.g. EVT_DIAL_UP x3 (capped so a fast slide can't flood the host).
        val key = if (steps == 0) base else Actions.stepKey(base, steps)
        val action = Bridge.resolve(ctx, key)
        val n = if (steps == 0) 1 else minOf(abs(steps), Actions.stepCap(action))
        Trace.add("air: $key${if (n > 1) " x$n" else ""} -> $action")
        var sent: Boolean? = null
        if (!testing || sendWhileTesting) {
            val continuous = steps != 0 &&
                (action in Actions.directional || action == Action.SCROLL_UP || action == Action.SCROLL_DOWN)
            sent = when {
                continuous -> Bridge.handleContinuous(ctx, key, n)
                n > 1 -> Bridge.handleSteps(ctx, key, n)
                else -> Bridge.handle(ctx, key, discrete = true)
            }
        }
        lastRoute = when {
            action == Action.NONE -> "$key: no Car-mode mapping"
            sent == false -> "$key → ${action.label}: received but not dispatched (bridge/output unavailable)"
            sent == null -> "$key → ${action.label}: only observed; Test send disabled"
            else -> "$key → ${action.label}: handed to output, device response not confirmed"
        }
        GestureTests.note(key, action, sent, fromBand = true)
    }
}

/** Receives Air Gestures STATUS (sent only to apps that announced themselves via SET_PROFILE). */
class AirStatusReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.hasExtra("connected")) AirGestures.bandConnected = i.getBooleanExtra("connected", false)
        if (i.hasExtra("battery")) AirGestures.battery = i.getIntExtra("battery", -1)
    }
}
