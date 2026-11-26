package app.tether.adb

/** The two ways to reach the phone's own adbd (see [connectInOrder]). */
interface AdbEndpoints {
    /** Wireless debugging (TLS, found by mDNS). */
    fun connectTls(): Boolean
    /** Legacy TCP mode (`adb tcpip`) on 127.0.0.1:[port], RSA key approval. */
    fun connectLegacy(port: Int): Boolean
}

/** How a connection was made: "tls", "legacy", or null when neither worked. */
object ConnectOrder {
    const val DEFAULT_LEGACY_PORT = 5555

    /** Wireless debugging exists from Android 11. */
    fun tlsSupported(sdk: Int) = sdk >= 30
}

/**
 * Wireless debugging first (when this Android has it), then, only if the user opted in, legacy TCP adb on the
 * loopback address. A failure or exception of one way never stops the other; [log] gets one line per failure.
 */
fun connectInOrder(e: AdbEndpoints, tlsSupported: Boolean, legacyEnabled: Boolean, legacyPort: Int, log: (String) -> Unit = {}): String? {
    if (tlsSupported) {
        try { if (e.connectTls()) return "tls" } catch (t: Throwable) { log("wireless debugging connect failed: ${t.message}") }
    }
    if (legacyEnabled) {
        try { if (e.connectLegacy(legacyPort)) return "legacy" } catch (t: Throwable) { log("TCP adb connect failed: ${t.message}") }
    }
    return null
}
