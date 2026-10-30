package com.hand.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.lumen.band.BandLink
import dev.lumen.band.GestureDevice
import dev.lumen.band.Identity
import dev.lumen.band.Phase
import dev.lumen.band.PointerCounts
import dev.lumen.band.PointerPacer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PREVIEW: direct Meta Neural Band connection. Uses MIT-licensed Lumen band-core,
 * no Air Gestures application or broadcast hop. Imports the user's existing
 * owner-key export; never claims/resets a band automatically.
 *
 * Only one app can own the band's live Bluetooth input channel at a time.
 */
object NativeBand {
    private var link: BandLink? = null
    private var ctx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val pacer = PointerPacer()
    private val counts = PointerCounts()
    private var framing = false
    private var buttons = 0
    private var pointerTestOnly = false
    @Volatile private var diagnostic = false
    @Volatile var pointerMotionRecords = 0
        private set
    @Volatile var pointerPresses = 0
        private set
    @Volatile var pointerReleases = 0
        private set
    @Volatile var pointerLastAt = 0L
        private set
    @Volatile var pointerTotalMovement = 0.0
        private set
    private val frame = Runnable { frame() }

    @Volatile var phase: Phase = Phase.STOPPED
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var pointerOn = false
        private set
    @Volatile var lastEvent = "-"
        private set
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private fun changed() = main.post { listeners.forEach { it() } }

    fun enabled(c: Context) = c.getSharedPreferences("native", Context.MODE_PRIVATE)
        .getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences("native", Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        if (on) start(c) else stop()
    }
    fun identityPresent(c: Context): Boolean = Identity.present(c)

    fun importKey(c: Context, content: ByteArray): String {
        if (content.size > 65536) return "Invalid key: file too large"
        stop()
        val result = Identity.import(c, listOf(content))
        changed()
        return result
    }

    /** Recognized gestures in the Lumen native engine. Discrete drag events are NOT supported. */
    val GESTURES = listOf(
        "swipe_up", "swipe_down", "swipe_left", "swipe_right",
        "index_tap", "index_double", "index_hold",
        "middle_tap", "middle_double", "middle_hold",
        "dial_up", "dial_down"
    )

    private fun mapping(): String = GESTURES.joinToString(";") { name ->
        // Default: avoid the 400ms tap-vs-double wait when navigating.
        // On the Test tab, enable doubles so all native gestures can be diagnosed.
        val active = diagnostic || name !in setOf("index_double", "middle_double")
        name + "=" + if (active) "hb." + name else ""
    } + ";hand=band"

    /** Enables double-tap recognition during testing; restore low-latency mapping afterward. */
    fun setDiagnostic(on: Boolean) {
        if (diagnostic == on) return
        diagnostic = on
        link?.setMapping(mapping())
        changed()
    }
    fun diagnosticEnabled() = diagnostic
    fun stopPointerTest() {
        if (pointerTestOnly) setPointer(false)
    }
    fun pointerTesting() = pointerOn && pointerTestOnly
    fun clearPointerTests() {
        pointerMotionRecords = 0
        pointerPresses = 0
        pointerReleases = 0
        pointerTotalMovement = 0.0
        pointerLastAt = 0L
        changed()
    }

    @Synchronized fun start(c: Context) {
        if (!enabled(c) || link != null) return
        val app = c.applicationContext
        ctx = app
        if (!Identity.present(app)) {
            error = "Import an Air Gestures owner-key export first"
            changed()
            return
        }
        if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            error = "Grant Nearby devices permission"
            changed()
            return
        }
        error = null
        val listener = object : GestureDevice.Listener {
            override fun onPhase(value: Phase, band: String?) {
                phase = value
                Trace.add("native band: $value")
                if (value == Phase.CONNECTED && pointerOn) {
                    link?.setPointer(true, "steadiness=0.5;boost=1.0")
                }
                changed()
            }
            override fun onStatus(status: org.json.JSONObject) = Unit
            override fun onActions(actions: List<String>) {
                actions.forEach { action ->
                    if (action.startsWith("hb.")) {
                        val gesture = action.removePrefix("hb.")
                        val key = "EVT_" + gesture.uppercase()
                        lastEvent = gesture
                        val mapped = Actions.resolve(Prefs.mapping(app), key)
                        // The Test tab defaults to recognition-only. A successful request
                        // means queued to output, NOT that the TV visibly acted on it.
                        val sent: Boolean? = if (diagnostic && !AirGestures.sendWhileTesting) null
                                             else Bridge.handle(app, key, discrete = true)
                        GestureTests.note(key, mapped, sent, fromBand = true)
                        changed()
                    }
                }
            }
            override fun onLog(line: String) { Trace.add("native: $line") }
            override fun onPointer(records: DoubleArray) {
                // Lumen emits [kind, sample time, x, y]; keep the event ordering.
                main.post { consumePointer(records) }
            }
        }
        val config = object : BandLink.Config {
            override fun paused() = false
            override fun mapping(): String = NativeBand.mapping()
        }
        try {
            link = BandLink(app, listener, config).also { it.start() }
        } catch (t: Throwable) {
            error = "Band engine unavailable: ${t.javaClass.simpleName}: ${t.message}"
            Trace.add(error!!)
            link = null
            phase = Phase.STOPPED
        }
        changed()
    }

    @Synchronized fun stop() {
        pointerOn = false
        pointerTestOnly = false
        runCatching { link?.setPointer(false, "") }
        runCatching { link?.stop() }
        link = null
        phase = Phase.STOPPED
        releaseMouse()
        changed()
    }

    /** This needs an actual HID mouse host; Android TV Remote v2 has no mouse-coordinate messages. */
    fun setPointer(on: Boolean, testOnly: Boolean = false): Boolean {
        val app = ctx ?: return false
        val hidAvailable = Bridge.hid(app).host != null && Bridge.activeMode(app) == Mode.MOUSE
        if (on && !testOnly && !hidAvailable) {
            error = "Continuous air mouse requires a Bluetooth HID host. Wi-Fi uses directional D-pad controls instead."
            changed()
            return false
        }
        pointerOn = on
        pointerTestOnly = testOnly && on
        if (pointerTestOnly) clearPointerTests()
        val accepted = link?.setPointer(on, if (on) "steadiness=0.5;boost=1.0" else "") ?: false
        if (!accepted) pointerOn = false
        if (!pointerOn) releaseMouse()
        changed()
        return accepted
    }

    private fun consumePointer(a: DoubleArray) {
        if (!pointerOn) return
        var i = 0
        while (i + 3 < a.size) {
            when (a[i]) {
                0.0 -> {
                    pointerMotionRecords++
                    pointerLastAt = SystemClock.elapsedRealtime()
                    pointerTotalMovement += kotlin.math.hypot(a[i + 2], a[i + 3])
                    if (!pointerTestOnly) pacer.add(a[i + 2] * 40.0, a[i + 3] * 40.0, a[i + 1])
                }
                1.0 -> { pacer.clear(); counts.reset() }
                2.0 -> {
                    val nextButtons = a[i + 2].toInt()
                    if (buttons == 0 && nextButtons != 0) pointerPresses++
                    if (buttons != 0 && nextButtons == 0) pointerReleases++
                    pointerLastAt = SystemClock.elapsedRealtime()
                    if (pointerTestOnly) {
                        buttons = nextButtons
                        i += 4
                        continue
                    }
                    ctx?.let { Bridge.hid(it).mouse(nextButtons, 0, 0) }
                    buttons = nextButtons
                }
            }
            i += 4
        }
        if (pointerTestOnly) { changed(); return }
        if (!framing && !pacer.isEmpty) {
            framing = true
            main.post(frame)
        }
    }

    private fun frame() {
        framing = false
        if (!pointerOn) return
        pacer.take(SystemClock.elapsedRealtimeNanos() / 1e9)?.let { delta ->
            val whole = counts.add(delta[0], delta[1])
            if (whole[0] != 0 || whole[1] != 0) {
                ctx?.let { Bridge.hid(it).mouse(buttons, whole[0], whole[1]) }
            }
        }
        if (!pacer.isEmpty) {
            framing = true
            main.postDelayed(frame, 8)
        }
    }

    private fun releaseMouse() {
        main.removeCallbacks(frame)
        framing = false
        pacer.clear()
        counts.reset()
        buttons = 0
        ctx?.let { Bridge.hid(it).mouse(0, 0, 0) }
    }
}
