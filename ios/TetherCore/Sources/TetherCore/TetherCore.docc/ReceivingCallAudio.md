# Receiving call audio

Decode the phone's call audio and send your microphone back.

## Overview

When a call's audio goes to your client, the phone sends `audio.start` (``AudioStart``: codec, sample rate, frame
length, whether it wants uplink audio) and then one ``AudioFrame`` every 20 ms on ``Proto/channelAudio``:
`codec ‖ seq ‖ payload`. `audio.stop` ends it. Opus at 16 kHz is the normal codec; G.711 µ-law at 8 kHz is the
fallback. Say which codecs you support in `sync` (``SyncBody/codecs``).

### Decoding

```swift
let decoder = LibOpusDecoder(rate: 16_000)!   // libopus, bundled with the package
var lastSeq: UInt16?

func link(_ link: TetherLink, didReceiveAudio frame: AudioFrame) {
    switch frame.codec {
    case .opus:
        // 1-2 lost frames: let the decoder conceal them before the new one.
        if let last = lastSeq {
            let lost = Int(frame.seq &- last) - 1
            if (1...2).contains(lost) {
                for _ in 0..<lost {
                    playout.push(decoder.conceal(samples: decoder.lastFrameSamples), concealed: true)
                }
            }
        }
        playout.push(decoder.decode(frame.payload))
    case .pcmu:
        playout.push(frame.payload.map(G711.decode))
    }
    lastSeq = frame.seq
}
```

Decoders are not thread safe: use one from one queue (``TetherLink`` delivers audio on its queue).
See <doc:UsingThePlayoutBuffer> for `playout`.

### Sending the uplink

Encode 20 ms of 16-bit mono samples per frame and keep `seq` counting for the whole call:

```swift
let encoder = LibOpusEncoder(rate: 16_000, bitrate: start.bitrate ?? 32_000)!
var seq: UInt16 = 0

func microphone(_ pcm: [Int16]) {          // 320 samples = 20 ms at 16 kHz
    link.sendAudio(AudioFrame(codec: .opus, seq: seq, payload: encoder.encode(pcm)))
    seq &+= 1
}
```

``TetherLink/sendAudio(_:)`` drops a frame instead of queueing it when the link is backed up (audio is drop-late).
With your own transport, seal the frame with `session.seal(channel: Proto.channelAudio, frame.encoded())`.
