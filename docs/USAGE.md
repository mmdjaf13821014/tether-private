# Using Tether

Everything happens in the iPhone app; the Android phone can stay in a bag. Both phones keep working on their own:
what you do on the Android phone shows up on the iPhone, and the other way round.

## Calls

- **Incoming calls** ring on the iPhone with the system call screen, also on the lock screen. With two SIMs, the
  SIM's name follows the caller's name ("Sara · Work").
- **Answer, decline, hang up, mute, hold, keypad, speaker** work as in the Phone app. Holding the phone to your ear
  turns the screen off.
- **Headsets and cars**: with a Bluetooth headset, a car kit or a wired headset connected, the Speaker button becomes
  **Audio**: pick iPhone, Speaker or the accessory to move the call there and back. Choices made on the system
  call screen, in Control Center or on the car's screen are followed too, and a car can answer and end calls (they
  are CallKit calls, so CarPlay shows them without anything extra).
- **Dialling**: Keypad, Recents or a contact. With two SIMs you choose the SIM; the phone's own default is used
  otherwise. While the call connects you hear a ringback tone from the iPhone.
- **Call waiting**: a second call rings as usual; answering it holds the first.
- **Move the call here**: a call answered on the Android phone itself shows on the iPhone with *Move here*, which
  moves its audio to the iPhone.
- **Audio** is Opus at 16 kHz in both directions. The Link log lists, after each call, frames sent and received,
  gaps concealed and the link's round-trip time.

## Calling numbers from other apps

Numbers tapped elsewhere on the iPhone (Safari, Mail, Notes, a contact card, a `tel:` link) can be called through the
Android phone. A link never dials by itself: Tether shows "Call …?" and calls when you tap **Call**; if the Android
phone is not connected it says so and **Show in Keypad** (or Cancel) leaves the number in the Keypad. Service codes
(`*140*1#`) and extensions (`;ext=12`, pauses `p` / `,`) are kept.

| Route | What you do once | Account |
| --- | --- | --- |
| Default calling app (iOS 18.2 or later): every phone number you tap opens Tether | Settings › Apps › Default Apps › Calling › choose Tether (shown under its display name) | Paid developer program only (`com.apple.developer.calling-app`); with a free account the app is not offered there |
| The Phone app's Recents: tap a Tether call there to call back through the Android phone | Link tab › Numbers in other apps › **Show calls in the Phone app** | Any |
| Shortcuts / Siri: "Call with Tether" | Nothing (the App Shortcut is built in). Siri asks for the number. In a shortcut, pass a number or any text containing one, e.g. *Receive text from Share Sheet → Call a Number*, to call a number selected in any app via Share | Any |
| URL scheme: `tether://dial?number=%2B15550100` (also `tether:call?number=…`, `tether://dial/<number>`) | Use it from Shortcuts (Open URL) or a link; iOS asks "Open in …?" first | Any |

Shortcuts, Siri and the Phone app's Recents dial at once when the Android phone is linked (they are your own taps
in system UI), waiting a few seconds for the link after a cold start; links always ask. Without the paid account
there is no way to put Tether in the button list of the Contacts app or to make plain `tel:` links open it.

## Messages

- All conversations, with new texts arriving live. Long texts, emoji and right-to-left text are shown as in
  Messages; links, phone numbers and one-time codes are recognised (codes get a Copy button in the notification).
- **Send** from either SIM (the conversation remembers the last one used). A text that could not be confirmed shows
  *Not Delivered* and is never sent twice by itself.
- **Read state** is shared: reading on either phone marks the conversation read on both.
- **Swipe** a conversation to delete, mute (no notifications) or block the sender. Deleting removes it from the
  Android phone too.
- Search finds names, numbers and message text.

## Contacts and Recents

- Contacts: search (any notation of a number, Persian digits too), edit, add, delete numbers, favourites at the
  top. Edits are written to the Android phone's contacts and keep each number's account and label.
- Recents: all calls with the SIM used, search, delete one or all, a *Missed* filter. Missed calls badge the tab and
  the app icon and post a notification, as Phone does. Blocked numbers never count.
- Blocking a number blocks it on the Android phone (Android's own block list), so it is blocked whichever phone
  you use.

## Phone tools

Link tab › **Phone tools** (shown when the Android phone's Tether supports it):

- **Carrier codes**: type a code such as a balance check (`*123#`), pick the SIM and tap Send; the carrier's answer shows
  below (30 s limit). Only `*` / `#` codes are accepted. Carrier codes may cost money.
- **Find phone**: rings the Android phone loudly (alarm volume at maximum, restored afterwards) for up to 30 seconds. It
  stops when you switch it off, tap Stop in its notification or unlock it.
- **Ringer**: Ring, Vibrate or Silent. Silent needs Do Not Disturb access for Tether on the Android phone (Settings ›
  Notifications › Do Not Disturb); without it the iPhone shows the reason.

## Internet sharing

Use the Android phone's mobile data on the iPhone:

- **Wi-Fi hotspot** (Link tab): turn it on, then join it in the iPhone's Settings › Wi-Fi. Fastest.
- **Bluetooth tethering**: turn it on, then on the iPhone pick the Android phone under Settings › Bluetooth (pair
  it once when asked). Slower, but no Wi-Fi needed. While it is on, Tether's own link also uses the classic
  Bluetooth connection, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md#bluetooth-tethering).

## Share VPN

Android does not send hotspot or tethered traffic through a VPN app. *Share VPN* runs a small proxy in Tether that
does: apps on the iPhone that use it go out through the Android phone's VPN.

1. Turn the VPN on in its own app on the Android phone, and the hotspot on.
2. Turn on **Share VPN** in the Link tab.
3. Once, on the iPhone: Settings › Wi-Fi › the hotspot network › Configure Proxy › Automatic, URL as shown under
   Share VPN in the Link tab.

The proxy listens only on the hotspot and tethering interfaces, never on the phone's own Wi-Fi or mobile network.
iOS has no proxy setting for Bluetooth tethering, so Share VPN works with the hotspot.

## Notifications

New texts and missed calls are notified on the iPhone; reply from the notification, copy one-time codes, and mute
conversations you do not want notified. There are two build variants:

| Variant | When | Notifications show |
| --- | --- | --- |
| Communication Notifications | Signed with a paid Apple developer account | The sender's picture, as in Messages |
| Plain | Free Apple account (`tools/reinstall-ios.sh` falls back to it automatically) | The app icon |

## Keep the app running

Leave Tether in the background; do not swipe it away in the app switcher. iOS does not start a swiped-away app
for Bluetooth, so calls and texts stop reaching the iPhone until you open it again (it links within a few seconds
then). Calls still ring on the Android phone meanwhile. See [TROUBLESHOOTING.md](TROUBLESHOOTING.md#after-closing-the-app).

## Both phones in your hand

The Android phone stays a normal phone: you can call and text on it as usual and the iPhone follows. A call
answered on one is ended on the other's screen.
