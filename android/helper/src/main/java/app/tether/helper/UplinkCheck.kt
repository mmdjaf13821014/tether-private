package app.tether.helper

/**
 * Does the iPhone's voice reach the call while the phone's own microphone is muted locally? Fed once a second with
 * what was injected (the iPhone's speech) and what a `VOICE_UPLINK` capture heard of the call's uplink in that second.
 *
 * - While the iPhone clearly talks and the uplink capture stays quiet for [SILENT_WINDOWS] such seconds, the local
 *   mutes are undone ([Action.UNMUTE]). The mute is then off for the rest of the call whatever follows: a muted far end
 *   is far worse than some bag noise.
 * - The uplink heard the speech in [ALIVE_WINDOWS] seconds: [State.OK] with the mute still on, or [State.FIXED] after
 *   undoing it (the app remembers that for this phone model and starts the next calls without the mute).
 * - The capture is quiet without the mutes too, or no answer came within [maxWindows] seconds: [State.UNVERIFIED].
 *   Some HALs never show the injected audio in `VOICE_UPLINK` (Tether's own test line is one): nothing is remembered.
 *
 * Pure: the helper does the audio, this decides. Levels are RMS of 16-bit PCM.
 */
class UplinkCheck(private val maxWindows: Int = 90, val mitigation: Mitigation = Mitigation.MUTE) {
    enum class State(val wire: String) { CHECKING("checking"), OK("ok"), FIXED("muted-locally-fixed"), UNVERIFIED("unverified") }
    enum class Action { NONE, UNMUTE }
    /**
     * What keeps the phone's mic out of the call and is undone when the uplink stays silent ([Action.UNMUTE]): its local
     * mute ([LocalMutes]) or a redirect audio mode ([RedirectGuard]); `wire` as INFO `uplink.for`.
     */
    enum class Mitigation(val wire: String, val text: String) { MUTE("mute", "the phone's mic muted"), REDIRECT("redirect", "the redirect audio mode") }

    /** One second: frames of injected speech, their mean level, and the uplink capture's mean level. */
    data class Window(val speechFrames: Int, val speechRms: Double, val uplinkRms: Double)

    companion object {
        /** A 20 ms frame at or above this level is speech (about -40 dBFS). */
        const val SPEECH_RMS = 300.0
        /** A second with at least this many speech frames (of 50) counts. */
        const val SPEAKING_FRAMES = 15
        /** The uplink "hears" the speech when its level reaches this share of the injected level, and at least [ALIVE_RMS]. */
        const val ALIVE_SHARE = 0.1
        const val ALIVE_RMS = 40.0
        const val SILENT_WINDOWS = 3
        const val ALIVE_WINDOWS = 2
    }

    @Volatile var state = State.CHECKING; private set
    /** The mutes were undone during this call. */
    @Volatile var unmuted = false; private set
    @Volatile var note = ""; private set
    /** Ended by [fail] (no capture), not by what it heard. */
    @Volatile var failed = false; private set
    /** Ended because the capture heard the iPhone neither with nor without the mitigation: VOICE_UPLINK is blind here. */
    @Volatile var blind = false; private set
    val done: Boolean get() = state != State.CHECKING

    private var windows = 0
    private var skip = 1   // the first second: the paths are still starting
    private var alive = 0
    private var quiet = 0

    fun window(w: Window): Action {
        if (done) return Action.NONE
        if (++windows > maxWindows) { finish(State.UNVERIFIED, "no clear answer in $maxWindows s (the iPhone said little)"); return Action.NONE }
        if (skip > 0) { skip--; return Action.NONE }
        if (w.speechFrames < SPEAKING_FRAMES) return Action.NONE
        if (w.uplinkRms >= maxOf(ALIVE_RMS, ALIVE_SHARE * w.speechRms)) alive++ else quiet++
        if (alive >= ALIVE_WINDOWS) {
            finish(if (unmuted) State.FIXED else State.OK,
                if (unmuted) "the uplink was silent with ${mitigation.text} and hears the iPhone without it"
                else "the uplink hears the iPhone with ${mitigation.text}")
        } else if (quiet >= SILENT_WINDOWS) {
            if (!unmuted) {
                unmuted = true; alive = 0; quiet = 0; skip = 1
                note = "the uplink stayed silent for $SILENT_WINDOWS s of iPhone speech with ${mitigation.text}: undone"
                return Action.UNMUTE
            }
            blind = true
            finish(State.UNVERIFIED, "the uplink capture stays silent without ${mitigation.text} too: this phone's VOICE_UPLINK does not show the injected voice")
        }
        return Action.NONE
    }

    /** The uplink capture could not be opened (or broke): nothing can be checked. */
    fun fail(reason: String) { if (!done) { failed = true; finish(State.UNVERIFIED, reason) } }

    private fun finish(s: State, why: String) { state = s; note = why }
}

/** The injected side of [UplinkCheck]: the up thread adds every frame it writes, the check takes a window a second. */
class InjectMeter {
    private var speech = 0
    private var sum = 0.0

    @Synchronized fun add(rms: Double) { if (rms >= UplinkCheck.SPEECH_RMS) { speech++; sum += rms } }

    /** Speech frames and their mean level since the last call. */
    @Synchronized fun take(): Pair<Int, Double> {
        val r = speech to (if (speech > 0) sum / speech else 0.0)
        speech = 0; sum = 0.0
        return r
    }
}
