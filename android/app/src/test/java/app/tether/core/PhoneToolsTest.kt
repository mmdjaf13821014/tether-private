package app.tether.core

import app.tether.data.UssdCode
import app.tether.telecom.RingerControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolsTest {
    @Test fun ussdCodes() {
        for (ok in listOf("*123#", "#123#", "*#06#", "*100*1#", "*1#")) assertTrue(ok, UssdCode.valid(ok))
        for (bad in listOf("", "123", "*123", "*#", "#", "*12a#", "*123# ", "tel:*123#", "*123#\n", "123#", "*" + "1".repeat(31) + "#"))
            assertFalse(bad, UssdCode.valid(bad))
        assertTrue(UssdCode.valid("*" + "1".repeat(30) + "#"))
    }

    @Test fun phoneToolMessages() {
        val m = Message.parse(Message(T.USSD_SEND, org.json.JSONObject().put("code", "*123#").put("sub", 3), id = 7).toJson())
        assertEquals(T.USSD_SEND, m.t); assertEquals("*123#", m.b.getString("code")); assertEquals(3, m.b.getInt("sub")); assertEquals(7, m.id)
        val f = Message.parse(Message(T.PHONE_FIND, org.json.JSONObject().put("on", true)).toJson())
        assertTrue(f.b.getBoolean("on"))
        assertEquals("ringer.set", T.RINGER_SET)
    }

    @Test fun ringerModes() {
        assertEquals(listOf("normal", "vibrate", "silent"), RingerControl.modes)
        assertNull(RingerControl.parse("loud")); assertNull(RingerControl.parse(null))
    }
}
