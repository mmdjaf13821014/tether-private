package app.tether.client.report

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.tether.client.R
import app.tether.core.ProblemReport

/**
 * Help › Send a problem report: builds the report off the main thread, then shows its start for checking, with Share
 * (as a `.txt` file through [ReportProvider]) and Copy (a shorter copy: the clipboard holds less).
 */
object ReportUi {
    private const val CLIP_BYTES = 200_000

    fun send(a: Activity, build: () -> ProblemReport) {
        val main = Handler(Looper.getMainLooper())
        val wait = AlertDialog.Builder(a).setMessage(R.string.report_preparing).setCancelable(false).show()
        Thread({
            val result = runCatching {
                val r = build()
                val text = r.build()
                Triple(ReportProvider.write(a, text), text, r.build(CLIP_BYTES))
            }
            main.post {
                runCatching { wait.dismiss() }
                if (a.isFinishing || a.isDestroyed) return@post
                result.onSuccess { (file, text, short) -> show(a, file, text, short) }
                    .onFailure { Toast.makeText(a, a.getString(R.string.report_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show() }
            }
        }, "report").start()
    }

    private fun show(a: Activity, file: java.io.File, text: String, short: String) {
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, pad / 2) }
        box.addView(TextView(a).apply {
            this.text = a.getString(R.string.report_ready, (file.length() + 1023) / 1024); textSize = 14f; setTextColor(a.getColor(R.color.text))
        })
        // The start of the report, to check what goes out (the whole file can be large).
        box.addView(TextView(a).apply {
            this.text = text.take(20_000); textSize = 10f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            setTextColor(a.getColor(R.color.muted)); textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(0, pad / 2, 0, 0)
        })
        AlertDialog.Builder(a).setTitle(R.string.report_title).setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(R.string.report_share) { _, _ ->
                val uri = ReportProvider.uri(a, file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Tether problem report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(file.name, uri)
                runCatching { a.startActivity(Intent.createChooser(send, a.getString(R.string.report_title))) }
            }
            .setNeutralButton(R.string.copy) { _, _ ->
                runCatching {
                    a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tether problem report", short))
                    Toast.makeText(a, if (short.length < text.length) a.getString(R.string.report_copied_short, CLIP_BYTES / 1000) else a.getString(R.string.report_copied), Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(a, a.getString(R.string.report_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }
}
