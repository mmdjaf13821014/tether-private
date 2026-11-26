package app.tether.link

import app.tether.adb.AdbPairing
import app.tether.adb.Adb
import app.tether.core.Phone as PhoneEntry
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.tether.TetherApp
import app.tether.report.PhoneReport
import app.tether.report.ReportProvider
import app.tether.helper.MicPath
import app.tether.core.AudioFrame
import app.tether.core.CentralHandshake
import app.tether.core.Message
import app.tether.core.P256
import app.tether.core.Proto
import app.tether.core.RecordReader
import app.tether.core.T
import app.tether.telecom.CallRegistry
import app.tether.telecom.TestConnectionService
import org.json.JSONObject
import app.tether.data.UssdCode
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * adb-only test hooks (protected by the DUMP permission, which apps cannot hold):
 *
 *   adb shell am broadcast -n app.tether.android/app.tether.link.DebugReceiver -a app.tether.DEBUG --es cmd <cmd>
 *
 * cmd = state | pair_code | fake_incoming [--es num N] | fake_end | dryrun_on | dryrun_off |
 *       loopback (full handshake + sync against an in-process fake iPhone) | probe | helper_cmd
 */
class DebugReceiver : BroadcastReceiver() {
    private fun isTestNumber(n: String) = TestGuards.isTestNumber(n)

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TetherApp
        val cmd = intent.getStringExtra("cmd") ?: "state"
        val pending = goAsync()
        Thread {
            try {
                val out = run(app, cmd, intent).trim().replace("\n", " | ")
                // Never write the pairing code or the helper's token into logcat (bug reports carry it).
                // Nor the problem report's lines (they would land in the next report's app log).
                Log.i(TAG, "debug $cmd: " + if (cmd in setOf("pair_code", "helper_cmd", "report_lines")) "(${out.length} chars, not logged)" else out)
                pending.resultData = out
            } catch (t: Throwable) {
                Log.e(TAG, "debug $cmd failed", t)
                pending.resultData = "error: $t"
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Builds the problem report as the Help button does and says where it is and what is in it (counts only). */
    private fun report(app: TetherApp): String {
        val t0 = System.currentTimeMillis()
        val text = PhoneReport.build(app).build()
        val f = ReportProvider.write(app, text)
        val lines = text.lines()
        val sections = lines.withIndex().filter { it.value.startsWith("## ") }
        val outline = sections.mapIndexed { i, s -> "${s.value.removePrefix("## ")} ${(sections.getOrNull(i + 1)?.index ?: lines.size) - s.index - 1}" }
        return "path=${f.absolutePath} bytes=${f.length()} lines=${lines.size} built in ${System.currentTimeMillis() - t0} ms; sections: " +
            outline.joinToString(", ") + "; isClientSilenced lines=${lines.count { "isClientSilenced=" in it }}, snapshots=${lines.count { it.startsWith("-- snapshot ") }}"
    }

    /** x.tether.echo to every client that announced it: round trip, clock offset and transport per answer. */
    private fun extEcho(app: TetherApp, data: String?): String {
        val echo = app.hub.extensions.extensions.filterIsInstance<app.tether.core.ext.EchoExtension>().firstOrNull() ?: return "echo not registered"
        val results = LinkedBlockingQueue<app.tether.core.ext.EchoExtension.Result>()
        echo.onResult = { results.put(it) }
        try {
            val sent = app.hub.extensions.sessions(echo).count { echo.ping(it, data) != null }
            val got = (1..sent).mapNotNull { results.poll(3, TimeUnit.SECONDS) }
            return "sent $sent, answered ${got.size}" + got.joinToString("") { r ->
                if (r.error != null) "; error ${r.error}" else "; rtt ${r.rttMs} ms, offset ${r.offsetMs} ms, via ${r.via}, ${r.bytes} bytes"
            }
        } finally { echo.onResult = null }
    }

    private fun run(app: TetherApp, cmd: String, intent: Intent): String = when (cmd) {
        "state" -> (LinkService.running?.debugState() ?: JSONObject().put("service", false))
            .put("status", app.hub.statusJson().also { st ->
                // The SIMs' own numbers never go to logcat or a terminal.
                st.optJSONArray("sims")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.remove("number") }
            }).put("calls", CallRegistry.list().size).toString()
        "pair_code" -> app.keys.openPairing().encode()
        // Never rings the user's iPhone by accident: with an iPhone connected it refuses (use loopback_call instead).
        // A test-line ring reaches a linked iPhone only when asked for (--es allow iphone).
        "fake_incoming" -> if (!TestGuards.fakeCallAllowed(app.hub.targets().map { it.kind }, intent.getStringExtra("allow")))
                "refused: an iPhone would ring; add --es allow iphone to ring it on purpose, or use loopback_call"
            else { TestConnectionService.ringIn(app, intent.getStringExtra("num") ?: "5550100"); "ringing" }
        // fake_outgoing: an outgoing call on the self-managed test line (never a SIM), shown on a linked iPhone.
        "fake_outgoing" -> if (!TestGuards.fakeCallAllowed(app.hub.targets().map { it.kind }, intent.getStringExtra("allow")))
                "refused: an iPhone would show it; add --es allow iphone"
            else { TestConnectionService.dialOut(app, "+10000000009"); "dialing on the test line" }
        "fake_end" -> { TestConnectionService.endCurrent(); "ended" }
        "dryrun_on" -> { Hub.dryRun = true; "dry run on" }
        "dryrun_off" -> { Hub.dryRun = false; "dry run off" }
        // Creates a throw-away contact, edits it through the same path the iPhone uses, reads it back and
        // deletes it again. Touches no existing contact.
        "contact_test" -> {
            val repo = app.contacts
            // Three numbers, one with a custom kind. Edit 1 (as an older iPhone, no labels): the first changes type, the
            // labelled one must keep "Satellite", the third is dropped, a new labelled one is added. Edit 2 clears the
            // label (""). Only throw-away +1000000000x numbers; the contact is deleted at the end.
            val id = repo.add("Tether Test", listOf(PhoneEntry("+10000000006", "mobile"), PhoneEntry("+10000000007", "other", "Satellite"),
                PhoneEntry("+10000000009", "home")), emptyList())
            fun shown() = repo.all(withPhotos = false).firstOrNull { it.id == id }?.phones?.sortedBy { it.n }?.map { it.n.takeLast(1) + "/" + it.type + (it.label?.let { l -> "/$l" } ?: "") }
            val added = shown()
            val err = repo.update(id, "Tether Test Edited", listOf(PhoneEntry("+10000000006", "work"), PhoneEntry("+10000000007", "other"),
                PhoneEntry("+10000000008", "home", "Studio")), listOf("test@example.com"))
            val edited = shown()
            val err2 = repo.update(id, "Tether Test Edited", listOf(PhoneEntry("+10000000006", "work"), PhoneEntry("+10000000007", "home", ""),
                PhoneEntry("+10000000008", "home", "Studio")), listOf("test@example.com"))
            val cleared = shown()
            val after = repo.all(withPhotos = false).firstOrNull { it.id == id }
            val deleted = app.contentResolver.delete(android.provider.ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(android.provider.ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                "${android.provider.ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(id.toString()))
            "id=$id added=$added update=${err ?: "ok"} edited=$edited update2=${err2 ?: "ok"} cleared=$cleared after=${after?.name} emails=${after?.emails} deleted=$deleted"
        }
        // sms_read <thread> <upTo>: what an sms.read from the iPhone does (mark read, granting the app-op if needed)
        "sms_read" -> {
            val thread = intent.getStringExtra("thread")?.toLongOrNull() ?: -1; val upTo = intent.getStringExtra("upTo")?.toLongOrNull() ?: -1
            val pending = app.sms.unreadCount(thread, upTo)
            var n = if (pending == 0) 0 else app.sms.markRead(thread, upTo)
            var granted = ""
            if (pending > 0 && n <= 0) { granted = " granted=" + Adb.allowSmsWrite(app).trim(); n = app.sms.markRead(thread, upTo) }
            "pending=$pending marked=$n$granted left=${app.sms.unreadCount(thread, upTo)}"
        }
        // ---- delete / block / mute tests: only throw-away rows with a +1000000000x number are ever touched.
        // test_sms <addr> <body>: insert an incoming row (as if received); says whether Tether would forward it
        "test_sms" -> {
            val addr = intent.getStringExtra("num") ?: ""
            if (!isTestNumber(addr)) "refused: test numbers only (+1000000000x)" else {
                // "out" inserts it as sent (clients show no notification for it)
                val id = app.sms.insertTest(addr, intent.getStringExtra("body") ?: "Tether test", if (intent.getStringExtra("out") == "1") 2 else 1)
                val row = app.sms.query("_id = ?", arrayOf(id.toString()), "_id ASC", 1).firstOrNull()
                "id=$id thread=${row?.thread} blocked=${app.lists.isBlocked(addr)} forwarded=${row != null && !app.lists.isBlocked(addr)}"
            }
        }
        // test_sms_delete <id|thread:N>: the sms.delete path, on test rows only
        "test_sms_delete" -> {
            val arg = intent.getStringExtra("what") ?: ""
            val thread = arg.removePrefix("thread:").toLongOrNull()?.takeIf { arg.startsWith("thread:") }
            val ids = if (thread != null) app.sms.idsIn(thread) else listOfNotNull(arg.toLongOrNull())
            val rows = ids.chunked(200).flatMap { ch -> app.sms.query("_id IN (${ch.joinToString(",")})", null, "_id ASC", 1000) }
            if (rows.isEmpty() || rows.any { !isTestNumber(it.addr) }) "refused: not only test rows (${rows.size} rows)"
            else app.hub.applySmsDelete(if (thread != null) emptyList() else ids, thread)
                .also { app.hub.broadcast(Message(T.SMS_DELETED, it)) }.toString()
        }
        // test_call <num>: a throw-away missed-call row; test_call_delete <id>: the calls.delete path on it
        "test_call" -> intent.getStringExtra("num").let { n -> if (n == null || !isTestNumber(n)) "refused: test numbers only" else "id=${app.callLog.insertTest(n)}" }
        "test_call_delete" -> {
            val id = intent.getStringExtra("what")?.toLongOrNull() ?: -1
            val row = app.callLog.since(id - 1, 1).firstOrNull { it.id == id }
            if (row == null || !isTestNumber(row.num)) "refused: not a test row"
            else app.hub.applyCallsDelete(listOf(id)).also { app.hub.broadcast(Message(T.CALLS_DELETED, it)) }.toString()
        }
        // block <num> on|off (test numbers only) | lists: the lists.set path
        "block" -> {
            val n = intent.getStringExtra("num") ?: ""
            if (!isTestNumber(n)) "refused: test numbers only" else
                app.hub.applyListsSet(JSONObject().put("what", "block").put("num", n).put("on", intent.getStringExtra("on") != "off")).let { j ->
                    app.hub.broadcast(Message(T.LISTS, j))
                    "where=${j.optString("where")} count=${j.getJSONArray("blocked").length()} listed=${(0 until j.getJSONArray("blocked").length()).any { j.getJSONArray("blocked").getString(it) == n }}" } +
                    " isBlocked=${app.lists.isBlocked(n)} system=${runCatching { android.provider.BlockedNumberContract.isBlocked(app, n) }.getOrElse { "err " + it.message }}"
        }
        "mute" -> app.hub.applyListsSet(JSONObject().put("what", "mute").put("thread", intent.getStringExtra("thread")?.toLongOrNull() ?: -1)
            .put("on", intent.getStringExtra("on") != "off")).let { app.hub.broadcast(Message(T.LISTS, it)); "muted=${it.get("muted")}" }
        // ussd_test <code>: DRY. Validates the way ussd.send does and logs; nothing ever reaches the network.
        "ussd_test" -> intent.getStringExtra("code").orEmpty().let { c -> "valid=${UssdCode.valid(c)} sent=false" }
            .also { android.util.Log.i("Tether", "ussd_test (dry) $it") }
        // phone_find on|off: really rings the Android phone on the alarm stream (max 30 s); ringer: state only
        "phone_find" -> if (intent.getStringExtra("on") == "on") (app.hub.finder.start() ?: "ringing") else { app.hub.finder.stop(); "stopped" }
        "ringer" -> "mode=${app.hub.ringer.mode()} dnd=${app.hub.ringer.dndAccess()} finding=${app.hub.finder.active}"
        "lists" -> app.lists.json().let { j -> JSONObject().put("blockedCount", j.getJSONArray("blocked").length()).put("muted", j.get("muted")).put("system", j.get("system")) }.toString()
        // share on|off|loopback|noloopback|state: the Share VPN proxy (loopback = also on 127.0.0.1 for adb-forward tests)
        "share" -> {
            when (intent.getStringExtra("on")) {
                "on" -> { app.hub.share.sharing = true; app.prefs.edit().putBoolean("share_vpn", true).apply() }
                "off" -> { app.hub.share.sharing = false; app.prefs.edit().putBoolean("share_vpn", false).apply() }
                "loopback" -> app.hub.share.loopbackForTests = true
                "noloopback" -> app.hub.share.loopbackForTests = false
            }
            app.hub.share.refresh()
            val seen = runCatching { java.net.NetworkInterface.getNetworkInterfaces().toList().map { n -> n.name + (if (n.isUp) "+" else "-") + n.inetAddresses.toList().count { it is java.net.Inet4Address } } }.getOrElse { listOf("err ${it.message}") }
            "sharing=${app.hub.share.sharing} loopback=${app.hub.share.loopbackForTests} tether=${app.hub.share.tetherAddresses().map { it.first }} seen=$seen ${app.hub.share.json()}"
        }
        // forget_kind <kind>: unpair every client of that kind ("mac") and drop its link
        "forget_kind" -> {
            val kind = intent.getStringExtra("kind") ?: ""
            val gone = app.keys.centrals().filter { it.kind == kind }
            gone.forEach { app.keys.removeCentral(it.key) }
            app.hub.conns.filter { it.kind == kind }.forEach { it.close("unpaired (client removed)") }
            app.hub.refresh()
            "removed ${gone.size} $kind client(s); left: ${app.keys.centrals().map { it.kind }}"
        }
        // test_contact +1000000000x | star <id> on|off | test_contact_delete <id>: favourites on a throw-away contact
        "test_contact" -> intent.getStringExtra("num").let { n ->
            if (n == null || !isTestNumber(n)) "refused: test numbers only"
            else "id=" + app.contacts.add("Tether Test", listOf(PhoneEntry(n, "mobile")), emptyList())
        }
        "star" -> {
            val id = intent.getStringExtra("id")?.toLongOrNull() ?: -1
            val c = app.contacts.all(withPhotos = false).firstOrNull { it.id == id }
            if (c == null || c.phones.isEmpty() || c.phones.any { !isTestNumber(it.n) }) "refused: test contacts only"
            else "changed=${app.contacts.setStarred(id, intent.getStringExtra("on") != "off")} starred=${app.contacts.isStarred(id)}"
        }
        "test_contact_delete" -> app.contacts.deleteTestContact(intent.getStringExtra("id")?.toLongOrNull() ?: -1)
        // airplane on|off: what the iPhone's airplane switch does (fail-safe and tethering restore included)
        "airplane" -> app.hub.helper.ensureRunning()?.let { "helper: $it" } ?: app.hub.setAirplane(intent.getStringExtra("on") == "on").toString()
        // linklog [n]: the persistent Bluetooth link event log
        "linklog" -> LinkLog.tail(intent.getStringExtra("n")?.toIntOrNull() ?: 120)
        // The problem report of Help › Send a problem report, written to the app's cache (never to shared storage): its
        // path, size and an outline; report_lines prints part of it (lines are joined with " | ").
        "report" -> report(app)
        "report_lines" -> ReportProvider.latest(app)?.readLines()?.let { l ->
            val from = intent.getStringExtra("from")?.toIntOrNull() ?: 0
            l.drop(from).take(intent.getStringExtra("n")?.toIntOrNull() ?: 200).joinToString("\n")
        } ?: "no report yet: run report first"
        // Extensions (protocol §7): what is registered, what each client announced, counters; no message contents.
        "ext" -> app.hub.extensions.debug().toString()
        "ext_echo" -> extEcho(app, intent.getStringExtra("data"))
        // net datasub <subId>: choose the SIM that carries mobile data
        "net_datasub" -> app.hub.helper.ensureRunning()?.let { "helper: $it" }
            ?: app.hub.helper.net("dataSub", true, intent.getStringExtra("sub")?.toIntOrNull()).toString()
        // net [what on|off]: read or flip mobile data / hotspot / bt tethering through the running helper
        "net" -> app.hub.helper.ensureRunning()?.let { "helper: $it" }
            ?: app.hub.helper.net(intent.getStringExtra("what"), intent.getStringExtra("on") == "on").toString()
        "probe" -> app.hub.helper.ensureRunning()?.let { "helper: $it" } ?: app.hub.helper.request("probe").toString()
        "helper_cmd" -> app.hub.helper.launchCommand()
        "loopback" -> loopback(app)
        "codec_bench" -> codecBench()
        "codec_bench_paced" -> codecBenchPaced()
        // inject_ms <20..200|off>: cap of the helper's uplink playback buffer for the next calls (default 40 ms).
        "inject_ms" -> {
            val v = intent.getStringExtra("ms")?.toIntOrNull()
            app.prefs.edit().apply { if (v == null) remove("inject_ms") else putInt("inject_ms", v.coerceIn(20, 200)) }.apply()
            "inject_ms=${app.prefs.getInt("inject_ms", 40)}"
        }
        // inject auto|intercept|telephony_tx|policy_mix: the uplink injection method for the next calls (the self-test's choice).
        "inject" -> {
            val m = intent.getStringExtra("m")
            if (m == "auto") app.prefs.edit().remove("inject").apply()
            else if (m in setOf("intercept", "telephony_tx", "policy_mix")) app.prefs.edit().putString("inject", m).apply()
            "inject=${app.prefs.getString("inject", "auto")}"
        }
        // mic_mute on|off|state: "mute this phone's mic during bridged calls" (on/off also forget the remembered check result).
        "mic_mute" -> {
            when (intent.getStringExtra("on")) { "on" -> app.hub.audio.setMicSetting(true); "off" -> app.hub.audio.setMicSetting(false) }
            app.hub.pushStatus()
            "micMute=${app.hub.audio.micSetting} remembered=${app.hub.audio.memory()?.wire ?: "none"} helper=${app.hub.audio.helperStatus()} uplink=${app.hub.helper.lastUplink}"
        }
        // mic auto|mute|open|state [keep]: "this phone's microphone during bridged calls" (as the app's picker: a new choice
        // forgets the mute's result, the same choice again also the redirect modes'; `keep` forgets nothing).
        "mic" -> {
            val keep = intent.getStringExtra("keep") != null
            intent.getStringExtra("on")?.let { w -> MicPath.Setting.entries.firstOrNull { it.wire == w } }?.let { app.hub.audio.setMic(it, forget = !keep) }
            app.hub.pushStatus()
            "mic=${app.hub.audio.mic.wire} redirect=${MicPath.memoryJson(app.hub.audio.redirectMemory())} mute=${app.hub.audio.memory()?.wire ?: "none"} " +
                "helper=${app.hub.audio.helperStatus()} micReport=${app.hub.helper.lastMic} uplink=${app.hub.helper.lastUplink}"
        }
        "only" -> { app.hub.onlyKind = intent.getStringExtra("kind")?.takeIf { it != "off" }; "only=${app.hub.onlyKind}" }
        "audio_stats" -> "hub: down=${app.hub.audio.downFrames} up=${app.hub.audio.upFrames} " +
            "encode=%.2f ms decode=%.2f ms ".format(app.hub.audio.encodeNanos / maxOf(1, app.hub.audio.downFrames) / 1e6, app.hub.audio.decodeNanos / maxOf(1, app.hub.audio.upFrames) / 1e6) +
            "echo=%.1f ms over %d; concealed=%d; helper=${app.hub.helper.lastInfo} stats=${app.hub.helper.lastStats}".format(app.hub.audio.echoNanos / maxOf(1, app.hub.audio.echoCount) / 1e6, app.hub.audio.echoCount, app.hub.audio.upLost)
        "audio_reset" -> { app.hub.audio.resetStats(); "reset" }
        // loopback_call [--es mode answer|decline|remote|outgoing|selftest]: answer = ring, answer, audio, keypad, end here;
        // decline = reject while ringing; remote = the far side hangs up; outgoing = a call placed on the test line.
        // drop: close every live session from this side (as a lost link would), to test the iPhone's reconnect.
        // wake: the wake notification now, to the live sessions and to anything else connected to our GATT server.
        "wake" -> {
            val live = app.hub.conns.filter { it.isUp && it.transport.label != "memory" }.map { "${it.name}: ${app.hub.wake(it)}" }
            (live + ("unlinked: " + (LinkService.running?.peripheral?.wakeUnlinked() ?: "no peripheral"))).joinToString("; ")
        }
        "drop" -> app.hub.conns.filter { it.isUp }.let { l -> l.forEach { it.close("dropped by test") }; "dropped ${l.size}" }
        "loopback_call" -> loopbackCall(app, intent.getStringExtra("sync") == "full", intent.getStringExtra("mode") ?: "answer")
        "loopback_handover" -> loopbackHandover(app)
        "adb_pair" -> {
            // The notification's path (a short foreground service), so this hook never blocks its broadcast either.
            val code = AdbPairing.normalizeCode(intent.getStringExtra("code")) ?: error("--es code with 6 digits required")
            AdbPairing.start(app, code); "started (see the notification and the log)"
        }
        // adb_pair_clear: removes the pairing notification (after testing adb_pair on a phone that is already paired).
        "adb_pair_clear" -> { app.getSystemService(android.app.NotificationManager::class.java).cancel(Adb.NOTIF_ADB); "cleared" }
        "adb_shell" -> app.adbShell(intent.getStringExtra("sh") ?: "id")
        "helper_start" -> app.hub.helper.ensureRunning() ?: "helper running"
        "helper_quit" -> if (app.hub.helper.quit()) "quit sent" else "helper not connected"
        else -> "unknown command"
    }

    /**
     * Pairs an in-process fake central over an in-memory transport, runs sync and a dry-run SMS send,
     * then restores the real pairing. Exercises Connection/Hub/repos exactly as the iPhone would.
     */
    private fun loopback(app: TetherApp): String {
        val saved = app.keys.snapshot()
        val wasDry = Hub.dryRun
        Hub.dryRun = true
        val log = StringBuilder()
        val pipe = MemoryTransport()
        try {
            val code = app.keys.openPairing()
            val central = CentralHandshake(P256.generate(), code.peripheralKey, code.token)
            app.hub.exclusive = pipe
            app.hub.onTransport(pipe)
            pipe.toPeer(central.hello())
            val (session, fin) = central.receive(pipe.next()!!)
            pipe.toPeer(fin)
            central.verifyFinish(session, pipe.next()!!)
            log.append("handshake ok; ")
            fun send(m: Message) = pipe.toPeer(session.seal(Proto.CH_CONTROL, m.toJson()))
            send(Message(T.SYNC, JSONObject().put("sms", 0).put("calls", 0).put("contacts", "")
                .put("codecs", org.json.JSONArray().put("opus").put("pcmu")), id = 1))
            send(Message(T.SMS_SEND, JSONObject().put("to", "+15550100").put("body", "dry run"), id = 2))
            send(Message(T.CALL_DIAL, JSONObject().put("num", "+15550100"), id = 3))
            send(Message(T.CONTACT_ADD, JSONObject().put("name", "Dry Run").put("phones", org.json.JSONArray()
                .put(JSONObject().put("n", "+15550100").put("type", "mobile"))).put("emails", org.json.JSONArray()), id = 4))
            send(Message(T.PING, JSONObject().put("ts", 1), id = 5))
            val counts = HashMap<String, Int>()
            val items = HashMap<String, Int>()
            val seen = HashSet<Int>()
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val r = pipe.next(3) ?: break
                val (ch, p) = session.open(r)
                if (ch != Proto.CH_CONTROL) continue
                val m = Message.parse(p)
                counts[m.t] = (counts[m.t] ?: 0) + 1
                m.b.optJSONArray("items")?.let { items[m.t] = (items[m.t] ?: 0) + it.length() }
                if (m.re != null) log.append("${m.t}(re=${m.re}) ${m.b}; ")
                m.re?.let { seen.add(it) }
                if (seen.containsAll(listOf(2, 3, 4, 5))) break
            }
            log.append("received=$counts items=$items")
        } finally {
            app.hub.exclusive = null
            pipe.close()
            app.keys.restore(saved)
            app.keys.closePairing()
            Hub.dryRun = wasDry
        }
        return log.toString()
    }

    /**
     * Fake iPhone + fake line: ring, expect call.state ringing, answer from the "iPhone", expect active and
     * audio.start, exchange audio frames both ways, end from the "iPhone", expect ended and audio.stop.
     */
    private fun loopbackCall(app: TetherApp, ringDuringSync: Boolean = false, mode: String = "answer"): String {
        val saved = app.keys.snapshot()
        val log = StringBuilder()
        val pipe = MemoryTransport()
        try {
            val code = app.keys.openPairing()
            val central = CentralHandshake(P256.generate(), code.peripheralKey, code.token)
            app.hub.exclusive = pipe
            app.hub.onTransport(pipe)
            pipe.toPeer(central.hello())
            val (session, fin) = central.receive(pipe.next()!!)
            pipe.toPeer(fin)
            central.verifyFinish(session, pipe.next()!!)
            fun send(m: Message) = pipe.toPeer(session.seal(Proto.CH_CONTROL, m.toJson()))
            // With ringDuringSync the "iPhone" asks for a full sync first, so the ring competes with ~250 pages.
            send(Message(T.SYNC, JSONObject().put("sms", if (ringDuringSync) 0 else Long.MAX_VALUE / 2)
                .put("calls", if (ringDuringSync) 0 else Long.MAX_VALUE / 2)
                .put("contacts", if (ringDuringSync) "" else app.contacts.version()).put("codecs", org.json.JSONArray().put("opus").put("pcmu")), id = 1))
            if (ringDuringSync) Thread.sleep(150)
            app.hub.audio.resetStats()
            val tRing = System.nanoTime()
            var ringMs = -1.0
            if (mode == "outgoing") {
                TestConnectionService.dialOut(app, "+10000000009")
                // Never go on unless the call is the test line's own (a placed call must not reach a SIM).
                Thread.sleep(800)
                val real = CallRegistry.active().filterNot { CallRegistry.isTestCall(app, CallRegistry.cidOf(it)) }
                if (real.isNotEmpty() || TestConnectionService.current == null) {
                    real.forEach { it.disconnect() }
                    error("outgoing test call did not land on the test line: aborted")
                }
            } else TestConnectionService.ringIn(app, "5550123")
            var cid: String? = null
            var codec = -1
            var downFrames = 0
            var states = ArrayList<String>()
            val deadline = System.currentTimeMillis() + if (mode == "selftest") 45_000 else 25_000
            var selftestSent = false
            var taken = false
            var selftestDone = false
            var phase = 0
            var audioSeq = 0
            var tEnd = 0L
            var endMs = -1.0
            while (System.currentTimeMillis() < deadline) {
                val r = pipe.next(2)
                if (r != null) {
                    val (ch, p) = session.open(r)
                    if (ch == Proto.CH_AUDIO) {
                        downFrames++
                        val f = AudioFrame.parse(p)!!
                        // Echo it back as uplink, as the iPhone would send mic audio.
                        pipe.toPeer(session.seal(Proto.CH_AUDIO, AudioFrame(f.codec, audioSeq++ and 0xffff, f.payload).encoded()))
                    }
                    val m = if (ch == Proto.CH_CONTROL) Message.parse(p) else null
                    if (m != null) when (m.t) {
                        T.CALL_STATE -> {
                            val st = m.b.getString("state")
                            if (st == "ringing" && ringMs < 0) ringMs = (System.nanoTime() - tRing) / 1e6
                            if (states.lastOrNull() != st) states.add(st); cid = m.b.getString("cid")
                        }
                        T.AUDIO_START -> { log.append("audio.start ${m.b}; "); codec = if (m.b.getString("codec") == "opus") 1 else 2 }
                        T.AUDIO_STOP -> log.append("audio.stop; ")
                        T.SELFTEST_RESULT -> { log.append("selftest ${m.b}; "); selftestDone = true }
                    }
                }
                if (phase == 0 && mode == "decline" && states.lastOrNull() == "ringing") {
                    tEnd = System.nanoTime(); send(Message(T.CALL_REJECT, JSONObject().put("cid", cid))); phase = 2
                }
                // The test line dials from the phone side: a call dialed on the phone stays there (AudioOwner), so the
                // client moves it to itself as a user would with "Move here" (call.take).
                if (phase == 0 && mode == "outgoing" && cid != null && !taken) { send(Message(T.CALL_TAKE, JSONObject().put("cid", cid))); taken = true }
                if (phase == 0 && mode == "outgoing" && states.lastOrNull() == "active") phase = 1
                if (phase == 0 && mode != "decline" && mode != "outgoing" && states.lastOrNull() == "ringing") { send(Message(T.CALL_ANSWER, JSONObject().put("cid", cid))); phase = 1 }
                // selftest: the client runs the call-audio self-test in the middle of the call (as the iPhone's button does).
                if (phase == 1 && mode == "selftest" && !selftestSent && downFrames >= 50) {
                    send(Message(T.SELFTEST, JSONObject(), id = 2)); selftestSent = true
                }
                if (phase == 1 && downFrames >= 250 && (mode != "selftest" || selftestDone)) {
                    send(Message(T.CALL_DTMF, JSONObject().put("cid", cid).put("d", "5"))); tEnd = System.nanoTime()
                    if (mode == "remote") TestConnectionService.endCurrent() else send(Message(T.CALL_END, JSONObject().put("cid", cid)))
                    phase = 2
                }
                if (phase == 2 && states.lastOrNull() == "ended") { endMs = (System.nanoTime() - tEnd) / 1e6; phase = 3; Thread.sleep(300) }
                if (phase == 3 && r == null) break
            }
            val a = app.hub.audio
            log.append("mode=$mode states=$states downFrames=$downFrames upFrames=$audioSeq codec=$codec ")
            log.append("ring→iPhone=%.1f ms; end→ended=%.1f ms; ".format(ringMs, endMs))
            if (a.downFrames > 0) log.append("encode avg %.2f ms; ".format(a.encodeNanos / a.downFrames / 1e6))
            if (a.upFrames > 0) log.append("decode avg %.2f ms; ".format(a.decodeNanos / a.upFrames / 1e6))
            if (a.echoCount > 0) log.append("Android phone round trip (encode→link→decode→inject queue) avg %.1f ms over %d frames; ".format(a.echoNanos / a.echoCount / 1e6, a.echoCount))
            if (a.upLost > 0) log.append("uplink concealed ${a.upLost} frames; ")
            Thread.sleep(400) // helper stats arrive with its stop
            log.append("timing: ${a.lastTiming}; ")
            log.append("helper=${app.hub.helper.lastInfo} stats=${app.hub.helper.lastStats}")
        } finally {
            TestConnectionService.endCurrent()
            app.hub.exclusive = null
            pipe.close()
            app.keys.restore(saved)
            app.keys.closePairing()
        }
        return log.toString()
    }

    /** Opus encode/decode cost per 20 ms frame on this phone, by encoder complexity (after JIT warm-up). */
    private fun codecBench(): String {
        val rate = 16000
        // Speech-like test signal: a voiced harmonic stack with a slow pitch glide plus some noise.
        val rnd = java.util.Random(1)
        val sig = ShortArray(rate * 6) { i ->
            val f0 = 120 + 40 * Math.sin(i * 2 * Math.PI / rate / 1.3)
            var v = 0.0
            for (h in 1..12) v += Math.sin(i * 2 * Math.PI * f0 * h / rate) / h
            (v * 5000 + rnd.nextGaussian() * 300).toInt().coerceIn(-32768, 32767).toShort()
        }
        val sb = StringBuilder()
        for (cx in listOf(10, 8, 5, 3, 1)) {
            val c = app.tether.audio.OpusCodec(complexity = cx)
            var enc = 0L; var dec = 0L; var bytes = 0; var n = 0
            for (pass in 0..1) for (i in 0 until sig.size / 320) {
                val fr = sig.copyOfRange(i * 320, i * 320 + 320)
                val t0 = System.nanoTime(); val out = c.encode(fr); val t1 = System.nanoTime()
                out.forEach { c.decode(it) }; val t2 = System.nanoTime()
                if (pass == 1) { enc += t1 - t0; dec += t2 - t1; bytes += out.sumOf { it.size }; n++ }
            }
            sb.append("complexity $cx: encode %.2f ms, decode %.2f ms, %.1f kbps; ".format(enc / n / 1e6, dec / n / 1e6, bytes * 8.0 / (n * 0.02) / 1000))
        }
        return sb.toString()
    }

    /** Like a call: one 20 ms frame every 20 ms on an urgent-audio thread (CPU not boosted by back-to-back work). */
    private fun codecBenchPaced(): String {
        val out = StringBuilder()
        val t = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            for (cx in listOf(5, 3, 1)) {
                val c = app.tether.audio.OpusCodec(complexity = cx)
                var enc = 0L; var n = 0; var worst = 0L
                for (i in 0 until 250) {
                    val fr = ShortArray(320) { k -> (6000 * Math.sin((i * 320 + k) * 2 * Math.PI * 180 / 16000)).toInt().toShort() }
                    val t0 = System.nanoTime(); c.encode(fr); val d = System.nanoTime() - t0
                    if (i >= 50) { enc += d; n++; worst = maxOf(worst, d) }
                    Thread.sleep(20)
                }
                out.append("complexity $cx paced: encode avg %.2f ms, worst %.2f ms; ".format(enc / n / 1e6, worst / 1e6))
            }
        }
        t.start(); t.join()
        return out.toString()
    }

    /**
     * Two fake clients ("Test A", "Test B") on the fake line, the real iPhone/Mac untouched: both ring; A answers
     * and gets the audio while B is told `elsewhere`; B sends call.take and the audio moves to B; B hangs up.
     */
    private fun loopbackHandover(app: TetherApp): String {
        val saved = app.keys.snapshot()
        val log = StringBuilder()
        class Fake(val name: String) {
            val pipe = MemoryTransport()
            lateinit var session: app.tether.core.SecureSession
            val events = ArrayList<String>()
            var cid: String? = null
            fun send(m: Message) = pipe.toPeer(session.seal(Proto.CH_CONTROL, m.toJson()))
            fun drain(ms: Long) {
                val until = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < until) {
                    val r = pipe.next(0) ?: run { Thread.sleep(20); null } ?: continue
                    val (ch, p) = session.open(r)
                    if (ch == Proto.CH_AUDIO) { if (events.lastOrNull() != "audio") events.add("audio"); continue }
                    val m = Message.parse(p)
                    when (m.t) {
                        T.CALL_STATE -> { cid = m.b.optString("cid"); events.add("state:" + m.b.optString("state") +
                            (if (m.b.optBoolean("elsewhere")) "@" + m.b.optString("where") else "")) }
                        T.AUDIO_START, T.AUDIO_STOP -> events.add(m.t)
                    }
                }
            }
        }
        val a = Fake("Test A"); val b = Fake("Test B")
        try {
            app.hub.exclusiveSet = setOf(a.pipe, b.pipe)
            for (f in listOf(a, b)) {
                val code = app.keys.openPairing()
                val central = CentralHandshake(P256.generate(), code.peripheralKey, code.token)
                app.hub.onTransport(f.pipe)
                f.pipe.toPeer(central.hello())
                val (session, fin) = central.receive(f.pipe.next()!!)
                f.pipe.toPeer(fin)
                central.verifyFinish(session, f.pipe.next()!!)
                f.session = session
                f.send(Message(T.SYNC, JSONObject().put("sms", Long.MAX_VALUE / 2).put("calls", Long.MAX_VALUE / 2)
                    .put("contacts", app.contacts.version()).put("codecs", org.json.JSONArray().put("opus"))
                    .put("client", JSONObject().put("kind", "test").put("name", f.name)), id = 1))
            }
            a.drain(800); b.drain(10)
            a.events.clear(); b.events.clear()
            TestConnectionService.ringIn(app, "5550199")
            a.drain(1500); b.drain(300)
            a.send(Message(T.CALL_ANSWER, JSONObject().put("cid", a.cid)))
            a.drain(2500); b.drain(300)
            log.append("after A answers: A=${a.events} B=${b.events}; ")
            a.events.clear(); b.events.clear()
            b.send(Message(T.CALL_TAKE, JSONObject().put("cid", b.cid)))
            b.drain(2500); a.drain(300)
            log.append("after B takes: A=${a.events} B=${b.events}; ")
            a.events.clear(); b.events.clear()
            b.send(Message(T.CALL_END, JSONObject().put("cid", b.cid)))
            b.drain(1500); a.drain(300)
            log.append("after B ends: A=${a.events} B=${b.events}")
        } finally {
            TestConnectionService.endCurrent()
            app.hub.exclusiveSet = null
            a.pipe.close(); b.pipe.close()
            app.keys.restore(saved)
            app.keys.closePairing()
        }
        return log.toString()
    }

    /** In-memory transport: what we "receive" is fed by the fake central; what we write is queued for it. */
    private class MemoryTransport : Transport {
        override val label = "memory"
        override val address = "loopback"
        override val dry = true
        private var onBytes: ((ByteArray, Int) -> Unit)? = null
        private var onClosed: (() -> Unit)? = null
        private val fromHub = RecordReader()
        private val written = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false

        override fun start(onBytes: (ByteArray, Int) -> Unit, onClosed: () -> Unit) {
            this.onBytes = onBytes; this.onClosed = onClosed
        }

        override fun write(bytes: ByteArray) { if (closed) throw IOException("closed"); written.put(bytes) }

        override fun close() { if (!closed) { closed = true; onClosed?.invoke() } }

        fun toPeer(r: app.tether.core.Record) { val b = r.encoded(); onBytes!!(b, b.size) }

        fun next(timeoutSec: Long = 5): app.tether.core.Record? {
            while (true) {
                fromHub.next()?.let { return it }
                val b = written.poll(timeoutSec, TimeUnit.SECONDS) ?: return null
                fromHub.append(b)
            }
        }
    }

    companion object { const val TAG = "Tether" }
}
