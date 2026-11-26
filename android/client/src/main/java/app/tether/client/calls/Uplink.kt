package app.tether.client.calls

/**
 * When this phone sends its microphone to the SIM phone: the rule of the iPhone's `CallUplink.open(callStates:)`
 * (LinkPolicy.swift). Only while a call here is connected ("active"): while an outgoing call is still dialing nobody
 * hears the uplink, and sending it anyway uses airtime the downlink needs and builds a backlog on the SIM phone.
 */
object CallUplink {
    fun open(callStates: List<String>): Boolean = callStates.contains("active")
}

/**
 * The recorder thread's gate on [CallUplink]: while closed the microphone is read and thrown away (the recorder stays
 * ready), nothing is sent; every opening starts afresh (only what is captured from then on is sent).
 */
class UplinkGate(private val log: (String) -> Unit) {
    enum class Admit { HOLD, OPENED, SEND }

    @Volatile private var wanted = false
    private var open = false
    private var heldBack = 0

    /** Main thread: the rule's current answer. */
    fun set(on: Boolean) { wanted = on }

    /** For a problem report: whether the call wants the uplink, and whether it flows. */
    val isWanted get() = wanted
    val isOpen get() = open

    /** Recorder thread, once per captured 20 ms frame. */
    fun admit(): Admit {
        val w = wanted
        var opened = false
        if (w != open) {
            open = w
            if (w) { log("call audio: uplink open ($heldBack frames held back before the call connected)"); heldBack = 0; opened = true }
        }
        if (!open) { heldBack++; return Admit.HOLD }
        return if (opened) Admit.OPENED else Admit.SEND
    }

    /** A new call: closed, nothing held back. */
    fun reset() { wanted = false; open = false; heldBack = 0 }
}

/** What is wrong with this phone's microphone in a call (null: nothing known). */
enum class MicWarning {
    /** RECORD_AUDIO is not granted: every call's uplink is silence. */
    DENIED,
    /** The recorder could not be created or did not initialise. */
    UNAVAILABLE,
    /** The recorder delivered nothing but digital silence for [MicPolicy.SILENT_AFTER_SECONDS] of a connected call. */
    SILENT,
}

/** The microphone rules (iPhone: MicPolicy, #14), without Android classes so they are tested on the JVM. */
object MicPolicy {
    /** Seconds of a connected call with only exact zeros from the recorder before it counts as silent. */
    const val SILENT_AFTER_SECONDS = 6.0   // same as the iPhone (MicPolicy.silentAfterSeconds)

    enum class CallCheck { FINE, ASK }

    /** The check when a call starts: ask for the permission again while it is missing. */
    fun atCall(granted: Boolean) = if (granted) CallCheck.FINE else CallCheck.ASK

    /** The warning for the call screen and the main screen: a refusal wins over a failed recorder, that over silence. */
    fun warning(granted: Boolean, recorderFailed: Boolean, captureSilent: Boolean): MicWarning? = when {
        !granted -> MicWarning.DENIED
        recorderFailed -> MicWarning.UNAVAILABLE
        captureSilent -> MicWarning.SILENT
        else -> null
    }
}

/**
 * Watches the recorder while the uplink is open: reports [Event.SILENT] once after [afterSeconds] of nothing but
 * exact zeros (a refused or broken microphone, no error), and [Event.SOUND] when sound comes after that. A real
 * microphone always delivers some noise. Recorder thread only.
 */
class SilenceDetector(private val rate: Int, private val afterSeconds: Double = MicPolicy.SILENT_AFTER_SECONDS) {
    enum class Event { SILENT, SOUND }

    private var samples = 0L
    private var heard = false
    private var reported = false

    fun reset() { samples = 0; heard = false; reported = false }

    fun feed(pcm: ShortArray, n: Int): Event? {
        if (!heard) for (i in 0 until n) if (pcm[i].toInt() != 0) { heard = true; break }
        if (heard) {
            if (reported) { reported = false; return Event.SOUND }
            return null
        }
        samples += n
        if (!reported && samples >= afterSeconds * rate) { reported = true; return Event.SILENT }
        return null
    }
}
