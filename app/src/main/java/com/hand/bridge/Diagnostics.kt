package com.hand.bridge

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** A plain-text snapshot of the app's state, for copying into a message when something doesn't work. */
object Diagnostics {
    @Suppress("MissingPermission")
    fun report(ctx: Context): String = buildString {
        val pm = ctx.packageManager
        val ver = runCatching { pm.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        appendLine("Hand Bridge report")
        appendLine("App $ver · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        fun perm(p: String) = if (ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "yes" else "NO"
        appendLine("Permissions: bluetooth ${perm(Manifest.permission.BLUETOOTH_CONNECT)}, scan ${perm(Manifest.permission.BLUETOOTH_SCAN)}, notifications ${if (Build.VERSION.SDK_INT >= 33) perm(Manifest.permission.POST_NOTIFICATIONS) else "n/a"}")
        val a11y = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.equals(ComponentName(ctx, GestureAccessibilityService::class.java).flattenToString(), true) } == true
        appendLine("Air Gestures broadcasts received: ${AirGestures.received}${if (AirGestures.received > 0) ", last '${AirGestures.lastGesture}' ${(System.currentTimeMillis() - AirGestures.lastAt) / 1000}s ago, ignored because: ${AirGestures.lastRejected ?: "n/a"}" else ""}")
        appendLine("Car media mode: ${Prefs.carMode(ctx)} (phone media only; no BMW display control)")
        appendLine("Phone call permission: ${PhoneCalls.permitted(ctx)}; last: ${PhoneCalls.lastStatus}")
        appendLine("Phone volume: ${PhoneAudio.level(ctx)}; last: ${PhoneAudio.lastStatus}")
        appendLine("2026 BMW media route probe: enabled ${Prefs.remoteCarVolume(ctx)}")
        appendLine("2026 BMW selected audio route: ${CarVolumeRoute.describe(ctx)}")
        appendLine("2026 BMW routes: ${CarVolumeRoute.allRoutes(ctx)}")
        appendLine("2026 BMW remote volume last request: ${CarVolumeRoute.lastResult}")
        if (Prefs.carMode(ctx)) appendLine("Preset: index tap=play/pause, thumb left/right=previous/next, dial=phone volume")
        appendLine("Bridge on: ${Prefs.enabled(ctx)} · accessibility: $a11y · Air Gestures installed: ${AirGestures.installed(ctx)} · band: ${AirGestures.bandConnected} ${if (AirGestures.battery >= 0) "${AirGestures.battery}%" else ""}")

        appendLine()
        appendLine("Bluetooth keyboard/mouse")
        val bt = runCatching { ctx.svc<BluetoothManager>().adapter }.getOrNull()
        val hid = Bridge.hid(ctx)
        appendLine("Bluetooth on: ${bt?.isEnabled} · phone name: ${runCatching { bt?.name }.getOrNull()}")
        appendLine("Keyboard/mouse role registered: ${hid.registered} · connected to: ${hid.host?.let { "${it.name} (${it.address})" } ?: "nobody"}")
        hid.lastError?.let { appendLine("Problem: $it") }
        hid.bondedStates().forEach { appendLine("  paired: ${it.first} ${it.second} -> ${hid.stateName(it.third)}") }
        Prefs.devices(ctx).forEach { appendLine("  saved: ${it.name} ${it.address} mode=${it.mode} auto=${it.auto}") }

        appendLine()
        appendLine("Wi-Fi TV")
        val net = TvFinder.localSubnet(ctx)
        appendLine("Wi-Fi address: ${net?.let { "${it.first}/${it.second}" } ?: "not on Wi-Fi"}")
        appendLine("State: ${TvManager.state} ${TvManager.error?.let { "($it)" } ?: ""}")
        Prefs.tvs(ctx).forEach { appendLine("  saved: ${it.name} ${it.host} mac=${it.mac} auto=${it.auto}") }

        CrashLog.last(ctx)?.let {
            appendLine()
            appendLine("Last crash")
            appendLine(it.lines().take(30).joinToString("\n"))
        }
        appendLine()
        appendLine("Recent events")
        appendLine(Trace.dump().ifBlank { "(none yet)" })
    }
}
