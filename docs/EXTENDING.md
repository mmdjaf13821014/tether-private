# Extending Tether

Tether's link carries calls, texts, contacts and call history between an Android phone and its clients (the iPhone
app and the Android client). Extensions let you add your own features on top of that link without forking the core
apps: your messages travel in the same encrypted session, under a namespace of their own, and each side says which
extensions it speaks, so phones and clients that do not have yours keep working exactly as before.

The wire rules are in [protocol/PROTOCOL.md §7](../protocol/PROTOCOL.md#7-extensions). This page shows how to use them.

- [How it fits together](#how-it-fits-together)
- [Your own feature in 15 minutes](#your-own-feature-in-15-minutes)
- [Testing](#testing)
- [Debugging](#debugging)
- [Rules that keep the link safe](#rules-that-keep-the-link-safe)
- [Cookbook](#cookbook)

## How it fits together

| | Android phone | iPhone | Android client |
| --- | --- | --- | --- |
| API | `TetherExtension`, `ExtensionRegistry` in `android/core` (`app.tether.core.ext`) | `TetherExtension`, `ExtensionRegistry` in TetherCore (`Extensions.swift`) | same as the Android phone |
| Register yours in | `android/app/.../ext/PhoneExtensions.kt` | `AppModel.registerExtensions` (`ios/Tether/Model/AppModel.swift`) | `android/client/.../ext/ClientExtensions.kt` |
| Where messages enter | the `else` branch of `Hub.onMessage` | the `default` branch of `AppModel.received` | the fallback of `ClientCore.message` |
| Handlers run on | one extension thread (never a link's reader) | the main thread | the main thread |
| Example | `EchoExtension` (`x.tether.echo`) | `EchoExtension` | `EchoExtension` |

Every side registers the built-in `x.tether.echo` (link diagnostics: round trip, clock offset, transport). Read its
code next to this page: `android/core/src/main/java/app/tether/core/ext/EchoExtension.kt` and
`ios/TetherCore/Sources/TetherCore/EchoExtension.swift`.

What the registry guarantees, so you do not have to:

- Your extension only ever sees its own message types and the `error` answers to its own requests. Core messages
  never reach it, and registering an extension that claims a core type fails.
- It can only send its own types, only to a peer that announced a common version (answers to a peer's request are
  always allowed), within the size and rate limits.
- An exception it throws is caught and logged; a request that carried an `id` is answered with `error` `failed`; after
  10 failures in a row it is switched off and its capabilities are withdrawn. Calls, texts and the session go on.

## Your own feature in 15 minutes

As an example we build `x.com.example.battery`: a client can ask the Android phone for its battery, and the phone
tells clients when it gets low. Replace `com.example` with a domain you control (`x.tether.*` is reserved for this
repository).

### 1. Define the messages (2 minutes)

Write them down the way §5 of PROTOCOL.md does, in your extension's README or as a comment:

| Type | Direction | Body |
| --- | --- | --- |
| `x.com.example.battery` with `id` | client → phone | `{}` |
| `x.com.example.battery` with `re` | phone → client | `{level, charging}` (`level` 0–100) |
| `x.com.example.battery.low` | phone → client | `{level}`, once when the level falls below 15 |

Capability: `x.com.example.battery/1`. Later additions of optional fields keep version 1; a change older clients
would misread needs `/2` (PROTOCOL.md §7.6).

### 2. Handle it on the Android phone (5 minutes)

`android/app/src/main/java/app/tether/ext/BatteryExtension.kt`:

```kotlin
package app.tether.ext

import android.content.Context
import android.os.BatteryManager
import app.tether.core.Message
import app.tether.core.ext.ExtensionHost
import app.tether.core.ext.ExtensionSession
import app.tether.core.ext.TetherExtension
import org.json.JSONObject

class BatteryExtension(private val context: Context) : TetherExtension {
    override val id = "x.com.example.battery"
    override val types = setOf(id, "$id.low")
    private var host: ExtensionHost? = null
    private var warned = false

    override fun start(host: ExtensionHost) { this.host = host }

    private fun level() = context.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    override fun onMessage(session: ExtensionSession, message: Message) {
        if (message.t == id && message.id != null) {
            val charging = context.getSystemService(BatteryManager::class.java).isCharging
            session.reply(message, JSONObject().put("level", level()).put("charging", charging))
        }
    }

    /** Call this from a battery broadcast (or a timer). */
    fun check() {
        val level = level()
        if (level < 15 && !warned) {
            warned = true
            // Only clients that announced x.com.example.battery/1 are listed; older ones never see this message.
            host?.sessions()?.forEach { it.send("$id.low", JSONObject().put("level", level)) }
        } else if (level >= 20) warned = false
    }
}
```

Register it in `android/app/src/main/java/app/tether/ext/PhoneExtensions.kt`:

```kotlin
fun phoneExtensions(app: TetherApp): List<TetherExtension> = listOf(
    EchoExtension(),
    BatteryExtension(app),
)
```

That is all on the phone: its `status` now carries `"caps": [..., "x.com.example.battery/1"]`.

### 3. Call it from the iPhone (5 minutes)

`ios/TetherCore/Sources/TetherCore/` is for code every app shares; an app-only extension can live in the app
(`ios/Tether/Model/`):

```swift
import TetherCore

final class BatteryExtension: TetherExtension {
    struct Battery: Codable { var level: Int; var charging: Bool? }
    let id = "x.com.example.battery"
    var types: Set<String> { [id, id + ".low"] }
    var onBattery: ((Battery, _ low: Bool) -> Void)?

    func ask(_ session: ExtensionSession) -> Int? { session.request(id, Empty()) }

    func received(_ message: ExtensionMessage, on session: ExtensionSession) throws {
        guard message.t != MsgType.error else { return }   // the phone refused (see message.body(ErrorBody.self))
        onBattery?(try message.body(Battery.self), message.t == id + ".low")
    }
}
```

Register it in `AppModel.registerExtensions` (`for e in [echo, battery] as [TetherExtension]`), keep an instance as a
property, and use it:

```swift
if let s = extensions.session(for: battery) { _ = battery.ask(s) }      // nil: the phone has no such extension
battery.onBattery = { [weak self] b, low in self?.phoneBattery = b.level; if low { /* notify */ } }
```

A thrown error (a body that does not decode) is caught by the registry and logged in the Link log; the app goes on.

### 4. Call it from the Android client (2 minutes)

The Kotlin class from step 2 works on the client too when it does not need the phone's hardware; here the client side
only asks and listens:

```kotlin
class BatteryClient : TetherExtension {
    override val id = "x.com.example.battery"
    override val types = setOf(id, "$id.low")
    var onBattery: ((level: Int, low: Boolean) -> Unit)? = null
    override fun onMessage(session: ExtensionSession, message: Message) {
        if (message.t != T.ERROR) onBattery?.invoke(message.b.optInt("level", -1), message.t == "$id.low")
    }
}
```

Add it to `clientExtensions()` in `android/client/src/main/java/app/tether/client/ext/ClientExtensions.kt`, then
`app.extensions.sessions(battery).firstOrNull()?.request("x.com.example.battery")`.

### 5. Try it (1 minute)

Build and install as in [BUILD.md](BUILD.md). On the phone, `tools/dbg.sh ext` lists your extension and the
version each client agreed. In a Debug iPhone build, Link › Developer shows the registry's counters.

## Testing

**Unit tests, Kotlin** (no phone needed): drive your extension through a real `ExtensionRegistry` and a fake link,
exactly as `android/core/src/test/java/app/tether/core/ext/ExtensionRegistryTest.kt` does:

```kotlin
class Link : ExtensionLink {
    override val peerName = "Test iPhone"; override val peerKind = "iphone"; override val transport = "memory"
    val sent = ArrayList<Message>()
    override fun send(m: Message): Boolean { sent += m; return true }
}

@Test fun answersTheLevel() {
    val reg = ExtensionRegistry().apply { register(BatteryExtension(fakeContext)); start() }
    val link = Link().also(reg::linkUp)
    assertTrue(reg.route(link, Message("x.com.example.battery", JSONObject(), id = 3)))
    assertEquals(3, link.sent.single().re)
}
```

Test the three things that matter: a request is answered; a peer without your capability gets nothing unsolicited
(`reg.peerCaps(link, null)`, then `assertTrue(reg.sessions(ext).isEmpty())`); a malformed body does not break
anything. Put Android-only logic (the battery read) behind a function you can replace in the test.

**Unit tests, Swift**: the same with `ExtensionRegistry` and a fake `ExtensionPort`, as in
`ios/TetherCore/Tests/TetherCoreTests/ExtensionRegistryTests.swift`. The iPhone app's router hook is pinned by
`ios/TetherTests/ExtensionRouterTests.swift`.

**End to end over a real session**: `android/client/src/test/java/app/tether/client/ExtensionsTest.kt` links the
Android client's real session to a fake phone with its own registry; add a test there for a feature that both
Android sides run.

**Fixtures**: an extension shipped in this repository (`x.tether.*`) adds `protocol/fixtures/<type>.json` (format in
[protocol/fixtures/README.md](../protocol/fixtures/README.md)) and names its types in PROTOCOL.md §7; both contract
tests then read them. A third-party extension keeps its fixtures next to its own tests.

Run everything with `tools/test-all.sh` ([TESTING.md](TESTING.md)).

## Debugging

| Where | How |
| --- | --- |
| Android phone | `tools/dbg.sh ext`: registered extensions, each client's announced capabilities and agreed versions, counters (handled, refused, dropped; never contents). `tools/dbg.sh ext_echo [data]`: an `x.tether.echo` round trip to every linked client that speaks it. Registry lines in logcat: `adb logcat -s Tether \| grep ext:` |
| iPhone | Link › Link log (lines starting `ext:`); Debug builds: Link › Developer (echo round trip, registry summary) |
| Android client | its link journal (lines starting `ext:`) |

`refused.unknown-type` growing on one side means the other sends a type nobody registered there (a name typo, or an
extension missing on that side); `unsent.no-version` means you sent to a peer that never announced your capability.

## Rules that keep the link safe

1. **Never override or wrap core messages.** The registry refuses it; do not work around it in the routers. A
   feature that needs a core change is a pull request to PROTOCOL.md §5.
2. **Stay small and slow.** At most 16 KiB per message (2 KiB during a call), 20 messages per second per link. Page
   large data yourself and send it only when asked. Extension messages never wake a sleeping iPhone: design for
   answers that arrive when the client is in front.
3. **Never block.** On the Android phone all extensions share one thread; do slow work on your own executor and
   answer from there (`session.reply` may be called from any thread on Android). On the iPhone, the main thread.
4. **Privacy first.** Extensions run with the app's permissions and see what the app sees. Send personal data
   (numbers, texts, notifications, location) only for a feature the user switched on, never log message contents
   (counts only), and validate every field you read.
5. **Respect test transports.** `session.dry` is true for on-device test clients: answer them, but never touch
   anything real (no calls, texts, settings) for them.
6. **Version with care.** Optional fields keep the major version; anything older peers would misread needs a new one,
   announced next to the old one until installed apps have moved on.
7. **Fail loudly in tests, softly in use.** Throw on bad input; the registry contains it. Ten failures in a row switch
   your extension off for the rest of the process.

## Cookbook

Ideas that fit the extension model, with what to watch out for:

| Idea | Shape | Watch out for |
| --- | --- | --- |
| Notification mirroring | Phone: a `NotificationListenerService` pushes `…notify.posted {app, title}` to agreed clients | Strictly opt-in, per-app allow-list, no message text unless the user asks; clients show it as a local notification |
| Battery alerts | The tutorial above; also `status.ext` fields (`{level, low}`) for a widget | Keep the status entry under 1 KiB; push only on change |
| Remote camera trigger | Client asks `…camera.shoot`; the phone takes a photo in a foreground activity and answers with a thumbnail | Android needs a visible activity for the camera; page the JPEG in 12 KiB chunks, never during a call |
| Clipboard hand-over | Client sends `…clip.set {text}` (≤ 4 KiB) on a button press | Only on an explicit action, never automatically; never log the text |
| Ring my iPhone | Phone sends `…ring`; the iPhone shows a local notification with sound | Only works while the iPhone app runs or is woken by other traffic: extension messages never wake it |
| Media controls | Client sends `…media {cmd: play/pause/next}`; the phone uses `MediaSessionManager` | Needs notification access on the phone |
| Data usage | Client asks `…usage`; the phone answers from `NetworkStatsManager` per SIM | Read-only; ask for usage access once |
| Car mode | The iPhone tells the phone it joined CarPlay (`…car {on}`); the phone switches its ringer | Combine with the core `ringer.set` rules: Do Not Disturb access |
| Presence / last seen | `status.ext` with the time the phone last saw the user unlock it | Personal data: opt-in only |

When an idea becomes something every Tether build should do, it can be promoted to a core message (PROTOCOL.md
§7.7).
