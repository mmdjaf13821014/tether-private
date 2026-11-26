# Module tether-core

The Tether protocol for Kotlin and Java on any JVM 17 runtime and on Android: the pieces both ends of a Tether link
use, independent of the transport (Bluetooth LE, a socket, memory). Wire format: `protocol/PROTOCOL.md`.

A session: parse a [app.tether.core.PairingCode], run [app.tether.core.CentralHandshake] (client) against
[app.tether.core.PeripheralHandshake] (phone) over [app.tether.core.Record]s read with
[app.tether.core.RecordReader], then exchange [app.tether.core.Message]s and [app.tether.core.AudioFrame]s sealed by
[app.tether.core.SecureSession]. Guide and examples: `docs/SDK-kotlin.md`.

# Package app.tether.core

Framing ([app.tether.core.Record], [app.tether.core.RecordReader]), P-256 keys and the handshake, the AES-GCM
record layer, the control message envelope and its type names ([app.tether.core.T]), body types (SMS, contacts,
calls) and the audio frame.

# Package app.tether.audio

Voice codecs for 20 ms mono PCM16 frames: [app.tether.audio.OpusCodec] (Concentus, pure Java) and
[app.tether.audio.PcmuCodec] (G.711 µ-law), plus a linear resampler.
