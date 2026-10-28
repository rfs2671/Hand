package com.hand.bridge

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * ACL_CONNECTED is exempt from implicit-broadcast limits, so this fires even when the app isn't
 * running: a remembered projector/PC/car linking up at the Bluetooth level starts the bridge.
 * Starting the foreground service from here relies on the companion-device association (or the
 * battery exemption); if Android still refuses, the CompanionDeviceService path covers it.
 */
class BluetoothEventReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != BluetoothDevice.ACTION_ACL_CONNECTED || !Prefs.enabled(c)) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        val dev = Prefs.device(c, device.address.uppercase()) ?: return
        if (!dev.auto) return
        val hid = Bridge.hid(c)
        val connect = if (dev.mode == Mode.LOCAL || hid.host != null) null else dev.address
        BridgeService.start(c, connect)
    }
}
