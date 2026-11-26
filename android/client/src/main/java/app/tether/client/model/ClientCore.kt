package app.tether.client.model

import app.tether.client.calls.CallTracker
import app.tether.core.AudioFrame
import app.tether.core.Ext
import app.tether.core.Message
import app.tether.core.T
import app.tether.core.ext.ExtensionLink
import app.tether.core.ext.ExtensionRegistry

/** Call audio as the core sees it (CallAudio on the phone, a recorder in tests). */
interface AudioPort {
    /** audio.start: codec, rate, frame length, bitrate (protocol §5). */
    fun configure(codec: String, rate: Int, bitrate: Int)
    /** audio.stop: the call audio went to another client or the call ended. */
    fun remoteStopped()
    fun frame(f: AudioFrame)
}

/** How this client introduces itself in `sync` (protocol §5, §5.1). */
data class ClientHello(val kind: String, val name: String, val codecs: List<String>)

/**
 * The client minus the platform: routes every message of a session to the synced state, the call tracker or call
 * audio, the way the iPhone's AppModel.received does. Main thread (tests: the test thread).
 *
 * Extension messages (protocol §7) go to [extensions] over [extLink]; core messages never do.
 */
class ClientCore(val state: ClientState, val calls: CallTracker, private val audio: AudioPort, private val hello: ClientHello,
                 val extensions: ExtensionRegistry = ExtensionRegistry(), private val extLink: ExtensionLink? = null) {
    fun linkUp() {
        extLink?.let(extensions::linkUp)
        state.linkUp(hello.codecs, hello.kind, hello.name, caps())
    }

    fun linkDown() {
        calls.linkLost()
        extLink?.let(extensions::linkDown)
        state.linkDown()
    }

    fun requestSync(full: Boolean = false) = state.requestSync(full, hello.codecs, hello.kind, hello.name, caps())

    private fun caps() = if (extLink == null) emptyList() else extensions.caps()

    fun message(m: Message) {
        when (m.t) {
            T.CALL_STATE -> calls.handle(Call.of(m.b))
            T.CALL_LIST -> {
                val list = m.b.optJSONArray("calls").mapObjects(Call::of)
                list.forEach(calls::handle)
                calls.reconcile(list)
            }
            T.CALL_DIALED -> if (m.b.optBoolean("ok")) calls.dialAccepted() else {
                calls.dialFailed()
                state.problem = Problem(Problem.CALL_FAILED, m.b.optStringOrNull("err"))
            }
            T.AUDIO_START -> audio.configure(m.b.optString("codec", "pcmu"), m.b.optInt("rate", 8000), m.b.optInt("bitrate", 32000))
            T.AUDIO_STOP -> audio.remoteStopped()
            else -> {
                val l = extLink
                if (m.t == T.STATUS && l != null) extensions.peerCaps(l, m.b.optJSONArray(Ext.CAPS))
                // Extension types and errors answering an extension's request; everything else as before.
                if (l == null || !extensions.route(l, m)) state.received(m.t, m.b, m.re)
            }
        }
    }

    fun audio(f: AudioFrame) = audio.frame(f)
}
