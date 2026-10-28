package com.hand.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class Mode(val label: String) {
    MOUSE("Mouse / pointer"),
    REMOTE("Remote / D-pad"),
    LOCAL("This phone only")
}

data class Dev(val address: String, val name: String, val mode: Mode, val auto: Boolean)

object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("hand", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun allowBroadcast(c: Context) = sp(c).getBoolean("broadcast", false)
    fun setAllowBroadcast(c: Context, v: Boolean) = sp(c).edit().putBoolean("broadcast", v).apply()

    fun devices(c: Context): List<Dev> {
        val arr = JSONArray(sp(c).getString("devices", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Dev(
                o.getString("a"), o.optString("n", o.getString("a")),
                runCatching { Mode.valueOf(o.optString("m")) }.getOrDefault(Mode.MOUSE),
                o.optBoolean("auto", true)
            )
        }
    }

    fun device(c: Context, address: String) = devices(c).firstOrNull { it.address == address }

    fun upsert(c: Context, d: Dev) {
        val list = devices(c).filter { it.address != d.address } + d
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("a", it.address).put("n", it.name).put("m", it.mode.name).put("auto", it.auto))
        }
        sp(c).edit().putString("devices", arr.toString()).apply()
    }

    fun remove(c: Context, address: String) {
        val keep = devices(c).filter { it.address != address }
        sp(c).edit().remove("devices").apply()
        keep.forEach { upsert(c, it) }
    }

    /** input name (e.g. KEY_DPAD_UP, EVT_PINCH) -> Action name */
    fun mapping(c: Context): Map<String, String> {
        val s = sp(c).getString("map", null) ?: return Actions.defaults
        val o = JSONObject(s)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    fun setMapping(c: Context, m: Map<String, String>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        sp(c).edit().putString("map", o.toString()).apply()
    }

    fun resetMapping(c: Context) = sp(c).edit().remove("map").apply()
}
