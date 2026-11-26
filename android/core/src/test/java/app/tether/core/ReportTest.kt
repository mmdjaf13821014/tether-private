package app.tether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The problem report's privacy scrubber and builder (docs/TROUBLESHOOTING.md, "What to include in a report"). */
class ReportTest {
    private val s = ReportScrubber()
    private fun scrub(t: String) = s.scrub(t)

    // ---------------------------------------------------------------- what goes

    @Test fun internationalNumbers() {
        for (n in listOf("+989123456789", "+98 912 345 6789", "+98-912-345-6789", "+98 (912) 345-6789", "+1 (555) 010-0123",
                "+15550100123", "+10000000001", "+44 20 7946 0958", "+49 151 23456789", "+۹۸۹۱۲۳۴۵۶۷۸۹", "+1.555.010.0123")) {
            assertEquals(n, "call from <number> ended", scrub("call from $n ended"))
        }
        assertEquals("{\"number\":\"<number>\"}", scrub("{\"number\":\"+989123456789\"}"))
        assertEquals("(<number>)", scrub("(+98 912 345 6789)"))
    }

    @Test fun localNumbers() {
        for (n in listOf("09123456789", "0912 345 6789", "0912-345-6789", "(021) 1234-5678", "021 1234 5678", "07700 900123", "0151 23456789", "۰۹۱۲۳۴۵۶۷۸۹")) {
            assertEquals(n, "to <number>.", scrub("to $n."))
        }
    }

    @Test fun taggedNumbersOfAnyShape() {
        assertEquals("tel:<number>", scrub("tel:9123456789"))
        assertEquals("sms:<number>", scrub("sms:5550100"))
        assertEquals("number=<number> slot 1", scrub("number=9123456789 slot 1"))
        assertEquals("\"address\":\"<number>\"", scrub("\"address\":\"98912345\""))
    }

    @Test fun emails() {
        assertEquals("from <email> and <email>", scrub("from someone@example.com and first.last+tag@example.org"))
    }

    @Test fun macAddressesKeepTheirLastTwoBytes() {
        assertEquals("ACL up …9D:77 (LE)", scrub("ACL up AA:BB:CC:A1:9D:77 (LE)"))
        assertEquals("addr …ef:01", scrub("addr ab-cd-12-34-ef-01"))
        assertEquals("addr=…9d:77", scrub("addr=4c:2e:5e:a1:9d:77"))
        assertEquals("link …9D:77: interval 30.00 ms", scrub("link AA:BB:CC:B6:9D:77: interval 30.00 ms"))
        assertEquals("addr:…9D:77 name", scrub("addr:AA:BB:CC:A1:9D:77 name"))
        assertEquals("[…9D:77]", scrub("[AA:BB:CC:A1:9D:77]"))
        // Seven bytes are no address (and stay for the key-digest rule only from eight).
        assertEquals("aa:bb:cc:a1:9d:77:01", scrub("aa:bb:cc:a1:9d:77:01"))
        // Already short (the link log's own form): unchanged.
        assertEquals("from …9D:77", scrub("from …9D:77"))
    }

    @Test fun ipAddresses() {
        assertEquals("proxy <ip>:8080 and <ip>", scrub("proxy 192.168.44.1:8080 and 10.0.0.12"))
        assertEquals("helper on 127.0.0.1:47123, bind 0.0.0.0", scrub("helper on 127.0.0.1:47123, bind 0.0.0.0"))
        assertEquals("v6 <ip> and <ip>", scrub("v6 fe80::1c2d:3eff:fe4f:5a6b%wlan0 and 2001:db8:85a3:0:0:8a2e:370:7334"))
        assertEquals("loopback ::1", scrub("loopback ::1"))
    }

    @Test fun identifiersAndSecrets() {
        assertEquals("imei <id>", scrub("imei 356938035643809"))
        assertEquals("iccid <id>", scrub("iccid 8998112345678901234"))
        assertEquals("token <secret>", scrub("token 3f9a1c0be47d2a65f0c3b8e1d4a7f26c"))
        assertEquals("key <secret>.", scrub("key BHk3n9Qx2V8mZp4Lr7Tw1Yc6Ua0Sd5Fg8Hj2Kl4Nm6Pq9Rs3Tv7Wx0Yz1Ab5Cd8E."))
        assertEquals("pairing tether1:<secret>.<secret>.Phone", scrub("pairing tether1:BHk3n9Qx2V8mZp4Lr7Tw1Yc6Ua0Sd5Fg8Hj2Kl4N.4f8a2c9e1b7d3f60a5c8e2b4d6f19a3c.Phone"))
        assertEquals("digest <secret>", scrub("digest 0a:1b:2c:3d:4e:5f:60:71:82:93:a4:b5:c6:d7:e8:f9"))
        assertEquals("peripheral <id>", scrub("peripheral 3F2504E0-4F89-11D3-9A0C-0305E82C3301"))
    }

    @Test fun names() {
        val n = ReportScrubber(names = listOf("Amir's iPhone", "MCI", "Galaxy Buds2 Pro", "x", "iPhone"), keep = listOf("SM-A546E"))
        assertEquals("link up (<name>, l2cap)", n.scrub("link up (Amir's iPhone, l2cap)"))
        assertEquals("SIM <name> · <name>", n.scrub("SIM MCI · mci"))
        assertEquals("MCInfo stays", n.scrub("MCInfo stays"))
        assertEquals("route to <name>", n.scrub("route to Galaxy Buds2 Pro"))
        // Generic words and too short names are never replaced.
        assertEquals("an iPhone, x", n.scrub("an iPhone, x"))
        assertEquals("model SM-A546E", ReportScrubber(names = listOf("SM-A546E"), keep = listOf("SM-A546E")).scrub("model SM-A546E"))
    }

    // ---------------------------------------------------------------- what stays

    @Test fun timestampsStay() {
        for (t in listOf("11-25 16:20:13.123  4321  4398 I Tether  : link up", "2026-11-25 16:20:13 +03:30", "11-25 15:14:51:358 rec stop",
                "Wed Nov 25 16:17:35 GMT+03:30 2026", "2026-11-25T12:50:13.123Z", "at 09:12:34", "since 2026-01-25 08:12")) {
            assertEquals(t, t, scrub(t))
        }
    }

    @Test fun versionsAndBuildsStay() {
        for (t in listOf("Tether 1.2.3 (1020399) · Android phone app", "versionCode 1020399", "Opus 1.5.2", "One UI 6.1", "Android 16 (SDK 36)",
                "samsung/a54xnsxx/a54x:14/UP1A.231005.007/A546EXXU7BXA1:user/release-keys",
                "OnePlus/OnePlus9Pro_EEA/OnePlus9Pro:13/RKQ1.211119.001/R.202305312147:user/release-keys",
                "Xiaomi/alioth_global/alioth:13/TKQ1.221114.001/V14.0.5.0.TKHMIXM:user/release-keys",
                "security patch 2026-10-01", "ro.build.version.incremental: A546EXXU7BXA1", "SoC: Qualcomm SM7325", "WebView 131.0.6778.135",
                "helper version 3 uid 2000 sdk 36")) {
            assertEquals(t, t, scrub(t))
        }
    }

    @Test fun figuresStay() {
        for (t in listOf("down 980 frames, encode 1.92 ms, jitter 3.4 ms worst 87 ms (5 long gaps)", "concealed 0.3 %", "46 ms", "250/250 frames",
                "uplink dropped 0 of 972 frames (12 held back before the call was active)", "clocks capture 16000.4 inject 15999.8 Hz",
                "ring 142-237 ms", "battery 87 %", "-1", "port 47123", "riid:29503 uid:2000 session:33041 src:VOICE_COMMUNICATION not silenced",
                "Flags: 00200002", "Channel mask: 0x3", "pid 12345 tid 12399", "AUDIO_DEVICE_OUT_TELEPHONY_TX", "AudioRecordingConfiguration",
                "isClientSilenced=false", "MODE_CALL_REDIRECT", "android.permission.BLUETOOTH_ADVERTISE: granted", "0 dB", "+3 dB", "drift +150 ppm",
                "sub 2 slot 1", "Device type: 0x20000001, driving stream 3", "Handler (com.android.server.audio.AudioDeviceBroker\$BrokerHandler) {4844bad} @ 581738350")) {
            assertEquals(t, t, scrub(t))
        }
    }

    @Test fun scrubbingIsIdempotent() {
        val once = scrub("+989123456789 a@example.com AA:BB:CC:A1:9D:77 192.168.1.2 356938035643809 3f9a1c0be47d2a65f0c3b8e1d4a7f26c 09123456789")
        assertEquals("<number> <email> …9D:77 <ip> <id> <secret> <number>", once)
        assertEquals(once, scrub(once))
    }

    // ---------------------------------------------------------------- the report

    @Test fun reportFormat() {
        val r = ProblemReport(ProblemReport.appLine("1.2.3", 1020399, "Android phone app"), "2026-11-25 16:20:13 +03:30",
            ReportScrubber(names = listOf("Amir's iPhone")))
        r.section("Device").kv("Manufacturer", "samsung").kv("Empty", null).kv("Owner", "Amir's iPhone")
        r.section("Link").line("connected: 1 (ios) via l2cap\nfrom AA:BB:CC:A1:9D:77")
        r.log("Link log", listOf("11-25 16:20:13.123 link up (Amir's iPhone, l2cap) from …9D:77", "11-25 16:20:14.000 call from +989123456789"))
        val text = r.build()
        val lines = text.lines()
        assertEquals(ProblemReport.TITLE, lines[0])
        assertEquals("App: Tether 1.2.3 (1020399) · Android phone app", lines[1])
        assertEquals("Created: 2026-11-25 16:20:13 +03:30", lines[2])
        assertTrue(lines[3].startsWith("Privacy: phone numbers, names"))
        assertTrue("## Device\nManufacturer: samsung\nEmpty: –\nOwner: <name>\n" in text)
        assertTrue("## Link\nconnected: 1 (ios) via l2cap\nfrom …9D:77\n" in text)
        assertTrue("11-25 16:20:13.123 link up (<name>, l2cap) from …9D:77\n11-25 16:20:14.000 call from <number>\n" in text)
        assertFalse("Amir" in text)
    }

    @Test fun reportKeepsTheNewestLogLinesWithinItsSize() {
        val r = ProblemReport("Tether 1 (1) · test", "now", maxBytes = 20_000)
        r.section("Device").kv("Model", "x")
        r.log("App log", (1..5000).map { "line %05d of the app log, with some words to make it longer".format(it) })
        r.log("Link log", (1..50).map { "link %02d".format(it) })
        val text = r.build()
        assertTrue(text.toByteArray().size <= 20_000)
        assertTrue("line 05000 of the app log" in text)
        assertFalse("line 00001 of the app log" in text)
        assertTrue("older lines left out" in text)
        // The small log fits whole.
        assertTrue("link 01\n" in text && "link 50\n" in text)
    }

    @Test fun reportIsScrubbedAsAWhole() {
        // A value split over two adds still meets the final pass as one line.
        val r = ProblemReport("Tether 1 (1) · test", "now")
        r.section("Setup").kv("Default SMS app", "com.example.sms").line("ip 192.168.1.20 ok")
        assertTrue("ip <ip> ok" in r.build())
    }

    @Test fun createdHasTheUtcOffset() {
        assertEquals("2026-11-25 16:20:13 +03:30", ProblemReport.created(1795611013000L, ZoneId.of("Asia/Tehran")))
        assertEquals("2026-11-25 12:50:13 +00:00", ProblemReport.created(1795611013000L, ZoneId.of("UTC")))
    }
}
