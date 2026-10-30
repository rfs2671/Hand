package com.hand.bridge

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing

/**
 * Optional, zero-ADB experiment: track the audio session YouTube Music announces via
 * Android's standard AudioEffect OPEN/CLOSE broadcasts, then modify that session's
 * PCM gain BEFORE Android Auto projects it.
 *
 * Does not control BMW's iDrive master amplifier. Does not read other apps' sessions.
 * YouTube Music must emit the OPEN broadcast; Android may filter or omit it.
 * No sound output can be verified by this app merely by reading an effect back.
 */
object YoutubeMusicGain {
    private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    private const val MIN_DB = -24f
    private const val MAX_DB = 3f
    private const val STEP_DB = 2f
    private const val PRIORITY = 1000

    @Volatile var lastStatus: String = "Not enabled"
        private set
    @Volatile var lastSession: Int = -1
        private set
    @Volatile var lastGainDb: Float = 0f
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var receivedOpenCount: Int = 0
        private set

    private val engines = linkedMapOf<Int, DynamicsProcessing>()

    fun active(ctx: Context): Boolean = Prefs.carMode(ctx) && Prefs.youtubeGain(ctx)

    @Synchronized
    fun refresh(ctx: Context) {
        if (!active(ctx)) {
            resetAll()
            lastStatus = "Off (phone volume unchanged)"
        } else if (engines.isEmpty()) {
            lastStatus = "Listening for a YouTube Music playback session; start or restart playback"
        }
    }

    @Synchronized
    fun onSessionIntent(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action != AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION &&
            action != AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) return
        if (intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) != YOUTUBE_MUSIC) return
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (session <= 0) {
            lastStatus = "YouTube Music announced an invalid audio session: $session"
            return
        }
        lastSession = session
        if (action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
            release(session)
            lastStatus = "YouTube Music closed session $session; listening for its next track"
            return
        }
        receivedOpenCount++
        Trace.add("yt-dsp: YouTube Music OPEN session $session")
        if (!active(ctx)) return
        if (engines.containsKey(session)) return
        attach(session)
    }

    @Synchronized
    private fun attach(session: Int) {
        try {
            // Insert-only DSP, initially flat so joining playback never changes music.
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, false, 0, false, 0, false, 0, true
            ).build()
            val dp = DynamicsProcessing(PRIORITY, session, cfg)
            try {
                dp.setControlStatusListener { _, granted ->
                    if (!granted) {
                        lastStatus = "Audio effect control lost to another player/equalizer"
                        Trace.add("yt-dsp: lost DSP control on session $session")
                    }
                }
                dp.setInputGainAllChannelsTo(lastGainDb)
                setLimiter(dp, lastGainDb)
                dp.enabled = true
                engines[session] = dp
                val hasControl = dp.hasControl()
                lastError = ""
                lastStatus = if (hasControl) {
                    "YouTube Music session $session: DSP attached at ${lastGainDb.toInt()} dB; audible BMW result unverified"
                } else {
                    "Session $session: audio DSP opened but another app controls the effect"
                }
                Trace.add("yt-dsp: $lastStatus")
            } catch (e: Exception) {
                runCatching { dp.release() }
                throw e
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            lastStatus = "YouTube Music DSP unavailable: $lastError"
            Trace.add("yt-dsp: $lastStatus")
        }
    }

    private fun setLimiter(dp: DynamicsProcessing, db: Float) {
        dp.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true, db > 0f, 0, 1f, 60f, 10f, -1f, 0f
            )
        )
    }

    /** No fake fallback to Pixel volume: when this is enabled, report whether DSP was applied. */
    @Synchronized
    fun adjust(up: Boolean): Boolean = setGainDb(lastGainDb + if (up) STEP_DB else -STEP_DB)

    @Synchronized
    fun setGainDb(requested: Float): Boolean {
        if (engines.isEmpty()) {
            lastStatus = "No YouTube Music audio session detected. Restart YouTube Music while HandBridge is running."
            Trace.add("yt-dsp: cannot set gain; no YouTube Music session announced")
            return false
        }
        val db = requested.coerceIn(MIN_DB, MAX_DB)
        if (db == lastGainDb) {
            lastStatus = "YouTube Music gain already at ${db.toInt()} dB (range -24 to +3)"
            return false
        }
        var applied = 0
        val dead = mutableListOf<Int>()
        engines.forEach { (session, dp) ->
            try {
                if (!dp.hasControl()) {
                    lastStatus = "Cannot change gain: DSP control belongs to another app"
                } else {
                    dp.setInputGainAllChannelsTo(db)
                    setLimiter(dp, db)
                    val readBack = dp.getInputGainByChannelIndex(0)
                    if (dp.enabled && kotlin.math.abs(readBack - db) < 0.15f) applied++
                    else {
                        lastError = "Effect parameter readback failed on session $session"
                        lastStatus = lastError
                    }
                }
            } catch (e: Exception) {
                dead.add(session)
                lastError = "${e.javaClass.simpleName}: ${e.message ?: ""}"
                lastStatus = "Audio gain update failed: $lastError"
            }
        }
        dead.forEach { release(it) }
        if (applied == 0) return false
        lastGainDb = db
        lastStatus = "YouTube Music DSP set to ${db.toInt()} dB on $applied session(s); audible BMW response not verified"
        Trace.add("yt-dsp: $lastStatus")
        return true
    }

    @Synchronized
    private fun release(session: Int) {
        engines.remove(session)?.let { dp ->
            runCatching { dp.setInputGainAllChannelsTo(0f) }
            runCatching { dp.enabled = false }
            runCatching { dp.release() }
        }
    }

    @Synchronized
    fun resetAll() {
        engines.keys.toList().forEach { release(it) }
        lastGainDb = 0f
    }

    @Synchronized
    fun status(): String =
        "$lastStatus | sessions=${engines.keys.joinToString().ifBlank { "none" }} | opens=$receivedOpenCount | gain=${lastGainDb.toInt()} dB"
}
