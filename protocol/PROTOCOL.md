# Tether link protocol, version 1

This is the contract between the Android app (BLE *peripheral*, holds the SIM)
and the iOS app (BLE *central*). Both implementations are tested against
`vectors.json` in this folder.

All integers are big-endian unless stated otherwise.

## 1. Bluetooth LE

| Item | UUID | Notes |
|---|---|---|
| Service | `5ABF5C3C-2BF1-4B1C-9E8E-5D153F835BD5` | Advertised (service UUID only, no name). |
| PSM characteristic | `6C0D1EB1-67F4-4181-9752-9CC90D6EFF88` | Read. Value: L2CAP PSM as `uint16` **little-endian**; empty when the phone has no L2CAP listener (Android 8 and 9), and the central then uses the GATT transport at once. |
| RX characteristic (fallback) | `214E1CD9-F397-4A0F-BB6C-ED1D8C7A38A5` | Write-without-response, central → peripheral. |
| TX characteristic (fallback) | `7BA52139-0888-4234-A49D-0271E0F2A647` | Notify, peripheral → central. |
| Wake characteristic | `9A3F1C52-7E0B-4D8A-B1E6-2C5D4F7A8E31` | Notify. The central always subscribes. The peripheral sends a 1-byte notification right before urgent traffic (ringing, call end, new SMS); a GATT notification reliably wakes a suspended iOS app, which then drains the L2CAP channel. |

**No link-layer security.** No characteristic or descriptor asks for encryption, the L2CAP channel is the
insecure kind, and nothing triggers Bluetooth pairing: the link is secured by Tether's own handshake and records
(§3–4), never by a bond. Until 2026-11-22 every characteristic required encryption; once the owner paired the phones
over classic Bluetooth for tethering, the Android phone held a derived LE key recorded with size 0 and iOS reached the GATT
service over the classic (BR/EDR) link, so every read failed "insufficient encryption key size". The Android phone checks its
service at start-up (`gattSecurity` in the state hook) and a unit test fails if an encrypted permission or the
secure L2CAP listener comes back. Trade-off: without a bond made by Tether, iOS may not follow the Android phone's rotating
address on its own; a bond made for anything else (tethering) still lets it, and the service-UUID scan reconnects
otherwise.

The primary transport is one L2CAP connection-oriented channel on the PSM.
Android's L2CAP channel is LE only: when iOS reaches the service over BR/EDR (the phones are also linked for
Bluetooth tethering), the channel cannot open and the GATT transport is used. A failed PSM read also goes straight
to the GATT transport.

The peripheral holds an LE link to its paired central (a GATT client connection) while a session is up, so iOS keeps
the session on LE and L2CAP even when the phones are also linked over classic Bluetooth, instead of moving GATT
between links and dropping the session. It never holds one otherwise: iOS does not complete an app's new connect
while an LE link to the peripheral exists. Without a session it pulses an LE link (up 3 s, only when no LE link
exists: every 5 s for a minute after a session ended, then every 30 s), because a link coming up completes the
central's waiting connect; and 3 s after an LE session ended without a new one it closes the idle connection iOS
keeps for a dead app. A GATT session ends when the
ACL it runs on goes or the central's LE link appears under a classic-link session; a pair-mode HELLO repeated by the
same central within 10 minutes of a completed pairing is accepted (it may have lost the FIN); a stream left mid-record
for 10 s ends the session on either side.
If the channel cannot be opened, the central falls back to GATT: RX writes and
TX notifications carry the *same byte stream* as the L2CAP channel, cut into
chunks of at most `ATT_MTU - 3` bytes. Chunk boundaries carry no meaning.

The central keeps a pending `connect` to the bonded peripheral at all times
(iOS state restoration), and also scans for the service UUID as a backup.
Neither side drops an idle session: a suspended iPhone may be silent for a long
time. Liveness comes from the BLE supervision timeout and L2CAP EOF; the
peripheral pings once a minute; after 15 minutes of silence it wakes the central (wake characteristic) and gives up
only if nothing arrives within 45 s more. A central that gets a wake notification **answers at once** (a ping is
enough) and, if it has no session or the session does not answer within a few seconds, sets a new one up on the open
connection; it does the same when the peripheral's Tether service changes (Service Changed: Tether restarted there).
While a paired central has no session, the peripheral sends a wake notification every 3 minutes to whatever is
connected to its GATT server: a suspended iOS app is not told that an L2CAP channel closed, and with Bluetooth
tethering its connection stays up over classic, so only a notification reaches it. The iPhone pings every 25 s
while it runs (the round trip is shown in its call statistics); while it is suspended its timers do not run.
During calls the peripheral asks for high connection priority and the 2M PHY (the state hook shows the PHY and the
connection parameters each link really got).

## 2. Records

The byte stream is a sequence of records:

```
+----------------+-----------+-----------------+
| len : uint16   | kind: u8  | body (len-1 B)  |
+----------------+-----------+-----------------+
```

`len` counts `kind` + `body`, so the largest body is 65534 bytes. Senders keep
plaintext payloads at or below 16 KiB; larger data is paged at the message
layer.

| kind | Name | Direction | Body |
|---|---|---|---|
| `0x01` | HELLO | C → P | see §3 |
| `0x02` | HELLO_ACK | P → C | see §3 |
| `0x03` | REJECT | P → C | `reason: u8` (1 = bad version, 2 = unknown device, 3 = pairing closed or token expired, 4 = busy) |
| `0x10` | SEALED | both | `ctr: u64` ‖ AES-256-GCM ciphertext ‖ 16-byte tag |

Any other kind closes the connection.

## 3. Handshake

Keys are NIST P-256. Public keys travel as 65-byte uncompressed X9.63 points
(`0x04 ‖ X ‖ Y`). ECDH output is the 32-byte X coordinate.

* `s_P` / `S_P`: the Android app's long-term (static) key pair.
* `s_C` / `S_C`: the iPhone's long-term key pair.
* `e_P`, `e_C`: fresh ephemeral key pairs, one per connection.

**Pairing QR code** (shown on the Android phone, valid 5 minutes, one use):

```
tether1:<base64url(S_P)>.<base64url(token)>.<base64url(utf8 device name)>
```

`token` is 16 random bytes. Base64url is without padding.

Since 1.2 the QR code holds the same three fields as a link, so the iPhone's Camera app offers to open it in the app:

```
tether://pair?c=<base64url(S_P)>.<base64url(token)>.<base64url(utf8 device name)>
```

Readers accept both forms (`tether1:` is what 1.1 phones show and what is typed or pasted), ignore other query items,
and match `tether://pair` and `tether:pair` without regard to case. The Android app can still show the `tether1:`
form of the same code for 1.1 iPhone apps, whose scanner reads only that. A link opened from outside the app never
pairs by itself: the app asks first. The link holds the token: never log it. `vectors.json` › `pairing_code` has both.

**HELLO** body (C → P), 148 bytes:

```
ver: u8 = 1 | mode: u8 (0 = resume, 1 = pair) | E_C: 65 | n_C: 16 | S_C: 65
```

**HELLO_ACK** body (P → C), 82 bytes:

```
ver: u8 = 1 | E_P: 65 | n_P: 16
```

In resume mode the peripheral sends REJECT 2 if `S_C` is not the stored
paired key. In pair mode it sends REJECT 3 unless a pairing token is open.

**Key schedule** (both sides):

```
dh1 = ECDH(e_C, E_P)          ephemeral-ephemeral (forward secrecy)
dh2 = ECDH(e_C, S_P)          proves the peripheral holds s_P
dh3 = ECDH(s_C, E_P)          proves the central holds s_C
psk = token (pair mode) or empty (resume mode)
th  = SHA-256("TETHER-HS-1" ‖ HELLO body ‖ HELLO_ACK body)
okm = HKDF-SHA256(salt = th, ikm = dh1 ‖ dh2 ‖ dh3 ‖ psk, info = "tether/1 keys", L = 64)
k_c2p = okm[0..32)    k_p2c = okm[32..64)
```

**Finish.** The central's first SEALED record (counter 0) has plaintext
`0xFF ‖ th`. The peripheral checks it (this authenticates the central and, in
pair mode, proves knowledge of the token), stores `S_C` if pairing, marks the
token used, and answers with its own SEALED record (counter 0) with plaintext
`0xFF ‖ th`. The central checks it. The session is then up.

## 4. Sealed records

```
nonce = 0x00000000 ‖ ctr (u64)        12 bytes
aad   = 0x10 ‖ ctr (u64)              9 bytes
key   = k_c2p (C → P) or k_p2c (P → C)
```

Each direction counts from 0 and adds 1 per record. The receiver requires
`ctr` to equal the next expected value exactly; anything else (replay, gap,
reorder, bad tag) closes the connection. A session never sends 2^32 records;
it reconnects instead.

Plaintext = `channel: u8` ‖ payload:

| channel | Payload |
|---|---|
| `0x00` | control message, UTF-8 JSON (§5) |
| `0x01` | audio frame: `codec: u8` (1 = Opus, 2 = G.711 µ-law) ‖ `seq: u16` ‖ frame bytes. 20 ms per frame. |
| `0xFF` | handshake finish (`th`) |

Audio is drop-late: the Android phone discards queued audio frames more than 60 ms old *before* sealing (so
counters stay contiguous). The receivers keep a playout / jitter buffer: the Android phone drops uplink frames whose
`seq` goes back (but follows a client whose counter restarted after 3 such frames) and conceals 1–2 missing
frames; the iPhone plays out from a 30–120 ms adaptive buffer whose depth follows the spread of frame arrival times over
the last ~10 s (around the far clock's rate), fills 1–2 lost frames with the Opus decoder's concealment, conceals
short gaps (the last 10 ms faded out) and drains clock drift in quiet frames. On the Android phone the uplink waits
in a buffer of up to 1 s whose delay is corrected in quiet frames (cut or repeated; speech sped up or slowed by
≤ 3 % only without a pause). `seq` keeps counting for the whole call.

## 5. Control messages

Envelope: `{"t": "<type>", "id": <int>?, "re": <int>?, "b": { ... }?}`.
`id` is set on requests that expect an answer; the answer carries `re`.
Unknown types are ignored; unknown fields too. Types starting with `x.` are extension messages, not core ones: an
unknown extension type that carries an `id` is answered with `error` (§7.4). Times are Unix milliseconds. Phone
numbers are as stored on the Android phone.

| Type | Dir | Body |
|---|---|---|
| `ping` / `pong` | both | `{ts}` |
| `status` | P→C | `{device, battery, charging, sims:[{slot, sub, name, carrier, number?, signal, net?, service?, roaming?, country?}], defaultSub?, dialer, helper:{state, method?, capture?, up, down, silent?, micMute?, uplink?, mic?, micPath?, audio?}, wifi, adbWifi, clients?:[{name, kind, self?, audio?}]}` (`helper.method` / `capture`: the call-audio inject / capture method of the current or last call; `audio`: what call audio the phone's Android version allows, "full" (13+) \| "fallback" (11–12, best effort) \| "unavailable" (8–10: calls are controlled from the client, their audio stays on the phone; clients say so; since 1.2.2 "blocked": this phone's software gave no call audio in a real call (the downlink capture read only zeros), so its calls are not bridged until a system update or a self-test that hears the call; clients say so); `silent` "full" \| "earpiece" \| "none": how silent the Android phone itself is during a bridged call; `micMute`: the setting "mute the Android phone's microphone during bridged calls", absent from builds without it (clients hide the switch; newer builds keep sending it, true only when `mic` is "mute"); `mic`: the setting "the Android phone's microphone during bridged calls", "auto" (default: a redirect audio mode takes the phone's mic and earpiece out of the call where the phone allows it, else the vendor's own mic-source key mutes it where the phone has one that is known or checked to keep the client's voice, else the mic stays on) \| "mute" \| "open", absent from builds before it (clients then show the `micMute` switch); `micPath`: during a bridged call, what the phone's own mic does, "off" (out of the call) \| "muted" \| "open"; `uplink`: whether the client's voice reaches the call, "checking" \| "ok" \| "muted-locally-fixed" (the phone's mic mute silenced it and was undone; calls on this phone start without it) \| "unverified" \| "off" (no local mic mute this call): this call's check, else what the phone remembers; `clients`: every connected client; `self` marks the receiver, `audio` the one call audio goes to; per SIM: `signal` 0–4, `net` "5G" \| "LTE+" \| "LTE" \| "H+" \| "H" \| "3G" \| "E" \| "2G", `service` "in" \| "none" \| "emergency" \| "off", `roaming`, `country` the SIM's lowercase ISO 3166 code (else the network's; optional, for grouping national numbers on display). Pushed again within ~2 s of a signal / network change). Optional, from builds with the phone tools: `tools:{ussd, find, ringer}` (clients hide the matching controls when absent), `ringer` "normal" \| "vibrate" \| "silent", `dnd` (Do Not Disturb access granted). Optional, from builds with extensions (§7): `caps:["<id>/<major>"]` (the Android phone's extensions), `ext:{"<id>": {…}}` (their status fields, only those the receiving client agreed) |
| `sync` | C→P | `{sms: lastId, calls: lastId, contacts: version, codecs: ["opus", "pcmu"], client?: {kind: "iphone" \| "android", name}, caps?: ["<id>/<major>"]}` (0 / "" for a full sync; `codecs` = what the client can decode/encode, best first; `client` names it in the Android phone's device list; `kind` "android" is the Android client, see §5.1; `caps`: the client's extensions, §7.2, absent when it has none) |
| `sms.batch` | P→C | `{items:[Sms], more}` |
| `sms.new` | P→C | `{item: Sms}` |
| `sms.send` | C→P | `{to, body, sub?}` → `sms.result` `{ok, state: "sent"\|"delivered"\|"failed", err?}` (may arrive twice: sent, then delivered) |
| `contacts.batch` | P→C | `{items:[Contact], more, version, full}` |
| `contact.star` | C→P | `{id, on}`: Favourites (the contact's starred flag) → `contact.result`; the contacts version changes |
| `contact.add` | C→P | `{name, phones:[{n, type, label?}], emails:[...]}` → `contact.result` `{ok, id?, err?}` (`label`: a custom kind such as "Satellite", stored as Android's custom type) |
| `contact.update` | C→P | `{id, name, phones:[{n, type, label?}], emails:[...]}`: replaces the contact's name, numbers and e-mail addresses → `contact.result` `{ok, id?, err?}`; the contacts version changes. Per number: a `label` sets the custom kind, `""` clears it (the number takes `type`), no `label` keeps an existing custom kind unless `type` changed (older clients never send labels) |
| `calls.batch` | P→C | `{items:[CallEntry], more}` |
| `call.state` | P→C | `Call` |
| `call.list` | P→C | `{calls:[Call]}` (sent after every handshake) |
| `call.answer` / `call.reject` / `call.end` | C→P | `{cid}` |
| `call.hold` | C→P | `{cid, on}` |
| `call.mute` | C→P | `{on}` |
| `call.dtmf` | C→P | `{cid, d}` (`0-9 * #`) |
| `call.dial` | C→P | `{num, sub?}` → `call.dialed` `{ok, err?}` |
| `call.take` | C→P | `{cid}`: move the call's audio to this client (see §5.1) |
| `audio.start` | P→C | `{codec: "opus"\|"pcmu", rate, ms, up, bitrate?}` (`bitrate`: what the Android phone encodes at; the client uses it for the uplink) |
| `audio.stop` | P→C | `{}` |
| `audio.stats` | P→C | `{summary, helper?}`: the Android phone's half of a call's audio statistics, sent ~0.5 s after `audio.stop` to the client that had the audio (`summary`: one line for its log; `helper`: the shell helper's figures). Optional, informational |
| `selftest` | C→P | `{}` → `selftest.result` `{methods:[{name, ok, note, delayMs?}], chosen?, silence?, micByTelecom?}` (`delayMs`: an inject method's measured delay into the call uplink; first row `call-audio`: the call's own inject / capture method and whether the phone's mic was muted; rows `mic:<mode>` (`call_redirect`, `call_screening`, `communication_redirect`) test a redirect audio mode: the uplink's level with nothing injected (the phone's mic), the injected tone and the downlink, without and with the mode, with an optional `verdict` "ok" \| "unverified" \| "breaks-downlink" \| "breaks-uplink" \| "refused" \| "no-effect" that the phone remembers for its model; a last row `inject:<method>, phone mic muted` repeats the chosen method with the mic muted, and its answer is remembered like the in-call check's; optional `redirect: {mode: verdict}`, `callMode`) |
| `audio.set` | C→P | `{mic}` ("auto" \| "mute" \| "open", to Android builds whose status has `helper.mic`) or `{micMute}` (older builds; true reads as "mute", false as "auto"): change the setting "the Android phone's microphone during bridged calls" (it also forgets earlier check results); applied to a running call at once and answered with a `status` |
| `sms.read` | both | `{thread, upTo}`: read on a client; the Android phone marks it read and forwards it to the other clients |
| `sms.unread` | P→C | `{ids:[id], all}`: the Android phone's unread incoming texts, after every sync and within ~2 s of a read-state change there; everything else is read (`all` false: list capped at 3000, only ids ≥ its smallest are known) |
| `sms.delete` | C→P | `{ids?:[id], thread?}` → `sms.deleted` `{ok, ids:[id], thread?, err?}` to the asking client (with `re`) and to every other client (see §5.2) |
| `calls.delete` | C→P | `{ids?:[id], all?}` → `calls.deleted` `{ok, ids, all?, err?}` to all clients |
| `lists.set` | C→P | `{what: "block", num, on}` \| `{what: "mute", thread, on}` → `lists` to all clients |
| `lists` | P→C | `{blocked:[num], muted:[thread], system, err?}` (also after every sync; `system`: Android's own block list is used) |
| `sync.ids` | P→C | `{sms:[[first,last]…], calls:[[first,last]…], more?}` after every sync: ids that still exist (inclusive ranges); split into pages with `more: true` when very long, applied once the last page is in |
| `net.get` / `net.set` | C→P | `{}` / `{what, on, sub?}` → `net.state` (see §5.3) |
| `ussd.send` / `ussd.result` | C→P / P→C | `{code, sub?}` (code matches `^[*#][0-9*#]{1,30}#$`, else refused) → `{ok, text?, err?}` (`re` = the request; sent on SIM `sub` through `TelephonyManager.sendUssdRequest`, 30 s timeout; one at a time; a test client is never sent to the network) |
| `phone.find` / `phone.finding` | C→P / P→C | `{on}` → `{on, err?}`: the Android phone rings on the alarm stream at maximum alarm volume for at most 30 s (volume restored afterwards; stops on `on:false`, the notification's Stop action or a screen unlock). `phone.finding` is also pushed to every client when it starts or stops |
| `ringer.set` / `ringer.result` | C→P / P→C | `{mode: "normal"\|"vibrate"\|"silent"}` → `{ok, mode, err?}` (`err` e.g. Do Not Disturb access missing for silent); `status` then carries the new `ringer` |
| `unpair` | C→P | `{}` (peripheral forgets `S_C`) |
| `error` | both | `{msg, t?, code?}` (`t`, `code`: set when an extension message is refused, §7.4) |

Objects:

```
Sms       {id, thread, addr, body, date, box, read, sub?}
          box: 1 inbox, 2 sent, 3 draft, 4 outbox, 5 failed, 6 queued
Contact   {id, name, phones:[{n, type, label?}], emails:[...], photo?(base64 JPEG ≤ 96 px), starred}
          type: "mobile" | "home" | "work" | "main" | "other"
CallEntry {id, num, name?, date, dur, type, sub?}
          type: 1 incoming, 2 outgoing, 3 missed, 4 voicemail, 5 rejected, 6 blocked
Call      {cid, num, name?, state, dir, sub?, since?, cause?, hold, elsewhere?, where?}
          state: "ringing" | "dialing" | "connecting" | "active" | "holding" | "ended"
          dir: "in" | "out"; hold: whether hold is supported
          elsewhere: the call's audio is on another client, named by `where` (per receiver, see §5.1)
```

### 5.1 Several clients

The Android phone accepts more than one client (it did serve an iPhone and a Mac at once until the Mac client was removed
on 2026-11-22). Today's clients are the iPhone app (`kind` "iphone") and the Android client (`kind` "android", a second
Android phone running `android/client`). The rules below stay in force.

Only an iPhone gets the held / pulsed LE link of §1 and has its address remembered for it: an Android client keeps
its own connection (it reconnects by scanning for the service) and is not woken by a pulse. A client paired in the
current session has no kind until its first `sync`, so the Android phone decides this at that sync, never at the
handshake.

The peripheral keeps a list of paired clients (static keys + names) and accepts one link per client at
the same time; a client reconnecting replaces only its own old link. Pairing a new client never forgets
the others. Messages, call-log rows, status and call events go to every connected client.

Call audio has exactly one **owner**:
- the client that sends `call.answer` or `call.dial` for the call, or `call.take` later (remembered by the client's
  key while calls go on, so a client that reconnects mid-call gets the audio back);
- nobody when no client claimed it (answered or dialed on the Android phone itself, or with a headset or car connected
  to it): the call stays on the Android phone, nothing is bridged and nothing there is muted. Clients see it as
  `elsewhere` with `where` = the Android phone's name and may `call.take` it. (Before 1.2.2 such a call went to the
  client used most recently, else the iPhone, which left a call taken on the phone without audio there.)

`audio.start` / `audio.stop` and audio frames go to the owner only; audio frames from other clients are
ignored. Every `call.state` is sent per client: for non-owners, an answered / dialing / held call carries
`elsewhere: true, where: <owner name>` (the Android phone's name when the call has no owner, or when the phone gives
no call audio: `helper.audio` "blocked"). A client that receives `elsewhere` ends its local ringing or call
(CallKit reason *answered elsewhere*) and offers **Move here**, which sends `call.take`. On a change of
owner the peripheral sends `audio.stop` to the old owner, `audio.start` to the new one, and the current
state of every call to all clients.

### 5.2 Delete, block, mute

The Android phone is the source of truth. A client asks (`sms.delete`, `calls.delete`, `lists.set`), removes the
items or updates its lists at once, and the Android phone answers every connected client, so the other client
follows live (and clears its delivered notifications of deleted or muted conversations). A client that
was away learns deletions on its next sync from `sync.ids`: every local row with a positive id outside
those ranges is gone (local placeholders of texts being sent have negative ids and are kept).

Blocking writes Android's block list (`BlockedNumberContract`, allowed for the default phone app) and
mirrors it in Tether's own list. Android then refuses blocked calls and texts itself; Tether also rejects
blocked callers in its call screening / in-call service and does not forward texts from blocked senders,
so nothing from them reaches the iPhone even if the system list is unavailable. Muted
conversations are kept on the Android phone by thread id; clients show no notification for them. Deleting a
conversation also unmutes its thread id.

### 5.3 Network switches, airplane mode, network mode, Share VPN

`net.set {what, on, sub?, slot?, mode?}`, `what` ∈ `data`, `hotspot`, `bt`, `dataSub` (with `sub`), `airplane`,
`rat` (with `slot` 0/1 and `mode` "5G" | "4G" | "3G" | "2G": every technology up to that one, like the phone's
"auto connect" modes), `share`.
`net.state`: `{data, hotspot, bt, dataSub, vpn, airplane, rat:[{slot, mode}], share, proxy: {port, addrs:[{iface,
ip, kind: "hotspot"|"bt"|"usb", pac}]}, err?}`. `vpn` is read-only (a VPN is up on the Android phone; it is switched in the
VPN app). A `net.state` caused by `share` or `airplane` is also sent to the other clients (without `re`).

Airplane mode keeps Bluetooth on. If no client has been linked for 90 s while airplane mode (switched on from a
client) is on, the Android phone switches it off by itself; the hotspot / Bluetooth tethering that were on before come back.

The proxy at `ip:port` speaks HTTP (absolute-form requests), HTTPS via `CONNECT`, SOCKS5 (no auth, CONNECT) and
serves `GET /proxy.pac`.

## 6. Shell helper socket (Android-internal)

The helper (`app_process`, uid 2000) is started with
`HelperMain serve <port> <token>` and listens on `127.0.0.1:<port>`; the app
connects to it. (SELinux denies the shell domain `connectto` on an app's Unix
socket, so the helper must be the listener.) `<token>` is random and new for every
launch; it never crosses the socket. Mutual challenge-response (helper version 3):

1. Helper → app: CHALLENGE with 16 random bytes `h`.
2. App → helper: AUTH with 16 random bytes `a` ‖ HMAC-SHA256(token, "tether-helper app" ‖ h ‖ a).
3. Helper → app: PROOF = HMAC-SHA256(token, "tether-helper helper" ‖ a ‖ h). The app sends nothing else to a
   listener that does not send a valid PROOF (an app that took the port first gets neither the token nor audio).
4. Helper → app: HELLO; then the frames below. Each connection authenticates on its own thread (2 s limit); an
   authenticated connection replaces the previous one. At most 4 connections authenticate at once (more are closed);
   each launch has a new port and token. Frames are at most 1 MiB.

Frames: `type: u8 ‖ len: u32 ‖ payload`.

| type | Dir | Payload |
|---|---|---|
| 1 HELLO | H→A | JSON `{pid, uid, sdk, version, apk}` (`apk`: the helper's class path; a different one means a stale helper, relaunched) |
| 2 START | A→H | JSON `{id?, rate, capture, inject, injectMs?, testLine?, mic?, micMute?, micCheck?, redirect?, muteMemory?, active?}` (`id`: echoed in this start's INFO; `auto` tries every method in order, but nothing opens before the phone is in a call's audio mode (or the call has been active 3 s), and the interception API refusing for the mode is retried quietly meanwhile; `injectMs`: cap of the uplink playback buffer, default 40; `mic`: auto \| mute \| open, absent: "mute" when `micMute` is true, else "auto"; `micMute`: with "mute", mute the phone's own microphone this call, default false; `micCheck`: then watch the uplink, default true; `redirect`: `{mode: verdict}` what this phone model showed of the redirect modes, so a failed or unverified one is skipped; `muteMemory`: what this phone model showed of the mic mute, ok \| muted-locally-fixed \| unverified (its VOICE_UPLINK shows nothing) or null; `active`: the call is already active, default true) |
| 3 STOP | A→H | – |
| 4 DOWN | H→A | PCM 16-bit LE mono, 20 ms |
| 5 UP | A→H | PCM 16-bit LE mono, 20 ms |
| 6 INFO | H→A | JSON `{id?, capture, inject, routed, routedToCall?, openWaitMs?, openMode?, active?, earpiece, silent, silence, telecomMicMute, uplink, mic, errors}` once a call's audio paths are open (`routedToCall`: the track plays into the call's uplink; `openWaitMs`: how long the paths waited for the call's audio mode; `silence`: how the phone's own earpiece / mic are silenced: samsung, samsung_qualcomm, qualcomm, mediatek, other; `silent`: full, earpiece, none; `telecomMicMute` true: the app mutes the mic through Telecom, never otherwise), `{uplink: {state, micMuted, telecomMicMute, vendorMicMute, unmuted, blind?, kept?, note, local}}` whenever the mic mute or the uplink check changes (`state` as status `helper.uplink`; `unmuted`: the check undid the mute; `blind`: the check heard the client neither with the mute nor without it; `kept`: the mute was put back anyway, the vendor's key being known to keep the injected voice), `{mic: {setting, path, layer?, mode?, state?, verdict?, reasserts?, note}}` with the start INFO and whenever the redirect mode changes (`path` off \| muted \| open; `layer` with the "auto" setting: redirect \| vendor-mute \| open; `state` waiting \| on \| reverted \| stopped; `verdict` as in `selftest.result`, for the app to remember; the `uplink` report then has `for: "redirect"`, else `for: "mute"`), `{path: {event, note}}` when a call's path looks dead (`event` down-nothing \| down-zeros \| up-stuck: the downlink capture delivers nothing or only zeros, or the phone takes nothing from the injection, while the call is active), or `{stats}` at stop, `{autodiag, trigger, testLine}` (see DIAG); replies `{probe}`, `{selftest}`, `{net}`, `{diag}` (a reply after its request timed out is dropped) |
| 7 PROBE | A→H | – (answers INFO `{probe: …}`) |
| 8 QUIT | A→H | – (helper exits) |
| 9 SELFTEST | A→H | – or JSON `{methods?, micMute?, redirect?}` (answers INFO `{selftest: …}`; needs a live call; `micMute`: the vendor's mic key is on during the test; without it the redirect modes are tested too unless `redirect` is false) |
| 15 LOCAL | A→H | JSON `{mic?, micMute, micCheck?, redirect?, muteMemory?}`: the mic setting changed during a bridged call (answers INFO `{uplink, mic}`) |
| 16 CALL | A→H | JSON `{active}`: the bridged call became active (connected) or stopped being so (hold, end); the helper drops uplink frames while it is not active and starts the uplink afresh when it becomes active |
| 17 DIAG | A→H | – or JSON `{logcat?}`: call-audio diagnostics for a problem report, without touching a call's audio (answers INFO `{diag: {at, sdk, audioMode, interceptable, silence, recordings: [{uid, package, self, source, session, device, rate, isClientSilenced, portId?}], playbacks, inputs, outputs, helper, props, dumpsysAudio, dumpsysPolicy, helperLog, logcat?}}`; `props` only allow-listed system properties, the dumpsys lines filtered and without device names). The helper also sends INFO `{autodiag: <the same>, trigger, testLine}` by itself when a path-watch event fires (`trigger` down-nothing \| down-zeros \| up-stuck) and when a bridged call's audio stops (`call-end`) |
| 11 NET | A→H | JSON `{what?, on?, sub?, …}` (network switches; no `what` only reads; answers INFO `{net: …}`) |
| 10 AUTH | A→H | 16-byte nonce ‖ 32-byte HMAC (see above) |
| 12 CHALLENGE | H→A | 16-byte nonce |
| 14 PROOF | H→A | 32-byte HMAC |

## 7. Extensions

Extensions add features on top of the link without changing the core apps or this document's §5: their messages
travel in the same sealed session (§4) and the same control envelope (§5), under a reserved namespace, and each side
says which ones it speaks. Builds from before 2026-11-23 know nothing of them and keep working: they never see an
extension message (nobody sends one to a peer that did not announce it) and ignore the new fields. How to write
one: [docs/EXTENDING.md](../docs/EXTENDING.md).

### 7.1 Names

An **extension id** is `x.` followed by a reverse-DNS name the author controls, at least two labels, lowercase:
`x.com.example.battery`. Labels match `[a-z0-9][a-z0-9-]*`; an id is at most 48 characters. `x.tether.*` is reserved
for extensions shipped in this repository.

An extension's **message types** are its id itself or the id plus one label (`x.com.example.battery`,
`x.com.example.battery.low`), at most 64 characters. Types without the `x.` prefix are core types: only §5 defines
them, and an implementation refuses to register an extension that claims one, so no extension can handle, replace or
send a core message. Each type belongs to exactly one extension.

### 7.2 Capabilities

Each side announces what it speaks in a `caps` array of `"<id>/<major>"` strings (`"x.tether.echo/1"`):

- the client in every `sync` (its first message of a session, and any later re-sync);
- the Android phone in every `status` (pushed at every link up and again whenever it changes, so an extension can
  be switched on or off at run time).

An extension that speaks several major versions lists each. Entries without the `x.` prefix are reserved for core
features (none are defined; receivers ignore entries they do not know), malformed entries are ignored, at most 64
entries count. An absent `caps` means none: every build from before extensions. The latest list of a session
replaces the previous one; both are forgotten when the session ends. For each extension the **agreed version** is the
highest major in both lists; without one the extension is off on that link.

Why these two messages rather than a new one: they already open every session in both directions, so negotiating
costs no message and no round trip, a client's extensions are known before the first answer to its sync, and older
apps already ignore unknown fields in both (pinned by the `unknown` fixture variants). A dedicated message would add a
core type, an ordering problem against `sync`, and nothing an older peer could use.

### 7.3 Sending

- An unsolicited extension message (no `re`) goes only to a peer that agreed a version of its extension. A request
  carries an `id`; the answer (same or another type of the extension) carries `re`. An answer to a peer's request is
  always allowed, agreed or not.
- `id`s are the sender's own; a sender keeps them unique among all its open requests, core and extension alike (the
  iPhone draws both from one counter; the Android apps start their extensions' ids at 2^30, far from their own).
- **Size:** the whole encoded envelope is at most 16 384 bytes, and at most 2 048 bytes while call audio runs on that
  link (a large record holds the next audio frames back). Larger data is paged by the extension.
- **Rate:** at most 20 extension messages per second per link and direction, in bursts of at most 40. A receiver
  drops what goes over, silently.
- Extension messages never use the urgent queue or the wake characteristic (§1): they never wake a suspended iPhone
  and never go ahead of call events or audio.

### 7.4 Receiving

1. Core types (§5) are handled by the app; extension code never sees them.
2. An `x.` type a registered, enabled extension handles goes to it.
3. Any other `x.` type that carries an `id` is answered with
   `error {msg, t: <type>, code: "unknown-type"}` and `re` = that `id`; without an `id` it is ignored.
4. An extension message over the size limit is refused the same way with `code: "too-large"`.
5. An `error` whose `re` matches an open request of an extension goes to that extension, not to the app.
6. Unknown core types are still ignored and never answered, as every build always did: an older peer may not
   understand `error` at all, and §5 types change only with this document.

What the apps did before extensions, and still do for core traffic: the Android phone ignores an unknown type (a log
line), the iPhone ignores it (only `ping` is answered by `TetherLink` itself), the Android client ignores it and shows
an `error` that answers none of its extensions' requests as a problem.

**Failures stay inside the extension.** An exception thrown by an extension is caught and logged; the session goes
on, and a request that carried an `id` is answered with `error` `code: "failed"`. After 10 failures in a row an
extension is switched off for the rest of the process: its types become unknown (rule 3) and its `caps` entries
disappear from the next `status` / `sync`.

### 7.5 Status fields

The Android phone's extensions may add small status fields: `status.ext` is an object keyed by extension id, each
value at most 1 024 bytes encoded, sent only to clients that agreed a version of that extension. Clients that do not
know an id ignore it.

### 7.6 Versions

A major version changes only for an incompatible change (a field removed or its meaning changed, a new required
field). Adding optional fields or new message types of the extension does not need a new major: receivers ignore what
they do not know, exactly as for core messages. A side keeps announcing and answering an older major as long as
installed peers may still use it.

### 7.7 From extension to core

An extension that becomes part of what every Tether build does is promoted:

1. New core type names without the `x.` prefix, added to §5, Kotlin `T`, Swift `MsgType` and `protocol/fixtures/`,
   like any new message type.
2. The Android phone keeps answering the `x.` types and keeps them in its `caps` while installed clients may still use
   them (at least one release); new clients use the core types when the phone's `status` shows it has them
   (its `caps` or the core fields of the promoted feature).
3. A retired `x.` name is never reused for something else. Third-party extensions are promoted only with their
   authors' agreement.

### 7.8 Security and privacy

Extension messages have the same protection as every other message: encrypted and authenticated end to end, only
between paired devices. An extension is trusted like the app it runs in: it sees only its own messages and can send
only its own types, but it runs with the app's permissions. Extensions must not carry phone numbers, texts or
contacts unless that is the feature the user turned on, never log message contents (counts only, as the debug hooks
do), validate every field they read, and treat a test transport (`dry`) as off-limits for anything real.

### 7.9 Built-in: `x.tether.echo` (version 1)

Link diagnostics, registered on every side (the Android phone, the iPhone, the Android client), both directions:

| Type | Body |
| --- | --- |
| `x.tether.echo` with `id` (request) | `{ts, data?}`: the asker's clock (Unix ms) and an optional payload |
| `x.tether.echo` with `re` (answer) | `{ts, data?, at, via}`: `ts` and `data` copied, `at` the responder's clock, `via` its transport ("l2cap", "gatt", "memory") |

The asker gets the control round trip (`now − ts`) and the clock offset (`at − (ts + rtt / 2)`); a larger `data` measures
throughput within the size limit. A message with neither `id` nor `re` is ignored. A test message type that no build
registers, `x.org.example.unknown`, pins rule 3 in the fixtures.
