package app.tether.data

import android.provider.ContactsContract.CommonDataKinds.Phone
import app.tether.data.PhoneLabels.Stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A number's kind and custom label survive edits from the iPhone (and older iPhone builds that send no labels). */
class PhoneLabelsTest {
    @Test fun newNumberWithLabelIsCustom() {
        assertEquals(Stored(Phone.TYPE_CUSTOM, "Studio"), PhoneLabels.forNew("other", "Studio"))
        assertEquals(Stored(Phone.TYPE_CUSTOM, "Studio"), PhoneLabels.forNew("mobile", "  Studio "))
    }

    @Test fun newNumberWithoutLabelKeepsItsType() {
        assertEquals(Stored(Phone.TYPE_WORK, null), PhoneLabels.forNew("work", null))
        assertEquals(Stored(Phone.TYPE_MOBILE, null), PhoneLabels.forNew("mobile", ""))
        assertEquals(Stored(Phone.TYPE_MOBILE, null), PhoneLabels.forNew("unknown kind", null))
    }

    @Test fun oldIphoneEditKeepsCustomLabel() {
        // Older builds send type "other" and no label for a custom-labelled number: nothing may change.
        assertNull(PhoneLabels.forExisting(Phone.TYPE_CUSTOM, "Satellite", "other", null))
    }

    @Test fun unchangedLabelChangesNothing() {
        assertNull(PhoneLabels.forExisting(Phone.TYPE_CUSTOM, "Satellite", "other", "Satellite"))
        assertNull(PhoneLabels.forExisting(Phone.TYPE_HOME, null, "home", null))
        assertNull(PhoneLabels.forExisting(Phone.TYPE_HOME, null, "home", ""))
    }

    @Test fun labelSetOrRenamed() {
        assertEquals(Stored(Phone.TYPE_CUSTOM, "Studio"), PhoneLabels.forExisting(Phone.TYPE_MOBILE, null, "mobile", "Studio"))
        assertEquals(Stored(Phone.TYPE_CUSTOM, "Studio"), PhoneLabels.forExisting(Phone.TYPE_CUSTOM, "Satellite", "other", "Studio"))
    }

    @Test fun emptyLabelClearsCustomToTheChosenType() {
        assertEquals(Stored(Phone.TYPE_OTHER, null), PhoneLabels.forExisting(Phone.TYPE_CUSTOM, "Satellite", "other", ""))
        assertEquals(Stored(Phone.TYPE_HOME, null), PhoneLabels.forExisting(Phone.TYPE_CUSTOM, "Satellite", "home", ""))
    }

    @Test fun typeChangeWithoutLabel() {
        assertEquals(Stored(Phone.TYPE_WORK, null), PhoneLabels.forExisting(Phone.TYPE_MOBILE, null, "work", null))
        // A standard kind picked for a custom one by an older build drops the label.
        assertEquals(Stored(Phone.TYPE_HOME, null), PhoneLabels.forExisting(Phone.TYPE_CUSTOM, "Satellite", "home", null))
    }

    @Test fun workMobileCountsAsWork() {
        assertNull(PhoneLabels.forExisting(Phone.TYPE_WORK_MOBILE, null, "work", null))
    }
}
