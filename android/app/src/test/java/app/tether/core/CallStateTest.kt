package app.tether.core

import app.tether.telecom.chooseCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallStateTest {
    @Test fun endTargetsItsOwnCall() {
        assertEquals("A", chooseCall("a", mapOf("a" to "A", "b" to "B"), emptySet()))
    }

    @Test fun lateEndForAnEndedCallNeverHitsTheNextCall() {
        // Call "a" ended here; a new call "b" is ringing; a late / re-sent end for "a" arrives.
        assertNull(chooseCall("a", mapOf("b" to "B"), setOf("a")))
    }

    @Test fun unknownIdAfterARestartFallsBackToTheOnlyCall() {
        assertEquals("B", chooseCall("x", mapOf("b" to "B"), emptySet()))
        assertNull(chooseCall("x", mapOf("b" to "B", "c" to "C"), emptySet()))   // ambiguous: touch nothing
        assertNull(chooseCall("x", emptyMap<String, String>(), emptySet()))
    }
}
