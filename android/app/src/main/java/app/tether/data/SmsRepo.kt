package app.tether.data

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import app.tether.TetherApp
import app.tether.core.Sms

/** Reads the SMS provider (read-only) and sends through SmsManager. */
class SmsRepo(private val ctx: Context) {
    private val uri: Uri = Telephony.Sms.CONTENT_URI
    private val prefs = ctx.getSharedPreferences("sms", Context.MODE_PRIVATE)
    private var observer: ContentObserver? = null
    private val thread = HandlerThread("sms-observer").apply { start() }

    /** Highest SMS id already pushed live, so the observer only reports new rows. */
    private var lastPushed: Long
        get() = prefs.getLong("last_pushed", -1)
        set(v) = prefs.edit().putLong("last_pushed", v).apply()

    fun maxId(): Long = query("_id > ?", arrayOf("0"), "_id DESC", 1).firstOrNull()?.id ?: 0

    /** Messages with id > [since], oldest first. A full sync (since = 0) is capped to the newest [cap]. */
    fun since(since: Long, cap: Int = 5000): List<Sms> {
        if (since <= 0) return query(null, null, "_id DESC", cap).reversed()
        return query("_id > ?", arrayOf(since.toString()), "_id ASC", cap)
    }

    @SuppressLint("Range")
    fun query(sel: String?, args: Array<String>?, order: String, limit: Int): List<Sms> {
        val out = ArrayList<Sms>()
        val cols = arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "sub_id")
        try {
            ctx.contentResolver.query(uri, cols, sel, args, "$order LIMIT $limit")?.use { c ->
                val iSub = c.getColumnIndex("sub_id")
                while (c.moveToNext()) {
                    out.add(Sms(
                        id = c.getLong(0), thread = c.getLong(1), addr = c.getString(2) ?: "",
                        body = c.getString(3) ?: "", date = c.getLong(4), box = c.getInt(5),
                        read = c.getInt(6) != 0, sub = if (iSub >= 0 && !c.isNull(iSub)) c.getInt(iSub) else null,
                    ))
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no SMS permission")
        }
        return out
    }

    /** Called after any change in the SMS store (read flags too), for the unread mirror. */
    @Volatile var changeListener: (() -> Unit)? = null

    /** Ids of unread incoming texts (newest first, capped): the iPhone marks everything else read.
     *  Null when the provider cannot be read, so nothing is sent (an empty list would mark every text read). */
    fun unreadIds(cap: Int = 3000): List<Long>? = try {
        ctx.contentResolver.query(uri, arrayOf("_id"), "read = 0 AND type = 1", null, "_id DESC LIMIT $cap")
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    } catch (t: Throwable) { null }

    fun observe(onNew: (Sms) -> Unit) {
        if (observer != null) return
        if (lastPushed < 0) lastPushed = maxId()
        val o = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean) {
                // A provider error must not take the whole app (and the call link) down.
                runCatching { poll(onNew) }.onFailure { Log.w(TAG, "sms poll failed: ${it.message}") }
                changeListener?.invoke()
            }
        }
        observer = o
        ctx.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, o)
    }

    /** Called by the observer and by SmsReceiver (the provider may lag the broadcast). */
    @Synchronized fun poll(onNew: (Sms) -> Unit) {
        val news = since(lastPushed.coerceAtLeast(1), 200)
        for (s in news) {
            onNew(s)
            if (s.id > lastPushed) lastPushed = s.id
        }
    }

    /**
     * Marks the incoming messages of [thread] up to id [upTo] as read (and seen), as if the user had opened
     * the conversation on this phone. Android only lets the default SMS app write here; Tether is allowed
     * because the WRITE_SMS app-op is granted through adb (see Adb.allowSmsWrite). Returns rows changed, or
     * -1 when the write was refused.
     */
    fun markRead(thread: Long, upTo: Long): Int = try {
        val v = android.content.ContentValues().apply { put("read", 1); put("seen", 1) }
        ctx.contentResolver.update(uri, v, "thread_id = ? AND _id <= ? AND read = 0 AND type = 1",
            arrayOf(thread.toString(), upTo.toString()))
    } catch (t: Throwable) {
        Log.w("Tether", "mark read refused: ${t.message}")
        -1
    }

    fun unreadCount(thread: Long, upTo: Long = Long.MAX_VALUE): Int = try {
        ctx.contentResolver.query(uri, arrayOf("_id"), "thread_id = ? AND _id <= ? AND read = 0 AND type = 1",
            arrayOf(thread.toString(), upTo.toString()), null)
            ?.use { it.count } ?: 0
    } catch (t: Throwable) { -1 }

    /**
     * Deletes messages by id, or a whole conversation (its SMS and MMS) when [thread] is set. Needs the same
     * WRITE_SMS app-op as [markRead]. Returns the ids that are gone afterwards, or null when the write was refused.
     */
    fun delete(ids: List<Long>, thread: Long?): List<Long>? {
        val targets = if (thread != null) idsIn(thread) else ids
        if (targets.isEmpty()) return emptyList()
        return try {
            if (thread != null) {
                // Threads URI removes the conversation (MMS too) the way the Messages app does.
                val n = ctx.contentResolver.delete(Uri.parse("content://mms-sms/conversations/$thread"), null, null)
                if (n <= 0) ctx.contentResolver.delete(uri, "thread_id = ?", arrayOf(thread.toString()))
            } else {
                for (chunk in targets.chunked(200)) {
                    ctx.contentResolver.delete(uri, "_id IN (${chunk.joinToString(",")})", null)
                }
            }
            val left = existing(targets)
            if (left.size == targets.size) null else targets - left.toSet()
        } catch (t: Throwable) {
            Log.w(TAG, "delete refused: ${t.message}")
            null
        }
    }

    fun idsIn(thread: Long): List<Long> = try {
        ctx.contentResolver.query(uri, arrayOf("_id"), "thread_id = ?", arrayOf(thread.toString()), null)
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptyList()
    } catch (t: Throwable) { emptyList() }

    private fun existing(ids: List<Long>): List<Long> = ids.chunked(200).flatMap { chunk ->
        try {
            ctx.contentResolver.query(uri, arrayOf("_id"), "_id IN (${chunk.joinToString(",")})", null, null)
                ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptyList()
        } catch (t: Throwable) { emptyList() }
    }

    /** Every SMS id on the phone (for sync.ids). */
    fun allIds(): List<Long> = try {
        ctx.contentResolver.query(uri, arrayOf("_id"), null, null, "_id ASC")
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptyList()
    } catch (t: Throwable) { emptyList() }

    /** Debug (adb): writes a throw-away incoming row, to test forwarding / blocking without a real SMS. */
    fun insertTest(addr: String, body: String, type: Int = 1): Long = try {
        val v = android.content.ContentValues().apply {
            put("address", addr); put("body", body); put("type", type); put("read", 1); put("seen", 1)
            put("date", System.currentTimeMillis())
        }
        ctx.contentResolver.insert(uri, v)?.lastPathSegment?.toLongOrNull() ?: -1
    } catch (t: Throwable) { Log.w(TAG, "insert refused: ${t.message}"); -1 }

    fun stop() {
        observer?.let { ctx.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    /**
     * Sends a text. As a non-default SMS app, the system writes the sent message to the provider for us.
     * [dryRun] skips the radio entirely (tests).
     */
    fun send(to: String, body: String, sub: Int?, requestId: Int, dryRun: Boolean = false): String? {
        if (to.isBlank() || body.isEmpty()) return "empty recipient or body"
        if (dryRun) return null
        val mgr = try {
            val base = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
            val subId = (ctx.applicationContext as TetherApp).sims.resolveSub(sub, forSms = true)
                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                if (Build.VERSION.SDK_INT >= 31) base.createForSubscriptionId(subId)
                else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subId)
            } else base
        } catch (t: Throwable) {
            return "no SmsManager: ${t.message}"
        }
        val parts = mgr.divideMessage(body)
        val sent = ArrayList<PendingIntent>()
        val delivered = ArrayList<PendingIntent>()
        parts.forEachIndexed { i, _ ->
            val last = i == parts.size - 1
            sent.add(pi(requestId, "sent", last, i))
            delivered.add(pi(requestId, "delivered", last, i))
        }
        return try {
            mgr.sendMultipartTextMessage(to, null, parts, sent, delivered)
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    private fun pi(req: Int, kind: String, last: Boolean, part: Int): PendingIntent {
        // The data URI makes every part's intent distinct, so no two parts or texts share (and overwrite) one.
        val i = Intent(ctx, SmsStatusReceiver::class.java).setAction("app.tether.SMS_$kind")
            .setData(android.net.Uri.parse("tether-sms://$req/$part")).putExtra("req", req).putExtra("last", last)
        return PendingIntent.getBroadcast(ctx, req * 64 + part * 2 + if (kind == "sent") 0 else 1, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object { const val TAG = "Tether" }
}

/** SMS_RECEIVED: nudge the provider poll (the observer also fires). */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TetherApp
        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(1500) // let the default SMS app store it
                runCatching { app.hub.pollSms() }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    companion object {
        /** Requests with a failed part: a multipart text is only "sent" when every part was. */
        private val failedParts = java.util.Collections.synchronizedSet(HashSet<String>())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val req = intent.getIntExtra("req", 0)
        val key = "$req/${intent.action}"
        if (!intent.getBooleanExtra("last", true)) {
            if (resultCode != android.app.Activity.RESULT_OK) failedParts.add(key)
            return
        }
        val app = context.applicationContext as TetherApp
        val ok = resultCode == android.app.Activity.RESULT_OK && !failedParts.remove(key)
        val state = when {
            !ok -> "failed"
            intent.action == "app.tether.SMS_delivered" -> "delivered"
            else -> "sent"
        }
        app.hub.smsResult(req, ok, state, if (ok) null else "error $resultCode")
    }
}
