package app.tether.client.ui

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.tether.client.ClientApp
import app.tether.client.R
import app.tether.client.model.Problem

/** Extra padding for the window's edges: system bars and display cutout, and at the bottom the keyboard when it is
 *  taller than the navigation bar (both sit at the bottom, so they never add up). Left, top, right, bottom. */
fun barPadding(bars: IntArray, imeBottom: Int): IntArray =
    intArrayOf(bars[0], bars[1], bars[2], maxOf(bars[3], imeBottom))

/**
 * Draws [root] edge to edge and pads it by the system bars, the cutout and the keyboard (#12). Android 15+ draws apps
 * with targetSdk 35+ behind the bars and ignores adjustResize, so text went behind the navigation bar and the
 * keyboard covered the text field. The same from Android 11 on, so every version behaves alike; Android 10 keeps
 * its own fitting (and adjustResize).
 */
fun Activity.fitSystemBars(root: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    window.setDecorFitsSystemWindows(false)
    val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
    root.setOnApplyWindowInsetsListener { v, insets ->
        val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val p = barPadding(intArrayOf(b.left, b.top, b.right, b.bottom), insets.getInsets(WindowInsets.Type.ime()).bottom)
        v.setPadding(base[0] + p[0], base[1] + p[1], base[2] + p[2], base[3] + p[3])
        WindowInsets.CONSUMED
    }
    root.requestApplyInsets()
}

/** Small helpers for the app's plain framework views (the Android phone's app is built the same way). */
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.col(id: Int) = getColor(id)

fun Context.label(t: CharSequence, colorId: Int = R.color.text, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
    text = t; textSize = size; setTextColor(col(colorId)); setPadding(0, dp(2), 0, dp(2))
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Context.pill(t: String, secondary: Boolean = false, colorId: Int = R.color.accent, onClick: () -> Unit) = Button(this).apply {
    text = t; isAllCaps = false; textSize = 15f
    setTextColor(if (secondary) col(colorId) else col(R.color.background))
    background = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        if (secondary) setStroke(dp(1), col(colorId)) else setColor(col(colorId))
    }
    layoutParams = LinearLayout.LayoutParams(-2, dp(42)).apply { topMargin = dp(6); marginEnd = dp(8) }
    setPadding(dp(16), 0, dp(16), 0)
    setOnClickListener { onClick() }
}

fun Context.round(colorId: Int, sizeDp: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col(colorId)); setSize(dp(sizeDp), dp(sizeDp)) }

fun Context.card() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(12), dp(16), dp(12))
    background = GradientDrawable().apply { setColor(col(R.color.surface)); cornerRadius = dp(16).toFloat() }
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
}

fun Context.row(vararg views: View, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    views.forEach { addView(it) }
}

fun View.weight(w: Float = 1f): View { layoutParams = LinearLayout.LayoutParams(0, -2, w); return this }

/** A row of buttons sharing the width equally (one line each), so long labels (Persian, "Bluetooth") never push a
 *  button off the screen (#12). */
fun Context.evenRow(vararg buttons: TextView) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    buttons.forEachIndexed { i, b ->
        val h = (b.layoutParams as? LinearLayout.LayoutParams)?.height ?: dp(42)
        b.setSingleLine(); b.ellipsize = android.text.TextUtils.TruncateAt.END; b.setPadding(dp(8), 0, dp(8), 0)
        addView(b, LinearLayout.LayoutParams(0, h, 1f).apply {
            topMargin = dp(6); marginStart = if (i == 0) 0 else dp(4); marginEnd = if (i == buttons.lastIndex) 0 else dp(4)
        })
    }
}

/** A contact's picture (base64 JPEG from the Android phone) or a circle with the initial. */
fun Context.avatar(name: String, photo: String?, sizeDp: Int = 40): View {
    photo?.let { p ->
        runCatching { Base64.decode(p, Base64.DEFAULT) }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { bmp ->
            return ImageView(this).apply {
                setImageBitmap(bmp); clipToOutline = true
                background = round(R.color.surface, sizeDp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(12) }
            }
        }
    }
    return TextView(this).apply {
        text = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
        gravity = Gravity.CENTER; textSize = sizeDp / 2.6f; setTextColor(col(R.color.background))
        background = round(R.color.accent, sizeDp)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(12) }
    }
}

fun Context.time(ms: Long): String = if (DateUtils.isToday(ms)) DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_TIME)
    else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** The words for a [Problem] (its detail comes from the Android phone as is). */
fun Context.describe(p: Problem): String {
    val base = when (p.kind) {
        Problem.NOT_CONNECTED -> getString(R.string.err_not_connected)
        Problem.NOT_DELETED -> getString(R.string.err_not_deleted)
        Problem.NOT_SENT -> getString(R.string.err_not_sent)
        Problem.CONTACT_NOT_SAVED -> getString(R.string.err_contact_not_saved)
        Problem.SEND_LATER -> getString(R.string.err_send_later)
        Problem.TOO_LONG -> getString(R.string.err_too_long)
        Problem.CALL_FAILED -> getString(R.string.err_call_failed)
        Problem.NO_ANSWER -> getString(R.string.err_no_answer)
        Problem.PAIRING_EXPIRED -> getString(R.string.err_pairing_expired)
        Problem.UNKNOWN_DEVICE -> getString(R.string.err_unknown_device)
        else -> p.detail ?: ""
    }
    return if (p.detail != null && p.kind != Problem.OTHER) "$base: ${p.detail}" else base
}

/** The line for a microphone warning (call screen and main screen). */
fun micText(w: app.tether.client.calls.MicWarning): Int = when (w) {
    app.tether.client.calls.MicWarning.DENIED -> R.string.mic_denied
    app.tether.client.calls.MicWarning.UNAVAILABLE -> R.string.mic_unavailable
    app.tether.client.calls.MicWarning.SILENT -> R.string.mic_silent
}

/** A list adapter from a list and a row builder. */
class Rows<T>(private val items: List<T>, private val build: (T) -> View) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?) = build(items[position])
}

val Activity.app get() = application as ClientApp
