package com.hand.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Optional entry point for automation apps (Tasker, MacroDroid, ...). Send a broadcast with
 * package com.hand.bridge (an implicit broadcast without a package is not delivered):
 *   action  com.hand.bridge.GESTURE
 *   extra   gesture = "pinch"            -> looked up as EVT_PINCH in the mapping table
 *   extra   steps   = 3 / -3             -> EVT_PINCH_UP / EVT_PINCH_DOWN, once per step
 *   extra   action  = "SELECT"           -> performed directly (any name from the Action list)
 * Disabled until "Allow Tasker / MacroDroid intents" is switched on in the app.
 * Air Gestures broadcasts are handled separately by the receiver registered in [BridgeService].
 */
class GestureReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!Prefs.allowBroadcast(c)) return
        i.getStringExtra("action")?.let {
            val a = Actions.parse(it.uppercase())
            if (a != Action.NONE) Bridge.perform(c, a, discrete = true)
            return
        }
        AirGestures.dispatch(c, i)
    }
}
