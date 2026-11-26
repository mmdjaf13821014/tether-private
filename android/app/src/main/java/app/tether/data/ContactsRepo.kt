package app.tether.data

import app.tether.core.numberKey

import android.content.ContentProviderOperation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import android.util.Log
import app.tether.core.Contact
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import app.tether.core.Phone as PhoneEntry

class ContactsRepo(private val ctx: Context) {
    private val cr get() = ctx.contentResolver

    /** Hash of every raw contact's (id, version); changes whenever any contact changes. */
    fun version(): String {
        val md = MessageDigest.getInstance("SHA-1")
        try {
            cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.VERSION),
                "${RawContacts.DELETED} = 0", null, RawContacts._ID)?.use { c ->
                while (c.moveToNext()) md.update("${c.getLong(0)}:${c.getLong(1)};".toByteArray())
            }
        } catch (e: SecurityException) {
            return ""
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    fun all(withPhotos: Boolean = true): List<Contact> {
        val byId = LinkedHashMap<Long, Builder>()
        try {
            cr.query(Data.CONTENT_URI,
                arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DISPLAY_NAME_PRIMARY, Data.DATA1, Data.DATA2, Data.DATA3,
                    Data.STARRED, Data.PHOTO_THUMBNAIL_URI),
                "${Data.MIMETYPE} IN (?, ?)", arrayOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE),
                "${Data.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val b = byId.getOrPut(id) {
                        Builder(id, c.getString(2) ?: "", c.getInt(6) != 0, c.getString(7))
                    }
                    when (c.getString(1)) {
                        Phone.CONTENT_ITEM_TYPE -> c.getString(3)?.let { n ->
                            // One entry per number however it is written (update() matches numbers the same way).
                            if (b.phones.none { it.n == n || numberKey(it.n).let { k -> k.isNotEmpty() && k == numberKey(n) } }) b.phones.add(PhoneEntry(n, phoneType(c.getInt(4)), c.getString(5)))
                        }
                        Email.CONTENT_ITEM_TYPE -> c.getString(3)?.let { if (it !in b.emails) b.emails.add(it) }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no contacts permission")
        }
        return byId.values.map { b ->
            Contact(b.id, b.name, b.phones, b.emails, if (withPhotos) b.photo?.let(::thumbnail) else null, b.starred)
        }
    }

    private class Builder(val id: Long, val name: String, val starred: Boolean, val photo: String?) {
        val phones = ArrayList<PhoneEntry>()
        val emails = ArrayList<String>()
    }

    private fun phoneType(t: Int) = PhoneLabels.typeName(t)

    /** Down-scaled JPEG (≤ 96 px), base64. */
    private fun thumbnail(uri: String): String? = try {
        cr.openInputStream(Uri.parse(uri))?.use { s ->
            val bmp = BitmapFactory.decodeStream(s) ?: return null
            val scale = 96f / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 70, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (t: Throwable) {
        null
    }

    /** Star / unstar a contact (Favourites, as in the Phone app). Returns rows changed or -1. */
    fun setStarred(contactId: Long, on: Boolean, dryRun: Boolean = false): Int = try {
        if (dryRun) 0 else cr.update(ContactsContract.Contacts.CONTENT_URI, android.content.ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (on) 1 else 0) },
            "${ContactsContract.Contacts._ID} = ?", arrayOf(contactId.toString()))
    } catch (t: Throwable) {
        android.util.Log.w("Tether", "star refused: ${t.message}"); -1
    }

    fun isStarred(contactId: Long): Boolean? = try {
        cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.STARRED), "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()), null)?.use { if (it.moveToFirst()) it.getInt(0) == 1 else null }
    } catch (t: Throwable) { null }

    /** Debug (adb) clean-up of a throw-away test contact: deletes it only if every number on it is a test number. */
    fun deleteTestContact(contactId: Long): String {
        val c = all(withPhotos = false).firstOrNull { it.id == contactId } ?: return "not found"
        if (c.phones.isEmpty() || c.phones.any { !Regex("""\+?1000000000\d""").matches(it.n.filter { ch -> !ch.isWhitespace() }) }) return "refused: not a test contact"
        val n = cr.delete(ContactsContract.Contacts.CONTENT_URI.buildUpon().appendPath(contactId.toString())
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(), null, null)
        return "deleted $n"
    }

    /** Inserts a device-local contact. Never modifies existing ones. */
    fun add(name: String, phones: List<PhoneEntry>, emails: List<String>, dryRun: Boolean = false): Long {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null).build())
        ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build())
        for (p in phones) { val k = PhoneLabels.forNew(p.type, p.label)
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, p.n).withValue(Phone.TYPE, k.type)
                .withValue(Phone.LABEL, k.label).build()) }
        for (e in emails) ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, e).withValue(Email.TYPE, Email.TYPE_HOME).build())
        if (dryRun) return -ops.size.toLong()
        val res = cr.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = res[0].uri?.lastPathSegment?.toLongOrNull() ?: return -1
        cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID), "${RawContacts._ID} = ?", arrayOf(rawId.toString()), null)?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return -1
    }

    /**
     * Edits an existing contact: sets its name and replaces its phone numbers and e-mail addresses.
     * A contact can be made of several raw contacts (phone, Google, WhatsApp…). Only the writable ones are
     * touched; the new numbers go into the first of them. Returns null on success, otherwise the reason.
     */
    fun update(contactId: Long, name: String, phones: List<PhoneEntry>, emails: List<String>, dryRun: Boolean = false): String? {
        if (contactId < 0 || name.isBlank()) return "missing contact or name"
        val raws = ArrayList<Long>()
        cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0 AND ${RawContacts.RAW_CONTACT_IS_READ_ONLY} = 0",
            arrayOf(contactId.toString()), RawContacts._ID)?.use { c -> while (c.moveToNext()) raws.add(c.getLong(0)) }
        if (raws.isEmpty()) return "this contact cannot be edited (read-only account)"
        val primary = raws.first()
        val inRaws = "${Data.RAW_CONTACT_ID} IN (${raws.joinToString(",")})"
        val ops = ArrayList<ContentProviderOperation>()
        // Name: replace the structured name of the primary raw contact (the provider re-splits DISPLAY_NAME).
        ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection("${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(primary.toString(), StructuredName.CONTENT_ITEM_TYPE)).build())
        ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.RAW_CONTACT_ID, primary)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build())
        // Phones and e-mails: touch only what changed. A row that stays keeps its raw contact (Google, phone…), its
        // type and its custom label; a removed one is deleted where it lives; a new one goes into the primary.
        data class Row(val id: Long, val mime: String, val value: String, val type: Int, val label: String?)
        val existing = ArrayList<Row>()
        cr.query(Data.CONTENT_URI, arrayOf(Data._ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3), "$inRaws AND ${Data.MIMETYPE} IN (?, ?)",
            arrayOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE), null)?.use { c ->
            while (c.moveToNext()) existing.add(Row(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getString(4)))
        }
        val keep = HashSet<Long>()
        for (p in phones) {
            val match = existing.firstOrNull { it.mime == Phone.CONTENT_ITEM_TYPE && it.id !in keep && numberKey(it.value) == numberKey(p.n) && numberKey(p.n).isNotEmpty() }
            if (match != null) {
                keep.add(match.id)
                // Same number: update the text if it was retyped, and the kind only when the user changed it
                // (a custom label stays unless one is sent: PhoneLabels).
                val v = android.content.ContentValues()
                if (match.value != p.n) v.put(Phone.NUMBER, p.n)
                PhoneLabels.forExisting(match.type, match.label, p.type, p.label)?.let { k ->
                    v.put(Phone.TYPE, k.type); if (k.label != null) v.put(Phone.LABEL, k.label) else v.putNull(Phone.LABEL)
                }
                if (v.size() > 0) ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                    .withSelection("${Data._ID} = ?", arrayOf(match.id.toString())).withValues(v).build())
            } else {
                val k = PhoneLabels.forNew(p.type, p.label)
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.RAW_CONTACT_ID, primary)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, p.n).withValue(Phone.TYPE, k.type)
                    .withValue(Phone.LABEL, k.label).build())
            }
        }
        for (e in emails) {
            val match = existing.firstOrNull { it.mime == Email.CONTENT_ITEM_TYPE && it.id !in keep && it.value.equals(e, ignoreCase = true) }
            if (match != null) keep.add(match.id)
            else ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.RAW_CONTACT_ID, primary)
                .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, e).withValue(Email.TYPE, Email.TYPE_HOME).build())
        }
        // The same number or address kept in two accounts shows once on the iPhone: a copy whose value is still
        // wanted stays where it is (only values the user removed are deleted).
        val wantedPhones = phones.map { numberKey(it.n) }.filter { it.isNotEmpty() }.toSet()
        val wantedEmails = emails.map { it.lowercase() }.toSet()
        fun wanted(r: Row) = if (r.mime == Phone.CONTENT_ITEM_TYPE) numberKey(r.value) in wantedPhones else r.value.lowercase() in wantedEmails
        for (r in existing) if (r.id !in keep && !wanted(r)) ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection("${Data._ID} = ?", arrayOf(r.id.toString())).build())
        if (dryRun) return null
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
        return null
    }

    /** Name lookup for a number (used for call.state). */
    fun nameFor(number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        cr.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    companion object { const val TAG = "Tether" }
}
