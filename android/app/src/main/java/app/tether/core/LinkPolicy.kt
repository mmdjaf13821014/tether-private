package app.tether.core

/** Small decisions of the link, kept pure so they are unit-tested (see LinkPolicyTest). */
object LinkPolicy {
    /**
     * Whether a GATT session must end when an ACL of the same central changes. iOS runs GATT over whichever
     * transport it has (classic Bluetooth while the phones are paired for tethering, LE otherwise) and moves it
     * when the other one appears or goes: the session on the old bearer is then dead even if no GATT disconnect
     * arrives for it.
     */
    fun gattPipeEnds(pipeOnLe: Boolean, aclIsLe: Boolean, aclUp: Boolean): Boolean =
        if (aclIsLe) (aclUp && !pipeOnLe) || (!aclUp && pipeOnLe) else (!aclUp && !pipeOnLe)

    /**
     * Our own LE link to the paired iPhone. Held only while a session is up (keeps iOS on LE under Bluetooth
     * tethering); never otherwise, because iOS does not complete an app's new connect over a link we hold. While
     * paired without a session, pulsed (up briefly, then dropped): a link coming up completes a waiting connect.
     */
    fun holdLeLink(sessionUp: Boolean) = sessionUp
    /**
     * [linkOpen]: any LE link to the iPhone exists, ours or a lingering one. Only a link coming *up* completes the
     * app's waiting connect; a pulse into an existing link just keeps it alive, and back-to-back pulses held one
     * link open forever so the reopened app never connected (seen 2026-11-22). So: pulse only when there is none.
     */
    fun pulseLeLink(paired: Boolean, sessionUp: Boolean, linkOpen: Boolean) = paired && !sessionUp && !linkOpen

    /** After a session ended or the service started: try a pulse every [PULSE_SOON_EVERY_S] s for [PULSE_SOON_FOR_S] s. */
    const val PULSE_SOON_EVERY_S = 5L
    const val PULSE_SOON_FOR_S = 60L
    const val PULSE_EVERY_S = 30L
    /** Short, so the link (plus Android's ~4 s linger after release) is gone before the next pulse. */
    const val PULSE_UP_MS = 3_000L

    /** What to do with a session that has been silent for [silentMs]; [sinceWakeMs]: since we woke the client without
     *  an answer yet (null: not woken since it last sent anything). */
    enum class Idle { KEEP, WAKE, CLOSE }

    /**
     * A suspended iPhone app sends nothing and cannot answer pings, so silence alone means nothing. After
     * [IDLE_WAKE_MS] of it the client is woken (GATT wake notification) and pinged; only a client that still says
     * nothing [WAKE_ANSWER_MS] later is gone (a half-open session). Closing at once instead made the iPhone relink,
     * handshake and sync every 15 minutes (seen 2026-11-23: 13 relinks in 3 hours).
     */
    fun idle(silentMs: Long, sinceWakeMs: Long?): Idle = when {
        silentMs < IDLE_WAKE_MS -> Idle.KEEP
        sinceWakeMs == null -> Idle.WAKE
        sinceWakeMs >= WAKE_ANSWER_MS -> Idle.CLOSE
        else -> Idle.KEEP
    }
    const val IDLE_WAKE_MS = 15 * 60_000L
    const val WAKE_ANSWER_MS = 45_000L

    /** With no session, a paired iPhone is woken this often (see Hub.wakeWithoutSession). */
    fun wakeUnlinked(sinceLastMs: Long) = sinceLastMs >= WAKE_UNLINKED_MS
    const val WAKE_UNLINKED_MS = 3 * 60_000L
}

/**
 * The token a pair-mode HELLO must prove. The open pairing code's; or, for [windowMs] after a pairing completed,
 * that pairing's token, for the same central only (it may not have received our FIN and says hello in pair mode
 * again; refusing it made it forget a pairing that had succeeded).
 */
class PairingTokens(private val windowMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var last: Triple<ByteArray, ByteArray, Long>? = null

    @Synchronized fun completed(token: ByteArray, centralKey: ByteArray) { last = Triple(token, centralKey, now() + windowMs) }

    @Synchronized fun tokenFor(centralKey: ByteArray, open: ByteArray?): ByteArray? =
        open ?: last?.takeIf { it.second.contentEquals(centralKey) && now() < it.third }?.first
}
