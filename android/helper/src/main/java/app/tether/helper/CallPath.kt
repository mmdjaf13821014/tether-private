package app.tether.helper

/**
 * When the helper opens a call's audio paths. Pure: the helper polls, this decides.
 *
 * A call's audio exists only once the phone routes it: right at dialing or answering the audio mode is still NORMAL
 * (on the reference phone for the first seconds of an outgoing call, 2026-11-24) and the interception API refuses
 * ("not available in mode MODE_NORMAL"). Falling back to the older methods then gave a capture that delivered nothing
 * (4 frames in a call) and no way back to the interception API except a restart. So:
 * - [ready]: open nothing until the phone is in a call's audio mode ([MicPath.Mode.isCall]), or the call has been
 *   active for [activeMs] (a phone that never reports one still gets its audio).
 * - [retry]: the interception API refusing for the audio mode is tried again quietly while the phone is not in a
 *   call's mode, and for [graceMs] once it is (the mode and the API's own state change a moment apart); any other
 *   error, or a refusal that lasts, moves on to the next method (the older ones are what phones without the API use).
 */
class OpenGate(private val graceMs: Long = 2000, private val activeMs: Long = 3000) {
    private var inCallSince = -1L

    /** [activeForMs]: how long the app has reported the call active (-1: not active, or not reported). */
    fun ready(mode: Int, activeForMs: Long): Boolean = MicPath.Mode.isCall(mode) || activeForMs >= activeMs

    /** After [method] failed with [error] at [nowMs] in audio [mode]: try the same method again? */
    fun retry(method: String, error: String, mode: Int, nowMs: Long, activeForMs: Long): Boolean {
        if (method != "intercept" || !transient(error)) return false
        if (!ready(mode, activeForMs)) { inCallSince = -1; return true }
        if (inCallSince < 0) inCallSince = nowMs
        return nowMs - inCallSince < graceMs
    }

    companion object {
        /** The interception API's refusal for the audio mode (it works once the call's audio is routed). */
        fun transient(error: String): Boolean = error.contains("not available in mode")
    }
}

/**
 * Watches one call's audio paths once a second and says, once each, what a person reporting "no audio" needs to know
 * (issues #13, #14): the downlink capture delivering nothing or only zeros although the call is active, the injected
 * uplink not being taken by the phone (its play position standing still), the track not routed to the call. Pure.
 */
class PathWatch(private val testLine: Boolean, private val deadSeconds: Int = 6, private val stuckSeconds: Int = 3) {
    /**
     * One second: whether the call was active, downlink frames read and their non-zero samples, whether uplink audio
     * was waiting for the track (written this second, or queued: a track nobody takes from blocks its writer), and how
     * far the track's play position moved (samples; -1 unknown).
     */
    data class Second(val active: Boolean, val downFrames: Int, val downNonZero: Int, val upWaiting: Boolean, val upPlayed: Long)

    enum class Event(val wire: String) { DOWN_NOTHING("down-nothing"), DOWN_ZEROS("down-zeros"), UP_STUCK("up-stuck") }

    private var activeSeconds = 0
    private var downAlive = false
    private var noFrames = 0
    private var stuck = 0
    private val said = HashSet<Event>()

    /** What this second showed that was not said before (null: nothing new). */
    fun second(s: Second): Event? {
        if (!s.active) { noFrames = 0; stuck = 0; return null }
        activeSeconds++
        if (s.downNonZero >= RedirectGuard.ALIVE_SAMPLES) downAlive = true
        noFrames = if (s.downFrames == 0) noFrames + 1 else 0
        stuck = if (s.upWaiting && s.upPlayed == 0L) stuck + 1 else 0
        return when {
            noFrames >= stuckSeconds && say(Event.DOWN_NOTHING) -> Event.DOWN_NOTHING
            // The test line's far end plays nothing: zeros there are right.
            !testLine && !downAlive && activeSeconds >= deadSeconds && s.downFrames > 0 && say(Event.DOWN_ZEROS) -> Event.DOWN_ZEROS
            stuck >= stuckSeconds && say(Event.UP_STUCK) -> Event.UP_STUCK
            else -> null
        }
    }

    private fun say(e: Event) = said.add(e)

    companion object {
        /** The link-log text for [e] with the methods in use. */
        fun text(e: Event, capture: String?, inject: String?, routed: String?): String = when (e) {
            Event.DOWN_NOTHING -> "the downlink capture ($capture) delivered nothing for 3 s of the active call: the iPhone hears nothing"
            Event.DOWN_ZEROS -> "the downlink capture ($capture) reads only zeros in the active call: this phone does not give it the call's audio (the iPhone hears nothing)"
            Event.UP_STUCK -> "the uplink injection ($inject, routed $routed) is not being taken by the phone: the far end cannot hear the iPhone"
        }
    }
}
