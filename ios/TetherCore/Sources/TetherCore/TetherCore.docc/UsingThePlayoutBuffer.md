# Using the playout buffer

Play decoded audio smoothly although Bluetooth delivers it in bursts.

## Overview

Frames over Bluetooth LE do not arrive every 20 ms: they come in bursts, with hold-ups of 60–80 ms now and then,
and the two phones' clocks run at slightly different rates. ``PlayoutBuffer`` sits between the decoder and the
audio output:

- It starts playing once it holds its target (40 ms at first) and adapts the target to the spread of frame
  arrival times over the last ~10 s (30–120 ms).
- A short shortfall is concealed (the last 10 ms faded out) instead of a hard gap; only ~100 ms without audio
  counts as an underrun.
- Clock drift is drained or filled in quiet frames only, so speech is never cut or stretched.

### Pushing and pulling

Push decoded frames from your network or decoder queue, with the arrival time if you have it (uptime
nanoseconds; the default is now). Pull from the real-time audio thread: ``PlayoutBuffer/pull(into:count:)``
takes no allocation and holds its lock only briefly.

```swift
let playout = PlayoutBuffer(rate: 16_000)

// Network / decoder queue
playout.push(decoder.decode(frame.payload))

// Audio output thread (for example an AVAudioSourceNode render block, or your own ALSA / PulseAudio loop)
let real = playout.pull(into: buffer, count: frameCount)   // Float samples, -1…1; returns how many were real audio
```

When the call ends, call ``PlayoutBuffer/reset()``.

### Watching it

``PlayoutBuffer/target``, ``PlayoutBuffer/buffered``, ``PlayoutBuffer/underruns``,
``PlayoutBuffer/concealedSamples``, ``PlayoutBuffer/trimmed`` and ``PlayoutBuffer/meanBuffered`` describe the call;
``PlayoutBuffer/arrivalSummary()`` gives one line of arrival statistics for a log.

``SampleRing`` is the lock-protected sample ring underneath; use it alone for the uplink direction (microphone
thread to encoder queue).

The repository's `examples/swift/Loopback` pushes 25 Opus frames through the buffer and reads them back.
