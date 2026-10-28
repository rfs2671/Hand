package com.hand.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Foreground service that keeps the process (and the Bluetooth HID registration) alive with the
 * screen off. Started by the UI, at boot, and by [PresenceService] when a remembered device appears.
 */
class BridgeService : Service() {
    private val hid get() = Bridge.hid(this)
    private val onChange: () -> Unit = { notifyStatus() }

    private val btState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) hid.start()
        }
    }

    // Air Gestures sends implicit broadcasts, which only runtime-registered receivers get.
    // (com.hand.bridge.GESTURE stays on the manifest receiver only, so nothing is handled twice.)
    private val gestureEvents = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (AirGestures.accept(c, this)) AirGestures.dispatch(c, intent)
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            hid.start() // re-register if the HID slot was lost and the backoff gave up
            hid.reconnectAuto()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = svc<NotificationManager>()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bridge status", NotificationManager.IMPORTANCE_LOW)
        )
        registerReceiver(btState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(gestureEvents, IntentFilter(AirGestures.ACTION_GESTURE), Context.RECEIVER_EXPORTED)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        hid.listeners.add(onChange)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            if (Prefs.airProfile(this)) AirGestures.announce(this, false)
            hid.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Prefs.setEnabled(this, true)
        if (Prefs.airProfile(this)) AirGestures.announce(this, true)
        hid.start()
        val connect = intent?.getStringExtra(EXTRA_CONNECT)
        if (connect != null) hid.connectWithRetry(connect) else hid.reconnectAuto()
        return START_STICKY
    }

    private fun statusText(): String {
        val host = hid.host
        return when {
            host != null -> "Connected to ${host.name ?: host.address}"
            hid.registered -> "Ready — waiting for a device"
            else -> "Starting Bluetooth…"
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Hand Bridge")
            .setContentText(statusText())
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun notifyStatus() {
        svc<NotificationManager>().notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        hid.listeners.remove(onChange)
        runCatching { unregisterReceiver(btState) }
        runCatching { unregisterReceiver(gestureEvents) }
        runCatching { unregisterReceiver(screenOn) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "bridge"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.hand.bridge.STOP"
        const val EXTRA_CONNECT = "connect"

        /** Returns false if Android refused a start from the background. */
        fun start(ctx: Context, connect: String? = null): Boolean {
            val i = Intent(ctx, BridgeService::class.java)
            connect?.let { i.putExtra(EXTRA_CONNECT, it) }
            return runCatching { ctx.startForegroundService(i) }.isSuccess
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}
