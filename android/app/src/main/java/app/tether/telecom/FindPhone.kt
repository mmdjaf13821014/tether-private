package app.tether.telecom

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import app.tether.R
import app.tether.TetherApp

/**
 * "Find my phone": rings loudly on the alarm stream for at most 30 s, then puts the alarm volume back.
 * Stops on [stop], from the notification's Stop action, or when the screen is unlocked.
 */
class FindPhone(private val ctx: Context) {
    companion object {
        const val MAX_MS = 30_000L
        private const val ACTION_STOP = "app.tether.FIND_STOP"
        private const val NOTIF_ID = 4107
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var receiver: BroadcastReceiver? = null
    /** Called (any thread) whenever the state changes, so clients can show it. */
    @Volatile var onChange: ((Boolean) -> Unit)? = null
    @Volatile var active = false; private set

    private val autoStop = Runnable { stop() }

    /** Starts ringing; returns an error text or null. */
    @Synchronized fun start(): String? {
        if (active) { main.removeCallbacks(autoStop); main.postDelayed(autoStop, MAX_MS); return null }
        return try {
            val am = ctx.getSystemService(AudioManager::class.java)
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            p.setDataSource(ctx, uri)
            p.isLooping = true
            p.prepare()
            p.start()
            player = p
            active = true
            listen()
            notify(true)
            main.postDelayed(autoStop, MAX_MS)
            onChange?.invoke(true)
            null
        } catch (t: Throwable) {
            cleanup()
            t.message ?: t.javaClass.simpleName
        }
    }

    @Synchronized fun stop() {
        if (!active && player == null) return
        main.removeCallbacks(autoStop)
        val was = active
        cleanup()
        if (was) onChange?.invoke(false)
    }

    private fun cleanup() {
        active = false
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        if (savedVolume >= 0) runCatching { ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        savedVolume = -1
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }; receiver = null
        notify(false)
    }

    private fun listen() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { stop() }
        }
        val f = IntentFilter().apply { addAction(ACTION_STOP); addAction(Intent.ACTION_USER_PRESENT) }
        ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun notify(on: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!on) { nm.cancel(NOTIF_ID); return }
        val stop = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_STOP).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_ID, Notification.Builder(ctx, TetherApp.CH_FIND).setSmallIcon(R.drawable.ic_tether)
            .setContentTitle(ctx.getString(R.string.find_title)).setContentText(ctx.getString(R.string.find_text))
            .setOngoing(true).setCategory(Notification.CATEGORY_ALARM)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.find_stop), stop).build()).build())
    }
}

/** Ringer mode (normal / vibrate / silent) of the Android phone. */
class RingerControl(private val ctx: Context) {
    companion object {
        val modes = listOf("normal", "vibrate", "silent")
        fun parse(s: String?): Int? = when (s) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> null
        }
    }

    fun mode(): String = when (ctx.getSystemService(AudioManager::class.java).ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    /** Silent (and sometimes vibrate) needs Do Not Disturb access on Android 7+. */
    fun dndAccess(): Boolean = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /** Null on success, else why not. Never throws. */
    fun set(mode: String?): String? {
        val m = parse(mode) ?: return "Unknown ringer mode"
        return try {
            ctx.getSystemService(AudioManager::class.java).ringerMode = m
            if (this.mode() != mode) "Android did not change the ringer (Do Not Disturb access may be needed)" else null
        } catch (e: SecurityException) {
            "Do Not Disturb access is needed for $mode: allow Tether in Settings > Notifications > Do Not Disturb on the Android phone"
        } catch (t: Throwable) { t.message ?: t.javaClass.simpleName }
    }
}
