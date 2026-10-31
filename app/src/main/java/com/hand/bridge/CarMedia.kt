package com.hand.bridge

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.service.notification.NotificationListenerService

/**
 * Optional, direct control of the Pixel's active Android media session.
 *
 * Android Auto does not expose its head unit as a controllable media session: the
 * transport commands target phone players such as YouTube Music. Notification
 * listener access is opt-in. If absent, media keys remain best-effort; seeking
 * is unavailable, rather than accidentally issuing next-track commands.
 */
object CarMedia {
    @Volatile var lastStatus = "Not tested"
        private set

    private var lastSeekSession: String? = null
    private var lastSeekTrack: String? = null
    private var lastSeekAt = 0L
    private var lastSeekTarget = 0L

    private const val SEEK_STEP_MS = 5_000L
    private const val SEEK_CACHE_MS = 1_200L

    /** Open this app's notification-listener permission, not ordinary app Notifications. */
    fun permissionIntent(ctx: Context) =
        android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(ctx, CarMediaListener::class.java)
            )
        }

    /** Fallback for Android builds without a listener-detail settings activity. */
    fun permissionListIntent() = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun allowed(ctx: Context): Boolean {
        val component = ComponentName(ctx, CarMediaListener::class.java)
        return runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component) == true
        }.getOrDefault(false)
    }

    private fun controllers(ctx: Context): List<MediaController> {
        if (!allowed(ctx)) return emptyList()
        return runCatching {
            ctx.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(ctx, CarMediaListener::class.java)).orEmpty()
        }.onFailure { Trace.add("car media: sessions unavailable: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
    }

    /** Avoid sending commands to WhatsApp's incidental media session. */
    private fun target(ctx: Context): MediaController? {
        val sessions = controllers(ctx)
        val chosen = sessions.firstOrNull { it.packageName == "com.google.android.apps.youtube.music" }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        return chosen?.takeIf { it.packageName !in setOf("com.whatsapp", "com.whatsapp.w4b") }
    }

    fun activePlayer(ctx: Context): String =
        target(ctx)?.packageName ?: if (!allowed(ctx)) "Media access not granted" else "No active music session"

    /** Copyable, concrete diagnosis for car control without exposing song names/messages. */
    fun diagnosis(ctx: Context): String = buildString {
        appendLine("Hand Bridge car-media diagnostic")
        appendLine("Car mode: ${Prefs.carMode(ctx)}")
        appendLine("Media access: ${allowed(ctx)}")
        val sessions = controllers(ctx)
        appendLine("Visible active sessions: ${sessions.size}")
        sessions.forEach { controller ->
            val state = controller.playbackState
            val actions = state?.actions ?: 0L
            appendLine("Player: ${controller.packageName}, state=${state?.state ?: "none"}, position=${state?.position ?: -1L}ms, seekTo=${(actions and PlaybackState.ACTION_SEEK_TO) != 0L}, forward=${(actions and PlaybackState.ACTION_FAST_FORWARD) != 0L}, rewind=${(actions and PlaybackState.ACTION_REWIND) != 0L}")
        }
        appendLine("Selected player: ${activePlayer(ctx)}")
        appendLine("Last result: $lastStatus")
        appendLine("Limitation: without a retained media session, Android media keys cannot guarantee starting an inactive player")
    }

    fun media(ctx: Context, action: Action): Boolean {
        val session = target(ctx)
        if (session != null) {
            try {
                val transport = session.transportControls
                when (action) {
                    Action.PLAY_PAUSE -> {
                        if (session.playbackState?.state == PlaybackState.STATE_PLAYING) transport.pause()
                        else transport.play()
                    }
                    Action.NEXT -> transport.skipToNext()
                    Action.PREV -> transport.skipToPrevious()
                    else -> return false
                }
                lastStatus = "${action.label} requested from ${session.packageName} (confirm in player)"
                Trace.add("car media: $lastStatus")
                return true
            } catch (e: Exception) {
                Trace.add("car media: direct command failed ${e.javaClass.simpleName}")
            }
        }
        // Legacy media keys can control a playing app without notification-listener access.
        // They are not proof that an inactive music app started successfully.
        val key = when (action) {
            // No active media session exists, so a PLAY request is less ambiguous
            // than PLAY_PAUSE. Android can still ignore this if the player is dormant.
            Action.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY
            Action.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            Action.PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return false
        }
        return try {
            val audio = ctx.getSystemService(AudioManager::class.java) ?: return false
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            lastStatus = "Sent ${if (action == Action.PLAY_PAUSE) "PLAY" else "media"} key; unconfirmed. ${if (!allowed(ctx)) "Media access is off." else "No controllable music session: start YouTube Music once to activate it."}"
            Trace.add("car media: $lastStatus")
            true
        } catch (e: Exception) {
            lastStatus = "Media key failed: ${e.javaClass.simpleName}"
            Trace.add("car media: $lastStatus")
            false
        }
    }

    /** Slider steps: 5 seconds each, at most 30 seconds per Air Gestures broadcast. */
    @Synchronized fun seek(ctx: Context, signedSteps: Int): Boolean {
        val session = target(ctx)
        if (session == null) {
            lastStatus = if (allowed(ctx)) "No active supported music player for seeking"
                         else "Allow media access in Devices to enable song seeking"
            Trace.add("car seek: $lastStatus")
            return false
        }
        val state = session.playbackState
        if (state == null) {
            lastStatus = "${session.packageName}: playback state is missing; start a song in the music app first"
            Trace.add("car seek: $lastStatus")
            return false
        }
        if ((state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) {
            val fallback = if (signedSteps > 0) PlaybackState.ACTION_FAST_FORWARD else PlaybackState.ACTION_REWIND
            if ((state.actions and fallback) != 0L) {
                return try {
                    if (signedSteps > 0) session.transportControls.fastForward()
                    else session.transportControls.rewind()
                    lastStatus = "${session.packageName}: requested ${if (signedSteps > 0) "fast-forward" else "rewind"}; player controls distance (not fixed 5 seconds)"
                    Trace.add("car seek: $lastStatus")
                    true
                } catch (e: Exception) {
                    lastStatus = "${session.packageName}: seek fallback failed (${e.javaClass.simpleName})"
                    Trace.add("car seek: $lastStatus")
                    false
                }
            }
            lastStatus = "${session.packageName}: this playback session does not support seeking (seekTo/forward/rewind unavailable)"
            Trace.add("car seek: $lastStatus")
            return false
        }
        val token = session.sessionToken.toString()
        val track = session.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?: session.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val now = SystemClock.elapsedRealtime()
        var position = state.position.coerceAtLeast(0L)
        if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = now - state.lastPositionUpdateTime
            if (elapsed in 0..60_000L) position += (elapsed * state.playbackSpeed).toLong()
        }
        if (token == lastSeekSession && track == lastSeekTrack && now - lastSeekAt in 0..SEEK_CACHE_MS)
            position = lastSeekTarget
        val delta = signedSteps.coerceIn(-6, 6).toLong() * SEEK_STEP_MS
        val duration = session.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        val next = if (duration > 0L) (position + delta).coerceIn(0L, duration)
                   else (position + delta).coerceAtLeast(0L)
        return try {
            session.transportControls.seekTo(next)
            lastSeekSession = token
            lastSeekTrack = track
            lastSeekAt = now
            lastSeekTarget = next
            lastStatus = "Seek ${if (delta > 0) "+" else ""}${delta / 1000}s requested from ${session.packageName} (confirm in player)"
            Trace.add("car seek: $lastStatus")
            true
        } catch (e: Exception) {
            lastStatus = "Seek request failed: ${e.javaClass.simpleName}"
            Trace.add("car seek: $lastStatus")
            false
        }
    }
}

/** Permission-gated media-session access; no notifications are read or stored by this service. */
class CarMediaListener : NotificationListenerService()
