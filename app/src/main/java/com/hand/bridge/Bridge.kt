package com.hand.bridge

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.hand.bridge.atv.Keys

/** Process-wide router: input name -> Action -> whichever output is currently active. */
object Bridge {
    @Volatile private var hidInstance: HidBridge? = null

    /** When set, the next input name is handed here instead of being acted on (the "Learn" button). */
    @Volatile var learn: ((String) -> Unit)? = null
    @Volatile var lastInput: String = "-"
        set(v) { field = v; lastAt = System.currentTimeMillis() }
    @Volatile var lastAt: Long = 0L
        private set

    fun hid(ctx: Context): HidBridge =
        hidInstance ?: synchronized(this) {
            hidInstance ?: HidBridge(ctx.applicationContext).also { hidInstance = it }
        }

    fun activeMode(ctx: Context): Mode {
        val host = hid(ctx).host ?: return Mode.LOCAL
        return Prefs.device(ctx, host.address)?.mode ?: Mode.MOUSE
    }

    /** True if we should swallow this input rather than let the phone treat it normally. */
    fun claims(ctx: Context, input: String): Boolean {
        if (learn != null) return true
        if (!Prefs.enabled(ctx)) return false
        val action = Actions.resolve(Prefs.mapping(ctx), input)
        if (action == Action.NONE) return false
        if (action == Action.POWER) return TvManager.connected || Prefs.tvs(ctx).isNotEmpty()
        if (TvManager.isTarget()) return true // a Wi-Fi TV understands every action
        return if (activeMode(ctx) == Mode.LOCAL) action in LOCAL_ACTIONS else true
    }

    /**
     * Returns true if the input was consumed. [repeat] is 0 for the first press, then 1, 2... while held.
     * [discrete] marks one-shot events (Air Gestures swipes/drags) that have no key-repeat, so a
     * pointer move uses a larger step instead of the slow first step of a held key.
     */
    fun handle(ctx: Context, input: String, repeat: Int = 0, discrete: Boolean = false): Boolean {
        lastInput = input
        learn?.let { learn = null; it(input); return true } // one capture, even for a multi-step dial
        if (!claims(ctx, input)) return false
        val action = Actions.resolve(Prefs.mapping(ctx), input)
        return perform(ctx, action, repeat, discrete)
    }

    fun perform(ctx: Context, action: Action, repeat: Int = 0, discrete: Boolean = false): Boolean {
        if (repeat > 0 && !action.repeats) return true
        if (action == Action.POWER) return TvManager.power(ctx)
        if (TvManager.isTarget()) {
            if (repeat > 0 && repeat % 3 != 0) return true // a held key repeats ~30x/s; the TV needs fewer
            return viaTv(action)
        }
        val mode = activeMode(ctx)
        return if (mode == Mode.LOCAL) local(ctx, action)
        else viaHid(hid(ctx), mode, action, if (discrete) DISCRETE_REPEAT else repeat)
    }

    private const val DISCRETE_REPEAT = 17 // ~40 px per event

    /** A slide of [n] steps. On a TV the presses are spaced out so each step shows; elsewhere they repeat. */
    fun handleSteps(ctx: Context, input: String, n: Int): Boolean {
        if (n <= 1 || learn != null) return handle(ctx, input, discrete = true)
        lastInput = input
        if (!claims(ctx, input)) return false
        val action = Actions.resolve(Prefs.mapping(ctx), input)
        if (TvManager.isTarget()) {
            val code = tvKey(action)
            if (code != null) return TvManager.keys(code, n, if (action == Action.VOL_UP || action == Action.VOL_DOWN) 25L else 35L)
        }
        var any = false
        repeat(n) { if (perform(ctx, action, 0, discrete = true)) any = true }
        return any
    }

    private fun viaTv(a: Action): Boolean = tvKey(a)?.let { TvManager.key(it) } ?: false

    private fun tvKey(a: Action): Int? {
        return when (a) {
            Action.UP -> Keys.UP
            Action.DOWN -> Keys.DOWN
            Action.LEFT -> Keys.LEFT
            Action.RIGHT -> Keys.RIGHT
            Action.SELECT -> Keys.CENTER
            Action.RIGHT_CLICK, Action.MENU -> Keys.MENU
            Action.BACK -> Keys.BACK
            Action.HOME -> Keys.HOME
            Action.PLAY_PAUSE -> Keys.PLAY_PAUSE
            Action.NEXT -> Keys.NEXT
            Action.PREV -> Keys.PREVIOUS
            Action.VOL_UP -> Keys.VOLUME_UP
            Action.VOL_DOWN -> Keys.VOLUME_DOWN
            Action.MUTE -> Keys.VOLUME_MUTE
            Action.SCROLL_UP -> Keys.PAGE_UP
            Action.SCROLL_DOWN -> Keys.PAGE_DOWN
            Action.POWER, Action.NONE -> null
        }
    }

    private val LOCAL_ACTIONS = setOf(
        Action.BACK, Action.HOME, Action.MENU, Action.PLAY_PAUSE, Action.NEXT, Action.PREV,
        Action.VOL_UP, Action.VOL_DOWN, Action.MUTE
    )

    private fun viaHid(h: HidBridge, mode: Mode, a: Action, repeat: Int): Boolean {
        val step = 6 + minOf(repeat, 30) * 2 // pointer accelerates while the key is held
        when (a) {
            Action.UP -> if (mode == Mode.MOUSE) h.move(0, -step) else h.key(HidBridge.K_UP)
            Action.DOWN -> if (mode == Mode.MOUSE) h.move(0, step) else h.key(HidBridge.K_DOWN)
            Action.LEFT -> if (mode == Mode.MOUSE) h.move(-step, 0) else h.key(HidBridge.K_LEFT)
            Action.RIGHT -> if (mode == Mode.MOUSE) h.move(step, 0) else h.key(HidBridge.K_RIGHT)
            Action.SELECT -> if (mode == Mode.MOUSE) h.click(HidBridge.BTN_LEFT) else h.key(HidBridge.K_ENTER)
            Action.RIGHT_CLICK ->
                if (mode == Mode.MOUSE) h.click(HidBridge.BTN_RIGHT) else h.consumer(HidBridge.C_MENU)
            Action.BACK -> h.consumer(HidBridge.C_AC_BACK)
            Action.HOME -> h.consumer(HidBridge.C_AC_HOME)
            Action.MENU -> h.consumer(HidBridge.C_MENU)
            Action.PLAY_PAUSE -> h.consumer(HidBridge.C_PLAY_PAUSE)
            Action.NEXT -> h.consumer(HidBridge.C_NEXT)
            Action.PREV -> h.consumer(HidBridge.C_PREV)
            Action.VOL_UP -> h.consumer(HidBridge.C_VOL_UP)
            Action.VOL_DOWN -> h.consumer(HidBridge.C_VOL_DOWN)
            Action.MUTE -> h.consumer(HidBridge.C_MUTE)
            Action.SCROLL_UP ->
                if (mode == Mode.MOUSE) h.mouse(0, 0, 0, 3) else h.key(HidBridge.K_PAGE_UP)
            Action.SCROLL_DOWN ->
                if (mode == Mode.MOUSE) h.mouse(0, 0, 0, -3) else h.key(HidBridge.K_PAGE_DOWN)
            Action.NONE, Action.POWER -> return false
        }
        return true
    }

    /**
     * Local mode: media/volume go to the phone's active media session and music stream, which is
     * what Android Auto (e.g. a BMW head unit) follows. Back/Home use the accessibility service.
     */
    private fun local(ctx: Context, a: Action): Boolean {
        val audio = ctx.svc<AudioManager>()
        fun media(code: Int) {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        fun vol(dir: Int) = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        val svc = GestureAccessibilityService.instance
        when (a) {
            Action.PLAY_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Action.NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            Action.PREV -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            Action.VOL_UP -> vol(AudioManager.ADJUST_RAISE)
            Action.VOL_DOWN -> vol(AudioManager.ADJUST_LOWER)
            Action.MUTE -> vol(AudioManager.ADJUST_TOGGLE_MUTE)
            Action.BACK -> svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Action.HOME -> svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            Action.MENU -> svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            else -> return false
        }
        return true
    }
}
