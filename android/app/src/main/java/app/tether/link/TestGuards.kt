package app.tether.link

import app.tether.core.T

/**
 * The decisions that keep tests away from the owner's real calls, texts and radios, kept pure so they are
 * unit-tested (TestGuardsTest). Hub and DebugReceiver ask these; nothing here touches Android.
 */
object TestGuards {
    private val testNumber = Regex("""\+?1000000000[1-9]""")

    /** The only numbers the delete / block / contact hooks accept: +10000000001 … +10000000009. */
    fun isTestNumber(n: String) = testNumber.matches(n.filter { !it.isWhitespace() })

    /** A fake ring / fake outgoing call may reach a linked iPhone only when asked for (`--es allow iphone`). */
    fun fakeCallAllowed(linkedKinds: Collection<String>, allow: String?) = "iphone" !in linkedKinds || allow == "iphone"

    /** Messages a test client may send only for calls on the test line. */
    val callControls = setOf(T.CALL_ANSWER, T.CALL_TAKE, T.CALL_REJECT, T.CALL_END, T.CALL_HOLD, T.CALL_DTMF, T.SELFTEST)

    /**
     * Whether message [t] from a client may go ahead. A dry (test) client may touch only calls on the test line;
     * taking the audio (answer, take) or a self-test needs every live call to be on it. [activeAreTest]: one entry
     * per live call, true when it is on the test line; [cidIsTest]: the message's own call id is a test-line call.
     */
    fun callControlAllowed(dry: Boolean, t: String, activeAreTest: List<Boolean>, cidIsTest: Boolean): Boolean {
        if (!dry || t !in callControls) return true
        val allTest = activeAreTest.isNotEmpty() && activeAreTest.all { it }
        return when (t) {
            T.CALL_TAKE, T.SELFTEST -> allTest
            T.CALL_ANSWER -> allTest && cidIsTest
            else -> cidIsTest
        }
    }

    /** A message from this client is a dry run: a test transport always; the debug dry run only for admitted clients. */
    fun isDry(transportDry: Boolean, dryRun: Boolean, admitted: Boolean) = transportDry || (dryRun && admitted)

    /**
     * Whether a client gets broadcasts: `only <kind>` (debug) filters real clients, never on-device test clients, and
     * only for test traffic: a [real] event (a call not on the test line, a text not from a test number) always reaches
     * every client. On 2026-11-25 a test left `only none` on and a call dialed from the iPhone was placed, answered and
     * ran silent with the iPhone never told.
     */
    fun receivesBroadcasts(up: Boolean, admitted: Boolean, onlyKind: String?, kind: String, dry: Boolean, real: Boolean = false) =
        up && admitted && (real || onlyKind == null || kind == onlyKind || dry)

    /** `only` switches itself off this long after it was set, so a test that forgets to clear it cannot hide calls for long. */
    const val ONLY_MS = 30 * 60_000L
    fun onlyActive(setAt: Long, now: Long) = now - setAt < ONLY_MS

    private val netSwitches = setOf("data", "hotspot", "bt", "dataSub", "share", "airplane", "rat")

    /** The switch a net.set flips; null for net.get, an unknown switch, or a test client (it only reads). */
    fun netSwitch(t: String, dry: Boolean, what: String?): String? =
        if (t == T.NET_SET && !dry) what?.takeIf { it in netSwitches } else null

    /** Whether a link's address is remembered as the iPhone's and held on LE: an iPhone on a real Bluetooth link only. */
    fun holdsLe(label: String, kind: String, addressValid: Boolean) = label != "memory" && kind == "iphone" && addressValid
}
