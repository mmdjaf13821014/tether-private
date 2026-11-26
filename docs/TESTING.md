# Testing

Tether is two apps on two phones that must keep understanding each other, plus a shell helper and a second client.
The tests exist so you can change any of it without breaking the others, losing someone's messages, or publishing
something private. Run them before every commit; CI runs the same set on every push and pull request.

## One command

```bash
tools/test-all.sh            # everything below, except the on-device checks (a few minutes)
tools/test-all.sh --quick    # Android unit tests and the Swift package (about a minute)
tools/test-all.sh --no-ios   # without the iPhone app build and its tests (Linux, or no Xcode)
```

It runs, in order: whitespace and script syntax, the privacy scan, the Android unit tests (A54 app, helper, shared
core, Android client) with lint and release builds, the Swift package tests, the iPhone app build and the iPhone app
unit tests (on a throw-away simulator it creates and deletes). It needs JDK 17 and the Android SDK, and on a Mac Xcode
and XcodeGen ([BUILD.md](BUILD.md)).

## What each suite protects

| Suite | Where | Run alone | What it pins |
| --- | --- | --- | --- |
| Protocol contract | `protocol/fixtures/`, `android/core/src/test`, `ios/TetherCore/Tests/.../ContractTests.swift` | `./gradlew :core:testDebugUnitTest`, `swift test --filter Contract` | Every message body as it crosses the link, read by both implementations; old-app variants (fields missing) and newer-app variants (unknown fields) still accepted; message types identical in Kotlin, Swift and PROTOCOL.md §5 |
| TetherCore (Swift package) | `ios/TetherCore/Tests` | `cd ios/TetherCore && swift test` | Handshake and record layer including tampering, replay, wrong keys and malformed frames; crypto and framing vectors shared with Kotlin; playout buffer invariants on seeded random traces and measured traces; audio route menu and engine restart rules; number grouping per country; `tel:` / `tether:` parsing; one-time codes and links in texts; reconnect back-off |
| iPhone app | `ios/TetherTests` | `xcodebuild test -scheme Tether -destination 'id=<simulator>' -skipMacroValidation` | Saved data written by released apps keeps loading (`Fixtures/snapshot-v1.json`), a damaged file is kept aside, never overwritten; every incoming message updates the model correctly, and `sync.ids` never deletes local history wrongly; links never dial without a tap; the Link log stays capped and free of numbers |
| A54 app | `android/app/src/test` | `./gradlew :app:testDebugUnitTest` | **Safety**: debug hooks accept only `+10000000001…9`, test clients can never touch a real call, a test transport is always dry; no Bluetooth link security (`LinkSecurityTest`); link policy and wake rules; GATT chunking; Share VPN request parsing; uplink buffer, capture timing and per-vendor silence choice |
| Helper | `android/helper/src/test` | `./gradlew :helper:testDebugUnitTest` | The helper's mutual challenge-response login (protocol §6): wrong token, impostor helper, replay, malformed frames; the token never crosses the socket |
| Android client | `android/client/src/test` | `./gradlew :client:testDebugUnitTest` | Its real session against a simulated Android phone; every synced message; call-state rules (hang-up guard, call waiting, "elsewhere"); reconnect and REJECT reasons; playout traces identical to TetherCore's |
| Privacy | `tools/check-private.sh` | `tools/check-private.sh [--all]` | Nothing from your local config, no home paths, device ids, Bluetooth addresses, personal e-mail addresses, commit trailers or assistant names in a change or commit message |

The Android commands run in `android/` with `JAVA_HOME` set to a JDK 17; the Swift ones need full Xcode
(`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` when `xcode-select` points at the Command Line Tools).

## On-device checks

Automatic tests cannot see Bluetooth, the phone network or audio hardware. With the Android phone attached:

```bash
tools/device-test.sh             # test-line calls (answer, decline, remote hang-up, outgoing), a dry sync, phone tools
tools/device-test.sh --iphone    # also one auto-answered test call through the linked iPhone (it rings the iPhone)
```

Both use only Tether's self-managed test line and test numbers: no real call or text, nothing written to the phone's
call log, contacts or messages. Install the builds you want to test first ([BUILD.md](BUILD.md)). For call audio,
`tools/dbg.sh loopback_call` prints the full figures (ring latency, frames, codec times, jitter, helper statistics),
and the iPhone's Link log has the matching line from its side.

Some things need a person: hearing call audio on a real call, Bluetooth headsets and car kits, notifications and their
look. [CONTRIBUTING.md](../CONTRIBUTING.md) lists what to report.

## Rules for new code

- **A new or changed message body**: update its file in `protocol/fixtures/` and PROTOCOL.md in the same commit, and
  add an `old` variant when you add an optional field. Never remove an `old` variant while an installed app may still
  send that shape ([protocol/fixtures/README.md](../protocol/fixtures/README.md)).
- **A change to what the iPhone saves**: keep `ios/TetherTests/Fixtures/snapshot-v1.json` loading. If the format
  must change, add a new fixture next to it instead of editing the old one.
- **Logic that needs a phone, Bluetooth or CallKit to run**: put the decision in a pure function or type (as
  `TestGuards`, `ReconnectPolicy`, `EngineDecision`, `AudioRoutes`, `ProxyRequests`, `HelperAuth` are) and test that.
- **A bug fix**: a test that fails without the fix.
- **Test data**: only `+1000000000x` numbers and made-up names; never anything from your own phones.
- **Never weaken a test to make it pass**. If a test pins behaviour that should change, change the test in the same
  commit as the behaviour and say why in the commit message.
