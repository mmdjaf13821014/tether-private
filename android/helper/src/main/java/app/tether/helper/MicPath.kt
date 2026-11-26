package app.tether.helper

import org.json.JSONObject

/**
 * How the Android phone's own microphone is kept out of a bridged call (issue #2), decided per call.
 *
 * The platform has audio modes made for this (Android 13+): with [Redirect.CALL_REDIRECT] ("a telephony call is
 * established and its audio is being redirected to another device") AOSP's audio policy releases the call's own routing
 * (microphone → Telephony Tx, Telephony Rx → earpiece: `AudioPolicyManager::setPhoneState` only routes the call in
 * MODE_IN_CALL) while the call stays accessible to the interception API (`isCallAudioAccessible`: IN_CALL,
 * CALL_SCREEN, CALL_REDIRECT). So the far end hears only the injected voice and the phone plays nothing, without any
 * mute that could also silence the injection. The shell may set it (`AudioService.setMode` needs MODIFY_PHONE_STATE for
 * IN_CALL / CALL_REDIRECT / COMMUNICATION_REDIRECT); the most recent privileged request owns the mode, so the helper
 * overrides Telecom's MODE_IN_CALL, and the mode returns to Telecom's when the helper releases it or dies.
 * [Redirect.CALL_SCREENING] (Android 11+, only where the audio policy declares `call_screen_mode_supported`) is the
 * mode Telecom itself uses for Call.enterBackgroundAudioProcessing and is handled the same way by the policy.
 * [Redirect.COMMUNICATION_REDIRECT] is the VoIP twin (Telecom's call streaming uses it); Tether's own test line is VoIP.
 *
 * What the vendor's audio HAL does in these modes is not specified, so each mode is verified per phone model (in the
 * call by [RedirectGuard], by the self-test) and remembered as a [Verdict]; a mode that failed, or whose effect on the
 * uplink could not be seen, is not used again.
 *
 * [Setting.AUTO] is a chain, each layer tried where this phone has it and has not shown it to fail, the first that
 * holds is kept for the call ([Layer]): a redirect mode (CALL_REDIRECT, then CALL_SCREENING where declared) → the
 * vendor's microphone-source key ([LocalMutes.autoPlan]: Samsung's tx control, MediaTek's UL mute; never Android's mic
 * mute, which silences the injected voice on several HALs) → the phone's mic open. [Setting.MUTE] and [Setting.OPEN]
 * are the user's own choice and override the chain.
 */
object MicPath {
    /** The user's choice (status `helper.mic`). */
    enum class Setting(val wire: String) {
        /** Default: the best way to keep the phone's mic out of the call that this phone allows, else open. */
        AUTO("auto"),
        /** The vendor key / Android mic mute of [LocalMutes], with its uplink check (issue #2's first answer). */
        MUTE("mute"),
        /** Nothing: the phone's mic stays in the call. */
        OPEN("open");

        companion object {
            /** [wire] from a newer client, else the old boolean `micMute` (true: mute, false: the default). */
            fun of(wire: String?, legacyMicMute: Boolean? = null): Setting =
                entries.firstOrNull { it.wire == wire } ?: if (legacyMicMute == true) MUTE else AUTO
        }
    }

    /** Android's audio modes (AudioManager.MODE_*), as plain numbers so no newer constant gets inlined. */
    object Mode {
        const val NORMAL = 0
        const val RINGTONE = 1
        const val IN_CALL = 2
        const val IN_COMMUNICATION = 3
        const val CALL_SCREENING = 4
        const val CALL_REDIRECT = 5
        const val COMMUNICATION_REDIRECT = 6

        /** A call's own audio mode or one of the redirect modes: the phone routes (or releases) a call's audio. */
        fun isCall(m: Int): Boolean = m == IN_CALL || m == IN_COMMUNICATION || m == CALL_SCREENING || m == CALL_REDIRECT || m == COMMUNICATION_REDIRECT

        fun name(m: Int): String = when (m) {
            NORMAL -> "normal"; RINGTONE -> "ringtone"; IN_CALL -> "in_call"; IN_COMMUNICATION -> "in_communication"
            CALL_SCREENING -> "call_screening"; CALL_REDIRECT -> "call_redirect"; COMMUNICATION_REDIRECT -> "communication_redirect"
            else -> "mode$m"
        }
    }

    /** A mode that takes the phone's mic and earpiece out of the call. [callMode]: the mode Telecom gives such a call. */
    enum class Redirect(val wire: String, val audioMode: Int, val callMode: Int, val minSdk: Int) {
        CALL_REDIRECT("call_redirect", Mode.CALL_REDIRECT, Mode.IN_CALL, 33),
        CALL_SCREENING("call_screening", Mode.CALL_SCREENING, Mode.IN_CALL, 33),
        COMMUNICATION_REDIRECT("communication_redirect", Mode.COMMUNICATION_REDIRECT, Mode.IN_COMMUNICATION, 33);

        companion object {
            fun of(wire: String?): Redirect? = entries.firstOrNull { it.wire == wire }

            /** The ones to try for a call in [callMode], best first. */
            fun ladder(callMode: Int, screeningSupported: Boolean): List<Redirect> = when (callMode) {
                Mode.IN_CALL -> listOfNotNull(CALL_REDIRECT, CALL_SCREENING.takeIf { screeningSupported })
                Mode.IN_COMMUNICATION -> listOf(COMMUNICATION_REDIRECT)
                else -> emptyList()
            }
        }
    }

    /** What a check found for one mode on this phone model (remembered by the app). */
    enum class Verdict(val wire: String, val usable: Boolean) {
        /** The injected voice was heard in the uplink with the mode on, or the mic left the uplink: use it. */
        OK("ok", true),
        /**
         * Nothing went wrong, but this phone cannot show its uplink to a check. Not used automatically (issue #15): a
         * redirect mode that silenced the uplink would leave the far end hearing nothing, and no check could tell.
         */
        UNVERIFIED("unverified", false),
        /** The downlink went silent in the mode and came back without it. */
        BREAKS_DOWNLINK("breaks-downlink", false),
        /** The injected voice left the uplink in the mode and came back without it. */
        BREAKS_UPLINK("breaks-uplink", false),
        /** The phone did not accept the mode (or Telecom kept taking it back). */
        REFUSED("refused", false),
        /** Accepted, but the phone's mic stayed in the uplink: nothing gained, so not used. */
        NO_EFFECT("no-effect", false);

        companion object {
            fun of(wire: String?): Verdict? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** `{mode wire: verdict wire}` as the app stores and sends it; unknown entries are skipped. */
    fun memoryOf(o: JSONObject?): Map<Redirect, Verdict> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<Redirect, Verdict>()
        for (k in o.keys()) { val r = Redirect.of(k) ?: continue; out[r] = Verdict.of(o.optString(k)) ?: continue }
        return out
    }

    fun memoryJson(m: Map<Redirect, Verdict>): JSONObject = JSONObject().apply { m.forEach { (r, v) -> put(r.wire, v.wire) } }

    /**
     * The mode for this call, or null (the mic stays open, or is muted with [Setting.MUTE]): only with [Setting.AUTO],
     * on Android 13+, with the interception API's injection (the older inject paths need the call's own routing), the
     * first mode of the call's ladder that this phone has not shown to fail. Once one mode of the ladder is
     * [Verdict.UNVERIFIED] (this phone's uplink cannot be seen), the ladder's untested modes are skipped too: the next
     * one could not be checked either, and a mode that silenced the uplink would give a one-way call (Poco F3 / X3 Pro
     * reports, 2026-11-25). Modes that passed a check stay usable.
     */
    fun choose(sdk: Int, setting: Setting, callMode: Int, inject: String?, screeningSupported: Boolean,
               memory: Map<Redirect, Verdict>, samsung: Boolean = false): Redirect? {
        // Never on Samsung: on the A54 the downlink went silent in the mode, on a Galaxy A73 the phone's call audio stayed
        // broken until a reboot after it (issue #13). Samsung's Exynos phones use the vendor's mic key instead.
        if (setting != Setting.AUTO || inject != "intercept" || samsung) return null
        val ladder = Redirect.ladder(callMode, screeningSupported)
        val blind = blindUplink(callMode, memory)
        return ladder.firstOrNull { sdk >= it.minSdk && memory[it]?.usable != false && !(blind && memory[it] == null) }
    }

    /** A mode of [callMode]'s ladder could not be checked on this phone: its uplink is not visible to a check. */
    fun blindUplink(callMode: Int, memory: Map<Redirect, Verdict>): Boolean =
        Redirect.ladder(callMode, true).any { memory[it] == Verdict.UNVERIFIED }

    /** Watch the uplink with speech (an [UplinkCheck]) only while the mode is untested on this phone. */
    fun wantSpeechCheck(v: Verdict?): Boolean = v == null

    /** The layers of [Setting.AUTO], best first; `wire` as INFO `mic.layer`. */
    enum class Layer(val wire: String) { REDIRECT("redirect"), VENDOR_MUTE("vendor-mute"), OPEN("open") }

    /**
     * Why [Setting.AUTO] has no redirect mode for this call (INFO `mic.note`), from what [choose] was given.
     */
    fun noRedirectWhy(sdk: Int, inject: String?, callMode: Int, memory: Map<Redirect, Verdict>, samsung: Boolean = false): String = when {
        samsung -> "no redirect mode on Samsung phones (it silenced their call audio)"
        sdk < 33 -> "no redirect mode before Android 13"
        inject != "intercept" -> "no redirect mode without the interception API (inject ${inject ?: "none"})"
        Redirect.ladder(callMode, true).isEmpty() -> "no redirect mode in ${Mode.name(callMode)}"
        else -> "redirect modes not used on this phone: " + Redirect.ladder(callMode, true)
            .joinToString(", ") { "${it.wire} ${memory[it]?.wire ?: if (blindUplink(callMode, memory)) "not tried (the uplink cannot be checked)" else "not declared"}" }
    }

    /**
     * The self-test's verdict for one mode, from levels measured with the mode off (…0) and on (…1): `accepted` the
     * phone switched to it; `downAlive*` the downlink capture had audio; `tone*` the injected tone reached VOICE_UPLINK;
     * `floor*` the uplink's level with nothing injected (the phone's own mic picking up the room).
     */
    fun selftestVerdict(accepted: Boolean, downAlive0: Boolean, downAlive1: Boolean, tone0: Boolean, tone1: Boolean,
                        floor0: Double, floor1: Double): Pair<Verdict, String> = when {
        !accepted -> Verdict.REFUSED to "the phone did not switch to the mode"
        downAlive0 && !downAlive1 -> Verdict.BREAKS_DOWNLINK to "the downlink went silent in the mode"
        tone0 && !tone1 -> Verdict.BREAKS_UPLINK to "the injected tone left the uplink in the mode"
        floor0 >= ROOM_RMS && floor1 * ISOLATION <= floor0 -> Verdict.OK to "the phone's mic left the uplink"
        floor0 >= ROOM_RMS -> Verdict.NO_EFFECT to "the phone's mic is still in the uplink"
        tone1 -> Verdict.OK to "the injected tone reaches the uplink in the mode (room too quiet to hear the mic)"
        else -> Verdict.UNVERIFIED to "accepted; this phone's VOICE_UPLINK shows neither the mic nor the tone"
    }

    /** The uplink's level with nothing injected at or above which the phone's mic is taken to be heard in it. */
    const val ROOM_RMS = 20.0
    /** The mic left the uplink when its level drops by this factor (10 dB) in the mode. */
    const val ISOLATION = 3.16
}

/**
 * Runs a [MicPath.Redirect] for one call, fed once a second. Pure: the helper sets the mode and measures, this decides.
 *
 * - [waitDownlink]: the mode is set only once the downlink has had audio (a real call: no far end hears the mic
 *   before that, and the downlink check below needs it alive first). Off on the test line (its far end plays nothing).
 * - Mode: Telecom may set its own mode again (a newer privileged request owns the mode; it does on hold / unhold):
 *   set ours again, at most [maxReasserts] times per call; then give up, remembered as [MicPath.Verdict.REFUSED] only
 *   when the phone never was in the mode. Any other mode (the call ending): stop.
 * - Downlink: silent ([deadSeconds] seconds without a non-zero sample) after the switch, although it had audio before
 *   → revert; audio again within [deadSeconds] seconds without the mode → [MicPath.Verdict.BREAKS_DOWNLINK].
 * - Uplink ([speechCheck], an untested mode): an [UplinkCheck] with the mode as the mitigation: the iPhone talks and
 *   VOICE_UPLINK stays quiet → revert; it hears the voice after that → [MicPath.Verdict.BREAKS_UPLINK]; it does not hear
 *   it either way → [MicPath.Verdict.UNVERIFIED] (blind uplink: later calls skip the check); it hears it with the mode
 *   on → [MicPath.Verdict.OK].
 * - No VOICE_UPLINK capture for an untested mode ([uplinkUnavailable]): the mode is withdrawn at once (or never set),
 *   remembered as [MicPath.Verdict.UNVERIFIED]: an unchecked mode could silence the uplink for the whole call.
 * A revert keeps the mode off for the rest of the call.
 */
class RedirectGuard(val redirect: MicPath.Redirect, private val waitDownlink: Boolean, speechCheck: Boolean,
                    private val maxReasserts: Int = 6, private val deadSeconds: Int = 3) {
    enum class State(val wire: String) { WAITING("waiting"), ON("on"), REVERTED("reverted"), STOPPED("stopped") }
    enum class Action { NONE, APPLY, REASSERT, REVERT }

    /** One second: downlink frames read and how many of their samples were not zero, the audio mode now, the uplink window. */
    data class Second(val downFrames: Int, val downNonZero: Int, val audioMode: Int, val uplink: UplinkCheck.Window? = null)

    val check: UplinkCheck? = if (speechCheck) UplinkCheck(mitigation = UplinkCheck.Mitigation.REDIRECT) else null

    @Volatile var state = State.WAITING; private set
    /** What this call found out about the mode (null: nothing to remember). */
    @Volatile var verdict: MicPath.Verdict? = null; private set
    @Volatile var note = if (waitDownlink) "waiting for audio in the downlink" else "set within a second"; private set
    /** Times the mode was set again after Telecom took it back. */
    @Volatile var reasserts = 0; private set
    /**
     * The mode is off for good and nothing more is being watched: the next layer of [MicPath.Setting.AUTO] may take
     * over the phone's mic now (not earlier: a mute during the after-check would blur what it compares).
     */
    val settled: Boolean get() = when (state) {
        State.WAITING, State.ON -> false
        State.STOPPED -> true
        State.REVERTED -> when (why) {
            Why.DOWNLINK -> true   // the downlink's after-check is not affected by a mic mute
            Why.UPLINK -> check?.done != false
            Why.NONE -> true
        }
    }
    /** This call showed that VOICE_UPLINK does not show the injected voice at all (with or without the mode). */
    val blindUplink: Boolean get() = check?.blind == true || unchecked

    /** The uplink capture for the speech check could not be opened or stopped: the mode goes (see [uplinkUnavailable]). */
    @Volatile private var unchecked = false

    private enum class Why { NONE, DOWNLINK, UPLINK }
    private var why = Why.NONE
    private var seenAlive = false
    private var seenOn = false
    private var settle = 0
    private var dead = 0
    private var after = 0

    private fun alive(s: Second) = s.downFrames > 0 && s.downNonZero >= ALIVE_SAMPLES

    fun second(s: Second): Action {
        if (unchecked && (state == State.WAITING || state == State.ON)) {
            val wasOn = state == State.ON
            state = State.REVERTED; why = Why.NONE
            note = "the uplink cannot be checked on this phone: ${redirect.wire} " + (if (wasOn) "undone" else "not used") + "; the next layer takes the mic"
            return if (wasOn) Action.REVERT else Action.NONE
        }
        when (state) {
            State.WAITING -> {
                if (!waitDownlink || alive(s)) {
                    seenAlive = alive(s); state = State.ON; settle = 1
                    note = "mode set" + if (waitDownlink) " once the downlink had audio" else ""
                    return Action.APPLY
                }
            }
            State.ON -> {
                if (s.audioMode != redirect.audioMode) {
                    if (s.audioMode == redirect.callMode && reasserts < maxReasserts) {
                        reasserts++; settle = 1; return Action.REASSERT
                    }
                    if (s.audioMode == redirect.callMode) {
                        state = State.REVERTED
                        if (!seenOn) verdict = MicPath.Verdict.REFUSED
                        note = if (seenOn) "the call's own mode came back $reasserts times: mode given up for this call"
                            else "the phone stayed in ${MicPath.Mode.name(s.audioMode)}: ${redirect.wire} refused"
                    } else {
                        state = State.STOPPED; note = "the audio mode changed to ${MicPath.Mode.name(s.audioMode)}: mode released"
                    }
                    return Action.REVERT
                }
                seenOn = true
                if (settle > 0) { settle--; return Action.NONE }
                if (alive(s)) seenAlive = true
                if (waitDownlink && seenAlive) {
                    dead = if (alive(s)) 0 else dead + 1
                    if (dead >= deadSeconds) {
                        state = State.REVERTED; why = Why.DOWNLINK; after = 0
                        note = "the downlink stayed silent for $deadSeconds s in ${redirect.wire}: mode undone"
                        return Action.REVERT
                    }
                }
                val c = check
                if (c != null && s.uplink != null && !c.done) {
                    if (c.window(s.uplink) == UplinkCheck.Action.UNMUTE) {
                        state = State.REVERTED; why = Why.UPLINK; note = c.note
                        return Action.REVERT
                    }
                    if (c.state == UplinkCheck.State.OK) { verdict = MicPath.Verdict.OK; note = c.note }
                    else if (c.state == UplinkCheck.State.UNVERIFIED && c.failed) { verdict = MicPath.Verdict.UNVERIFIED; note = c.note }
                }
            }
            State.REVERTED -> when (why) {
                Why.DOWNLINK -> if (verdict == null && after < deadSeconds + 1) {
                    after++
                    if (alive(s)) {
                        verdict = MicPath.Verdict.BREAKS_DOWNLINK
                        note = "the downlink was silent in ${redirect.wire} and has audio without it: not used on this phone"
                    } else if (after > deadSeconds) note = "the downlink is silent without the mode too (the far end quiet?): nothing remembered"
                }
                Why.UPLINK -> {
                    val c = check
                    if (c != null && s.uplink != null && !c.done) {
                        c.window(s.uplink)
                        when (c.state) {
                            UplinkCheck.State.FIXED -> { verdict = MicPath.Verdict.BREAKS_UPLINK; note = c.note }
                            UplinkCheck.State.UNVERIFIED -> { verdict = MicPath.Verdict.UNVERIFIED; note = c.note }
                            else -> {}
                        }
                    }
                }
                Why.NONE -> {}
            }
            State.STOPPED -> {}
        }
        return Action.NONE
    }

    /** The VOICE_UPLINK capture could not be opened: nothing to check with speech, the downlink check goes on. */
    fun uplinkUnavailable(reason: String) {
        val c = check ?: return
        c.fail(reason)
        if (state != State.REVERTED && verdict == null) { verdict = MicPath.Verdict.UNVERIFIED; note = reason; unchecked = true }
    }

    companion object {
        /** A second has downlink audio when at least this many of its samples are not zero (a dead path reads all zeros). */
        const val ALIVE_SAMPLES = 50
    }
}
