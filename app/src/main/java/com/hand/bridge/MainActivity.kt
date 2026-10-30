package com.hand.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hand.bridge.atv.AtvPairing
import com.hand.bridge.atv.Latency
import com.hand.bridge.atv.PinTrust
import com.hand.bridge.atv.Polo
import com.hand.bridge.atv.TvInfo
import com.hand.bridge.atv.WrongCode

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private enum class Tab(val title: String, val icon: Glyph.Shape) {
        HOME("Home", Glyph.Shape.HOME),
        DEVICES("Devices", Glyph.Shape.DEVICES),
        GESTURES("Gestures", Glyph.Shape.GESTURE),
        TEST("Test", Glyph.Shape.CHECK),
        REMOTE("Remote", Glyph.Shape.REMOTE)
    }

    private val reqNativeKey = 9132
    private var tab = Tab.HOME
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var headerSub: TextView
    /** Called every tick to update the visible page in place (or rebuild it when its state changed). */
    private var refresher: (() -> Unit)? = null
    private var dialog: AlertDialog? = null
    private var monitorOn = false
    /** Lets the Gesture check on Home see Air Gestures' messages even when the bridge is off. */
    private val monitor = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { AirGestures.observe(c, this, i) }
    }
    /** Live-updates whatever dialog is open (runs with the screen ticker). */
    private var dialogTick: (() -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresher?.invoke()
            dialogTick?.invoke()
            ui.postDelayed(this, 700)
        }
    }

    private val p get() = Ui.p

    // ---- lifecycle ------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        actionBar?.hide()
        window.statusBarColor = p.bg
        window.navigationBarColor = p.surface
        if (!Ui.dark) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        }
        tab = savedInstanceState?.getString("tab")?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.HOME
        buildShell()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("tab", tab.name)
    }

    override fun onResume() {
        super.onResume()
        syncAssociations()
        if (Prefs.enabled(this)) BridgeService.start(this)
        runCatching {
            registerReceiver(monitor, android.content.IntentFilter(AirGestures.ACTION_GESTURE), android.content.Context.RECEIVER_EXPORTED)
            monitorOn = true
        }
        showTab(tab)
        ticker.run()
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        if (monitorOn) { runCatching { unregisterReceiver(monitor) }; monitorOn = false }
        Bridge.learn = null
        dialogTick = null
        AirGestures.testing = false
        NativeBand.setDiagnostic(false)
        NativeBand.stopPointerTest()
        dialog?.dismiss()
        stopDiscovery()
        pairing?.close()
        pairing = null
        super.onPause()
    }

    // ---- shell ----------------------------------------------------------------------------

    private fun buildShell() {
        val root = vbox { setBackgroundColor(p.bg) }
        root.addView(vbox {
            setPadding(Ui.dp(22), Ui.dp(18), Ui.dp(22), Ui.dp(6))
            addView(label("Hand Bridge", 28f, bold = true))
            headerSub = label("", 14f, p.muted).apply { setPadding(0, Ui.dp(2), 0, 0) }
            addView(headerSub)
        })
        page = vbox { setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(28)) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(page)
        }
        root.addView(scroll, lp(h = 0, weight = 1f))
        root.addView(View(this).apply { setBackgroundColor(p.outline) }, lp(h = Ui.dp(1)))
        nav = hbox {
            setBackgroundColor(p.surface)
            setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(10))
        }
        Tab.values().forEach { t ->
            nav.addView(vbox {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(FrameLayout(context).apply {
                    addView(Glyph(context, t.icon, p.muted, 22), FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER))
                }, lp(Ui.dp(60), Ui.dp(32)))
                addView(label(t.title, 12.5f, p.muted).apply { setPadding(0, Ui.dp(4), 0, 0) })
                onTap { showTab(t) }
            }, lp(0, weight = 1f))
        }
        root.addView(nav)
        setContentView(root)
    }

    private fun paintNav() {
        Tab.values().forEachIndexed { i, t ->
            val item = nav.getChildAt(i) as LinearLayout
            val pillBox = item.getChildAt(0) as FrameLayout
            val on = t == tab
            pillBox.background = if (on) Ui.rounded(Ui.tint(p.accent, 0x33), 16) else null
            (pillBox.getChildAt(0) as Glyph).color = if (on) p.accent else p.muted
            (item.getChildAt(1) as TextView).apply {
                setTextColor(if (on) p.text else p.muted)
                typeface = if (on) Ui.medium else android.graphics.Typeface.DEFAULT
            }
        }
    }

    private fun showTab(t: Tab) {
        if (t != tab) Bridge.learn = null
        AirGestures.testing = t == Tab.TEST
        val native = NativeBand.enabled(this)
        if (native) {
            if (t != Tab.TEST) NativeBand.stopPointerTest()
            if (t == Tab.TEST && tab != Tab.TEST) AirGestures.sendWhileTesting = false
            NativeBand.setDiagnostic(t == Tab.TEST)
        }
        tab = t
        paintNav()
        page.removeAllViews()
        scroll.scrollTo(0, 0)
        headerSub.text = when (t) {
            Tab.HOME -> "Your band, any screen"
            Tab.DEVICES -> "What your gestures control"
            Tab.GESTURES -> "What each gesture does"
            Tab.TEST -> "Try each gesture and see it work"
            Tab.REMOTE -> "Test and control by touch"
        }
        refresher = when (t) {
            Tab.HOME -> homePage()
            Tab.DEVICES -> devicesPage()
            Tab.GESTURES -> gesturesPage()
            Tab.TEST -> testPage()
            Tab.REMOTE -> remotePage()
        }
        refresher?.invoke()
    }

    private fun rebuild() = showTab(tab)

    // ---- home -----------------------------------------------------------------------------

    private fun homePage(): () -> Unit {
        val dot = Glyph(this, Glyph.Shape.DOT, p.muted, 12)
        val title = label("", 24f, bold = true)
        val sub = label("", 14.5f, p.muted)
        val chips = hbox {}
        val action = vbox {}
        page.addView(card {
            setPadding(Ui.dp(20), Ui.dp(22), Ui.dp(20), Ui.dp(20))
            addView(hbox {
                addView(dot, lp(Ui.dp(12), Ui.dp(12)).margins(r = 12))
                addView(title)
            })
            addView(sub, lp().margins(t = 6))
            addView(chips, lp().margins(t = 16))
            addView(action, lp().margins(t = 20))
        })

        // If the app closed unexpectedly last time, say so and offer the details.
        CrashLog.last(this)?.let { crash ->
            page.addView(card {
                background = Ui.rounded(Ui.tint(p.bad, 0x1F), 22)
                addView(label("The app closed unexpectedly last time", 17f, bold = true))
                addView(label("Copy the details and paste them in a message so it can be fixed.", 14f, p.muted), lp().margins(t = 4, b = 12))
                addView(hbox {
                    addView(pill("Copy details", BtnStyle.PRIMARY) {
                        getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("Hand Bridge crash", crash))
                        toast("Copied")
                    }, lp(0, weight = 1f).margins(r = 10))
                    addView(pill("Dismiss", BtnStyle.SECONDARY) { CrashLog.clear(this@MainActivity); rebuild() })
                })
            })
        }

        val setupBox = vbox {}
        page.addView(setupBox)

        // Direct Meta Neural Band preview: reuse the Air Gestures owner's key,
        // but run the Lumen Rust band engine locally (no broadcast hop).
        val nativeStatus = label("", 13f, p.muted)
        page.addView(card {
            addView(label("Direct band input (experimental)", 17f, bold = true))
            addView(label("Requires an Air Gestures owner-key export. Turn Air Gestures off before connecting here; one app at a time can hold the band.", 13f, p.muted), lp().margins(t = 5, b = 10))
            addView(nativeStatus, lp().margins(b = 10))
            addView(pill("Import band key file", BtnStyle.SECONDARY) {
                val select = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(select, reqNativeKey)
            })
            addView(pill(
                if (NativeBand.enabled(this@MainActivity)) "Use Air Gestures instead" else "Enable direct band",
                BtnStyle.SECONDARY
            ) {
                val on = !NativeBand.enabled(this@MainActivity)
                if (on && !NativeBand.identityPresent(this@MainActivity)) {
                    toast("Import the Air Gestures band key first.")
                } else {
                    if (on) AirGestures.announce(this@MainActivity, false)
                    NativeBand.setEnabled(this@MainActivity, on)
                    if (on) BridgeService.start(this@MainActivity)
                    rebuild()
                }
            }, lp().margins(t = 8))
            addView(pill("Toggle native Air Mouse (Bluetooth HID)", BtnStyle.SECONDARY) {
                val ok = NativeBand.setPointer(!NativeBand.pointerOn)
                toast(if (ok) "Air Mouse input changed" else "Pair a Bluetooth mouse host and connect the native band first")
            }, lp().margins(t = 8))
            addView(label("For JMGO browser cursor movement, use Remote → D-pad calibration. No extra projector app is required. Test real held arrow events against the physical remote.", 12f, p.muted), lp().margins(t = 8))
        })

        val checkHint = label("", 14f, p.muted)
        val checkRows = vbox {}
        page.addView(card {
            addView(label("Gesture check", 17f, bold = true))
            addView(checkHint, lp().margins(t = 4, b = 6))
            addView(checkRows)
        })

        page.addView(card {
            addView(label("Something not working?", 17f, bold = true))
            addView(label("Copy a report of what the app sees (Bluetooth and Wi-Fi state, device names and addresses, recent events) and paste it in a message.", 14f, p.muted), lp().margins(t = 4, b = 12))
            addView(pill("Copy report", BtnStyle.SECONDARY) {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Hand Bridge report", Diagnostics.report(this@MainActivity)))
                toast("Report copied. Paste it into a message.")
            })
        })

        var sig = ""
        return {
            val hid = Bridge.hid(this)
            val host = hid.host
            val enabled = Prefs.enabled(this)
            val btOn = svc<BluetoothManager>().adapter?.isEnabled == true
            val tvNow = TvManager.connectedTv()
            val state = when {
                !enabled -> "off"
                tvNow != null -> "tv:${tvNow.id}"
                !btOn -> "bt"
                host != null -> "on:${host.address}"
                hid.registered -> "ready"
                else -> "starting"
            }
            val s = "$state|${setupSignature()}|${AirGestures.bandConnected}|${AirGestures.battery}|${TvManager.state}"
            if (s != sig) {
                sig = s
                fun set(color: Int, t: String, d: String) { dot.color = color; title.text = t; sub.text = d }
                when {
                    !enabled -> set(p.muted, "Bridge is off", "Turn it on and your band controls the device you pick.")
                    tvNow != null -> set(p.good, "Connected", "${tvNow.name} · Wi-Fi remote")
                    !btOn -> set(p.warn, "Bluetooth is off", "Hand Bridge needs Bluetooth to reach your devices.")
                    host != null -> {
                        val mode = Prefs.device(this, host.address)?.mode ?: Mode.MOUSE
                        set(p.good, "Connected", "${host.name ?: host.address} · ${modeName(mode)}")
                    }
                    hid.registered -> set(p.accent, "Ready", "Gestures control this phone until a device connects.")
                    else -> set(p.warn, "Starting…", "Getting Bluetooth ready.")
                }
                chips.removeAllViews()
                chips.addView(bandChip())
                val cfgTv = TvManager.tv
                if (enabled && cfgTv != null && tvNow == null) {
                    when (TvManager.state) {
                        TvState.CONNECTING -> chips.addView(chip("Connecting to ${cfgTv.name}…", p.accent, dot = true), lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(l = 8))
                        TvState.UNREACHABLE -> chips.addView(chip("${cfgTv.name} not reachable", p.warn, dot = true), lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(l = 8))
                        TvState.NEEDS_PAIRING -> chips.addView(chip("${cfgTv.name} needs pairing", p.warn, dot = true), lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(l = 8))
                        else -> {}
                    }
                }
                action.removeAllViews()
                action.addView(when {
                    !enabled -> pill("Turn on", BtnStyle.PRIMARY, Glyph.Shape.POWER) { startBridge() }
                    !btOn -> pill("Turn on Bluetooth", BtnStyle.PRIMARY, Glyph.Shape.BLUETOOTH) {
                        runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    }
                    else -> pill("Turn off", BtnStyle.SECONDARY, Glyph.Shape.POWER) { BridgeService.stop(this) }
                })
                setupBox.removeAllViews()
                setupCard()?.let { setupBox.addView(it) }
            }
            nativeStatus.text = "Native: ${NativeBand.phase} · Last ${NativeBand.lastEvent} · Mouse ${if (NativeBand.pointerOn) "ON" else "OFF"}${NativeBand.error?.let { " · $it" } ?: ""}"
            val events = AirGestures.events().take(6)
            checkHint.text = when {
                events.isEmpty() -> AirGestures.statusText(this)
                !enabled -> "Live. The bridge is off, so gestures are only shown here and sent nowhere."
                else -> "Live. Gestures appear here as they arrive."
            }
            checkRows.removeAllViews()
            val map = Prefs.mapping(this)
            events.forEachIndexed { n, e ->
                if (n > 0) checkRows.addView(divider())
                val key = "EVT_" + e.gesture.trim().uppercase().replace(' ', '_').replace('-', '_')
                val name = if (e.gesture == "test") "Test from Air Gestures"
                    else Actions.pretty(key) + if (e.steps != 0) "  ${if (e.steps > 0) "+" else ""}${e.steps}" else ""
                val act = Actions.resolve(map, if (e.steps != 0) Actions.stepKey(key, e.steps) else key)
                val (what, color) = when {
                    e.note != null -> "Ignored: ${e.note}" to p.warn
                    e.gesture == "test" -> "Received ✓" to p.good
                    act == Action.NONE -> "No action assigned" to p.muted
                    else -> "→ ${act.label}" to p.good
                }
                checkRows.addView(listRow(name, ago(e.at), if (e.gesture == "test") Glyph.Shape.CHECK else actionIcon(act), color,
                    trailing = label(what, 13.5f, color).apply { gravity = Gravity.END; maxWidth = Ui.dp(150) }))
            }
        }
    }

    private fun bandChip(): View = when (AirGestures.bandConnected) {
        true -> chip("Band connected" + if (AirGestures.battery >= 0) " · ${AirGestures.battery}%" else "", p.good, dot = true)
        false -> chip("Band disconnected", p.warn, dot = true)
        null -> if (AirGestures.installed(this)) chip("Air Gestures ready", p.accent, dot = true)
        else chip("Air Gestures not installed", p.warn, dot = true)
    }

    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 5 -> "just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    private class Step(val done: Boolean, val title: String, val sub: String, val icon: Glyph.Shape, val go: () -> Unit)

    private fun steps() = listOf(
        Step(hasBt(), "Allow Bluetooth", "So the phone can act as a remote", Glyph.Shape.BLUETOOTH) { requestPerms() },
        Step(batteryOk(), "Keep it running", "Lets it work with the screen off", Glyph.Shape.BOLT) { batteryExemption() },
        Step(AirGestures.installed(this), "Get Air Gestures", "The app that reads your Neural Band", Glyph.Shape.GESTURE) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AIR_GESTURES_URL))) }
        },
        Step(AirGestures.received > 0, "Send gestures from Air Gestures", "In Air Gestures set each gesture's action to “Send to automation”", Glyph.Shape.GESTURE) {
            packageManager.getLaunchIntentForPackage(AirGestures.PKG)?.let { startActivity(it) } ?: toast("Air Gestures isn't installed")
        },
        Step(Prefs.devices(this).isNotEmpty() || Prefs.tvs(this).isNotEmpty(), "Add a device", "Projector, TV, PC or your car", Glyph.Shape.DEVICES) { showTab(Tab.DEVICES) },
        Step(a11yOn(), "Phone controls (optional)", "Back and Home on this phone, plus keyboard-style input", Glyph.Shape.PHONE) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    )

    private fun setupSignature() = steps().joinToString("") { if (it.done) "1" else "0" }

    private fun setupCard(): View? {
        val all = steps()
        val done = all.count { it.done }
        if (done == all.size) return null
        return card {
            addView(hbox {
                addView(label("Finish setup", 17f, bold = true), lp(0, weight = 1f))
                addView(label("$done of ${all.size}", 13.5f, p.muted))
            })
            // progress bar
            addView(hbox {
                background = Ui.rounded(p.surface2, 4)
                addView(View(context).apply { background = Ui.rounded(p.accent, 4) }, lp(0, Ui.dp(6), done.toFloat()))
                addView(View(context), lp(0, Ui.dp(6), (all.size - done).toFloat()))
            }, lp(h = Ui.dp(6)).margins(t = 12, b = 6))
            all.forEach { s ->
                if (s.done) addView(listRow(s.title, null, Glyph.Shape.CHECK, p.good))
                else addView(listRow(s.title, s.sub, s.icon, p.accent, onClick = s.go))
            }
        }
    }

    private fun startBridge() {
        if (!hasBt()) { requestPerms(); return }
        if (!BridgeService.start(this)) toast("Couldn't start — open the app again")
        refresher?.invoke()
    }

    // ---- devices --------------------------------------------------------------------------

    private fun modeName(m: Mode) = when (m) {
        Mode.MOUSE -> "Mouse"
        Mode.REMOTE -> "Remote"
        Mode.LOCAL -> "Phone"
    }

    private fun modeIcon(m: Mode) = when (m) {
        Mode.MOUSE -> Glyph.Shape.MOUSE
        Mode.REMOTE -> Glyph.Shape.REMOTE
        Mode.LOCAL -> Glyph.Shape.PHONE
    }

    private fun modeHelp(m: Mode) = when (m) {
        Mode.MOUSE -> "Gestures move a pointer and click. Best for PCs, XREAL Beam Pro and smart TVs."
        Mode.REMOTE -> "Arrows and OK, like the original remote. Best for Android TV and JMGO projectors."
        Mode.LOCAL -> "Nothing is sent to this device. Use it for your car: gestures control music and volume, and Android Auto follows."
    }

    private fun devicesPage(): () -> Unit {
        val hid = Bridge.hid(this)
        page.addView(pill("Add a device", BtnStyle.PRIMARY, Glyph.Shape.PLUS) { addDevice() }, lp().margins(b = 14))
        val devs = Prefs.devices(this)
        val tvs = Prefs.tvs(this)
        tvs.forEach { page.addView(tvCard(it)) }
        // Android's own Bluetooth screen never lists a keyboard/mouse connection like this, so show it here.
        page.addView(card {
            setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6))
            val h = hid.host
            val (color, title, sub) = when {
                h != null -> Triple(p.good, "Bluetooth keyboard and mouse: connected", "Connected to ${h.name ?: h.address}")
                hid.lastError != null -> Triple(p.warn, "Bluetooth keyboard and mouse: problem", hid.lastError!!)
                hid.registered -> Triple(p.accent, "Bluetooth keyboard and mouse: ready", "Pair or connect a device below. Android's Bluetooth screen doesn't list this kind of connection.")
                Prefs.enabled(context) -> Triple(p.warn, "Bluetooth keyboard and mouse: starting…", "Getting Bluetooth ready.")
                else -> Triple(p.muted, "Bluetooth keyboard and mouse: off", "Turn the bridge on from Home.")
            }
            addView(listRow(title, sub, Glyph.Shape.BLUETOOTH, color))
        })
        if (devs.isEmpty() && tvs.isEmpty()) {
            page.addView(card {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(Ui.dp(24), Ui.dp(32), Ui.dp(24), Ui.dp(32))
                addView(Glyph(context, Glyph.Shape.DEVICES, p.muted, 48), lp(Ui.dp(48), Ui.dp(48)))
                addView(label("No devices yet", 18f, bold = true).apply { gravity = Gravity.CENTER }, lp().margins(t = 14))
                addView(label("Add your projector, TV, PC or XR glasses host. Your car can go here too.", 14f, p.muted)
                    .apply { gravity = Gravity.CENTER }, lp().margins(t = 6))
            })
        }
        devs.forEach { page.addView(deviceCard(it, hid.host?.address == it.address)) }
        page.addView(card {
            addView(listRow("Using it in the car", "Add the car's Bluetooth and choose Phone. When the car connects, your band controls music and volume.",
                Glyph.Shape.CAR, p.accent))
        })
        fun sig() = "${hid.host?.address}|${hid.registered}|${hid.lastError}|${TvManager.state}|${TvManager.connected}|${Prefs.devices(this).size}"
        val sig = sig()
        return { if (sig() != sig) rebuild() }
    }

    private fun deviceCard(d: Dev, connected: Boolean) = card {
        addView(hbox {
            addView(FrameLayout(context).apply {
                background = Ui.rounded(Ui.tint(p.accent, 0x22), 14)
                addView(Glyph(context, modeIcon(d.mode), p.accent, 22), FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER))
            }, lp(Ui.dp(46), Ui.dp(46)).margins(r = 14))
            addView(vbox {
                addView(label(d.name, 17f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(label(d.address, 12.5f, p.muted), lp().margins(t = 2))
            }, lp(0, weight = 1f).margins(r = 8))
            addView(when {
                connected -> chip("Connected", p.good, dot = true)
                d.auto -> chip("Auto", p.accent)
                else -> chip("Manual", p.muted)
            })
        })
        addView(label("Control as", 12.5f, p.muted, bold = true), lp().margins(t = 18, b = 8))
        val modes = Mode.values()
        addView(segmented(modes.map { modeName(it) to modeIcon(it) }, modes.indexOf(d.mode)) { i ->
            Prefs.upsert(this@MainActivity, d.copy(mode = modes[i]))
            ui.postDelayed({ rebuild() }, 120)
        })
        addView(label(modeHelp(d.mode), 13f, p.muted), lp().margins(t = 8))
        addView(divider(), lp(h = Ui.dp(1)).margins(t = 12))
        addView(toggleRow("Connect automatically", "When it's nearby or turns on", d.auto) { on ->
            Prefs.upsert(this@MainActivity, d.copy(auto = on))
        })
        addView(hbox {
            if (d.mode != Mode.LOCAL) {
                addView(
                    if (connected) pill("Disconnect", BtnStyle.SECONDARY) { Bridge.hid(this@MainActivity).disconnect() }
                    else pill("Connect", BtnStyle.PRIMARY, Glyph.Shape.BLUETOOTH) {
                        if (BridgeService.start(this@MainActivity, connect = d.address)) toast("Connecting to ${d.name}…")
                    },
                    lp(0, weight = 1f).margins(r = 10)
                )
            } else addView(View(context), lp(0, weight = 1f))
            addView(pill("Forget", BtnStyle.DANGER) {
                confirm("Forget ${d.name}?", "It won't connect automatically any more. You can add it again later.", "Forget") { forget(d) }
            })
        }, lp().margins(t = 6))
    }

    private fun addDevice() = safely("Opening Add device") {
        sheet("Add a device", listOf(
            Triple("Projector or TV on Wi-Fi", "Google TV, Android TV, JMGO. Works like the Google TV remote app. Best choice.", Glyph.Shape.DEVICES) to { findTvs() },
            Triple("Bluetooth: search nearby", "Keyboard-and-mouse mode for PCs and other devices. Put it in pairing mode first.", Glyph.Shape.SEARCH) to { associate(null) },
            Triple("Bluetooth: choose a paired device", "Something already in your phone's Bluetooth list", Glyph.Shape.BLUETOOTH) to { pickBonded() },
            Triple("Bluetooth: pair from the projector's side", "The projector finds this phone as a keyboard. Use this if the search can't see it.", Glyph.Shape.PHONE) to { pairFromDevice() }
        ))
    }

    // ---- Wi-Fi TVs ------------------------------------------------------------------------

    private var finder: TvFinder? = null
    private var pairing: AtvPairing? = null
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "pairing").apply { isDaemon = true } }

    private fun stopDiscovery() {
        finder?.stop()
        finder = null
    }

    /** Lists Google TV / Android TV devices as they're found: mDNS, Google Cast announcements and a network scan. */
    private fun findTvs() = safely("Searching Wi-Fi") {
        dialog?.dismiss()
        stopDiscovery()
        val body = vbox { setPadding(Ui.dp(18), Ui.dp(20), Ui.dp(18), Ui.dp(12)) }
        body.addView(label("Projectors and TVs on Wi-Fi", 20f, bold = true), lp().margins(l = 4))
        val status = label("Searching your Wi-Fi… the TV must be on.", 14f, p.muted)
        body.addView(status, lp().margins(l = 4, t = 4, b = 8))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(pickerRow("Enter its IP address", "If it isn't listed. Google Home shows it under the device's settings.", Glyph.Shape.SEARCH, false) {
            dialog?.dismiss(); askIp()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val known = Prefs.tvs(this).map { it.id }.toSet()
        val seen = HashSet<String>()
        val f = TvFinder(this, { t ->
            runOnUiThread {
                if (dialog?.isShowing != true || !seen.add(t.host)) return@runOnUiThread
                status.text = "Tap your projector or TV to pair with it."
                val added = (t.mac ?: t.host) in known
                list.addView(pickerRow(t.name + if (added) "  ·  already added" else "", t.host + (t.mac?.let { "  ·  $it" } ?: ""), Glyph.Shape.DEVICES, false) {
                    dialog?.dismiss(); startPairing(t.name, t.host)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }, { msg -> runOnUiThread { if (dialog?.isShowing == true && list.childCount == 0) status.text = msg } })
        finder = f
        dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(body) }).create().apply {
            window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(Ui.rounded(p.surface, 26), Ui.dp(16)))
            setOnDismissListener { stopDiscovery() }
            show()
        }
        f.start()
    }

    /** Pair from the projector's own Bluetooth menu: the phone shows up there as a keyboard. */
    private fun pairFromDevice() = safely("Starting Bluetooth pairing") {
        if (!hasBt()) { requestPerms(); return@safely }
        if (!Prefs.enabled(this)) BridgeService.start(this)
        val hid = Bridge.hid(this)
        hid.start()
        runCatching {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        }
        val phoneName = runCatching { svc<BluetoothManager>().adapter?.name }.getOrNull() ?: "this phone"
        val status = label("", 15.5f, p.accent, bold = true)
        val warn = label("", 13.5f, p.warn)
        showDialog(vbox {
            setPadding(Ui.dp(22), Ui.dp(22), Ui.dp(22), Ui.dp(16))
            addView(label("Pair from the projector", 20f, bold = true))
            addView(label(
                "1. On the projector open its Bluetooth or “Add accessory” screen.\n" +
                "2. Pick “$phoneName” in the list.\n" +
                "3. Accept the pairing request on this phone.\n\n" +
                "If the projector already lists this phone, remove it there and in your phone's Bluetooth settings first, then pair again. " +
                "A projector that paired before Hand Bridge was running never learned that this phone can act as a keyboard and mouse.",
                14.5f, p.muted
            ), lp().margins(t = 8, b = 14))
            addView(status)
            addView(warn, lp().margins(t = 6))
            addView(hbox {
                gravity = Gravity.END
                addView(pill("Close", BtnStyle.SECONDARY) { dialog?.dismiss() })
            }, lp().margins(t = 14))
        })
        val shown = dialog
        val t0 = System.currentTimeMillis()
        dialogTick = {
            val host = hid.host
            if (host != null) {
                status.text = "Connected to ${host.name ?: host.address} ✓"
                status.setTextColor(p.good)
                warn.text = ""
            } else {
                status.text = "Waiting for the projector to connect…"
                status.setTextColor(p.accent)
                warn.text = hid.lastError ?: if (!hid.registered && System.currentTimeMillis() - t0 > 6_000)
                    "The keyboard/mouse role isn't ready yet. Make sure Bluetooth is on and the bridge is running (Home)." else ""
            }
        }
        shown?.setOnDismissListener { dialogTick = null; showTab(tab) }
    }

    private fun styledField(hint: String, inputType: Int, maxLen: Int): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(p.text)
        setHintTextColor(p.muted)
        textSize = 20f
        isSingleLine = true
        this.inputType = inputType
        filters = arrayOf(InputFilter.LengthFilter(maxLen))
        background = Ui.rounded(p.surface2, 14)
        setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14))
    }

    private fun showDialog(body: View, focus: View? = null) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setView(body).create().apply {
            window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(Ui.rounded(p.surface, 26), Ui.dp(16)))
            if (focus != null) {
                // Custom dialog views don't open the keyboard unless the window is allowed to take focus.
                window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
            show()
        }
        focus?.requestFocus()
    }

    private fun askIp() {
        val field = styledField("192.168.1.50", InputType.TYPE_CLASS_PHONE, 15)
        val err = label("", 13.5f, p.bad)
        showDialog(vbox {
            setPadding(Ui.dp(22), Ui.dp(22), Ui.dp(22), Ui.dp(16))
            addView(label("TV address", 20f, bold = true))
            addView(label("On the TV: Settings → Network → your Wi-Fi → IP address.", 14.5f, p.muted), lp().margins(t = 8, b = 14))
            addView(field, lp())
            addView(err, lp().margins(t = 8))
            addView(hbox {
                gravity = Gravity.END
                addView(pill("Cancel", BtnStyle.TEXT) { dialog?.dismiss() })
                addView(pill("Next", BtnStyle.PRIMARY) {
                    val ip = field.text.toString().trim()
                    if (!Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(ip)) { err.text = "That doesn't look like an IP address."; return@pill }
                    dialog?.dismiss()
                    startPairing(ip, ip)
                }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(l = 8))
            }, lp().margins(t = 14))
        }, focus = field)
    }

    private fun startPairing(svc: String, host: String) {
        showDialog(vbox {
            setPadding(Ui.dp(22), Ui.dp(26), Ui.dp(22), Ui.dp(26))
            addView(label("Contacting $svc…", 18f, bold = true))
            addView(label("A code will appear on the TV screen.", 14.5f, p.muted), lp().margins(t = 6))
        })
        val wait = dialog
        worker.execute {
            val pr = AtvPairing(Prefs.identity(this), host, "Hand Bridge")
            try {
                pr.start()
                runOnUiThread { wait?.dismiss(); pairing = pr; askCode(svc, host, pr) }
            } catch (e: Exception) {
                runCatching { pr.close() }
                android.util.Log.e("HandBridge", "pairing start failed", e)
                runOnUiThread { wait?.dismiss(); toast("Couldn't reach $svc ($host): ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun askCode(svc: String, host: String, pr: AtvPairing) {
        val field = styledField("Code from the TV", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 6)
        val msg = label("", 13.5f, p.bad)
        var working = false
        showDialog(vbox {
            setPadding(Ui.dp(22), Ui.dp(22), Ui.dp(22), Ui.dp(16))
            addView(label("Enter the code", 20f, bold = true))
            addView(label("$svc is showing a 6-character code. Type it here.", 14.5f, p.muted), lp().margins(t = 8, b = 14))
            addView(field, lp())
            addView(msg, lp().margins(t = 8))
            addView(hbox {
                gravity = Gravity.END
                addView(pill("Cancel", BtnStyle.TEXT) { pr.close(); pairing = null; dialog?.dismiss() })
                addView(pill("Pair", BtnStyle.PRIMARY) {
                    if (working) return@pill
                    val code = field.text.toString().trim().uppercase()
                    if (!Polo.isValidCode(code)) { msg.text = "The code is 6 letters or numbers (0-9, A-F)."; return@pill }
                    working = true
                    msg.setTextColor(p.muted); msg.text = "Checking…"
                    val shown = dialog
                    worker.execute {
                        try {
                            pr.finish(code)
                            val cert = pr.serverCert ?: throw java.io.IOException("no certificate")
                            val info = TvInfo.parse(cert)
                            val tv = Tv(
                                id = info.mac ?: host, name = info.name ?: svc, host = host, mac = info.mac,
                                pin = PinTrust.fingerprint(cert), auto = true, svc = svc
                            )
                            Prefs.upsertTv(this@MainActivity, tv)
                            pr.close()
                            runOnUiThread {
                                pairing = null
                                shown?.dismiss()
                                TvManager.connect(this@MainActivity, tv) // also turns the bridge on
                                showTab(Tab.DEVICES)
                                toast("${tv.name} added. Connecting…")
                            }
                        } catch (e: WrongCode) {
                            runOnUiThread { working = false; msg.setTextColor(p.bad); msg.text = e.message }
                        } catch (e: Exception) {
                            android.util.Log.e("HandBridge", "pairing failed", e)
                            runOnUiThread { working = false; msg.setTextColor(p.bad); msg.text = "Pairing failed: ${e.message ?: e.javaClass.simpleName}. Cancel and try again." }
                        }
                    }
                }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(l = 8))
            }, lp().margins(t = 14))
        }, focus = field)
    }

    private fun tvCard(t: Tv): LinearLayout = card {
        val mine = TvManager.tv?.id == t.id
        val state = if (mine) TvManager.state else TvState.IDLE
        val live = mine && TvManager.connected
        addView(hbox {
            addView(FrameLayout(context).apply {
                background = Ui.rounded(Ui.tint(p.accent, 0x22), 14)
                addView(Glyph(context, Glyph.Shape.DEVICES, p.accent, 22), FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER))
            }, lp(Ui.dp(46), Ui.dp(46)).margins(r = 14))
            addView(vbox {
                addView(label(t.name, 17f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(label("${t.host} · Wi-Fi remote", 12.5f, p.muted), lp().margins(t = 2))
            }, lp(0, weight = 1f).margins(r = 8))
            addView(when {
                live -> chip("Connected", p.good, dot = true)
                state == TvState.CONNECTING -> chip("Connecting…", p.accent, dot = true)
                state == TvState.NEEDS_PAIRING -> chip("Needs pairing", p.warn, dot = true)
                state == TvState.UNREACHABLE -> chip("Can't reach", p.warn, dot = true)
                else -> chip("Not connected", p.muted)
            })
        })
        val help = when {
            live -> "Works like the Google TV remote app: arrows, OK, back, home, volume and power."
            state == TvState.NEEDS_PAIRING -> "The TV no longer recognises this phone. Pair again to fix it."
            state == TvState.UNREACHABLE -> "Can't reach it. Is it switched on and on the same Wi-Fi as this phone? Many projectors switch Wi-Fi off in standby: turn it on with its own remote, then tap Connect."
            else -> null
        }
        if (help != null) addView(label(help, 13f, if (state == TvState.IDLE || live) p.muted else p.warn), lp().margins(t = 12))
        addView(divider(), lp(h = Ui.dp(1)).margins(t = 12))
        addView(toggleRow("Connect automatically", "Whenever this phone joins its Wi-Fi", t.auto) { on ->
            Prefs.upsertTv(this@MainActivity, t.copy(auto = on))
        })
        addView(hbox {
            addView(
                when {
                    live -> pill("Disconnect", BtnStyle.SECONDARY) { TvManager.disconnect() }
                    state == TvState.NEEDS_PAIRING -> pill("Pair again", BtnStyle.PRIMARY) { startPairing(t.svc, t.host) }
                    else -> pill("Connect", BtnStyle.PRIMARY, Glyph.Shape.DEVICES) { TvManager.connect(this@MainActivity, t) }
                },
                lp(0, weight = 1f).margins(r = 10)
            )
            addView(pill("Forget", BtnStyle.DANGER) {
                confirm("Forget ${t.name}?", "This phone will stop controlling it. You can add it again later.", "Forget") {
                    if (TvManager.tv?.id == t.id) TvManager.disconnect()
                    Prefs.removeTv(this@MainActivity, t.id)
                    rebuild()
                }
            })
        }, lp().margins(t = 6))
        if (!live && t.mac != null) {
            addView(pill("Try to switch it on (Wake-on-LAN)", BtnStyle.TEXT) {
                TvManager.wake(this@MainActivity, t)
                toast("Sent a wake signal. If the TV supports it, it connects in about 30 seconds.")
            }, lp().margins(t = 4))
        }
    }

    // ---- gestures -------------------------------------------------------------------------

    private fun actionIcon(a: Action) = when (a) {
        Action.POWER -> Glyph.Shape.POWER
        Action.UP, Action.SCROLL_UP -> Glyph.Shape.UP
        Action.DOWN, Action.SCROLL_DOWN -> Glyph.Shape.DOWN
        Action.LEFT -> Glyph.Shape.LEFT
        Action.RIGHT -> Glyph.Shape.RIGHT
        Action.SELECT -> Glyph.Shape.CHECK
        Action.RIGHT_CLICK -> Glyph.Shape.MOUSE
        Action.BACK -> Glyph.Shape.BACK
        Action.HOME -> Glyph.Shape.HOME
        Action.MENU -> Glyph.Shape.MENU
        Action.PLAY_PAUSE -> Glyph.Shape.PLAY_PAUSE
        Action.NEXT -> Glyph.Shape.NEXT
        Action.PREV -> Glyph.Shape.PREV
        Action.VOL_UP -> Glyph.Shape.VOL_UP
        Action.VOL_DOWN -> Glyph.Shape.VOL_DOWN
        Action.ZOOM_IN -> Glyph.Shape.PLUS
        Action.ZOOM_OUT -> Glyph.Shape.DOT
        Action.MUTE -> Glyph.Shape.MUTE
        Action.NONE -> Glyph.Shape.DOT
    }

    private fun gesturesPage(): () -> Unit {
        val learning = Bridge.learn != null
        page.addView(card {
            if (learning) background = Ui.rounded(Ui.tint(p.accent, 0x2A), 22)
            addView(label(if (learning) "Listening…" else "Teach a new gesture", 17f, bold = true))
            addView(label(
                if (learning) "Do the gesture on your band now." else "Tap Learn, do the gesture, then pick what it does.",
                14f, p.muted
            ), lp().margins(t = 4, b = 14))
            addView(
                if (learning) pill("Cancel", BtnStyle.SECONDARY) { Bridge.learn = null; rebuild() }
                else pill("Learn", BtnStyle.PRIMARY, Glyph.Shape.GESTURE) { startLearning() }
            )
        })

        val map = Prefs.mapping(this)
        val order = Actions.defaults.keys.toList()
        val sorted = map.keys.sortedWith(compareBy({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
        fun group(title: String, inputs: List<String>) {
            if (inputs.isEmpty()) return
            page.addView(sectionTitle(title))
            page.addView(card {
                setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6))
                inputs.forEachIndexed { i, input ->
                    if (i > 0) addView(divider())
                    val a = Actions.parse(map[input])
                    addView(listRow(
                        Actions.pretty(input), null, actionIcon(a), if (a == Action.NONE) p.muted else p.accent,
                        trailing = label(if (a == Action.NONE) "Off" else a.label, 14f, if (a == Action.NONE) p.muted else p.text)
                    ) { chooseAction(input) })
                }
            })
        }
        group("Band gestures", sorted.filter { Actions.isBand(it) })
        group("Keys and buttons", sorted.filter { !Actions.isBand(it) })

        page.addView(sectionTitle("Where gestures come from"))
        page.addView(card {
            setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(8))
            addView(toggleRow("Air Gestures", "Set gestures to “Send to automation” in Air Gestures", Prefs.airGestures(context)) {
                Prefs.setAirGestures(context, it)
            })
            addView(divider())
            addView(toggleRow("Separate Air Gestures profile (advanced)", "Leave off unless you made a profile named com.hand.bridge in Air Gestures: it starts empty, so no gestures arrive until you fill it", Prefs.airProfile(context)) {
                Prefs.setAirProfile(context, it)
                if (Prefs.enabled(context)) AirGestures.announce(context, it)
            })
            addView(divider())
            addView(toggleRow("Tasker and MacroDroid", "Off unless you need it: any app could send these", Prefs.allowBroadcast(context)) {
                Prefs.setAllowBroadcast(context, it)
            })
        })
        page.addView(card {
            addView(label("Test the connection", 17f, bold = true))
            addView(label("Sends an “Index tap” to Hand Bridge the same way Air Gestures does. If your TV reacts, everything on this side works and only the Air Gestures setup is left.", 14f, p.muted), lp().margins(t = 4, b = 12))
            addView(pill("Simulate Index tap", BtnStyle.SECONDARY, Glyph.Shape.GESTURE) {
                if (!Prefs.enabled(context)) startBridge()
                AirGestures.simulate(this@MainActivity, "index_tap")
                toast("Sent. Your device should have reacted.")
            })
        })
        page.addView(pill("Reset gestures to defaults", BtnStyle.TEXT) {
            confirm("Reset gestures?", "Your custom assignments will be replaced with the defaults.", "Reset") {
                Prefs.resetMapping(this); rebuild()
            }
        }, lp().margins(t = 4))
        return {}
    }

    private fun startLearning() {
        Bridge.learn = { name ->
            runOnUiThread {
                val m = Prefs.mapping(this).toMutableMap()
                if (name !in m) {
                    m[name] = Action.SELECT.name
                    Prefs.setMapping(this, m)
                }
                rebuild()
                chooseAction(name, captured = true)
            }
        }
        rebuild()
    }

    private fun chooseAction(input: String, captured: Boolean = false) {
        val cur = Actions.parse(Prefs.mapping(this)[input])
        val rows = Action.values().filter { it != Action.NONE } + Action.NONE
        sheet(
            if (captured) "Got it: ${Actions.pretty(input)}" else Actions.pretty(input),
            rows.map { a ->
                Triple(if (a == Action.NONE) "Do nothing" else a.label, null as String?, actionIcon(a)) to {
                    val m = Prefs.mapping(this).toMutableMap()
                    m[input] = a.name // NONE is stored so a default stays switched off
                    Prefs.setMapping(this, m)
                    rebuild()
                }
            },
            subtitle = if (captured) "What should it do?" else null,
            selected = rows.indexOf(cur)
        )
    }

    /** Live native sensor counters on the Test tab, no device output. */
    private var nativePointerView: TextView? = null

    // ---- test -----------------------------------------------------------------------------

    /** Exact motions to use during direct-band diagnostic mode. */
    private fun nativeGestureInstruction(key: String): String = when (key) {
        "EVT_SWIPE_UP" -> "Slide thumb up on index finger"
        "EVT_SWIPE_DOWN" -> "Slide thumb down on index finger"
        "EVT_SWIPE_LEFT" -> "Slide thumb left on index finger"
        "EVT_SWIPE_RIGHT" -> "Slide thumb right on index finger"
        "EVT_INDEX_TAP" -> "Touch thumb to index once"
        "EVT_INDEX_DOUBLE" -> "Touch thumb to index twice quickly"
        "EVT_INDEX_HOLD" -> "Pinch index, hold at least 0.6s, then release"
        "EVT_MIDDLE_TAP" -> "Touch thumb to middle finger once"
        "EVT_MIDDLE_DOUBLE" -> "Touch thumb to middle finger twice quickly"
        "EVT_MIDDLE_HOLD" -> "Pinch middle finger and hold"
        "EVT_DIAL_UP" -> "Pinch index and rotate wrist clockwise"
        "EVT_DIAL_DOWN" -> "Pinch index and rotate wrist counterclockwise"
        else -> ""
    }

    private fun testGroup(key: String) = when {
        "SWIPE" in key -> "Swipes"
        "DRAG" in key -> "Drags"
        "DIAL" in key || "LEFT_RIGHT" in key || "UP_DOWN" in key -> "Dial and slides"
        else -> "Taps and holds"
    }

    /**
     * Two checks per gesture: does it arrive from the band, and can its action be carried out on the
     * connected device. Tapping a row tries the action without the band.
     */
    private fun testPage(): () -> Unit {
        val native = NativeBand.enabled(this)
        if (!native) AirGestures.sendWhileTesting = TvManager.connected || Bridge.hid(this).host != null
        val summary = label("", 17f, bold = true)
        val bar = hbox { background = Ui.rounded(p.surface2, 4) }
        page.addView(card {
            addView(summary)
            addView(bar, lp(h = Ui.dp(6)).margins(t = 10, b = 8))
            addView(label(
                if (native) "Do each gesture. Received means the band recognized it. Command queued means the app accepted it—not that the projector responded. Double taps are enabled only while this Test tab is open, so index/middle single taps may have extra recognition delay."
                else "Do each gesture on your band. Received means Hand Bridge saw it; command queued does not confirm the projector acted. Tap a row to test its assigned action.",
                14f, p.muted
            ))
            addView(divider(), lp(h = Ui.dp(1)).margins(t = 12))
            addView(toggleRow("Send actions to the connected device", "Off: recognition-only diagnostic. For native input, leave off while measuring. On: try forwarding mapped actions.", AirGestures.sendWhileTesting) {
                AirGestures.sendWhileTesting = it
            })
            addView(pill("Reset results", BtnStyle.TEXT) { GestureTests.clear(); rebuild() })
        })
        if (native) {
            val pointerSummary = label("", 14f, p.muted)
            page.addView(card {
                addView(label("Air Mouse sensor test", 17f, bold = true))
                addView(label("With the band connected: tap Start, rotate your forearm to move the imaginary cursor, pinch index to press and release. No command is sent to the projector.", 13f, p.muted), lp().margins(t = 5, b = 8))
                addView(pointerSummary, lp().margins(b = 8))
                addView(pill("Start / stop sensor-only Air Mouse", BtnStyle.SECONDARY) {
                    val ok = NativeBand.setPointer(!NativeBand.pointerTesting(), testOnly = true)
                    if (!ok && !NativeBand.pointerTesting()) toast("Connect the native band first, then try again.")
                })
                addView(pill("Clear pointer counters", BtnStyle.TEXT) { NativeBand.clearPointerTests() }, lp().margins(t = 4))
            })
            // The display refreshes from the existing main-thread 700ms ticker.
            nativePointerView = pointerSummary
            page.addView(card {
                addView(label("Not available in this native engine", 15f, bold = true))
                addView(label("Discrete index/middle drag-up/down/left/right, slider axes, and middle-finger zoom are not exposed by the imported Lumen gesture engine. Air Mouse has continuous motion instead; its movement and pinch presses are tested above.", 13f, p.muted), lp().margins(t = 6))
            })
        } else {
            nativePointerView = null
        }

        // Speed: keep Wi-Fi awake and measure the link to the TV.
        val speedText = label("", 14f, p.muted)
        page.addView(card {
            addView(label("Speed", 17f, bold = true))
            addView(toggleRow("Low-latency Wi-Fi", "Prioritizes Wi-Fi responsiveness while the screen is on and the app is foreground. Does not eliminate Air Gestures batching.", Prefs.lowLatency(context)) {
                Prefs.setLowLatency(this@MainActivity, it)
                TvManager.refreshLocks(this@MainActivity)
            })
            addView(label("Band dial/slider updates can be batched up to 250 ms by Air Gestures. This test only checks the TV's TCP connection time, not the time from your fingers to the screen.", 12f, p.muted))
            addView(speedText, lp().margins(t = 4, b = 8))
            addView(pill("Measure speed", BtnStyle.SECONDARY) {
                val host = TvManager.tv?.host ?: Prefs.tvs(this@MainActivity).firstOrNull()?.host
                if (host == null) { speedText.text = "Add a Wi-Fi TV first."; return@pill }
                speedText.text = "Measuring…"
                worker.execute {
                    val st = Latency.tcpRtt(host, 6466, 15)
                    val inApp = TvManager.lastSendDelayMs
                    val msg = when {
                        st.ok == 0 -> "Couldn't reach the TV at $host. Is it on and on this Wi-Fi?"
                        else -> {
                            val verdict = when {
                                st.ok < st.sent || st.maxMs > 150 -> "Unstable: move closer to the router, use the 5 GHz network, and keep Low-latency Wi-Fi on."
                                st.avgMs < 25 && st.maxMs < 80 -> "Fast and steady."
                                else -> "Fine, with some jitter."
                            }
                            "TCP connection to TV: average ${"%.0f".format(st.avgMs)} ms, worst ${"%.0f".format(st.maxMs)} ms, ${st.ok} of ${st.sent} answered.\n" +
                                (if (inApp >= 0) "Last key waited ${"%.1f".format(inApp)} ms inside the app.\n" else "") + verdict
                        }
                    }
                    runOnUiThread { speedText.text = msg }
                }
            })
        })

        val rows = vbox {}
        page.addView(rows)

        fun draw() {
            val map = Prefs.mapping(this)
            val order = Actions.defaults.keys.toList()
            val keys = if (native) NativeBand.GESTURES.map { "EVT_" + it.uppercase() } else
                (map.keys + GestureTests.keys()).filter { Actions.isBand(it) && it != "EVT_TEST" }.distinct()
                    .sortedWith(compareBy({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
            val seen = GestureTests.seenFromBand(keys)
            summary.text = "$seen of ${keys.size} ${if (native) "native" else ""} gesture types recognized"
            bar.removeAllViews()
            bar.addView(View(this).apply { background = Ui.rounded(p.accent, 4) }, lp(0, Ui.dp(6), seen.toFloat()))
            bar.addView(View(this), lp(0, Ui.dp(6), (keys.size - seen).coerceAtLeast(0).toFloat()))
            val target = TvManager.connectedTv()?.name ?: Bridge.hid(this).host?.name ?: "this phone"

            rows.removeAllViews()
            listOf("Taps and holds", "Swipes", "Drags", "Dial and slides").forEach { group ->
                val inGroup = keys.filter { testGroup(it) == group }
                if (inGroup.isEmpty()) return@forEach
                rows.addView(sectionTitle(group))
                rows.addView(card {
                    setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6))
                    inGroup.forEachIndexed { n, key ->
                        if (n > 0) addView(divider())
                        val action = Actions.resolve(map, key)
                        val st = GestureTests.status(key, action)
                        val r = GestureTests.get(key)
                        val (chip, color) = when (st) {
                            GestureTests.Status.NOT_TRIED -> "Not tried" to p.muted
                            GestureTests.Status.WORKS -> "✓ Forwarded" to p.good
                            GestureTests.Status.BAND_ONLY -> "✓ Recognized" to p.good
                            GestureTests.Status.BAND_NOT_SENT -> "Received, not sent" to p.warn
                            GestureTests.Status.NO_ACTION -> "No action" to p.warn
                            GestureTests.Status.DEVICE_ONLY -> "Action works" to p.accent
                            GestureTests.Status.DEVICE_FAILED -> "Couldn't send" to p.bad
                        }
                        val sub = buildString {
                            if (native) append(nativeGestureInstruction(key) + "\n")
                            append(if (action == Action.NONE) "No action assigned" else "→ ${action.label}")
                            if (r != null && r.bandCount > 0) append(" · received ${r.bandCount}× ${ago(r.bandAt)}")
                            when (r?.sent) {
                                true -> append(" · forwarded toward $target (no device acknowledgment)")
                                false -> append(" · couldn't send (nothing connected can do this)")
                                null -> if (r != null && r.bandCount > 0) append(" · not sent (recognition only)")
                            }
                            if (r == null && action != Action.NONE) append(" · tap to try")
                        }
                        addView(listRow(Actions.pretty(key), sub, actionIcon(action), color,
                            trailing = label(chip, 13.5f, color).apply { gravity = Gravity.END; maxWidth = Ui.dp(130) }) {
                            if (action == Action.NONE) { toast("This gesture has no action. Pick one on the Gestures tab."); return@listRow }
                            val ok = Bridge.perform(this@MainActivity, action, discrete = true)
                            GestureTests.note(key, action, ok, fromBand = false)
                            if (!ok) toast("Couldn't send. Connect a device that can do this first.")
                        })
                    }
                })
            }
            if (keys.isEmpty()) rows.addView(card { addView(label("No band gestures are set up yet.", 14f, p.muted)) })
        }

        var sig = Long.MIN_VALUE
        return {
            if (native) {
                nativePointerView?.text = "Connection: ${NativeBand.phase} · Air Mouse: ${if (NativeBand.pointerTesting()) "SENSOR TEST ON" else if (NativeBand.pointerOn) "output mode" else "OFF"}\nMotion records: ${NativeBand.pointerMotionRecords} · Movement: ${"%.1f".format(NativeBand.pointerTotalMovement)}°\nPinch presses: ${NativeBand.pointerPresses} · releases: ${NativeBand.pointerReleases}"
            }
            val s = GestureTests.signature() + System.currentTimeMillis() / 5000
            if (s != sig) { sig = s; draw() }
        }
    }

    // ---- remote ---------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun remotePage(): () -> Unit {
        val hid = Bridge.hid(this)
        val host = hid.host
        val mode = Bridge.activeMode(this)
        val tvLive = TvManager.connectedTv()
        page.addView(card {
            setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6))
            addView(if (tvLive != null) listRow("Controlling ${tvLive.name}", "Wi-Fi remote", Glyph.Shape.DEVICES, p.good,
                trailing = chip("Live", p.good, dot = true))
            else if (host != null) listRow("Controlling ${host.name ?: host.address}", "${modeName(mode)} mode", modeIcon(mode), p.good,
                trailing = chip("Live", p.good, dot = true))
            else listRow("Controlling this phone", "Connect a device to control it from here", Glyph.Shape.PHONE, p.muted) { showTab(Tab.DEVICES) })
        })

        if (TvManager.isTarget()) {
            page.addView(card {
                addView(label("JMGO browser D-pad calibration", 17f, bold = true))
                addView(label("Match your physical remote: hold DOWN to move the existing browser cursor; page scrolls at the bottom. No separate cursor app. Different Android TV apps can interpret Wi-Fi presses differently.", 13f, p.muted), lp().margins(t = 6, b = 10))
                addView(toggleRow("Repeated short arrows instead of held key", "Off = START_LONG at finger down, END_LONG at release. On = repeat SHORT arrows until release. Test both with the browser open.", Prefs.dpadRepeat(this@MainActivity)) {
                    Prefs.setDpadRepeat(this@MainActivity, it)
                    TvManager.endPointerGesture()
                    rebuild()
                })
                addView(hbox {
                    addView(pill("6 presses/sec", BtnStyle.SECONDARY) {
                        Prefs.setDpadRepeatMs(this@MainActivity, 160)
                        toast("D-pad repeat speed: 6/sec")
                    }, lp(0, weight = 1f).margins(r = 4))
                    addView(pill("10 presses/sec", BtnStyle.SECONDARY) {
                        Prefs.setDpadRepeatMs(this@MainActivity, 95)
                        toast("D-pad repeat speed: 10/sec")
                    }, lp(0, weight = 1f).margins(r = 4))
                    addView(pill("15 presses/sec", BtnStyle.SECONDARY) {
                        Prefs.setDpadRepeatMs(this@MainActivity, 65)
                        toast("D-pad repeat speed: 15/sec")
                    }, lp(0, weight = 1f))
                }, lp().margins(t = 6))
                addView(hbox {
                    addView(pill("Test DOWN hold · 2 sec", BtnStyle.SECONDARY) {
                        if (TvManager.connected && TvManager.beginDpad(com.hand.bridge.atv.Keys.DOWN, false)) {
                            ui.postDelayed({ TvManager.endDpad(com.hand.bridge.atv.Keys.DOWN) }, 2000)
                            toast("Held DOWN started. Watch the browser cursor.")
                        } else toast("Connect the JMGO over Wi-Fi.")
                    }, lp(0, weight = 1f).margins(r = 6))
                    addView(pill("Test DOWN repeat · 2 sec", BtnStyle.SECONDARY) {
                        if (TvManager.connected && TvManager.beginDpad(com.hand.bridge.atv.Keys.DOWN, true, Prefs.dpadRepeatMs(this@MainActivity))) {
                            ui.postDelayed({ TvManager.endDpad(com.hand.bridge.atv.Keys.DOWN) }, 2000)
                            toast("Repeated DOWN started. Watch the browser cursor.")
                        } else toast("Connect the JMGO over Wi-Fi.")
                    }, lp(0, weight = 1f))
                }, lp().margins(t = 8))
                addView(label("Compare these two tests with holding DOWN on the physical remote in the same browser page. If neither moves the existing cursor, the browser may distinguish Bluetooth remote input from Wi-Fi remote commands.", 12f, p.muted), lp().margins(t = 8))
            })
        }

        // Trackpad
        page.addView(card {
            val pad = FrameLayout(context).apply {
                background = Ui.rounded(p.surface2, 18)
                addView(label(if (TvManager.isTarget()) "Swipe to send D-pad commands · tap to select" else "Drag to move pointer · tap to click", 13.5f, p.muted), FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                var lx = 0f; var ly = 0f; var moved = false
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; moved = false; v.parent.requestDisallowInterceptTouchEvent(true) }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = ((e.x - lx) * 0.6f).toInt()
                            val dy = ((e.y - ly) * 0.6f).toInt()
                            if (dx != 0 || dy != 0) {
                                if (TvManager.isTarget()) {
                                    val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
                                    val delta = if (horizontal) dx else dy
                                    if (kotlin.math.abs(delta) >= Ui.dp(15)) {
                                        val code = if (horizontal) {
                                            if (delta > 0) com.hand.bridge.atv.Keys.RIGHT else com.hand.bridge.atv.Keys.LEFT
                                        } else if (delta > 0) com.hand.bridge.atv.Keys.DOWN else com.hand.bridge.atv.Keys.UP
                                        val steps = (kotlin.math.abs(delta) / Ui.dp(15)).coerceIn(1, 6)
                                        moved = TvManager.continuousKey(code, steps) || moved
                                        lx = e.x; ly = e.y
                                    }
                                } else {
                                    moved = Bridge.hid(context).move(dx, dy) || moved
                                    lx = e.x; ly = e.y
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            TvManager.endPointerGesture()
                            if (e.actionMasked == MotionEvent.ACTION_UP && !moved) {
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                Bridge.perform(context, Action.SELECT)
                            }
                        }
                    }
                    true
                }
            }
            addView(pad, lp(h = Ui.dp(200)))
            addView(hbox {
                addView(pill("Click", BtnStyle.SECONDARY) { Bridge.perform(context, Action.SELECT) }, lp(0, weight = 1f).margins(r = 8))
                addView(pill("Right click", BtnStyle.SECONDARY) { Bridge.perform(context, Action.RIGHT_CLICK) }, lp(0, weight = 1f))
            }, lp().margins(t = 12))
        })

        // D-pad
        fun key(a: Action, icon: Glyph.Shape, text: String? = null, accent: Boolean = false) =
            iconKey(icon, text, accent) { Bridge.perform(this, a, discrete = true) }
        fun arrow(a: Action, icon: Glyph.Shape): View {
            val holder = key(a, icon)
            val surface = (holder as LinearLayout).getChildAt(0)
            val code = when (a) {
                Action.UP -> com.hand.bridge.atv.Keys.UP
                Action.DOWN -> com.hand.bridge.atv.Keys.DOWN
                Action.LEFT -> com.hand.bridge.atv.Keys.LEFT
                Action.RIGHT -> com.hand.bridge.atv.Keys.RIGHT
                else -> return holder
            }
            surface.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!TvManager.isTarget()) return@setOnTouchListener false
                        v.isPressed = true
                        TvManager.beginDpad(code, Prefs.dpadRepeat(this@MainActivity), Prefs.dpadRepeatMs(this@MainActivity))
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        TvManager.endDpad(code)
                        v.isPressed = false
                        true
                    }
                    else -> TvManager.isTarget()
                }
            }
            return holder
        }
        fun spacer() = View(this)
        fun row(vararg v: View) = hbox {
            gravity = Gravity.CENTER
            v.forEach { addView(it, lp(Ui.dp(76), LinearLayout.LayoutParams.WRAP_CONTENT)) }
        }
        page.addView(card {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(row(spacer(), arrow(Action.UP, Glyph.Shape.UP), spacer()))
            addView(row(arrow(Action.LEFT, Glyph.Shape.LEFT), key(Action.SELECT, Glyph.Shape.CHECK, accent = true), arrow(Action.RIGHT, Glyph.Shape.RIGHT)), lp().margins(t = 8, b = 8))
            addView(row(spacer(), arrow(Action.DOWN, Glyph.Shape.DOWN), spacer()))
            addView(divider(), lp(h = Ui.dp(1)).margins(t = 16, b = 16))
            addView(row(key(Action.BACK, Glyph.Shape.BACK, "Back"), key(Action.HOME, Glyph.Shape.HOME, "Home"), key(Action.MENU, Glyph.Shape.MENU, "Menu")))
        })

        // Media
        page.addView(card {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(row(key(Action.PREV, Glyph.Shape.PREV, "Previous"), key(Action.PLAY_PAUSE, Glyph.Shape.PLAY_PAUSE, "Play"), key(Action.NEXT, Glyph.Shape.NEXT, "Next")))
            addView(row(key(Action.VOL_DOWN, Glyph.Shape.VOL_DOWN, "Vol −"), key(Action.MUTE, Glyph.Shape.MUTE, "Mute"), key(Action.VOL_UP, Glyph.Shape.VOL_UP, "Vol +")), lp().margins(t = 12))
            addView(row(key(Action.ZOOM_OUT, Glyph.Shape.DOT, "Zoom −"), spacer(), key(Action.ZOOM_IN, Glyph.Shape.PLUS, "Zoom +")), lp().margins(t = 12))
            addView(label("Zoom keys work only where the receiving app handles zoom commands. They do not change the JMGO projector's physical image size.", 12f, p.muted))
            addView(row(spacer(), key(Action.POWER, Glyph.Shape.POWER, "Power"), spacer()), lp().margins(t = 12))
        })
        val sig = "${host?.address}|${tvLive?.id}"
        return { if ("${Bridge.hid(this).host?.address}|${TvManager.connectedTv()?.id}" != sig) rebuild() }
    }

    // ---- dialogs --------------------------------------------------------------------------

    /** Bottom-sheet style picker built from our own rows so it matches the app. */
    private fun sheet(
        title: String,
        rows: List<Pair<Triple<String, String?, Glyph.Shape>, () -> Unit>>,
        subtitle: String? = null,
        selected: Int = -1
    ) {
        dialog?.dismiss()
        val body = vbox { setPadding(Ui.dp(18), Ui.dp(20), Ui.dp(18), Ui.dp(12)) }
        body.addView(label(title, 20f, bold = true), lp().margins(l = 4))
        if (subtitle != null) body.addView(label(subtitle, 14f, p.muted), lp().margins(l = 4, t = 4))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { i, (meta, go) ->
            val (t, s, icon) = meta
            list.addView(pickerRow(t, s, icon, selected = i == selected) {
                dialog?.dismiss()
                safely(t, go)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        body.addView(ScrollView(this).apply {
            addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }, lp())
        dialog = AlertDialog.Builder(this).setView(body).create().apply {
            window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(Ui.rounded(p.surface, 26), Ui.dp(16)))
            show()
        }
    }

    /**
     * One row in a picker sheet. Built from plain views with explicit sizes: the shared listRow
     * layout collapsed its text column in the sheet.
     */
    private fun pickerRow(title: String, sub: String?, icon: Glyph.Shape, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(64)
            setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(10))
            background = Ui.ripple(Ui.rounded(android.graphics.Color.TRANSPARENT, 14), 14)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val tint = if (selected) p.accent else p.muted
        row.addView(Glyph(this, icon, tint, 22), LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)).apply { rightMargin = Ui.dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(p.text) })
        if (sub != null) texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(p.muted) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) row.addView(Glyph(this, Glyph.Shape.CHECK, p.accent, 20), LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)))
        return row
    }

    private fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        dialog?.dismiss()
        val body = vbox {
            setPadding(Ui.dp(22), Ui.dp(22), Ui.dp(22), Ui.dp(16))
            addView(label(title, 20f, bold = true))
            addView(label(message, 14.5f, p.muted), lp().margins(t = 8, b = 18))
            addView(hbox {
                gravity = Gravity.END
                addView(pill("Cancel", BtnStyle.TEXT) { dialog?.dismiss() })
                addView(pill(yes, BtnStyle.DANGER) { dialog?.dismiss(); onYes() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(l = 8))
            })
        }
        dialog = AlertDialog.Builder(this).setView(body).create().apply {
            window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(Ui.rounded(p.surface, 26), Ui.dp(16)))
            show()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Runs a user action; a failure shows a message instead of closing the app. */
    private fun safely(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            android.util.Log.e("HandBridge", "$what failed", e)
            toast("$what didn't work: ${e.javaClass.simpleName}${e.message?.let { " – $it" } ?: ""}")
        }
    }

    // ---- state ----------------------------------------------------------------------------

    private fun hasBt() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun a11yOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, GestureAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, true) }
    }

    private fun batteryOk() = svc<PowerManager>().isIgnoringBatteryOptimizations(packageName)

    // ---- permissions / settings ------------------------------------------------------------

    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(p.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (hasBt()) {
            BridgeService.start(this)
            syncAssociations()
        }
        rebuild()
    }

    private fun batteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    // ---- device association ---------------------------------------------------------------

    private val cdm get() = svc<CompanionDeviceManager>()

    /** Opens the system picker. With [address] set it only offers that already-paired device. */
    private fun associate(address: String?) {
        if (!hasBt()) { requestPerms(); return }
        val filter = BluetoothDeviceFilter.Builder().apply {
            if (address != null) setAddress(address)
        }.build()
        val req = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(address != null)
            .build()
        cdm.associate(req, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooser: IntentSender) {
                runCatching { startIntentSenderForResult(chooser, REQ_ASSOC, null, 0, 0, 0) }
            }

            override fun onFailure(error: CharSequence?) {
                toast("No device found. Make sure it's in pairing mode and close by.")
            }
        }, null)
    }

    private fun pickBonded() {
        if (!hasBt()) { requestPerms(); return }
        val bonded = svc<BluetoothManager>().adapter?.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            toast("No paired devices yet. Try “Search nearby”.")
            return
        }
        sheet("Paired devices", bonded.map { dev ->
            Triple(dev.name ?: "Unknown device", dev.address as String?, Glyph.Shape.BLUETOOTH) to { addPaired(dev) }
        }, subtitle = "Tap a device to add it. Then connect it from the Devices tab.")
    }

    /** Adds an already-paired device to our list and registers it so it can auto-connect when nearby. */
    private fun addPaired(dev: android.bluetooth.BluetoothDevice) {
        val addr = dev.address.uppercase()
        Prefs.upsert(this, Dev(addr, dev.name ?: addr, Mode.MOUSE, true))
        showTab(Tab.DEVICES)
        associate(addr) // system asks once; afterwards the phone knows this device's presence
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == reqNativeKey) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val message = runCatching {
                    val bytes = contentResolver.openInputStream(data.data!!)?.use { it.readBytes() }
                        ?: error("Could not open file")
                    NativeBand.importKey(this, bytes)
                }.getOrElse { "Import failed: ${it.message}" }
                toast(message)
                rebuild()
            }
            return
        }
        if (requestCode == REQ_ASSOC && resultCode == RESULT_OK) {
            syncAssociations()
            showTab(Tab.DEVICES)
        }
    }

    /** Mirrors the system's companion associations into our device list and starts presence watching. */
    private fun syncAssociations() {
        if (!hasBt()) return
        try { syncAssociationsUnsafe() } catch (e: Exception) { android.util.Log.e("HandBridge", "association sync failed", e) }
    }

    private fun syncAssociationsUnsafe() {
        val found = mutableMapOf<String, String>()
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.myAssociations.forEach { a ->
                a.deviceMacAddress?.toString()?.uppercase()?.let { found[it] = a.displayName?.toString() ?: it }
            }
        } else {
            val bt = svc<BluetoothManager>().adapter
            cdm.associations.forEach { mac ->
                val m = mac.uppercase()
                found[m] = runCatching { bt?.getRemoteDevice(m)?.name }.getOrNull() ?: m
            }
        }
        val known = Prefs.devices(this).map { it.address }.toSet()
        found.forEach { (mac, name) ->
            if (mac !in known) Prefs.upsert(this, Dev(mac, name, Mode.MOUSE, true))
        }
        Prefs.devices(this).forEach { d ->
            if (d.address in found) watch(d.address) else if (found.isNotEmpty() || Build.VERSION.SDK_INT >= 33) {
                // Association was removed in system settings: forget it here too.
                Prefs.remove(this, d.address)
            }
        }
    }

    private fun watch(address: String) {
        runCatching { cdm.startObservingDevicePresence(address) }
    }

    private fun forget(d: Dev) {
        runCatching { cdm.stopObservingDevicePresence(d.address) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.filter { it.deviceMacAddress?.toString().equals(d.address, true) }
                    .forEach { cdm.disassociate(it.id) }
            } else cdm.disassociate(d.address)
        }
        Prefs.remove(this, d.address)
        rebuild()
    }

    companion object {
        const val REQ_PERMS = 1
        const val REQ_ASSOC = 2
        const val AIR_GESTURES_URL = "https://gitlab.com/896kb/air-gestures-android/-/releases"
    }
}
