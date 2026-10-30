package com.hand.bridge

import android.content.Context
import android.media.AudioManager

/**
 * Diagnostic for PHONE music stream only. Android Auto/BMW owns the vehicle
 * amplifier volume, so stream-index changes never prove car volume changed.
 */
object PhoneAudio {
    @Volatile var lastStatus = "Not tested"
        private set

    fun level(ctx: Context): String {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return "Audio unavailable"
        return try {
            if (audio.isVolumeFixed) "Fixed-volume phone route" else
                "Pixel music: ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        } catch (_: Exception) { "Phone media volume unavailable" }
    }

    fun adjust(ctx: Context, direction: Int): Boolean {
        val audio = ctx.getSystemService(AudioManager::class.java)
        if (audio == null) {
            lastStatus = "Audio service unavailable"
            return false
        }
        return try {
            if (audio.isVolumeFixed) {
                lastStatus = "Phone reports fixed volume. Car amplifier is controlled by BMW."
                Trace.add("car volume: fixed route")
                return false
            }
            val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val changed = after != before
            lastStatus = if (changed) "Pixel music ${before} → ${after}/$max (BMW volume unverified)"
                else "Pixel music unchanged at $after/$max; BMW may control volume separately"
            Trace.add("car volume: $lastStatus")
            changed
        } catch (e: Exception) {
            lastStatus = "Phone media volume failed: ${e.javaClass.simpleName}"
            Trace.add("car volume: error ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
