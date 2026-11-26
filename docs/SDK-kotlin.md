# Kotlin / JVM library: tether-core

`tether-core` is the Tether protocol for Kotlin and Java: pairing codes, the handshake, the encrypted record layer,
control messages and audio frames, and the voice codecs. It is a plain Kotlin/JVM library (JVM 17) and runs on
Android, desktops, servers and Linux. It brings no transport: you move bytes over Bluetooth LE (L2CAP or GATT, as the
apps do), a socket or memory. The wire format is [protocol/PROTOCOL.md](../protocol/PROTOCOL.md).

The Android phone app (`android/app`) and the Android client (`android/client`) use this same library.

## Install

Coordinates: `com.github.amirwmr.tether:tether-core:<version>`. The version is the repository's `VERSION` file
(a release tag on JitPack); builds without one are `<VERSION>-SNAPSHOT`.

**JitPack** (released tags):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { mavenCentral(); maven("https://jitpack.io") } }
// build.gradle.kts
dependencies { implementation("com.github.amirwmr.tether:tether-core:<tag>") }
```

**Local build** (any commit, no Android SDK needed):

```sh
cd android && ./gradlew -Ptether.libraryOnly=true :core:publishToMavenLocal
```

then add `mavenLocal()` to your repositories and depend on `com.github.amirwmr.tether:tether-core:1.2.0-SNAPSHOT`
(or the version in `VERSION`). `-Ptether.libraryOnly=true` (or `TETHER_LIBRARY_ONLY=true`) configures only the library.

Dependencies it brings: Concentus (pure-Java Opus, BSD) and `org.json:json`.

## Android vs JVM

| | JVM (desktop, server) | Android |
| --- | --- | --- |
| Dependency | `tether-core` as above | `tether-core` with `exclude(group = "org.json")`: Android has org.json built in, and the extra copy fails lint (`DuplicatePlatformClasses`) |
| Keys | `P256.generate()` (JCA) | the same, or an AndroidKeyStore `PrivateKey` for `PeripheralHandshake` |
| Opus | `OpusCodec` (Concentus) | the same; `MediaCodecOpus` (platform MediaCodec) is in the Android module `android/core-android`, in this repository only |

```kotlin
implementation("com.github.amirwmr.tether:tether-core:<tag>") { exclude(group = "org.json") } // Android
```

## API overview

Package `app.tether.core`:

| Type | What it is |
| --- | --- |
| `Record`, `RecordReader`, `Kind`, `Reject`, `FramingException` | Framing (§2): `len ‖ kind ‖ body`; the reader turns a byte stream into records |
| `PairingCode` | The pairing code `tether1:<key>.<token>.<name>` and its QR link form `tether://pair?c=…`: `parse` (both), `encode`, `link` |
| `P256` | Key generation, X9.63 encoding, ECDH, SHA-256, HKDF |
| `CentralHandshake` | The client's side: `hello()`, `receive(ack)` → session + FIN, `verifyFinish` |
| `PeripheralHandshake` | The phone's side: `receiveHello(record, isKnown, token)`, `receiveFinish` |
| `SecureSession` | AES-256-GCM records with strict counters: `seal(channel, bytes)`, `open(record)` |
| `Proto` | Constants: version, modes, channels `CH_CONTROL`, `CH_AUDIO`, `CH_FINISH` |
| `Message`, `T` | The control envelope `{t, id, re, b}` (JSON) and every message type name (§5) |
| `Sms`, `Contact`, `Phone`, `CallEntry`, `CallInfo` | Body types with `json()` |
| `ReportScrubber`, `ProblemReport` | Problem reports in the apps' shared format: the scrubber removes phone numbers, e-mail, names you pass, full Bluetooth / MAC addresses (last two bytes kept), IP addresses, IMEI-like runs and keys, and leaves timestamps, versions and figures alone; the builder writes the header and sections and keeps the newest log lines within about 1 MB |
| `AudioFrame`, `G711` | The audio channel frame `codec ‖ seq ‖ payload`; µ-law |

Package `app.tether.audio`: `VoiceCodec`, `OpusCodec` (16 kHz, 20 ms, 32 kbps VOIP), `PcmuCodec`, `Resample`.

A client session in short:

```kotlin
val code = PairingCode.parse(scannedText)!!
val hs = CentralHandshake(myKeyPair, code.peripheralKey, code.token) // token = null to resume a pairing
transport.write(hs.hello().encoded())
val (session, fin) = hs.receive(reader.next()!!)        // HELLO_ACK
transport.write(fin.encoded())
hs.verifyFinish(session, reader.next()!!)                // the phone's FIN
transport.write(session.seal(Proto.CH_CONTROL, Message(T.PING, JSONObject().put("ts", now)).toJson()).encoded())
val (channel, bytes) = session.open(reader.next()!!)
```

API reference (Dokka): `cd android && ./gradlew -Ptether.libraryOnly=true :core:dokkaGenerate`, then open
`android/core/build/dokka/html/index.html`. The published artifact carries it as its javadoc jar.

## Writing a client

The rules a client must follow (two pairing-code forms, an empty PSM means GATT, the microphone only once the call is
active, `status.helper.audio`, audio gaps with Bluetooth tethering) are the same for every language:
[SDK-swift.md › Writing a client](SDK-swift.md#writing-a-client-what-the-phone-expects). The Android client
(`android/client`) is a complete Kotlin implementation of them.

## Examples

- [examples/kotlin-jvm](../examples/kotlin-jvm): a command-line program running a complete session in memory (pairing,
  handshake, status, a text, one Opus frame). `cd examples/kotlin-jvm && ./gradlew run`.
- `android/client/src/test/.../Harness.kt`: a fake phone answering like the real one (sync, calls, audio ownership).
- `android/core/src/test/.../JvmSessionTest.kt`: the same flow as a unit test.

## Stability

The public API is every public declaration in `app.tether.core` and `app.tether.audio` of `tether-core`. Within a
major version, it changes only by additions; a removal or an incompatible change goes to a new major version and is
named in CHANGELOG.md. The wire format is versioned separately (`Proto.VERSION`, PROTOCOL.md): installed apps keep
talking to each other across library releases. Not covered: `android/core-android`, test helpers, and anything
marked internal. Message bodies are `org.json.JSONObject`s, so keys the library does not know pass through unchanged.
