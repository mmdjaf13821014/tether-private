# Security

## What Tether protects

Tether carries calls, texts, contacts and call history between two phones you own. The design goal: nobody but
those two devices can read or inject anything on the link, and no third party is involved at all.

- **No server, no account, no internet.** The phones talk directly over Bluetooth.
- **Pairing**: the Android phone shows a QR code with its long-term P-256 public key and a random one-time token,
  valid for 5 minutes. The iPhone scans it, so it learns the right key out of band; the token proves the iPhone
  was shown the code. After pairing, each side stores the other's public key.
- **Sessions**: every connection starts with three P-256 key agreements (ephemeral-ephemeral for forward secrecy,
  plus one with each side's long-term key, so both are authenticated), then all traffic is sealed with AES-256-GCM under per-direction keys and counters.
  Replayed, reordered or altered records are rejected. The exact construction is in
  [protocol/PROTOCOL.md](protocol/PROTOCOL.md) §3–4.
- **Bluetooth's own security is not relied on.** No characteristic requires Bluetooth encryption and no Bluetooth
  pairing is triggered; an attacker who can talk Bluetooth to either phone still has to break the handshake.
- **The shell helper** listens only on 127.0.0.1 and accepts the app only after a mutual HMAC challenge-response
  with a fresh random secret and port per launch, so another app on the phone cannot take its place or use it.
- **The Share VPN proxy** listens only on hotspot and tethering interfaces (not on Wi-Fi or mobile networks) and
  refuses connections to the phone itself and to local addresses.
- **Debug hooks** are reachable only from adb (they require the `DUMP` permission) and never print message text,
  phone numbers or keys.

## What it does not protect against

- **TCP debugging, if you enable it** (Android 8 to 10, or a phone without Wireless debugging): `adb tcpip` makes the
  phone's adbd listen on **every network interface**, including Wi-Fi, the hotspot and Bluetooth tethering, guarded
  only by the approval of a computer's RSA key. Anyone on those networks who can get you to accept a key prompt, or
  who holds an already approved key, gets a shell. Tether never turns this mode on by itself and connects only to
  `127.0.0.1`; keep it off on untrusted networks, accept key prompts only for your own computer, and switch it off
  with `adb usb` when you do not need it. Wireless debugging (TLS, pairing code) is the safer default.

- Someone with your **unlocked Android phone** or **unlocked iPhone** can read what those phones show, as with any
  phone.
- The Android phone grants Tether broad permissions (default phone app, SMS, contacts) and, through Wireless
  debugging, a shell helper with system-level audio and network permissions. Install only builds you made from
  source you trust.
- **Bluetooth metadata**: someone nearby can see that a device advertises Tether's service UUID and that the phones
  exchange traffic, but not what it is.
- The iOS app's data is protected by iOS data protection. The Android app's long-term key is in the Android Keystore
  (non-exportable where the phone supports key agreement there; otherwise in the app's private storage).

## Reporting a vulnerability

Please report security problems privately, not in a public issue: use GitHub's private vulnerability reporting
(**Security › Report a vulnerability** on this repository, or
[this link](https://github.com/amirwmr/tether/security/advisories/new)). Include the version, the devices and steps
to reproduce, and leave out real phone numbers, keys and tokens. You will get an answer within a week; fixes are
released as soon as they are ready, with a GitHub security advisory and credit if you want it.

## Supported versions

Fixes go into the latest release and `main`. Older releases are not patched; update to the latest one.
