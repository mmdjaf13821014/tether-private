package app.tether.client.link

/**
 * The reconnect decisions of the iPhone's TetherLink, kept pure so they are unit-tested (ClientLinkPolicyTest):
 * reconnect at once after a failure, back off only when setup keeps failing, a 12 s setup watchdog, a session
 * restarted on a Bluetooth connection that is still up (twice in a row at most), and what each REJECT means.
 */
class ClientLinkPolicy(private val now: () -> Long = System::currentTimeMillis) {
    private val failures = ArrayDeque<Long>()
    /** Sessions restarted in a row on a connection that stayed up (reset when a session comes up). */
    var restartsOnOpenConnection = 0; private set

    /** A session came up: forget earlier failures. */
    fun up() { failures.clear(); restartsOnOpenConnection = 0 }

    /** The app came to the foreground: connecting must take seconds, not a back-off. */
    fun forget() { failures.clear() }

    /**
     * A session failed. [connectionUp]: the Bluetooth connection is still there. Returns what to do next.
     * Setup timeouts and pairing attempts always start from a fresh connection.
     */
    fun failed(reason: String, connectionUp: Boolean, pairing: Boolean): Next {
        if (connectionUp && restartsOnOpenConnection < 2 && !pairing && !reason.startsWith(SETUP_TIMEOUT)) {
            restartsOnOpenConnection++
            return Next.RestartOnConnection
        }
        restartsOnOpenConnection = 0
        return reconnect()
    }

    /** The connection itself went (or failed to come up). */
    fun disconnected(): Next { restartsOnOpenConnection = 0; return reconnect() }

    private fun reconnect(): Next {
        val t = now()
        while (failures.isNotEmpty() && t - failures.first() >= 60_000) failures.removeFirst()
        failures.addLast(t)
        // At once for the first three failures in a minute; then a pause growing with each failure, at most 30 s.
        return if (failures.size <= 3) Next.Reconnect(0) else Next.Reconnect(minOf(30, 2 * failures.size) * 1000L)
    }

    /** What a REJECT from the Android phone means (protocol §3): [reason] is its first byte. */
    fun rejected(reason: Int, pairing: Boolean): Reject = when {
        reason == 3 && pairing -> Reject.PairingExpired
        reason == 2 -> Reject.Wait(UNKNOWN_RETRY_MS, "unknown_device")
        reason == 4 -> Reject.Wait(BUSY_RETRY_MS, null)
        else -> Reject.Fail
    }

    sealed class Reject {
        /** Pairing code expired or already used: stop this pairing attempt and forget the half-made pairing. */
        object PairingExpired : Reject()
        /** Keep the keys, try again after [ms]; [problem] is shown when not null. */
        data class Wait(val ms: Long, val problem: String?) : Reject()
        /** Anything else: an ordinary failure (reconnect rules apply). */
        object Fail : Reject()
    }

    sealed class Next {
        object RestartOnConnection : Next()
        data class Reconnect(val afterMs: Long) : Next()
    }

    companion object {
        const val SETUP_TIMEOUT = "setup timed out"
        /** Services → PSM → L2CAP → handshake normally takes well under a second. */
        const val SETUP_WATCHDOG_MS = 12_000L
        /** No L2CAP channel within this long after the PSM read: the GATT pipe is used. */
        const val L2CAP_TIMEOUT_MS = 6_000L
        /** A connect (to a known or a scanned address) that has not completed is renewed. */
        const val CONNECT_WATCHDOG_MS = 10_000L
        /** Keep-alive ping while linked (the Android phone answers at once). */
        const val PING_MS = 25_000L
        /** A record half received for this long: the stream is stuck (a lost GATT chunk). */
        const val STALL_MS = 10_000L
        /**
         * Nothing received for this long although we ping every 25 s: the link is dead (an Android central is not
         * suspended like an iPhone app, so a silent link is not normal). A ping is sent first and the verdict waits
         * [SILENCE_GRACE_MS] more, so a CPU that slept through our timers is not taken for a dead link.
         */
        const val SILENCE_MS = 80_000L
        const val SILENCE_GRACE_MS = 10_000L

        /** REJECT 2 (the Android phone does not know us): keep the keys, try again after a minute. */
        const val UNKNOWN_RETRY_MS = 60_000L
        /** REJECT 4 (busy, e.g. a test holds the link): try again soon. */
        const val BUSY_RETRY_MS = 5_000L
    }
}
