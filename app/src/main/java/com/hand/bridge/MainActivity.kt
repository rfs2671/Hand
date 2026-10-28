package com.hand.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var setup: TextView
    private lateinit var lastInput: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var mapBox: LinearLayout
    private lateinit var learnBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 700)
        }
    }

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(40), px(16), px(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        build()
    }

    override fun onResume() {
        super.onResume()
        syncAssociations()
        renderLists()
        ticker.run()
        if (hasBt() && Prefs.enabled(this)) BridgeService.start(this)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        stopLearning()
        super.onPause()
    }

    // ---- layout ---------------------------------------------------------------------------

    private fun text(s: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(4), 0, px(4))
    }

    private fun section(title: String) {
        root.addView(text(title, 18f, true).apply { setPadding(0, px(22), 0, px(4)) })
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun build() {
        root.addView(text("Hand Bridge", 26f, true))
        status = text("")
        root.addView(status)

        section("1 · Setup")
        setup = text("")
        root.addView(setup)
        root.addView(button("Allow Bluetooth + notifications") { requestPerms() })
        root.addView(button("Turn on accessibility service (reads your band's keys)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Stop Android killing the bridge (battery)") { batteryExemption() })
        root.addView(Switch(this).apply {
            text = "Bridge running (stays on with screen off, restarts at boot)"
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (!hasBt()) { isChecked = false; requestPerms(); return@setOnCheckedChangeListener }
                    BridgeService.start(this@MainActivity)
                } else BridgeService.stop(this@MainActivity)
            }
        })
        root.addView(Switch(this).apply {
            text = "Allow gesture broadcasts (Tasker / MacroDroid)"
            isChecked = Prefs.allowBroadcast(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAllowBroadcast(this@MainActivity, on) }
        })

        section("2 · Devices to control")
        root.addView(text("Projector, TV, XR-glasses host, laptop… Remembered devices reconnect on their own when they come into range."))
        root.addView(button("＋ Find and add a nearby device") { associate(null) })
        root.addView(button("＋ Add an already-paired device") { pickBonded() })
        root.addView(button("Make this phone discoverable (2 min)") {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        })
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesBox)
        root.addView(text("Car (BMW / Android Auto): no pairing needed. When no device above is connected the bridge works in “This phone only” mode: play/pause, next/prev, volume, back/home go to the phone, which Android Auto follows."))

        section("3 · Gestures")
        lastInput = text("")
        root.addView(lastInput)
        learnBtn = button("Learn a gesture") { toggleLearn() }
        root.addView(learnBtn)
        mapBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(mapBox)
        root.addView(button("Reset gesture list to defaults") {
            Prefs.resetMapping(this); renderLists()
        })

        section("4 · Test pad")
        root.addView(text("Drag to move the pointer on the connected device."))
        root.addView(trackpad())
        val row1 = row()
        row1.addView(smallBtn("Click", Action.SELECT))
        row1.addView(smallBtn("Right", Action.RIGHT_CLICK))
        row1.addView(smallBtn("Back", Action.BACK))
        row1.addView(smallBtn("Home", Action.HOME))
        root.addView(row1)
        val row2 = row()
        row2.addView(smallBtn("Vol−", Action.VOL_DOWN))
        row2.addView(smallBtn("Vol+", Action.VOL_UP))
        row2.addView(smallBtn("Play", Action.PLAY_PAUSE))
        row2.addView(smallBtn("Next", Action.NEXT))
        root.addView(row2)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun smallBtn(label: String, a: Action) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { Bridge.perform(this@MainActivity, a) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun trackpad() = View(this).apply {
        setBackgroundColor(0x33888888)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(180))
        var lx = 0f
        var ly = 0f
        var moved = false
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; moved = false; v.parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ((e.x - lx) * 0.6f).toInt()
                    val dy = ((e.y - ly) * 0.6f).toInt()
                    if (dx != 0 || dy != 0) {
                        Bridge.hid(context).move(dx, dy)
                        lx = e.x; ly = e.y; moved = true
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) Bridge.perform(context, Action.SELECT)
            }
            true
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

    private fun refreshStatus() {
        val hid = Bridge.hid(this)
        val host = hid.host
        status.text = when {
            host != null -> "● Connected to ${host.name ?: host.address} (${Prefs.device(this, host.address)?.mode?.label ?: Mode.MOUSE.label})"
            hid.registered -> "○ Ready. Waiting for a device. Gestures act on this phone."
            Prefs.enabled(this) -> "○ Starting Bluetooth…"
            else -> "○ Bridge is off"
        }
        fun ok(b: Boolean) = if (b) "✔" else "✘"
        setup.text = "${ok(hasBt())} Bluetooth permission   ${ok(a11yOn())} Accessibility service   ${ok(batteryOk())} Battery exemption"
        lastInput.text = "Last input seen: ${Bridge.lastInput}"
    }

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
        refreshStatus()
    }

    private fun batteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    // ---- devices ---------------------------------------------------------------------------

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
                Toast.makeText(this@MainActivity, "Couldn't find a device: ${error ?: "unknown"}", Toast.LENGTH_LONG).show()
            }
        }, null)
    }

    private fun pickBonded() {
        if (!hasBt()) { requestPerms(); return }
        val bonded = svc<BluetoothManager>().adapter?.bondedDevices?.toList().orEmpty()
        if (bonded.isEmpty()) {
            Toast.makeText(this, "No paired devices yet. Use “Find and add” instead.", Toast.LENGTH_LONG).show()
            return
        }
        val names = bonded.map { "${it.name ?: "?"}  (${it.address})" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Paired devices")
            .setItems(names) { _, i -> associate(bonded[i].address) }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_ASSOC && resultCode == RESULT_OK) {
            syncAssociations()
            renderLists()
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
        renderLists()
    }

    // ---- lists -----------------------------------------------------------------------------

    private fun renderLists() {
        devicesBox.removeAllViews()
        val devs = Prefs.devices(this)
        if (devs.isEmpty()) devicesBox.addView(text("No devices yet."))
        devs.forEach { d ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(8), 0, px(8))
            }
            card.addView(text("${d.name}  ·  ${d.address}", 15f, true))
            val modes = Mode.values()
            card.addView(Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes.map { it.label })
                setSelection(modes.indexOf(d.mode))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (modes[pos] != d.mode) Prefs.upsert(this@MainActivity, d.copy(mode = modes[pos]))
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            })
            card.addView(Switch(this).apply {
                text = "Connect automatically when in range"
                isChecked = d.auto
                setOnCheckedChangeListener { _, on -> Prefs.upsert(this@MainActivity, d.copy(auto = on)) }
            })
            val r = row()
            r.addView(Button(this).apply {
                text = "Connect now"; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { BridgeService.start(this@MainActivity, connect = d.address) }
            })
            r.addView(Button(this).apply {
                text = "Disconnect"; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { Bridge.hid(this@MainActivity).disconnect() }
            })
            r.addView(Button(this).apply {
                text = "Forget"; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { forget(d) }
            })
            card.addView(r)
            devicesBox.addView(card)
        }

        mapBox.removeAllViews()
        val actions = Action.values()
        Prefs.mapping(this).toSortedMap().forEach { (input, actionName) ->
            val r = row().apply { setPadding(0, px(2), 0, px(2)) }
            r.addView(text(input).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            r.addView(Spinner(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, actions.map { it.label })
                val cur = Actions.parse(actionName)
                setSelection(actions.indexOf(cur))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (actions[pos] == cur) return
                        val m = Prefs.mapping(this@MainActivity).toMutableMap()
                        if (actions[pos] == Action.NONE) m.remove(input) else m[input] = actions[pos].name
                        Prefs.setMapping(this@MainActivity, m)
                        renderLists()
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            })
            mapBox.addView(r)
        }
    }

    private fun toggleLearn() {
        if (Bridge.learn != null) { stopLearning(); return }
        learnBtn.text = "Waiting… do the gesture now (tap to cancel)"
        Bridge.learn = { name ->
            runOnUiThread {
                stopLearning()
                val m = Prefs.mapping(this).toMutableMap()
                if (name !in m) m[name] = Action.SELECT.name
                Prefs.setMapping(this, m)
                Toast.makeText(this, "Captured $name — pick what it does", Toast.LENGTH_LONG).show()
                renderLists()
            }
        }
    }

    private fun stopLearning() {
        Bridge.learn = null
        if (::learnBtn.isInitialized) learnBtn.text = "Learn a gesture"
    }

    companion object {
        const val REQ_PERMS = 1
        const val REQ_ASSOC = 2
    }
}
