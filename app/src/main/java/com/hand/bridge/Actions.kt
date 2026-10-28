package com.hand.bridge

enum class Action(val label: String) {
    NONE("(nothing)"),
    UP("Up"), DOWN("Down"), LEFT("Left"), RIGHT("Right"),
    SELECT("Select / left click"),
    RIGHT_CLICK("Right click"),
    BACK("Back"), HOME("Home"), MENU("Menu"),
    PLAY_PAUSE("Play / pause"), NEXT("Next track"), PREV("Previous track"),
    VOL_UP("Volume up"), VOL_DOWN("Volume down"), MUTE("Mute"),
    SCROLL_UP("Scroll up"), SCROLL_DOWN("Scroll down");

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
        "EVT_PINCH" to "SELECT", "EVT_DOUBLE_PINCH" to "BACK",
        "EVT_SWIPE_UP" to "UP", "EVT_SWIPE_DOWN" to "DOWN",
        "EVT_SWIPE_LEFT" to "LEFT", "EVT_SWIPE_RIGHT" to "RIGHT"
    )

    fun parse(name: String?): Action =
        runCatching { Action.valueOf(name ?: "") }.getOrDefault(Action.NONE)
}
