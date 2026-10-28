package com.hand.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Makes the phone look like a Bluetooth keyboard + mouse + media remote (a "combo" HID device)
 * so projectors, TVs, XR glasses hosts and PCs accept it as an input device.
 */
@SuppressLint("MissingPermission")
class HidBridge(context: Context) {
    private val ctx = context.applicationContext
    private val adapter: BluetoothAdapter? = ctx.svc<BluetoothManager>()?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    private var proxy: BluetoothHidDevice? = null
    private var proxyRequested = false
    private var pendingConnect: String? = null
    private var attemptGen = 0

    @Volatile var registered = false
        private set
    @Volatile var host: BluetoothDevice? = null
        private set

    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() = main.post { listeners.forEach { it() } }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidBridge.registered = registered
            if (registered) {
                host = proxy?.connectedDevices?.firstOrNull()
                pendingConnect?.let { connectWithRetry(it) }
            }
            changed()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> host = device
                BluetoothProfile.STATE_DISCONNECTED ->
                    if (host?.address == device.address) host = null
            }
            changed()
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hid = p as BluetoothHidDevice
            proxy = hid
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Hand Bridge", "Gesture bridge", "Hand",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
            )
            hid.registerApp(sdp, null, null, exec, callback)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            proxy = null
            proxyRequested = false
            registered = false
            host = null
            changed()
        }
    }

    /** Idempotent. Safe to call from any component; does nothing if Bluetooth is off. */
    @Synchronized
    fun start() {
        val a = adapter ?: return
        if (!a.isEnabled || proxyRequested || registered) return
        proxyRequested = a.getProfileProxy(ctx, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    @Synchronized
    fun stop() {
        attemptGen++
        proxy?.let {
            runCatching { it.unregisterApp() }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
        }
        proxy = null
        proxyRequested = false
        registered = false
        host = null
        changed()
    }

    /** Connect to a remembered host, retrying a few times (hosts are often still waking up). */
    fun connectWithRetry(address: String) {
        pendingConnect = address
        start()
        val gen = ++attemptGen
        val delays = longArrayOf(0, 4_000, 10_000, 30_000)
        fun attempt(i: Int) {
            if (gen != attemptGen || host?.address == address) {
                if (host?.address == address) pendingConnect = null
                return
            }
            val hid = proxy
            if (hid != null && registered) {
                val dev = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
                if (dev != null) hid.connect(dev)
            }
            if (i + 1 < delays.size) main.postDelayed({ attempt(i + 1) }, delays[i + 1])
            else pendingConnect = null
        }
        main.post { attempt(0) }
    }

    fun disconnect() {
        attemptGen++
        pendingConnect = null
        host?.let { proxy?.disconnect(it) }
    }

    // ---- reports -------------------------------------------------------------------------

    private fun send(id: Int, data: ByteArray): Boolean {
        val h = host ?: return false
        return proxy?.sendReport(h, id, data) == true
    }

    fun key(usage: Int, modifiers: Int = 0): Boolean {
        val ok = send(ID_KEYBOARD, byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        send(ID_KEYBOARD, ByteArray(8))
        return ok
    }

    fun consumer(usage: Int): Boolean {
        val ok = send(ID_CONSUMER, byteArrayOf(usage.toByte(), (usage shr 8).toByte()))
        send(ID_CONSUMER, byteArrayOf(0, 0))
        return ok
    }

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0): Boolean =
        send(
            ID_MOUSE,
            byteArrayOf(
                buttons.toByte(), dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte()
            )
        )

    fun move(dx: Int, dy: Int): Boolean {
        var x = dx
        var y = dy
        var ok = true
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            ok = mouse(0, sx, sy) && ok
            x -= sx
            y -= sy
        }
        return ok
    }

    fun click(mask: Int = BTN_LEFT): Boolean {
        val ok = mouse(mask, 0, 0)
        mouse(0, 0, 0)
        return ok
    }

    companion object {
        const val ID_KEYBOARD = 1
        const val ID_MOUSE = 2
        const val ID_CONSUMER = 3
        const val BTN_LEFT = 1
        const val BTN_RIGHT = 2

        // HID keyboard usages
        const val K_ENTER = 0x28
        const val K_PAGE_UP = 0x4B
        const val K_PAGE_DOWN = 0x4E
        const val K_RIGHT = 0x4F
        const val K_LEFT = 0x50
        const val K_DOWN = 0x51
        const val K_UP = 0x52

        // HID consumer usages
        const val C_MENU = 0x40
        const val C_PLAY_PAUSE = 0xCD
        const val C_NEXT = 0xB5
        const val C_PREV = 0xB6
        const val C_MUTE = 0xE2
        const val C_VOL_UP = 0xE9
        const val C_VOL_DOWN = 0xEA
        const val C_AC_HOME = 0x223
        const val C_AC_BACK = 0x224

        private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        val DESCRIPTOR: ByteArray = b(
            // Keyboard, report id 1
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // Mouse (3 buttons, relative x/y, wheel), report id 2
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0,
            // Consumer control (media / home / back), report id 3
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x03,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0
        )
    }
}
