package app.tether.adb

import app.tether.R
import app.tether.adb.AdbPairing.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pairing from the notification (issue #1): every code and every failure ends in a clear message, never a crash. */
class AdbPairingTest {
    @Test fun codeIsSixDigitsInAnyScript() {
        assertEquals("123456", AdbPairing.normalizeCode("123456"))
        assertEquals("123456", AdbPairing.normalizeCode(" 123 456 "))
        assertEquals("123456", AdbPairing.normalizeCode("123-456"))
        assertEquals("123456", AdbPairing.normalizeCode("۱۲۳۴۵۶"))       // Persian keyboard
        assertEquals("123456", AdbPairing.normalizeCode("١٢٣٤٥٦"))       // Arabic-Indic digits
    }

    @Test fun anythingElseIsNoCode() {
        for (s in listOf(null, "", "12345", "1234567", "abcdef", "12 34 5")) assertNull(s, AdbPairing.normalizeCode(s))
    }

    @Test fun reasonIsOneShortLine() {
        val long = IllegalStateException("x".repeat(5000) + "\n\tat somewhere\n\tat elsewhere")
        val r = AdbPairing.shortReason(long)
        assertTrue(r.length <= 160)
        assertFalse(r.contains('\n'))
        assertEquals("IllegalStateException", AdbPairing.shortReason(IllegalStateException()))
        assertEquals("closed by peer", AdbPairing.shortReason(java.io.IOException("  closed \n by   peer ")))
    }

    @Test fun eachResultHasItsMessage() {
        assertEquals(R.string.adb_paired to null, AdbPairing.message(Result.OK))
        assertEquals(R.string.adb_code_format to null, AdbPairing.message(Result.BAD_CODE))
        assertEquals(R.string.adb_no_wifi to null, AdbPairing.message(Result.NO_WIFI))
        assertEquals(R.string.adb_no_service to null, AdbPairing.message(Result.NO_SERVICE))
        assertEquals(R.string.adb_wrong_code to null, AdbPairing.message(Result.WRONG_CODE))
        assertEquals(R.string.adb_failed to "boom", AdbPairing.message(Result.FAILED, "boom"))
        assertEquals(R.string.adb_paired_no_connect to "refused", AdbPairing.message(Result.PAIRED_NO_CONNECT, "refused"))
    }

    @Test fun failuresKeepTheCodeFieldOpen() {
        for (r in listOf(Result.BAD_CODE, Result.NO_WIFI, Result.NO_SERVICE, Result.WRONG_CODE, Result.FAILED)) assertTrue(r.name, AdbPairing.asksAgain(r))
        // Paired: nothing more to type (the connection is retried by the app itself).
        assertFalse(AdbPairing.asksAgain(Result.OK))
        assertFalse(AdbPairing.asksAgain(Result.PAIRED_NO_CONNECT))
    }
}
