package com.hand.bridge

import android.content.Context
import org.json.JSONObject

/**
 * Gesture meanings are saved separately for the car, headphones, TV, and phone.
 * Does not change Air Gestures' own automation broadcasting requirements.
 */
enum class DeviceProfile(val title: String) {
    CAR("Car"),
    HEADPHONES("Headphones"),
    TV("TV / Projector"),
    PHONE("Phone")
}

object GestureProfiles {
    private val carDefaults = linkedMapOf(
        "EVT_INDEX_TAP" to Action.PLAY_PAUSE,
        "EVT_MIDDLE_TAP" to Action.ANSWER_CALL,
        "EVT_INDEX_HOLD" to Action.END_CALL,
        "EVT_MIDDLE_DOUBLE" to Action.END_CALL,
        "EVT_SWIPE_LEFT" to Action.PREV,
        "EVT_SWIPE_RIGHT" to Action.NEXT,
        "EVT_INDEX_LEFT_RIGHT_UP" to Action.SEEK_FORWARD,
        "EVT_INDEX_LEFT_RIGHT_DOWN" to Action.SEEK_BACK,
        "EVT_MIDDLE_LEFT_RIGHT_UP" to Action.SEEK_FORWARD,
        "EVT_MIDDLE_LEFT_RIGHT_DOWN" to Action.SEEK_BACK,
        "EVT_DIAL_UP" to Action.VOL_UP,
        "EVT_DIAL_DOWN" to Action.VOL_DOWN,
        "EVT_MIDDLE_DIAL_UP" to Action.VOL_UP,
        "EVT_MIDDLE_DIAL_DOWN" to Action.VOL_DOWN
    )
    private val mediaActions = setOf(
        Action.NONE, Action.PLAY_PAUSE, Action.NEXT, Action.PREV,
        Action.VOL_UP, Action.VOL_DOWN, Action.MUTE,
        Action.SEEK_FORWARD, Action.SEEK_BACK, Action.ANSWER_CALL, Action.END_CALL
    )

    fun allowed(profile: DeviceProfile): List<Action> = when (profile) {
        DeviceProfile.CAR, DeviceProfile.HEADPHONES -> mediaActions.toList()
        DeviceProfile.TV, DeviceProfile.PHONE -> Action.values().toList()
    }

    fun active(ctx: Context): DeviceProfile = when {
        Prefs.carMode(ctx) -> OutputRouter.mediaProfile(ctx)
        TvManager.selectedTarget(ctx) -> DeviceProfile.TV
        else -> DeviceProfile.PHONE
    }

    private fun store(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("hand_gesture_profiles", Context.MODE_PRIVATE)

    private fun overrides(ctx: Context, profile: DeviceProfile): Map<String, Action> {
        val json = store(ctx).getString(profile.name, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { key -> Actions.parse(obj.optString(key)) }
        }.getOrElse { emptyMap() }
    }

    fun choices(ctx: Context, profile: DeviceProfile): Map<String, Action> {
        val defaults = when (profile) {
            DeviceProfile.CAR, DeviceProfile.HEADPHONES -> carDefaults
            DeviceProfile.TV, DeviceProfile.PHONE ->
                Prefs.mapping(ctx).filterKeys { Actions.isBand(it) }
                    .mapValues { Actions.parse(it.value) }
        }
        return defaults + overrides(ctx, profile)
    }

    fun resolve(ctx: Context, profile: DeviceProfile, key: String): Action {
        if (!Actions.isBand(key)) {
            return if (profile == DeviceProfile.CAR || profile == DeviceProfile.HEADPHONES)
                Action.NONE else Actions.resolve(Prefs.mapping(ctx), key)
        }
        overrides(ctx, profile)[key]?.let { return it }
        return when (profile) {
            DeviceProfile.CAR, DeviceProfile.HEADPHONES -> carDefaults[key] ?: Action.NONE
            DeviceProfile.TV, DeviceProfile.PHONE -> Actions.resolve(Prefs.mapping(ctx), key)
        }
    }

    fun set(ctx: Context, profile: DeviceProfile, key: String, action: Action) {
        require(Actions.isBand(key)) { "Only wristband gestures can be edited here" }
        require(action in allowed(profile)) { "This action is not available on this device" }
        val map = overrides(ctx, profile).toMutableMap()
        map[key] = action
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v.name) }
        store(ctx).edit().putString(profile.name, json.toString()).apply()
    }

    fun reset(ctx: Context, profile: DeviceProfile) {
        store(ctx).edit().remove(profile.name).apply()
    }
}
