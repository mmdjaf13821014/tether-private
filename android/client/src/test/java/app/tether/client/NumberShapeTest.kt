package app.tether.client

import app.tether.client.model.NumberShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message bubbles never offer a date, version, address or amount as a phone number (same rules as the iPhone). */
class NumberShapeTest {
    @Test fun notPhoneNumbers() {
        for (s in listOf("2024-11-23", "23/11/2024", "23.11.2024", "11-23-2024", "۱۴۰۳/۰۹/۰۲", "1.2.3", "17.2.1",
            "192.168.1.10", "1,250,000", "12,345,678.90", "1234567.89", "09:00-17:30")) assertTrue(s, NumberShape.isNotAPhoneNumber(s))
    }

    @Test fun phoneNumbers() {
        for (s in listOf("+1 (000) 000-0001", "0912 123 4567", "021-1234-5678", "555.123.4567", "555-12-34", "+98 21 1234 5678",
            "۰۹۱۲۱۲۳۴۵۶۷")) assertFalse(s, NumberShape.isNotAPhoneNumber(s))
    }
}
