package app.tether.helper

/**
 * What a bridged call silences on the Android phone itself (issue #2: on a Galaxy Z Fold 6 the far end heard nothing
 * of the iPhone while the downlink was fine).
 *
 * The earpiece is always silenced: that never touches the uplink. The phone's own microphone is muted automatically
 * ([autoPlan], the default setting when no redirect mode keeps the mic out, see [MicPath]) only with the vendor's own
 * microphone-source key, never with Android's mic mute: on several audio HALs that also silences the audio injected
 * into the uplink (MediaTek for certain, Qualcomm whenever the injection is not their in-call music use case, and a
 * Galaxy Z Fold 6 with it and Samsung's tx key together, issue #2). With the user's "mute" setting ([manualPlan]) the
 * vendor key where there is one, else Android's mic mute.
 *
 * Every mute is watched during the call ([UplinkCheck]) until a check on this phone model gave an answer: the mute is
 * undone when the iPhone's voice does not reach the call. Where the check cannot see the uplink at all (a blind
 * VOICE_UPLINK, [Memory.UNVERIFIED]) only a key known to keep the injected voice ([CallAudio.Silence.blindSafe]:
 * Samsung's tx control, verified on real calls on the reference phone) stays on; any other mute is left off then.
 */
object LocalMutes {
    /** What the app remembers for this phone model (wire names as in status `helper.uplink`). */
    enum class Memory(val wire: String) {
        /** The local mic mute silenced the uplink: never mute again on this phone unless the user turns it on anew. */
        BREAKS_UPLINK("muted-locally-fixed"),
        /** A check heard the iPhone's voice in the uplink with the mute on: no need to check again. */
        OK("ok"),
        /** The uplink capture heard the iPhone neither with the mute nor without it: this phone cannot be checked. */
        UNVERIFIED("unverified");

        companion object {
            fun of(wire: String?): Memory? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * How this call mutes the phone's microphone: through the vendor's own keys (Samsung tx control, MediaTek UL
     * mute), through Android's mic mute (Telecom, done by the app), and whether the helper checks the uplink.
     */
    data class Plan(val vendorMic: Boolean, val telecomMic: Boolean, val check: Boolean) {
        val micMuted: Boolean get() = vendorMic || telecomMic

        companion object {
            val NONE = Plan(vendorMic = false, telecomMic = false, check = false)
        }
    }

    /** App side: ask the helper to mute the phone's microphone this call? */
    fun wantMicMute(setting: Boolean, memory: Memory?): Boolean = setting && memory != Memory.BREAKS_UPLINK

    /** App side: should the helper check the uplink while the mic is muted (not when a check on this phone already saw it work)? */
    fun wantCheck(memory: Memory?): Boolean = memory != Memory.OK

    /**
     * Whether this phone has any mic mute that leaves the injected uplink alone: the vendor's key, or Android's mic mute
     * where the vendor's HAL does not mute the injected voice with it ([CallAudio.Silence.telecomMuteKillsUplink]).
     */
    fun canMute(silence: CallAudio.Silence): Boolean = silence.vendorMicKeys || !silence.telecomMuteKillsUplink

    /**
     * Helper side: one way to mute the mic per vendor (never both: each extra mute is one more way to silence the
     * uplink): the vendor's keys where it has them, else Android's mic mute, but never where that mutes the injected
     * voice too (Qualcomm: its HAL mutes the whole Tx path, and VOICE_UPLINK may still show the voice, so a check
     * cannot be trusted): no mic mute at all then.
     */
    fun plan(silence: CallAudio.Silence, micMute: Boolean, check: Boolean): Plan =
        if (!micMute || !canMute(silence)) Plan.NONE
        else Plan(vendorMic = silence.vendorMicKeys, telecomMic = !silence.vendorMicKeys, check = check)

    /**
     * The automatic layer ([MicPath.Setting.AUTO] without a redirect mode): the vendor's microphone-source key, checked
     * until this phone gave an answer; nothing where the vendor has no such key, where it silenced the uplink, or where
     * the uplink cannot be checked and the key is not known to keep the injected voice.
     */
    fun autoPlan(silence: CallAudio.Silence, memory: Memory?): Plan = when {
        !silence.vendorMicKeys || memory == Memory.BREAKS_UPLINK -> Plan.NONE
        memory == Memory.UNVERIFIED && !silence.blindSafe -> Plan.NONE
        else -> Plan(vendorMic = true, telecomMic = false, check = memory == null)
    }

    /** The user's "mute" setting: as [plan], checked until this phone gave an answer that holds for this mute. */
    fun manualPlan(silence: CallAudio.Silence, memory: Memory?): Plan {
        val p = plan(silence, memory != Memory.BREAKS_UPLINK, check = true)
        if (!p.micMuted) return Plan.NONE
        return p.copy(check = memory == null || (memory == Memory.UNVERIFIED && !keepWhenBlind(p, silence)))
    }

    /** After the check found VOICE_UPLINK blind (the mute already undone): put [p]'s mute back, trusting the vendor? */
    fun keepWhenBlind(p: Plan, silence: CallAudio.Silence): Boolean = p.vendorMic && !p.telecomMic && silence.blindSafe

    /** The plan after the uplink check found the mute silencing the iPhone's voice: no mic mute of any kind. */
    fun undone(p: Plan): Plan = p.copy(vendorMic = false, telecomMic = false)

    /**
     * Status `helper.silent`: "full" with the mic muted too or out of the call ([redirected]: a redirect audio mode, see
     * [MicPath]), "earpiece" with the earpiece only, "none" when muting failed and no mode took the call off the phone.
     */
    fun silent(p: Plan, ok: Boolean, redirected: Boolean = false): String = when {
        redirected -> "full"
        !ok -> "none"
        p.micMuted -> "full"
        else -> "earpiece"
    }
}
