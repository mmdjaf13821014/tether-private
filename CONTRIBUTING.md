# Contributing

Thanks for helping. The most useful contributions right now:

- **Compatibility reports**: which Android phone and version, which iPhone or iPad, what works and what does not
  (especially call audio: run *Run call-audio self-test* during a call and include its result). Open an issue.
- Bug reports with the link logs (see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md#what-to-include-in-a-report)).
- Fixes and features through pull requests.

## Your first contribution

1. Look for issues labelled [good first issue](https://github.com/amirwmr/tether/labels/good%20first%20issue) or
   [help wanted](https://github.com/amirwmr/tether/labels/help%20wanted), or open a
   [feature request](https://github.com/amirwmr/tether/issues/new?template=feature_request.yml) before a bigger change.
2. Fork, clone and build: [docs/BUILD.md](docs/BUILD.md). You do not need the phones for most changes; the automatic
   tests run against fakes and fixtures.
3. Make the change on a branch, run the checks below, and open a pull request; the template has a short checklist.

Docs, translations (English / Persian) and device reports are as welcome as code.

## Repository layout

| Path | What |
| --- | --- |
| `android/app` | The Android app on the phone with the SIMs (link, Telecom, data sync, audio bridge, UI) |
| `android/core` | Shared Kotlin core: crypto, records, messages, codecs (the Kotlin SDK) |
| `android/client` | The Android client app |
| `android/helper` | The shell helper (call audio, network switches) |
| `ios/Tether` | The iPhone / iPad app (SwiftUI, CallKit) |
| `ios/TetherCore` | Swift package: protocol, crypto, link (the Swift SDK) |
| `protocol/` | `PROTOCOL.md`, test vectors and message fixtures |
| `tools/` | Test runners, debug hooks, install scripts |
| `docs/` | User and developer documentation (also the documentation site) |

## Before you open a pull request

1. Run every automatic check ([docs/TESTING.md](docs/TESTING.md)); CI runs the same on your pull request:
   ```bash
   tools/test-all.sh
   ```
   With the Android phone attached, also `tools/device-test.sh` (test line only, no real calls).
2. A change to the wire format updates `protocol/PROTOCOL.md`, both implementations and, for crypto or framing,
   the vectors and the message fixtures in `protocol/fixtures/` (`swift run tether-vectors` in `ios/TetherCore`, then copy `vectors.json` to `protocol/` and to the
   TetherCore tests).
3. A new message type: a `T` constant in `android/core/.../core/Messages.kt`, its handler in `Hub.onMessage`, the
   `MsgType` and body in `TetherCore/Messages.swift`, handling in `AppModel.received` and, for the Android client,
   in `ClientState.received` / `ClientCore.message`, a row in PROTOCOL.md §5, a fixture in `protocol/fixtures/`, and tests on every side.
4. Keep the link free of Bluetooth security (`LinkSecurityTest` fails otherwise) and test hooks dry (they may only
   touch the self-managed test line and the numbers `+10000000001…9`).
5. Never commit personal data: phone numbers, device ids, team ids, keys or logs from your phones. Local values go
   in the git-ignored files listed in BUILD.md; `tools/check-private.sh` checks your change against them.
6. User-facing text comes in English and Persian (`ios/Tether/Resources/*.lproj`, `android/app/src/main/res/values*`,
   `android/client/src/main/res/values*`).

## Done means everything is updated

A change is complete when everything that describes or mirrors it changes with it. This applies to people and to
coding agents alike:

| You changed | Also update |
| --- | --- |
| Anything a user sees | `CHANGELOG.md` (Unreleased), README, the guides in `docs/` and the Persian guide `docs/fa/` |
| UI text | English and Persian strings of every app that shows it |
| The wire format or a message | `protocol/PROTOCOL.md`, both implementations, `protocol/fixtures/`, the Android client, and [docs/SDK-swift.md › Writing a client](docs/SDK-swift.md#writing-a-client-what-the-phone-expects) if clients must behave differently |
| Public API of TetherCore or `tether-core` | Doc comments, the DocC articles, the API tables in docs/SDK-swift.md / SDK-kotlin.md, the examples in `examples/` (run them); minor versions only add |
| Call audio or the playout buffer | Both the iPhone (`ios/TetherCore`) and the Android client (`android/client`): they share the design and their trace tests must give the same numbers |
| A feature or fix in one client app | The other client app too, or a CHANGELOG line saying why not |
| Supported Android or iOS versions | The README badge and requirements, the version tables (README, docs/FAQ.md, docs/fa/faq.md, docs/devices/android-versions.md, docs/INSTALL.md) |
| A bug | A test that fails without the fix |
| A heading in the docs | Every link to it (`python3 tools/check-docs-links.py`) |

**Accounts stay personal.** Never commit or put into a workflow your Apple ID, team id, provisioning profile,
signing certificate, keystore, password or token. Local values go in the git-ignored files from BUILD.md. The
published iPhone app is unsigned on purpose: everyone signs it with their own Apple ID, so nothing may need a
particular (or a paid) developer account. Release keys live only in the repository's secrets.

## Pull requests: what gets merged

Everyone is welcome to contribute; a pull request is merged only when it keeps every installed app working and
stays inside the design rules above. Pull requests are reviewed by maintainers with help from automated review; a
part that breaks a rule is left out (and said so), the rest can still be merged with your name on it. The
`main` branch enforces this: all five CI jobs must pass on the pull request (Android, Kotlin library, Swift on Linux,
Apple, hygiene), the branch must be up to date with `main`, and a maintainer must approve the latest push. For
contributors from outside, CI starts once a maintainer has looked at the change.

1. **One topic per pull request.** A fix, a feature or a translation, with the docs and tests it needs. Unrelated
   changes (formatting, renames, CI tweaks) go in their own pull request. Open an issue or a feature request first
   for anything big.
2. **Keep the project's identity.** Do not rename the apps or the project, or change the application id
   (`app.tether.android`), bundle ids, display names, logos or the README's purpose. A fork may brand itself as it
   likes (MIT: keep the licence and NOTICE); such changes stay in the fork.
3. **Never break installed apps.** The wire format only gains optional fields and new message types (protocol §5);
   pairing, saved data and settings keep working across the update; minimum Android / iOS versions only go down.
   A breaking change needs an issue and a maintainer's yes first.
4. **CI and release workflows** change only in a pull request of their own that says why. A workflow must never run
   code from a pull request with secrets (`pull_request`, never `pull_request_target`), and must not move off the
   runners the release builds on without a reason.
5. **Test it.** `tools/test-all.sh` passes; a bug fix comes with a test that fails without it; UI changes show
   screenshots in English and Persian; say on which devices or simulators you tried it.
6. **No personal data or accounts** in the change: phone numbers, logs from real phones, Apple IDs, team ids,
   profiles, keystores, passwords or tokens (`tools/check-private.sh`).
7. **Fill in the template.** A pull request with an empty description, failing CI, or a change of the project's name
   is closed with a note on what to do instead.

## Design rules

Both apps share one look; a change that leaves it is not merged.

- **Dark first.** Black page backgrounds, dark grey cards (on iOS the system grouped backgrounds, on Android
  `@color/surface`), light text. Light mode on iOS must stay readable; never force light mode.
- **One accent.** The teal `AccentColor` (iOS) / `@color/accent` #2FD3BE (Android) for buttons, links, selection and
  icons. Status colours from the palette only: ok (green), warn (orange), bad (red). No new colours, gradients, brand
  colours or fonts; system fonts and, on iOS, SF Symbols.
- **Native controls.** Lists, sections, buttons and sheets as the platform draws them; no custom chrome.
- **Two languages, both directions.** Every user-facing string in English and Persian, and Persian screens laid out
  right to left.
- **The name stays.** Tether, the iPhone app Cellular, Tether Client; icons and logo unchanged.

`tools/check-guardrails.py` checks this in CI: raw colours in code (Swift `Color(red:…)`, Kotlin
`Color.parseColor`, hex values outside `colors.xml`) and colours off the palette fail the build, and on a pull
request so does any change to the palette, icons, app names, ids, signing, workflows or the checks themselves. A line
that truly needs a fixed colour says why: `// theme: ok <reason>`.

## Testing on devices without real calls

`tools/dbg.sh loopback_call` runs complete calls on the Android app's self-managed test line with an in-process
client (ring, answer, Opus both ways, keypad, hang up) and prints latencies. `tools/dbg.sh only none` keeps test
traffic away from a linked iPhone; `only off` restores it. Never test with real calls or texts to real numbers.

## Running the checks

```bash
tools/test-all.sh            # everything CI runs, plus the privacy scan (~3 min on a Mac)
tools/test-all.sh --quick    # Android unit tests and the Swift package only (about a minute)
tools/test-all.sh --no-ios   # without Xcode (Linux)
```

What each suite protects: [docs/TESTING.md](docs/TESTING.md).

## Releases

Maintainers cut releases; the steps, versioning and what gets published are in
[docs/RELEASING.md](docs/RELEASING.md). Add a line under *Unreleased* in [CHANGELOG.md](CHANGELOG.md) for every
user-visible change.

## Style

Kotlin and Swift as in the surrounding code; comments explain why, not what. Commits: one topic per commit, a
single subject line saying what changed and why, in the style of `git log`, for example
`Contacts: the Android phone keeps a number's custom label on edits that send none`. No trailers.

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).
