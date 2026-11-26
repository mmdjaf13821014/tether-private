package app.tether.data

import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * How a phone number's kind is stored: Android's type code plus, for a custom kind ("Satellite", "Studio"), its label.
 * The wire carries `type` (mobile, home, work, main, other) and an optional `label`. A label means the custom kind;
 * an empty label clears a custom one; no label at all leaves an existing custom label alone (older iPhone builds
 * never send labels, and their edits must not wipe the ones the Android phone has).
 */
object PhoneLabels {
    data class Stored(val type: Int, val label: String?)

    fun typeName(t: Int) = when (t) {
        Phone.TYPE_MOBILE -> "mobile"
        Phone.TYPE_HOME -> "home"
        Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "work"
        Phone.TYPE_MAIN -> "main"
        else -> "other"
    }

    fun typeCode(t: String) = when (t) {
        "home" -> Phone.TYPE_HOME
        "work" -> Phone.TYPE_WORK
        "main" -> Phone.TYPE_MAIN
        "other" -> Phone.TYPE_OTHER
        else -> Phone.TYPE_MOBILE
    }

    /** For a new number. */
    fun forNew(type: String, label: String?): Stored =
        if (!label.isNullOrBlank()) Stored(Phone.TYPE_CUSTOM, label.trim()) else Stored(typeCode(type), null)

    /** For a number that stays: the new values, or null when nothing changes. */
    fun forExisting(curType: Int, curLabel: String?, type: String, label: String?): Stored? {
        val custom = curType == Phone.TYPE_CUSTOM && !curLabel.isNullOrBlank()
        return when {
            !label.isNullOrBlank() ->
                if (custom && curLabel == label.trim()) null else Stored(Phone.TYPE_CUSTOM, label.trim())
            label != null ->   // "": the user picked a standard kind for a custom one
                if (!custom && typeName(curType) == type) null else Stored(typeCode(type), null)
            else ->            // no label sent: change only a kind the user changed
                if (typeName(curType) == type) null else Stored(typeCode(type), null)
        }
    }
}
