package com.hand.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Optional entry point for gesture apps that can fire intents (Tasker, MacroDroid, ...).
 *   action  com.hand.bridge.GESTURE
 *   extra   gesture = "pinch"            -> looked up as EVT_PINCH in the mapping table
 *   extra   action  = "SELECT"           -> performed directly (any name from the Action list)
 * Disabled until "Allow gesture broadcasts" is switched on in the app.
 */
class GestureReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!Prefs.allowBroadcast(c)) return
        i.getStringExtra("action")?.let {
            val a = Actions.parse(it.uppercase())
            if (a != Action.NONE) Bridge.perform(c, a)
            return
        }
        i.getStringExtra("gesture")?.let { gesture ->
            val key = "EVT_" + gesture.trim().uppercase().replace(' ', '_').replace('-', '_')
            val steps = i.getIntExtra("steps", 0)
            if (steps != 0) {
                val direction = if (steps > 0) Action.VOL_UP else Action.VOL_DOWN
                repeat(kotlin.math.abs(steps).coerceAtMost(20)) { Bridge.perform(c, direction) }
            } else {
                Bridge.handle(c, key)
            }
        }
    }
}
