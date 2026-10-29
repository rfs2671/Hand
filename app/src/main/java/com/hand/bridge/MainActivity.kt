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
        val tvs = Prefs.tvs(this)
        tvs.forEach { page.addView(tvCard(it)) }
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
        fun sig() = "${hid.host?.address}|${hid.registered}|${TvManager.state}|${TvManager.connected}"
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
            Triple("Bluetooth: let a device find this phone", "Then pair from the device's own Bluetooth menu", Glyph.Shape.PHONE) to {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                toast("After pairing, come back and choose “Choose a paired device”")
            }
        ))
    }

    // ---- Wi-Fi TVs ------------------------------------------------------------------------

    private var discovery: TvDiscovery? = null
    private var pairing: AtvPairing? = null
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "pairing").apply { isDaemon = true } }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    /** Lists Google TV / Android TV devices as they're discovered on the Wi-Fi. */
    private fun findTvs() = safely("Searching Wi-Fi") {
        dialog?.dismiss()
        stopDiscovery()
        val body = vbox { setPadding(Ui.dp(18), Ui.dp(20), Ui.dp(18), Ui.dp(12)) }
        body.addView(label("Projectors and TVs on Wi-Fi", 20f, bold = true), lp().margins(l = 4))
        val status = label("Looking… the TV must be on and on the same Wi-Fi as this phone.", 14f, p.muted)
        body.addView(status, lp().margins(l = 4, t = 4, b = 8))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(pickerRow("Enter its IP address", "If it isn't listed. It's in the TV's network settings.", Glyph.Shape.SEARCH, false) {
            dialog?.dismiss(); askIp()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val known = Prefs.tvs(this).map { it.svc }.toSet()
        val d = TvDiscovery(this) { f ->
            runOnUiThread {
                if (dialog?.isShowing != true) return@runOnUiThread
                status.text = "Tap your TV to pair with it."
                list.addView(pickerRow(f.svc + if (f.svc in known) "  ·  already added" else "", f.host, Glyph.Shape.DEVICES, false) {
                    dialog?.dismiss(); startPairing(f.svc, f.host)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        discovery = d
        dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(body) }).create().apply {
            window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(Ui.rounded(p.surface, 26), Ui.dp(16)))
            setOnDismissListener { stopDiscovery() }
            show()
        }
        d.start()
        ui.postDelayed({
            if (list.childCount == 0 && dialog?.isShowing == true) {
                status.text = "Nothing found yet. Check the TV is on and on this Wi-Fi (not a guest network), or enter its IP address."
            }
        }, 10_000)
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
                                TvManager.connect(this@MainActivity, tv)
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
        val tvLive = TvManager.connectedTv()
        page.addView(card {
            setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6))
            addView(if (tvLive != null) listRow("Controlling ${tvLive.name}", "Wi-Fi remote", Glyph.Shape.DEVICES, p.good,
                trailing = chip("Live", p.good, dot = true))
            else if (host != null) listRow("Controlling ${host.name ?: host.address}", "${modeName(mode)} mode", modeIcon(mode), p.good,
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
