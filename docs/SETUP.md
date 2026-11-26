# First-time setup

Install both apps first ([BUILD.md](BUILD.md)). Keep both phones near each other with Bluetooth on.

## 1. Android phone

Open **Tether**. Its main screen has four cards (iPhone link, Setup, Call audio, Help); work through **Setup** from top
to bottom. **Help › Send a problem report** makes the file to attach when something does not work.

| Step | What it does | Why |
| --- | --- | --- |
| Permissions | Grants SMS, contacts, call log, phone, notifications and nearby devices (Bluetooth) | To read and send texts, show calls and contacts, and advertise over Bluetooth |
| Default phone app | Makes Tether the phone's dialer | Android only lets the default phone app see and control calls. Your usual dialer still works for calls you place on the phone itself; Tether shows its own call screen there |
| Unrestricted battery use | Exempts Tether from battery optimisation | So Android does not stop the link in the background |
| Wireless debugging | Pairs Tether with the phone's own Wireless debugging (see below) | Needed only for call audio and the network switches |

Your default **SMS** app can stay as it is (for example Google Messages). Tether reads and sends texts through the
system; marking texts read and deleting them works without being the SMS app.

### Wireless debugging (call audio and switches)

Bridging call audio and switching mobile data, hotspot, tethering or airplane mode needs permissions only Android's
shell user has. Tether gets them the way a computer would: it pairs with the phone's own Wireless debugging and
starts a small helper as the shell user. Nothing leaves the phone.

1. Enable Developer options (Settings › About phone › tap Build number seven times).
2. Connect the phone to any Wi-Fi network (Wireless debugging only runs on Wi-Fi; it is needed when the helper
   starts, not afterwards).
3. In Tether, tap **Pair** next to Wireless debugging. Developer options opens and a Tether notification appears.
4. In Developer options › Wireless debugging, turn it on, tap **Pair device with pairing code**, and type the
   six-digit code into the Tether notification.
5. The **Audio helper** line in the Call audio card turns to "Running".

### TCP debugging (Android 8 to 10, or no Wi-Fi)

Android 8 to 10 have no Wireless debugging, and Wireless debugging also needs a Wi-Fi network the phone trusts. For those
cases Tether can use adb's older TCP mode on the phone itself. It is off until you enable it:

1. Connect the phone to a computer with USB once (USB debugging on) and run `adb tcpip 5555`.
2. Accept the key prompt on the phone (tick Always allow).
3. In Tether, tap **Enable** next to TCP debugging. Tether connects to `127.0.0.1:5555`, grants itself what it needs
   and starts the helper. Wireless debugging is tried first where the phone has it; the TCP mode is the fallback.

The TCP mode is lost when the phone restarts: run `adb tcpip 5555` again after every restart (the helper does not
start until you do). `adb usb` switches it off; **Turn off** in Tether stops Tether using it. Read the security note
in [SECURITY.md](../SECURITY.md) first.

After a phone restart Tether starts the helper again by itself when Wi-Fi is on. On Android 8 to 10 the helper cannot bridge call
audio (see [features by Android version](devices/android-versions.md)); on Android 11 and 12 it uses a best-effort path. On some Android builds the shell user is not allowed to
intercept call audio; then calls are still controlled from the iPhone and their audio stays on the Android phone.
Use **Run call-audio self-test** during a call to see which audio paths work on your phone.

## 2. iPhone or iPad

1. Open the app (named "Cellular" unless you changed it). Allow Bluetooth and notifications.
2. Go to the **Link** tab and tap **Pair with Android phone**.
3. On the Android phone, tap **Pair iPhone**: a QR code appears for 5 minutes.
4. Scan it: with the iPhone's Camera app (tap **Open in Cellular**), or in the app with **Link › Pair with Android phone**. (No camera? Tap **Enter Code Manually** and paste the code if you have it as text.) An iPhone app older than 1.2 reads only the old code: on the Android phone tap **Show the old code** and scan it in the app.
5. The Link tab shows the phone's name, battery and SIMs; the first sync of messages, contacts and calls follows.

The pairing is kept until you remove it on either side (Link › Unpair on the iPhone, or Unpair on the Android
phone). To move to another iPhone, pair the new one the same way.

## 3. The Link tab

| Switch | What it does on the Android phone |
| --- | --- |
| Mobile data | Turns mobile data on or off |
| Data SIM | Which SIM carries mobile data (only with two SIMs) |
| Wi-Fi hotspot | Starts or stops the hotspot; join it from the iPhone's Wi-Fi settings to use the phone's internet |
| Bluetooth tethering | Shares the phone's internet over Bluetooth (slower than the hotspot) |
| Airplane mode | Turns airplane mode on and off, keeping Bluetooth on so the link survives; it turns itself off again after 90 s if the link is lost, and restores the hotspot, tethering and Wi-Fi when turned off |
| Network · SIM | The highest network generation each SIM may use (5G, 4G, 3G, 2G), where the phone allows it |
| Share VPN | Lets the iPhone use the Android phone's VPN through a proxy (see [USAGE.md](USAGE.md#share-vpn)) |

Switches the phone does not support are hidden. All of them need the audio helper running (step 1).

Below the switches: the link log (useful when reporting a problem), the block list, and how long the iOS app's
signature still lasts.
