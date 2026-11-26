# Hello, Tether (Kotlin/JVM)

A command-line program that runs a complete Tether session in memory between a minimal "phone" and a client, with
nothing but the public API of `tether-core`: pairing code, handshake, a status message, a text message and one Opus
audio frame, all sealed with the session keys. Swap the in-memory `RecordReader`s for a socket or a Bluetooth
channel and you have a real client.

Run (JDK 17):

```sh
cd android && ./gradlew -Ptether.libraryOnly=true :core:publishToMavenLocal   # once, from the repository root
cd ../examples/kotlin-jvm && ./gradlew run
```

With a released version from JitPack instead of the local build: `./gradlew run -PtetherVersion=<tag>`.
The guide is [docs/SDK-kotlin.md](../../docs/SDK-kotlin.md).
