package app.tether

import app.tether.core.LinkSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The link must never depend on Bluetooth bonding (see LinkSecurity): fails if anyone brings encryption back. */
class LinkSecurityTest {
    @Test fun permissionsUsedByTheServiceNeedNoLinkSecurity() {
        for (p in listOf(LinkSecurity.CHAR_READ, LinkSecurity.CHAR_WRITE, LinkSecurity.DESC_READ_WRITE))
            assertFalse(LinkSecurity.requiresLinkSecurity(p))
        assertTrue(LinkSecurity.requiresLinkSecurity(0x02))   // READ_ENCRYPTED
        assertTrue(LinkSecurity.requiresLinkSecurity(0x20))   // WRITE_ENCRYPTED
        assertEquals("ok", LinkSecurity.check(listOf("psm" to listOf(LinkSecurity.CHAR_READ))))
        assertTrue(LinkSecurity.check(listOf("psm" to listOf(0x02))).contains("psm"))
    }

    @Test fun peripheralSourceUsesNoEncryptedPermissionOrSecureL2cap() {
        val raw = listOf("src/main/java/app/tether/link/BlePeripheral.kt", "app/src/main/java/app/tether/link/BlePeripheral.kt")
            .map(::File).first { it.exists() }.readText()
        // The only mention of encrypted permissions allowed is the block that cross-checks LinkSecurity's constants.
        val code = raw.replace(Regex("val androidMask[\\s\\S]*?securityCheck = "), "securityCheck = ")
            .lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        for (bad in listOf("_ENCRYPTED", "_SIGNED", "listenUsingL2capChannel(", "createBond(", "createL2capChannel("))
            assertFalse("BlePeripheral.kt uses $bad", code.contains(bad))
        assertTrue(code.contains("listenUsingInsecureL2capChannel("))
    }
}
