package app.tether.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import app.tether.client.ui.CallActivity
import app.tether.client.ui.MainActivity
import app.tether.client.ui.ThreadActivity
import app.tether.core.CallEntry
import app.tether.core.Sms

/** Notifications: the link service, incoming calls (full screen, ringing), texts with reply, missed calls. */
class Notifier(private val app: ClientApp) {
    private val nm = app.getSystemService(NotificationManager::class.java)
    /** Texts shown per conversation (a notification shows the unread ones of its thread). */
    private val shown = HashMap<Long, MutableList<Sms>>()

    fun channels() {
        nm.createNotificationChannel(NotificationChannel(CH_LINK, app.getString(R.string.channel_link), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, app.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            // Self-managed calls ring through their notification: the phone's ringtone, insistent, on the ringer stream.
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 800, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, app.getString(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SMS, app.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, app.getString(R.string.channel_missed), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun pi(i: Intent, req: Int) = PendingIntent.getActivity(app, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun br(action: String, req: Int, mutable: Boolean = false, extra: Intent.() -> Unit = {}) =
        PendingIntent.getBroadcast(app, req, Intent(app, NotificationActions::class.java).setAction(action).apply(extra),
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    fun linkNotification(): Notification = Notification.Builder(app, CH_LINK)
        .setSmallIcon(R.drawable.ic_tether)
        .setContentTitle(app.getString(R.string.app_name))
        .setContentText(app.getString(R.string.link_service_text))
        .setContentIntent(pi(Intent(app, MainActivity::class.java), 0))
        .setOngoing(true)
        .build()

    // ------------------------------------------------------------------ calls

    fun showIncomingCall(local: String, silent: Boolean = false) {
        val c = app.calls.calls.firstOrNull { it.local == local } ?: return
        val who = c.name ?: app.format(c.number)
        val sim = app.state.simName(c.sub)
        val full = pi(Intent(app, CallActivity::class.java).putExtra(CallActivity.EXTRA_LOCAL, local).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), local.hashCode())
        val answer = pi(Intent(app, CallActivity::class.java).putExtra(CallActivity.EXTRA_LOCAL, local).putExtra(CallActivity.EXTRA_ANSWER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), local.hashCode() + 1)
        val decline = br(ACTION_DECLINE, local.hashCode() + 2) { putExtra(CallActivity.EXTRA_LOCAL, local) }
        val b = Notification.Builder(app, if (silent) CH_ONGOING else CH_CALLS)
            .setSmallIcon(R.drawable.ic_tether)
            .setContentTitle(who)
            .setContentText(listOfNotNull(app.getString(R.string.incoming_call), sim).joinToString(" · "))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName(who).setImportant(true).build(), decline, answer))
        } else {
            b.addAction(Notification.Action.Builder(null, app.getString(R.string.decline), decline).build())
            b.addAction(Notification.Action.Builder(null, app.getString(R.string.answer), answer).build())
        }
        val n = b.build()
        if (!silent) n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(TAG_CALL, local.hashCode(), n)
    }

    fun silenceIncoming(local: String) = showIncomingCall(local, silent = true)
    fun cancelIncoming(local: String) = nm.cancel(TAG_CALL, local.hashCode())

    fun showMissedCall(c: CallEntry, from: String, sim: String?) {
        val call = br(ACTION_CALL_BACK, c.id.toInt()) { putExtra(EXTRA_NUMBER, c.num); c.sub?.let { putExtra(EXTRA_SUB, it) } }
        nm.notify(TAG_MISSED, c.id.toInt(), Notification.Builder(app, CH_MISSED)
            .setSmallIcon(R.drawable.ic_tether)
            .setContentTitle(from)
            .setContentText(listOfNotNull(app.getString(R.string.missed_call), sim).joinToString(" · "))
            .setWhen(c.date).setShowWhen(true)
            .setCategory(if (Build.VERSION.SDK_INT >= 31) Notification.CATEGORY_MISSED_CALL else Notification.CATEGORY_CALL)
            .setContentIntent(pi(Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_RECENTS), 1))
            .addAction(Notification.Action.Builder(null, app.getString(R.string.call_back), call).build())
            .setAutoCancel(true)
            .build())
    }

    // ------------------------------------------------------------------ texts

    fun showSms(m: Sms, from: String, sim: String?) {
        val list = shown.getOrPut(m.thread) { ArrayList() }
        if (list.none { it.id == m.id }) list.add(m)
        val me = Person.Builder().setName(app.getString(R.string.me)).build()
        val sender = Person.Builder().setName(from).setKey(m.addr).build()
        val style = Notification.MessagingStyle(me).setConversationTitle(sim?.let { "$from · $it" })
        list.takeLast(8).forEach { style.addMessage(it.body, it.date, sender) }
        val open = pi(Intent(app, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_THREAD, m.thread).putExtra(ThreadActivity.EXTRA_ADDRESS, m.addr)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), m.thread.toInt())
        val reply = Notification.Action.Builder(null, app.getString(R.string.reply),
            br(ACTION_REPLY, m.thread.toInt(), mutable = true) { putExtra(EXTRA_THREAD, m.thread); putExtra(EXTRA_NUMBER, m.addr); m.sub?.let { putExtra(EXTRA_SUB, it) } })
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(app.getString(R.string.reply)).build()).build()
        val read = Notification.Action.Builder(null, app.getString(R.string.mark_read), br(ACTION_READ, m.thread.toInt()) { putExtra(EXTRA_THREAD, m.thread) }).build()
        nm.notify(TAG_SMS, m.thread.toInt(), Notification.Builder(app, CH_SMS)
            .setSmallIcon(R.drawable.ic_tether)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .addAction(reply).addAction(read)
            .setAutoCancel(true)
            .build())
    }

    fun clearSms(thread: Long) { shown.remove(thread); nm.cancel(TAG_SMS, thread.toInt()) }
    fun clearSmsIds(ids: List<Long>) {
        val gone = ids.toHashSet()
        for (t in shown.keys.toList()) { val l = shown[t]!!; if (l.removeAll { it.id in gone } && l.isEmpty()) clearSms(t) }
    }

    fun clearAll() { shown.clear(); nm.cancelAll() }

    companion object {
        const val CH_LINK = "link"; const val CH_CALLS = "calls"; const val CH_ONGOING = "ongoing"; const val CH_SMS = "messages"; const val CH_MISSED = "missed"
        const val TAG_CALL = "call"; const val TAG_SMS = "sms"; const val TAG_MISSED = "missed"
        const val ACTION_DECLINE = "app.tether.client.DECLINE"; const val ACTION_REPLY = "app.tether.client.REPLY"
        const val ACTION_READ = "app.tether.client.READ"; const val ACTION_CALL_BACK = "app.tether.client.CALL_BACK"
        const val EXTRA_THREAD = "thread"; const val EXTRA_NUMBER = "number"; const val EXTRA_SUB = "sub"; const val KEY_REPLY = "reply"
    }
}

/** Notification buttons: decline a call, reply to or mark a conversation read, call back. */
class NotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ClientApp
        val sub = if (intent.hasExtra(Notifier.EXTRA_SUB)) intent.getIntExtra(Notifier.EXTRA_SUB, 0) else null
        when (intent.action) {
            Notifier.ACTION_DECLINE -> intent.getStringExtra(CallActivity.EXTRA_LOCAL)?.let { local ->
                app.telecom.connection(local)?.onReject() ?: app.calls.performEnd(local)
                app.notifier.cancelIncoming(local)
            }
            Notifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString() ?: return
                val number = intent.getStringExtra(Notifier.EXTRA_NUMBER) ?: return
                app.state.sendSms(number, text, sub ?: app.state.preferredSim(number))
                app.state.markRead(intent.getLongExtra(Notifier.EXTRA_THREAD, -1))
                app.notifier.clearSms(intent.getLongExtra(Notifier.EXTRA_THREAD, -1))
            }
            Notifier.ACTION_READ -> intent.getLongExtra(Notifier.EXTRA_THREAD, -1).takeIf { it >= 0 }?.let { app.state.markRead(it); app.notifier.clearSms(it) }
            Notifier.ACTION_CALL_BACK -> intent.getStringExtra(Notifier.EXTRA_NUMBER)?.let { app.dial(it, sub) }
        }
    }
}
