package app.tether.client.link

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.tether.client.ClientApp

/**
 * Foreground service (connected device) that keeps the link to the Android phone up while the app is in the
 * background, like the iPhone's Bluetooth background mode. During a call it is also a phone-call and microphone
 * service (Android lets a background app use the microphone only then) and holds a partial wake lock.
 */
class LinkService : Service() {
    private val app get() = application as ClientApp
    private var wake: PowerManager.WakeLock? = null
    private var inCall = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = intent?.getBooleanExtra(EXTRA_CALL, false) ?: inCall
        if (intent?.hasExtra(EXTRA_CALL) == true || !running) foreground(call)
        running = true
        app.link.start()
        return START_STICKY
    }

    private fun foreground(call: Boolean) {
        inCall = call
        val n = app.notifier.linkNotification()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (call) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, n, type)
            } else startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (t: Throwable) {
            // Microphone from the background is refused before the app was in front: keep the plain link service.
            app.journal.add("foreground service ${if (call) "for a call " else ""}refused: ${t.message}")
            if (call) runCatching { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        }
        if (call && wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tether:call").apply { setReferenceCounted(false); acquire(4 * 3600_000L) }
        if (!call) { wake?.release(); wake = null }
    }

    override fun onDestroy() {
        running = false
        wake?.release(); wake = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CALL = "call"
        const val NOTIFICATION_ID = 1
        @Volatile var running = false; private set

        fun start(ctx: Context) {
            val app = ctx.applicationContext as ClientApp
            if (!app.hasLinkPermissions()) return
            runCatching { ctx.startForegroundService(Intent(ctx, LinkService::class.java)) }
                .onFailure { app.journal.add("link service not started: ${it.message}") }
        }

        fun callMode(ctx: Context, on: Boolean) {
            val app = ctx.applicationContext as ClientApp
            if (!app.hasLinkPermissions()) return
            runCatching { ctx.startForegroundService(Intent(ctx, LinkService::class.java).putExtra(EXTRA_CALL, on)) }
                .onFailure { app.journal.add("call service mode not set: ${it.message}") }
        }
    }
}

/** After a reboot or an update: the link comes back without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as ClientApp
        if (app.identity.isPaired) LinkService.start(context)
    }
}
