package com.hand.bridge

import android.accessibilityservice.AccessibilityService
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Receives key events from the band / air-gesture app (any external input device) before apps do. */
class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Bridge.hid(this).start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (isPhoneOwnKey(event)) return false
        val name = "KEY_" + KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
        return if (event.action == KeyEvent.ACTION_DOWN) {
            Bridge.handle(this, name, event.repeatCount)
        } else {
            Bridge.lastInput = name
            Bridge.claims(this, name)
        }
    }

    /** Don't hijack the phone's hardware buttons (volume rocker, power, built-in keypads). */
    private fun isPhoneOwnKey(e: KeyEvent): Boolean {
        val d = InputDevice.getDevice(e.deviceId) ?: return false
        if (d.isVirtual) return false // synthetic input from other apps is fair game
        val n = d.name.lowercase()
        return BUILTIN.any { n.contains(it) }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: GestureAccessibilityService? = null
        private val BUILTIN = listOf("gpio", "qpnp", "pwrkey", "power", "touchkey", "keypad", "fingerprint", "uinput-")
    }
}
