package com.hand.bridge

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService

/**
 * The system binds this (even if the app is not running and the screen is off) when a Bluetooth
 * device we registered with CompanionDeviceManager comes into range. We then bring up the bridge
 * and connect to it as a HID device.
 */
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
class PresenceService : CompanionDeviceService() {
    override fun onDeviceAppeared(address: String) = appeared(address)

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { appeared(it.uppercase()) }
    }

    override fun onDeviceDisappeared(address: String) {}

    private fun appeared(address: String) {
        val addr = address.uppercase()
        val now = System.currentTimeMillis()
        if (addr == lastAddr && now - lastAt < 2_000) return // both callbacks may fire
        lastAddr = addr
        lastAt = now
        val dev = Prefs.device(this, addr) ?: return
        if (!dev.auto) return
        // A "This phone only" device (e.g. the car) just brings the bridge up so gestures are
        // received; there is no HID host to connect to.
        BridgeService.start(this, connect = if (dev.mode == Mode.LOCAL) null else addr)
    }

    private companion object {
        var lastAddr = ""
        var lastAt = 0L
    }
}
