package app.tether.link

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small persistent record of what happens to the Bluetooth link (ACL connects / disconnects with transport
 * and reason, Bluetooth tethering, Tether's own link up / down, advertising). Logcat only keeps the last
 * minutes; this keeps the last few hundred events so a drop reported later can still be explained.
 * Addresses are shortened to their last two bytes. Read with `tools/dbg.sh linklog`.
 */
object LinkLog {
    private const val MAX = 600
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()

    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "link-events.log")
        file = f
        runCatching { f.readLines().takeLast(MAX).forEach { lines.addLast(it) } }
    }

    fun short(address: String?) = address?.takeLast(5)?.let { "…$it" } ?: "?"

    @Synchronized fun add(event: String) {
        val line = "${fmt.format(Date())} $event"
        Log.i("Tether", "linklog: $event")
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        // Rewritten in full now and then, appended otherwise (cheap: events are rare).
        runCatching {
            val f = file ?: return
            if (lines.size % 50 == 0) f.writeText(lines.joinToString("\n", postfix = "\n")) else f.appendText(line + "\n")
        }
    }

    @Synchronized fun tail(n: Int = 120): String = lines.toList().takeLast(n).joinToString("\n")
}
