package com.hand.bridge

import android.content.Context
import com.hand.bridge.atv.Identity
import org.json.JSONArray
import org.json.JSONObject

enum class Mode(val label: String) {
    MOUSE("Mouse / pointer"),
    REMOTE("Remote / D-pad"),
    LOCAL("This phone only")
}

data class Dev(val address: String, val name: String, val mode: Mode, val auto: Boolean)

/** A Google TV / Android TV controlled over Wi-Fi. [pin] is the SHA-256 of its certificate. */
data class Tv(
    val id: String, val name: String, val host: String, val mac: String?,
    val pin: String, val auto: Boolean, val svc: String
)

object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("hand", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** Tasker/MacroDroid intents (com.hand.bridge.GESTURE). Opt-in: any app could send them. */
    fun allowBroadcast(c: Context) = sp(c).getBoolean("broadcast", false)
    fun setAllowBroadcast(c: Context, v: Boolean) = sp(c).edit().putBoolean("broadcast", v).apply()

    /** Air Gestures broadcasts (dev.kb896.airgestures.GESTURE). On by default; sender is checked. */
    fun airGestures(c: Context) = sp(c).getBoolean("airgestures", true)
    fun setAirGestures(c: Context, v: Boolean) = sp(c).edit().putBoolean("airgestures", v).apply()

    /** Ask Air Gestures to switch to a profile named after this app, and receive band status. */
    fun airProfile(c: Context) = sp(c).getBoolean("airprofile", false)
    fun setAirProfile(c: Context, v: Boolean) = sp(c).edit().putBoolean("airprofile", v).apply()

    fun lastHost(c: Context) = sp(c).getString("lasthost", null)
    fun setLastHost(c: Context, v: String) = sp(c).edit().putString("lasthost", v).apply()

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
        // Saved choices win; defaults added in later versions still show up. "NONE" disables an input.
        return Actions.defaults + o.keys().asSequence().associateWith { o.getString(it) }
    }

    fun setMapping(c: Context, m: Map<String, String>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        sp(c).edit().putString("map", o.toString()).apply()
    }

    fun resetMapping(c: Context) = sp(c).edit().remove("map").apply()

    // ---- Wi-Fi TVs ---------------------------------------------------------------------------

    fun tvs(c: Context): List<Tv> {
        val arr = JSONArray(sp(c).getString("tvs", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Tv(
                o.getString("id"), o.getString("name"), o.getString("host"),
                o.optString("mac").takeIf { m -> m.isNotEmpty() }, o.getString("pin"),
                o.optBoolean("auto", true), o.optString("svc", o.getString("name"))
            )
        }
    }

    fun tv(c: Context, id: String) = tvs(c).firstOrNull { it.id == id }

    fun upsertTv(c: Context, t: Tv) {
        val arr = JSONArray()
        (tvs(c).filter { it.id != t.id } + t).forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("mac", it.mac ?: "")
                    .put("pin", it.pin).put("auto", it.auto).put("svc", it.svc)
            )
        }
        sp(c).edit().putString("tvs", arr.toString()).apply()
    }

    fun removeTv(c: Context, id: String) {
        val keep = tvs(c).filter { it.id != id }
        sp(c).edit().remove("tvs").apply()
        keep.forEach { upsertTv(c, it) }
    }

    fun lastTv(c: Context) = sp(c).getString("lasttv", null)
    fun setLastTv(c: Context, id: String) = sp(c).edit().putString("lasttv", id).apply()

    /** Our client certificate, created on first use. TVs remember it after pairing, so it must persist. */
    @Synchronized
    fun identity(c: Context): Identity {
        val cert = sp(c).getString("tv_cert", null)
        val key = sp(c).getString("tv_key", null)
        if (cert != null && key != null) return Identity.fromBase64(cert, key)
        val id = Identity.generate()
        sp(c).edit().putString("tv_cert", id.certBase64()).putString("tv_key", id.keyBase64()).apply()
        return id
    }
}
