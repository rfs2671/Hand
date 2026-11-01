package com.hand.bridge

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** User preference. AUTO is the default; the old persistent car-mode toggle is ignored. */
enum class OutputChoice { AUTO, MEDIA, TV }

/**
 * Route band commands according to the current output rather than the app in the foreground.
 * The Meta Neural Band is a BLE input, not a Bluetooth audio output, so it never triggers
 * headphone mode by itself.
 *
 * When Android Auto projection is active or a media headset/audio sink is connected, prefer
 * controls for the phone's media session. Otherwise retain the chosen TV/BT remote.
 * Android Auto connection comes from the official CarConnection API observed in BridgeService.
 */
object OutputRouter {
    @Volatile private var androidAutoProjection = false

    fun setProjection(connected: Boolean) {
        if (androidAutoProjection != connected) {
            androidAutoProjection = connected
            Trace.add("route: Android Auto projection = $connected")
        }
    }

    /** Only true for phone audio output devices; BLE input bands are not media outputs. */
    fun externalAudio(ctx: Context): String? {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return null
        return runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstNotNullOfOrNull { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth headphones or car audio"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> "Wired or USB audio"
                    else -> if (Build.VERSION.SDK_INT >= 33 &&
                        device.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                    ) "Bluetooth broadcast audio" else null
                }
            }
        }.getOrNull()
    }

    /**
     * Active projected Android Auto wins. Otherwise an actually connected home TV
     * wins over a lingering BMW Bluetooth audio connection. Unreachable TV must not
     * hijack music commands; manual choice remains authoritative.
     */
    fun reason(ctx: Context): String = when (Prefs.routeChoice(ctx)) {
        OutputChoice.MEDIA -> "Manual media selection"
        OutputChoice.TV -> "Manual TV selection"
        OutputChoice.AUTO -> when {
            androidAutoProjection -> "Android Auto connected"
            Prefs.selectedTvId(ctx) != null && TvManager.connected -> "JMGO connected on home Wi-Fi"
            externalAudio(ctx) != null -> externalAudio(ctx)!!
            else -> "No external media device; TV remote preferred"
        }
    }

    /** Separate car/headphone gesture presets while both control Pixel music. */
    fun mediaProfile(ctx: Context): DeviceProfile {
        if (Prefs.routeChoice(ctx) == OutputChoice.MEDIA) return Prefs.preferredMediaProfile(ctx)
        if (androidAutoProjection) return DeviceProfile.CAR
        val audio = ctx.getSystemService(AudioManager::class.java)
        val carLike = runCatching {
            audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { d ->
                val name = d.productName.toString().lowercase()
                (name.contains("bmw") || name.contains("mini") ||
                    name.contains("car audio") || name.contains("android auto"))
            } ?: false
        }.getOrDefault(false)
        return if (carLike) DeviceProfile.CAR else DeviceProfile.HEADPHONES
    }

    fun mediaSelected(ctx: Context): Boolean = when (Prefs.routeChoice(ctx)) {
        OutputChoice.MEDIA -> true
        OutputChoice.TV -> false
        OutputChoice.AUTO -> androidAutoProjection ||
            (externalAudio(ctx) != null && !(Prefs.selectedTvId(ctx) != null && TvManager.connected))
    }

    /** Recheck TV in the background even while BMW's music Bluetooth link lingers. */
    fun shouldReconnectTv(ctx: Context): Boolean = when (Prefs.routeChoice(ctx)) {
        OutputChoice.MEDIA -> false
        OutputChoice.TV -> true
        OutputChoice.AUTO -> !androidAutoProjection
    }

    fun diagnostic(ctx: Context): String = buildString {
        appendLine("Output selection: ${Prefs.routeChoice(ctx)}")
        appendLine("Android Auto projection: $androidAutoProjection")
        appendLine("External audio: ${externalAudio(ctx) ?: "none"}")
        appendLine("TV connected: ${TvManager.connected} · selected: ${Prefs.selectedTvId(ctx) ?: "none"}")
        appendLine("TV reconnect permitted: ${shouldReconnectTv(ctx)}")
        appendLine("Effective output: ${if (mediaSelected(ctx)) "phone media" else "TV/Bluetooth remote"}")
        appendLine("Reason: ${reason(ctx)}")
    }
}
