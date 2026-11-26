package app.tether.adb

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.tether.R
import app.tether.TetherApp

/**
 * Pairing with Wireless debugging from the notification's code field (issue #1: "it cannot connect and the app
 * force-closes"). The work (finding the pairing service for up to 15 s, pairing, the first adb connection and the
 * grants) used to run inside the notification's broadcast, which Android stops after ~10 s by killing the app. It now
 * runs in a short foreground service, and every way it can fail ends in a message that says what to do.
 */
object AdbPairing {
    enum class Result { OK, BAD_CODE, NO_WIFI, NO_SERVICE, WRONG_CODE, FAILED, PAIRED_NO_CONNECT }

    /** The 6-digit code from what was typed (spaces, dashes, Persian or Arabic-Indic digits allowed), else null. */
    fun normalizeCode(raw: String?): String? {
        val digits = buildString {
            for (ch in raw ?: "") when (ch) {
                in '0'..'9' -> append(ch)
                in '۰'..'۹' -> append('0' + (ch - '۰'))
                in '٠'..'٩' -> append('0' + (ch - '٠'))
            }
        }
        return digits.takeIf { it.length == 6 }
    }

    /** One short line for a notification: long or multi-line exception texts made Android refuse the notification. */
    fun shortReason(t: Throwable): String =
        (t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName).replace(Regex("\\s+"), " ").trim().take(160)

    /** The message for a result (the string resource and its argument, if any). */
    fun message(r: Result, detail: String? = null): Pair<Int, String?> = when (r) {
        Result.OK -> R.string.adb_paired to null
        Result.BAD_CODE -> R.string.adb_code_format to null
        Result.NO_WIFI -> R.string.adb_no_wifi to null
        Result.NO_SERVICE -> R.string.adb_no_service to null
        Result.WRONG_CODE -> R.string.adb_wrong_code to null
        Result.FAILED -> R.string.adb_failed to (detail ?: "")
        Result.PAIRED_NO_CONNECT -> R.string.adb_paired_no_connect to (detail ?: "")
    }

    /** A result ends the flow; these leave the code field in the notification so the user can try again. */
    fun asksAgain(r: Result) = r != Result.OK && r != Result.PAIRED_NO_CONNECT

    /** Runs the whole flow (on a background thread): pair, remember it, grant ourselves what we need, start the helper. */
    fun run(ctx: Context, code: String): Pair<Result, String?> {
        val app = ctx.applicationContext as TetherApp
        val adb = try { Adb.get(app) } catch (t: Throwable) { return Result.FAILED to shortReason(t) }
        val found = try { adb.findPairingService() } catch (t: Throwable) { null }
            ?: return (if (onWifi(app)) Result.NO_SERVICE else Result.NO_WIFI) to null
        val paired = try { adb.pairAt(found.first, found.second, code) } catch (t: Throwable) {
            Log.w(Adb.TAG, "pairing failed", t)
            return Result.FAILED to shortReason(t)
        }
        if (!paired) return Result.WRONG_CODE to null
        app.prefs.edit().putBoolean("adb_paired", true).apply()
        return try {
            Adb.bootstrap(app)
            app.hub.helper.ensureRunning()
            app.hub.refresh()
            Result.OK to null
        } catch (t: Throwable) {
            Log.w(Adb.TAG, "paired, but the first adb connection failed", t)
            Result.PAIRED_NO_CONNECT to shortReason(t)
        }
    }

    private fun onWifi(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (t: Throwable) { true }

    /** Shows the outcome: success closes the flow, a failure keeps the code field with the reason. */
    fun show(ctx: Context, r: Result, detail: String?) {
        val (res, arg) = message(r, detail)
        val text = if (arg != null) ctx.getString(res, arg) else ctx.getString(res)
        Adb.updateNotification(ctx, text, done = !asksAgain(r))
    }

    /** Starts the flow for a code typed in the notification, without blocking the broadcast that delivered it. */
    fun start(ctx: Context, code: String) {
        Adb.updateNotification(ctx, ctx.getString(R.string.adb_pairing), done = true)
        val i = Intent(ctx, AdbPairingService::class.java).putExtra(AdbPairingService.EXTRA_CODE, code)
        try {
            ctx.startForegroundService(i)
        } catch (t: Throwable) {
            // Not allowed to start a foreground service right now: the app's own link service keeps the process alive.
            Log.w(Adb.TAG, "pairing service not started (${t.javaClass.simpleName}); pairing on a thread")
            Thread({ val (r, d) = run(ctx, code); show(ctx, r, d) }, "adb-pair").start()
        }
    }
}

/** Runs [AdbPairing.run] for at most a few minutes as a short foreground service. */
class AdbPairingService : Service() {
    @Volatile private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, TetherApp.CH_SETUP).setSmallIcon(R.drawable.ic_tether)
            .setContentTitle(getString(R.string.adb_notif_title)).setContentText(getString(R.string.adb_pairing)).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(NOTIF_WORK, n)
        } catch (t: Throwable) {
            Log.w(Adb.TAG, "pairing service could not go foreground: ${t.javaClass.simpleName}")
        }
        val code = intent?.getStringExtra(EXTRA_CODE)
        if (code == null || working) { if (!working) finish(); return START_NOT_STICKY }
        working = true
        Thread({
            val (r, d) = try { AdbPairing.run(this, code) } catch (t: Throwable) { AdbPairing.Result.FAILED to AdbPairing.shortReason(t) }
            AdbPairing.show(this, r, d)
            finish()
        }, "adb-pair").start()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {   // Android 14+: a short service gets ~3 minutes
        AdbPairing.show(this, AdbPairing.Result.FAILED, "timeout")
        finish()
    }

    private fun finish() {
        working = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val NOTIF_WORK = 31
    }
}
