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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private enum class Tab(val title: String, val icon: Glyph.Shape) {
        HOME("Home", Glyph.Shape.HOME),
        DEVICES("Devices", Glyph.Shape.DEVICES),
        GESTURES("Gestures", Glyph.Shape.GESTURE),
        REMOTE("Remote", Glyph.Shape.REMOTE)
    }

    private var tab = Tab.HOME
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var headerSub: TextView
    /** Called every tick to update the visible page in place (or rebuild it when its state changed). */
    private var refresher: (() -> Unit)? = null
    private var dialog: AlertDialog? = null

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresher?.invoke()
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
        if (hasBt() && Prefs.enabled(this)) BridgeService.start(this)
        showTab(tab)
        ticker.run()
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        Bridge.learn = null
        dialog?.dismiss()
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
        tab = t
        paintNav()
        page.removeAllViews()
        scroll.scrollTo(0, 0)
        headerSub.text = when (t) {
            Tab.HOME -> "Your band, any screen"
            Tab.DEVICES -> "What your gestures control"
            Tab.GESTURES -> "What each gesture does"
            Tab.REMOTE -> "Test and control by touch"
        }
        refresher = when (t) {
            Tab.HOME -> homePage()
            Tab.DEVICES -> devicesPage()
            Tab.GESTURES -> gesturesPage()
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

        val setupBox = vbox {}
        page.addView(setupBox)

        val lastName = label("", 20f, bold = true)
        val lastSub = label("", 14f, p.muted)
        page.addView(card {
            addView(label("LAST GESTURE", 12f, p.muted, bold = true).apply { letterSpacing = 0.08f })
            addView(lastName, lp().margins(t = 10))
            addView(lastSub, lp().margins(t = 4))
        })

        var sig = ""
        return {
            val hid = Bridge.hid(this)
            val host = hid.host
            val enabled = Prefs.enabled(this)
            val btOn = svc<BluetoothManager>().adapter?.isEnabled == true
            val state = when {
                !enabled -> "off"
                !btOn -> "bt"
                host != null -> "on:${host.address}"
                hid.registered -> "ready"
                else -> "starting"
            }
            val s = "$state|${setupSignature()}|${AirGestures.bandConnected}|${AirGestures.battery}"
            if (s != sig) {
                sig = s
                fun set(color: Int, t: String, d: String) { dot.color = color; title.text = t; sub.text = d }
                when {
                    !enabled -> set(p.muted, "Bridge is off", "Turn it on and your band controls the device you pick.")
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
            if (Bridge.lastAt == 0L) {
                lastName.text = "Nothing yet"
                lastSub.text = "Do a gesture on your band — it shows up here."
            } else {
                val input = Bridge.lastInput
                val act = Actions.parse(Prefs.mapping(this)[input])
                lastName.text = Actions.pretty(input)
                lastSub.text = (if (act == Action.NONE) "Not assigned" else "→ ${act.label}") + "  ·  " + ago(Bridge.lastAt)
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
        Step(Prefs.devices(this).isNotEmpty(), "Add a device", "Projector, TV, PC or your car", Glyph.Shape.DEVICES) { showTab(Tab.DEVICES) },
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
        if (devs.isEmpty()) {
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
        val sig = "${hid.host?.address}|${hid.registered}"
        return { if ("${hid.host?.address}|${hid.registered}" != sig) rebuild() }
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

    private fun addDevice() {
        if (!hasBt()) { requestPerms(); return }
        sheet("Add a device", listOf(
            Triple("Search nearby", "Put the device in Bluetooth pairing mode first", Glyph.Shape.SEARCH) to { associate(null) },
            Triple("Choose a paired device", "Something already in your phone's Bluetooth list", Glyph.Shape.BLUETOOTH) to { pickBonded() },
            Triple("Let a device find this phone", "Then pair from the projector or TV's own Bluetooth menu", Glyph.Shape.PHONE) to {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                toast("After pairing, come back and choose “Choose a paired device”")
            }
        ))
    }

    // ---- gestures -------------------------------------------------------------------------

    private fun actionIcon(a: Action) = when (a) {
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
            addView(toggleRow("Hand Bridge profile", "Air Gestures switches to a profile named com.hand.bridge and reports band status", Prefs.airProfile(context)) {
                Prefs.setAirProfile(context, it)
                if (Prefs.enabled(context)) AirGestures.announce(context, it)
            })
            addView(divider())
            addView(toggleRow("Tasker and MacroDroid", "Off unless you need it: any app could send these", Prefs.allowBroadcast(context)) {
                Prefs.setAllowBroadcast(context, it)
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

    // ---- remote ---------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun remotePage(): () -> Unit {
        val hid = Bridge.hid(this)
        val host = hid.host
        val mode = Bridge.activeMode(this)
        page.addView(card {
            setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6))
            addView(if (host != null) listRow("Controlling ${host.name ?: host.address}", "${modeName(mode)} mode", modeIcon(mode), p.good,
                trailing = chip("Live", p.good, dot = true))
            else listRow("Controlling this phone", "Connect a device to control it from here", Glyph.Shape.PHONE, p.muted) { showTab(Tab.DEVICES) })
        })

        // Trackpad
        page.addView(card {
            val pad = FrameLayout(context).apply {
                background = Ui.rounded(p.surface2, 18)
                addView(label("Drag to move  ·  tap to click", 13.5f, p.muted), FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                var lx = 0f; var ly = 0f; var moved = false
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; moved = false; v.parent.requestDisallowInterceptTouchEvent(true) }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = ((e.x - lx) * 0.6f).toInt()
                            val dy = ((e.y - ly) * 0.6f).toInt()
                            if (dx != 0 || dy != 0) { Bridge.hid(context).move(dx, dy); lx = e.x; ly = e.y; moved = true }
                        }
                        MotionEvent.ACTION_UP -> if (!moved) { v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); Bridge.perform(context, Action.SELECT) }
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
        fun spacer() = View(this)
        fun row(vararg v: View) = hbox {
            gravity = Gravity.CENTER
            v.forEach { addView(it, lp(Ui.dp(76), LinearLayout.LayoutParams.WRAP_CONTENT)) }
        }
        page.addView(card {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(row(spacer(), key(Action.UP, Glyph.Shape.UP), spacer()))
            addView(row(key(Action.LEFT, Glyph.Shape.LEFT), key(Action.SELECT, Glyph.Shape.CHECK, accent = true), key(Action.RIGHT, Glyph.Shape.RIGHT)), lp().margins(t = 8, b = 8))
            addView(row(spacer(), key(Action.DOWN, Glyph.Shape.DOWN), spacer()))
            addView(divider(), lp(h = Ui.dp(1)).margins(t = 16, b = 16))
            addView(row(key(Action.BACK, Glyph.Shape.BACK, "Back"), key(Action.HOME, Glyph.Shape.HOME, "Home"), key(Action.MENU, Glyph.Shape.MENU, "Menu")))
        })

        // Media
        page.addView(card {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(row(key(Action.PREV, Glyph.Shape.PREV, "Previous"), key(Action.PLAY_PAUSE, Glyph.Shape.PLAY_PAUSE, "Play"), key(Action.NEXT, Glyph.Shape.NEXT, "Next")))
            addView(row(key(Action.VOL_DOWN, Glyph.Shape.VOL_DOWN, "Vol −"), key(Action.MUTE, Glyph.Shape.MUTE, "Mute"), key(Action.VOL_UP, Glyph.Shape.VOL_UP, "Vol +")), lp().margins(t = 12))
        })
        val sig = host?.address
        return { if (Bridge.hid(this).host?.address != sig) rebuild() }
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
        val list = vbox { setPadding(0, Ui.dp(10), 0, 0) }
        rows.forEachIndexed { i, (meta, go) ->
            val (t, s, icon) = meta
            val check = if (i == selected) Glyph(this, Glyph.Shape.CHECK, p.accent, 20) else View(this)
            list.addView(listRow(t, s, icon, if (i == selected) p.accent else p.muted, trailing = check) {
                dialog?.dismiss()
                go()
            })
        }
        body.addView(ScrollView(this).apply { addView(list) }, lp())
        dialog = AlertDialog.Builder(this).setView(body).create().apply {
            window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(Ui.rounded(p.surface, 26), Ui.dp(16)))
            show()
        }
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
            Triple(dev.name ?: "Unknown device", dev.address as String?, Glyph.Shape.BLUETOOTH) to { associate(dev.address) }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_ASSOC && resultCode == RESULT_OK) {
            syncAssociations()
            showTab(Tab.DEVICES)
        }
    }

    /** Mirrors the system's companion associations into our device list and starts presence watching. */
    private fun syncAssociations() {
        if (!hasBt()) return
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
