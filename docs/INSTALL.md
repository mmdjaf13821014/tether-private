---
title: Install guide
---

# Install guide

Everything from downloading to the first call, without building anything. [راهنمای فارسی](fa/README.md) ·
Questions: [FAQ](FAQ.md).

## What is Tether, in short

You keep one Android phone with your SIM cards in a bag. Tether turns it into a "SIM box": your iPhone (or a second
Android phone) rings when a call comes in, places calls, sends and receives texts, and shows contacts and call history,
all through Bluetooth. There is no server, no account and no subscription; the two phones talk only to each other,
end to end encrypted.

## 1. What you need

| | |
| --- | --- |
| The phone with the SIMs | Any Android phone with Android 8.0 or later and Bluetooth LE. **Call audio** (hearing and talking on the iPhone) needs Android 11 or later and works best on Android 13+; on Android 10 you control calls from the iPhone but talk on the Android phone. Android 8 and 9 are coming in 1.2.0 for texts, contacts and call control (no call audio). Details: [features by Android version](devices/android-versions.md) |
| The phone in your hand | An iPhone or iPad with iOS 16 or later, **or** a second Android phone (Android 10+) with the Tether Client app |
| A Wi-Fi network | Once, for the Android phone's Wireless debugging pairing (needed for call audio and the network switches) |
| A computer | **Not needed for Android.** For the iPhone: once, a Mac **or** a Windows PC to set up the sideloading tool (see section 4). No Mac is required |

An iPhone cannot be the SIM phone: iOS does not let any app control the iPhone's own calls and texts. The phone with
the SIMs must be Android.

## 2. Download

All files are on the [latest release](https://github.com/amirwmr/tether/releases/latest):

| File | Install it on |
| --- | --- |
| `Tether-A54-<version>.apk` | **The Android phone with the SIM cards.** Everyone needs this one. ("A54" is the phone it was first made for; it works on other Android phones too.) |
| `Tether-<version>-unsigned.ipa` | **Your iPhone or iPad.** Install it with a sideloading tool and your own Apple ID (section 4). |
| `Tether-Client-<version>.apk` | **A second Android phone** in your hand, instead of an iPhone. |
| `SHA256SUMS` | Checksums, if you want to verify the downloads ([RELEASING.md](RELEASING.md)) |

From 1.2.0 the APKs are signed with the project's release key (certificate SHA-256
`16f456947ccc85880d9fe3cd24775b884d2f21d0f39c4f10d3b3d6e64901057f`; check with
`apksigner verify --print-certs`). The 1.1.0 APKs and APKs named `…-debug-signed.apk` were signed with a temporary
key: to move from one of those to 1.2.0, uninstall the old app first (your pairing is then made again).

## 3. Install the Android app (the phone with the SIMs)

1. On the Android phone, open the release page in the browser and download `Tether-A54-….apk`.
2. Open the downloaded file. Android asks to allow installing from this source (the browser or Files app): allow it,
   go back and tap **Install**. Play Protect may warn about an unknown app: tap **More details › Install anyway**.
   On Samsung phones, **Auto Blocker** (Settings › Security and privacy) must be off to install APKs.
3. Open **Tether** and work through the **Setup** card from top to bottom:
   - [ ] **Permissions**: allow SMS, contacts, call log, phone, notifications and nearby devices. If a permission
     is greyed out with "Restricted setting", open Settings › Apps › Tether › ⋮ › **Allow restricted settings**,
     then try again.
   - [ ] **Default phone app**: choose Tether. Your SMS app can stay as it is.
   - [ ] **Unrestricted battery use**: so Android never stops Tether in the background.
   - [ ] **Wireless debugging** (one time, for call audio and the switches):
     1. Settings › About phone › Software information › tap **Build number** seven times (Developer options).
     2. Connect the phone to any Wi-Fi.
     3. In Tether tap **Pair** next to Wireless debugging. Developer options opens and a Tether notification appears.
     4. Turn **Wireless debugging** on, tap **Pair device with pairing code**, and type the six-digit code into the
        Tether notification's reply field.
     5. The **Audio helper** line turns to "Running". Wi-Fi is needed only when the helper starts (after a restart).
   - Android 8, 9 and 10 have no Wireless debugging: use the TCP mode described in [SETUP.md](SETUP.md#tcp-debugging-android-8-to-10-or-no-wi-fi)
     (one `adb tcpip 5555` from a computer after every restart).

## 4. Install the iPhone app

The IPA is unsigned: a sideloading tool signs it with **your own** Apple ID on your computer or phone. Nobody else's
account is involved, and the project never sees your Apple ID. A free Apple ID is enough. What a free Apple ID
implies, whichever tool you use:

- The app must be **refreshed every 7 days** (the tool re-signs it). If you miss it the app stops opening until you
  refresh; your texts, settings and pairing stay.
- At most **3 sideloaded apps** at a time per free Apple ID (SideStore itself counts as one), and 10 new app ids per
  week.
- Turn on **Developer Mode** once: Settings › Privacy & Security › Developer Mode (iOS 16+), restart.
- Some tools ask you to trust the profile: Settings › General › VPN & Device Management › your Apple ID › Trust.
- In some countries Apple's signing servers are only reachable with a VPN on the computer or phone that signs.
- The tool gives the app a bundle id of its own. Stay with one tool: switching tools installs a second copy, which
  needs pairing again.

The source for SideStore and AltStore (add it once, then install and update from inside the app):

```
https://github.com/amirwmr/tether/releases/latest/download/altstore.json
```

### Option A: SideStore (refreshes on the iPhone itself; computer needed once)

1. On a Mac, Windows or Linux computer, install SideStore with its installer, following
   [docs.sidestore.io](https://docs.sidestore.io) (it creates a pairing file for your iPhone; USB once).
2. On the iPhone, install the small VPN app SideStore asks for (it lets SideStore talk to the iPhone itself) and
   sign in to SideStore with your Apple ID.
3. In SideStore: **Sources › +**, paste the source address above, open **Tether**, tap **Get** (or **Free**).
4. Every few days (before 7 days), open SideStore with its VPN on and tap **Refresh All**. No computer needed after
   setup.

### Option B: AltStore (refreshes from a computer on the same Wi-Fi)

1. Install **AltServer** on a Mac or Windows PC ([altstore.io](https://altstore.io)); on Windows also iTunes and
   iCloud from Apple's website (not the Microsoft Store versions).
2. Connect the iPhone with USB, choose **Install AltStore** in AltServer, sign in with your Apple ID.
3. In AltStore on the iPhone: **Browse › Sources › +**, paste the source address above, install **Tether**.
   (Or download the IPA in Safari, then **My Apps › +** and pick it.)
4. AltStore refreshes in the background while AltServer runs on the computer on the same Wi-Fi.

### Option C: Sideloadly (Mac or Windows)

1. Install [Sideloadly](https://sideloadly.io) on a Mac or Windows PC (Windows: iTunes and iCloud from Apple's site).
2. Download `Tether-<version>-unsigned.ipa` from the release to the computer.
3. Connect the iPhone with USB, drag the IPA into Sideloadly, type your Apple ID, **Start**.
4. Repeat within 7 days (Sideloadly can refresh automatically while the computer is on).

### Option D: TrollStore (no 7-day limit, only some iOS versions)

On the iOS versions TrollStore supports (for iOS 16+ at the time of writing: 16.0–16.6.1 and 17.0; check
TrollStore's own page), open the IPA in TrollStore and install. It does not expire. On other versions TrollStore
cannot be installed: use A, B or C.

### Option E: build it yourself

With a Mac and Xcode: [BUILD.md](BUILD.md). With a paid developer account the signature lasts a year.

## 5. Pair the phones

1. Keep both phones close, Bluetooth on.
2. On the iPhone open the app (named **Cellular**), allow Bluetooth, notifications, microphone and camera.
3. Link tab › **Pair with Android phone**. On the Android phone tap **Pair iPhone**: a QR code appears for 5 minutes.
4. Scan it in the app, or point the iPhone's Camera at it and tap **Open in Cellular** (the app asks before pairing).
   An Android phone still on 1.1 shows an older code that only the app's own scanner reads. The Link tab then shows the phone's name, battery and SIMs; messages, contacts and calls sync.

Microphone: allow it. If it is refused, the call screen and the Link tab say so, because the other side would hear
nothing.

On a second Android phone: open Tether Client and scan the same QR code ([ANDROID-CLIENT.md](ANDROID-CLIENT.md)).

## 6. First call

1. Ask someone to call one of your SIM numbers: the iPhone rings with its own call screen. Answer there.
2. Place a call from the Keypad tab and choose the SIM.
3. On the Android phone, **Run call-audio self-test** in the Call audio card during a call shows which audio paths
   work on that phone.

## 7. Bluetooth tethering and internet

The Link tab can turn on the Android phone's hotspot or Bluetooth tethering so the iPhone uses its mobile data. Over
Bluetooth tethering the Tether link keeps working (it switches its transport by itself). Bluetooth tethering is
slower than the hotspot. **Share VPN** lets the iPhone use the Android phone's VPN ([USAGE.md](USAGE.md#share-vpn)).

## 8. Troubleshooting

| Problem | What to do |
| --- | --- |
| The APK does not install | Allow the browser / Files app to install apps; Samsung: Auto Blocker off; enough free space; uninstall an older copy with another signing key |
| A permission is greyed out | Settings › Apps › Tether › ⋮ › Allow restricted settings |
| The iPhone app does not open any more | The 7-day signature ran out: refresh in SideStore / AltStore / Sideloadly |
| "Unable to verify app" / "Untrusted developer" | Settings › General › VPN & Device Management › Trust; Developer Mode on |
| SideStore cannot refresh | Turn on its VPN app, be on Wi-Fi or mobile data, refresh again; if Apple's servers are blocked where you are, use a VPN |
| The iPhone does not link | Both apps open, Bluetooth on, phones close; Link log on both sides; [TROUBLESHOOTING.md](TROUBLESHOOTING.md#the-iphone-does-not-link) |
| No call audio on the iPhone | Audio helper "Running"? Android 11+? Run the self-test; [No call audio](TROUBLESHOOTING.md#no-call-audio) |
| **The other side cannot hear me** | iPhone: Settings › Privacy & Security › Microphone › Cellular on; not muted. Android app › Call audio › "This phone's microphone during bridged calls": try **On**; [details](TROUBLESHOOTING.md#the-other-side-cannot-hear-me) |
| Audio helper stopped after a restart | Connect the Android phone to Wi-Fi once (Wireless debugging needs it at start) |

When reporting a problem, attach a problem report from each app (Tether › Help › Send a problem report on the Android
phone, Link › Send a problem report on the iPhone), or the phone models, Android and iOS versions and the Link logs of both sides
([what to include](TROUBLESHOOTING.md#what-to-include-in-a-report)).
