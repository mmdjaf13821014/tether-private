package app.tether.core

/**
 * The Bluetooth link carries Tether's own end-to-end encryption (P-256 handshake, AES-GCM records), so it must not
 * depend on Bluetooth bonding at all: no GATT characteristic or descriptor asks for link encryption, the L2CAP
 * channel is the insecure one, and nothing triggers an LE pairing. A bond the phones make for something else
 * (Bluetooth tethering) can leave a broken LE key behind — on 2026-11-22 the Android phone recorded the iPhone's derived LE
 * key with size 0, and every encrypted read failed with "insufficient encryption key size".
 *
 * The permission values are Android's (BluetoothGattCharacteristic / BluetoothGattDescriptor), kept here so the
 * rule is unit-tested without the Android framework.
 */
object LinkSecurity {
    const val PERMISSION_READ = 0x01
    const val PERMISSION_WRITE = 0x10
    /** READ_ENCRYPTED, READ_ENCRYPTED_MITM, WRITE_ENCRYPTED, WRITE_ENCRYPTED_MITM, WRITE_SIGNED, WRITE_SIGNED_MITM. */
    const val SECURE_MASK = 0x02 or 0x04 or 0x20 or 0x40 or 0x80 or 0x100

    const val CHAR_READ = PERMISSION_READ
    const val CHAR_WRITE = PERMISSION_WRITE
    const val DESC_READ_WRITE = PERMISSION_READ or PERMISSION_WRITE

    fun requiresLinkSecurity(permissions: Int) = permissions and SECURE_MASK != 0

    /** "ok", or which characteristics (by UUID) ask for link security. */
    fun check(items: List<Pair<String, List<Int>>>): String {
        val bad = items.filter { (_, perms) -> perms.any(::requiresLinkSecurity) }.map { it.first }
        return if (bad.isEmpty()) "ok" else "link security required by $bad"
    }
}
