package app.tether.data

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.util.Log
import app.tether.core.numberKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Blocked numbers and muted conversations, kept on this phone so every client (iPhone, Mac) sees the same.
 *
 * Blocking uses Android's own block list (BlockedNumberContract) when Tether may write it, which it may as the
 * default phone app: the system then refuses those calls and texts itself, as if blocked in the Phone app.
 * If the provider refuses, Tether keeps its own list and enforces it on what it forwards (see Hub / screening).
 * Tether's own list is also checked in every case, so a number blocked here is never forwarded.
 */
class Lists(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("lists", Context.MODE_PRIVATE)

    /** Whether the system block list is readable and writable for us (default dialer or SMS app). */
    fun systemAvailable(): Boolean = try {
        BlockedNumberContract.canCurrentUserBlockNumbers(ctx) &&
            ctx.contentResolver.query(BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumbers.COLUMN_ID), null, null, null)?.use { true } == true
    } catch (t: Throwable) { false }

    /** Every blocked number: the system list (when readable) plus Tether's own. */
    fun blocked(): List<String> {
        val out = LinkedHashMap<String, String>()
        if (systemAvailable()) try {
            ctx.contentResolver.query(BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { out.putIfAbsent(numberKey(it), it) }
            }
        } catch (t: Throwable) { Log.w(TAG, "block list unreadable: ${t.message}") }
        for (n in own()) out.putIfAbsent(numberKey(n), n)
        return out.values.toList()
    }

    fun isBlocked(number: String): Boolean {
        if (number.isBlank()) return false
        val key = numberKey(number)
        if (own().any { numberKey(it) == key }) return true
        return try { systemAvailable() && BlockedNumberContract.isBlocked(ctx, number) } catch (t: Throwable) { false }
    }

    /** Blocks or unblocks [number]. Returns "system" or "tether" (where it was recorded), or an error. */
    fun setBlocked(number: String, on: Boolean): String {
        val key = numberKey(number)
        if (key.isEmpty()) return "error: empty number"
        var where = "tether"
        if (systemAvailable()) try {
            if (on) {
                if (!BlockedNumberContract.isBlocked(ctx, number)) {
                    ctx.contentResolver.insert(BlockedNumbers.CONTENT_URI,
                        ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) })
                }
            } else {
                // Remove every system entry that is the same number in another notation.
                ctx.contentResolver.query(BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { c ->
                    while (c.moveToNext()) if (numberKey(c.getString(1) ?: "") == key) {
                        ctx.contentResolver.delete(BlockedNumbers.CONTENT_URI, "${BlockedNumbers.COLUMN_ID} = ?", arrayOf(c.getLong(0).toString()))
                    }
                }
            }
            where = "system"
        } catch (t: Throwable) {
            Log.w(TAG, "system block list refused (${t.message}); using Tether's own list")
        }
        // Tether's own list mirrors the system one, so blocking keeps working if the role is lost later.
        val own = own().filter { numberKey(it) != key }.toMutableList()
        if (on) own.add(number)
        prefs.edit().putString("blocked", JSONArray(own).toString()).apply()
        return where
    }

    private fun own(): List<String> = try {
        val a = JSONArray(prefs.getString("blocked", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (t: Throwable) { emptyList() }

    /** Conversations whose alerts are hidden (thread ids). */
    fun muted(): List<Long> = try {
        val a = JSONArray(prefs.getString("muted", "[]"))
        (0 until a.length()).map { a.getLong(it) }
    } catch (t: Throwable) { emptyList() }

    fun isMuted(thread: Long) = thread in muted()

    fun setMuted(thread: Long, on: Boolean) {
        val m = muted().filter { it != thread }.toMutableList()
        if (on) m.add(thread)
        prefs.edit().putString("muted", JSONArray(m).toString()).apply()
    }

    /** The "lists" message body. */
    fun json(): JSONObject = JSONObject().put("blocked", JSONArray(blocked())).put("muted", JSONArray(muted()))
        .put("system", systemAvailable())

    companion object { const val TAG = "Tether" }
}
