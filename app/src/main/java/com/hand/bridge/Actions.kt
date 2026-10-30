package com.hand.bridge

enum class Action(val label: String) {
    NONE("(nothing)"),
    UP("Up"), DOWN("Down"), LEFT("Left"), RIGHT("Right"),
    SELECT("Select / left click"),
    RIGHT_CLICK("Right click"),
    BACK("Back"), HOME("Home"), MENU("Menu"),
    PLAY_PAUSE("Play / pause"), NEXT("Next track"), PREV("Previous track"),
    VOL_UP("Volume up"), VOL_DOWN("Volume down"), MUTE("Mute"),
    SCROLL_UP("Scroll up"), SCROLL_DOWN("Scroll down"),
    ZOOM_IN("Zoom in (supported apps)"), ZOOM_OUT("Zoom out (supported apps)"),
    POWER("Power on / off");

    /** Actions that make sense to keep firing while a key is held. */
    val repeats get() = this in setOf(UP, DOWN, LEFT, RIGHT, VOL_UP, VOL_DOWN, SCROLL_UP, SCROLL_DOWN)
}

object Actions {
    /**
     * Starting mapping. Phone volume keys are intentionally absent so the phone's own
     * buttons keep working; use "Learn gesture" to bind whatever your band/app emits.
     */
    val defaults: Map<String, String> = linkedMapOf(
        "KEY_DPAD_UP" to "UP", "KEY_DPAD_DOWN" to "DOWN",
        "KEY_DPAD_LEFT" to "LEFT", "KEY_DPAD_RIGHT" to "RIGHT",
        "KEY_DPAD_CENTER" to "SELECT", "KEY_ENTER" to "SELECT",
        "KEY_BACK" to "BACK", "KEY_ESCAPE" to "BACK",
        "KEY_MENU" to "MENU",
        "KEY_PAGE_UP" to "SCROLL_UP", "KEY_PAGE_DOWN" to "SCROLL_DOWN",
        "KEY_MEDIA_PLAY_PAUSE" to "PLAY_PAUSE", "KEY_MEDIA_NEXT" to "NEXT", "KEY_MEDIA_PREVIOUS" to "PREV",
        // Air Gestures names (dev.kb896.airgestures "gesture" extra, prefixed EVT_).
        // middle_hold is left unmapped: Air Gestures uses it to toggle controls on/off.
        "EVT_SWIPE_UP" to "UP", "EVT_SWIPE_DOWN" to "DOWN",
        "EVT_SWIPE_LEFT" to "LEFT", "EVT_SWIPE_RIGHT" to "RIGHT",
        "EVT_INDEX_TAP" to "SELECT", "EVT_INDEX_DOUBLE" to "RIGHT_CLICK",
        "EVT_INDEX_TRIPLE" to "PLAY_PAUSE", "EVT_INDEX_HOLD" to "MENU",
        "EVT_MIDDLE_TAP" to "BACK", "EVT_MIDDLE_DOUBLE" to "HOME",
        "EVT_INDEX_DRAG_UP" to "UP", "EVT_INDEX_DRAG_DOWN" to "DOWN",
        "EVT_INDEX_DRAG_LEFT" to "LEFT", "EVT_INDEX_DRAG_RIGHT" to "RIGHT",
        "EVT_INDEX_DRAG_AWAY" to "SCROLL_UP", "EVT_INDEX_DRAG_TOWARD" to "SCROLL_DOWN",
        // Dials/sliders arrive as one event with signed "steps"; each step becomes _UP or _DOWN.
        "EVT_DIAL_UP" to "VOL_UP", "EVT_DIAL_DOWN" to "VOL_DOWN",
        "EVT_MIDDLE_DIAL_UP" to "ZOOM_IN", "EVT_MIDDLE_DIAL_DOWN" to "ZOOM_OUT",
        // Slides ("scroll") send steps too: + is right / up, - is left / down.
        "EVT_INDEX_LEFT_RIGHT_UP" to "RIGHT", "EVT_INDEX_LEFT_RIGHT_DOWN" to "LEFT",
        "EVT_INDEX_UP_DOWN_UP" to "UP", "EVT_INDEX_UP_DOWN_DOWN" to "DOWN",
        "EVT_MIDDLE_LEFT_RIGHT_UP" to "RIGHT", "EVT_MIDDLE_LEFT_RIGHT_DOWN" to "LEFT",
        "EVT_MIDDLE_UP_DOWN_UP" to "UP", "EVT_MIDDLE_UP_DOWN_DOWN" to "DOWN"
    )

    /** Mapping key for a dial/slider event: "EVT_INDEX_LEFT_RIGHT" with +3 steps -> "EVT_INDEX_LEFT_RIGHT_UP". */
    fun stepKey(eventKey: String, steps: Int) = eventKey + if (steps > 0) "_UP" else "_DOWN"

    /**
     * What a key means when nothing is assigned: a slider whose name says which axis it is
     * (…LEFT_RIGHT, …UP_DOWN) still works even if it isn't in the table above.
     */
    private fun inferStep(key: String): Action? = when {
        key.endsWith("_LEFT_RIGHT_UP") || key.endsWith("_RIGHT_LEFT_DOWN") -> Action.RIGHT
        key.endsWith("_LEFT_RIGHT_DOWN") || key.endsWith("_RIGHT_LEFT_UP") -> Action.LEFT
        key.endsWith("_UP_DOWN_UP") || key.endsWith("_DOWN_UP_DOWN") -> Action.UP
        key.endsWith("_UP_DOWN_DOWN") || key.endsWith("_DOWN_UP_UP") -> Action.DOWN
        else -> null
    }

    /** The action for [key]: the user's choice, else the built-in meaning, else NONE. An explicit "nothing" wins. */
    fun resolve(map: Map<String, String>, key: String): Action =
        if (key in map) parse(map[key]) else inferStep(key) ?: Action.NONE

    /** One slide can report many steps at once; a TV needs far fewer key presses than volume does. */
    fun stepCap(a: Action) = if (a in directional || a == Action.SCROLL_UP || a == Action.SCROLL_DOWN) 6 else 20

    val directional = setOf(Action.UP, Action.DOWN, Action.LEFT, Action.RIGHT)

    /** "EVT_INDEX_TAP" -> "Index tap", "KEY_DPAD_UP" -> "Dpad up". */
    fun pretty(input: String): String {
        val step = Regex("^(EVT_.*(?:DIAL|LEFT_RIGHT|UP_DOWN))_(UP|DOWN)$").find(input)
        if (step != null) return pretty(step.groupValues[1]) + if (step.groupValues[2] == "UP") " (+)" else " (−)"
        val raw = input.removePrefix("EVT_").removePrefix("KEY_").lowercase().replace('_', ' ')
        return raw.replaceFirstChar { it.uppercase() }
    }

    fun isBand(input: String) = input.startsWith("EVT_")

    fun parse(name: String?): Action =
        runCatching { Action.valueOf(name ?: "") }.getOrDefault(Action.NONE)
}
