package app.tether.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a call's audio goes (protocol §5.1), issue #13: a call taken on the Android phone stays on it. */
class AudioOwnerTest {
    private class Client(val key: String, val name: String)
    private val iphone = Client("aa", "iPhone")
    private val second = Client("bb", "Pixel")
    private val live = listOf(iphone, second)

    @Test fun aCallNobodyClaimedStaysOnTheAndroidPhone() {
        // Answered on the phone's own screen, a headset or a car connected to it: before 1.2.2 the iPhone got the
        // audio and the phone's earpiece and mic were muted, so the call had no audio where it was taken.
        assertNull(AudioOwner.pick(live, { it.key }, claimedKey = null))
        assertTrue(AudioOwner.elsewhere("active", owner = null, client = iphone, blocked = false))
        assertEquals("AMIR's A73", AudioOwner.where(null, "AMIR's A73"))
    }

    @Test fun theClientThatClaimedGetsItBackAfterReconnecting() {
        val again = Client("aa", "iPhone")   // a new link from the same iPhone (same static key)
        assertSame(again, AudioOwner.pick(listOf(second, again), { it.key }, claimedKey = "aa"))
        assertNull("not while it is away", AudioOwner.pick(listOf(second), { it.key }, claimedKey = "aa"))
    }

    @Test fun theClaimLastsWhileACallIsLeft() {
        assertTrue(AudioOwner.keepClaim(listOf("active")))
        assertTrue(AudioOwner.keepClaim(listOf("ended", "holding")))
        assertFalse(AudioOwner.keepClaim(listOf("ended")))
        assertFalse(AudioOwner.keepClaim(emptyList()))
    }

    @Test fun elsewhereFromEachClientsView() {
        assertFalse("the owner itself", AudioOwner.elsewhere("active", iphone, iphone, blocked = false))
        assertTrue("another client", AudioOwner.elsewhere("active", iphone, second, blocked = false))
        assertEquals("iPhone", AudioOwner.where(iphone.name, "A73"))
        for (s in listOf("ringing", "ended")) assertFalse("every client rings; an ended call is nowhere", AudioOwner.elsewhere(s, null, iphone, blocked = false))
        assertTrue("dialed on the phone itself", AudioOwner.elsewhere("dialing", null, iphone, blocked = false))
    }

    @Test fun aPhoneThatGivesNoCallAudioKeepsEveryCall() {
        assertTrue("even for the client that answered", AudioOwner.elsewhere("active", iphone, iphone, blocked = true))
        assertFalse(AudioOwner.elsewhere("ringing", iphone, iphone, blocked = true))
    }

    @Test fun blockedIsRememberedPerFirmware() {
        assertTrue(AudioOwner.blocked("samsung/a73xq/a73xq:16/X/1:user/release-keys", "samsung/a73xq/a73xq:16/X/1:user/release-keys"))
        assertFalse("a system update clears it", AudioOwner.blocked("samsung/a73xq/a73xq:16/X/1:user/release-keys", "samsung/a73xq/a73xq:16/Y/2:user/release-keys"))
        assertFalse(AudioOwner.blocked(null, "x"))
        assertFalse("a new Tether version tries again", AudioOwner.blocked("fp|1020299", "fp|1020499"))
        assertTrue(AudioOwner.blocked("fp|1020499", "fp|1020499"))
    }
}
