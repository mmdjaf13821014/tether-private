package app.tether.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What of the system's properties and dumpsys output may go into a problem report (Diag). */
class DiagFilterTest {
    @Test fun propertiesAreAllowListed() {
        val getprop = """
            [ro.product.model]: [SM-A736B]
            [ro.product.manufacturer]: [samsung]
            [ro.board.platform]: [taro]
            [ro.soc.model]: [SM7325]
            [ro.build.version.release]: [16]
            [ro.build.version.oneui]: [80000]
            [ro.build.fingerprint]: [samsung/a73xqxx/a73xq:16/BP2A.250605.031/A736BXXU9FYJ1:user/release-keys]
            [ro.vendor.audio.sdk.fluencetype]: [fluence]
            [ro.serialno]: [R58T123ABC]
            [ro.boot.serialno]: [R58T123ABC]
            [ro.product.serial]: [X]
            [persist.sys.device_name]: [Someone's phone]
            [ro.ril.imei]: [356938035643809]
            [wifi.interface]: [wlan0]
            [ro.product.vendor.bt.name]: [Someone]
            [net.hostname]: [someone-phone]
            [gsm.operator.alpha]: [Carrier]
            [ro.boot.hardware.revision]: [MP 1.0]
            [ro.product.vendor.model]: [SM-A736B]
            [ro.product.system.model]: [SM-A736B]
            [ro.product.model_for_attestation]: [SM-A736B]
            [ro.build.version.known_codenames]: [Base,Cupcake]
        """.trimIndent()
        val p = DiagFilter.props(getprop)
        assertEquals("SM-A736B", p.getString("ro.product.model"))
        assertEquals("taro", p.getString("ro.board.platform"))
        assertEquals("80000", p.getString("ro.build.version.oneui"))
        assertEquals("fluence", p.getString("ro.vendor.audio.sdk.fluencetype"))
        assertTrue(p.has("ro.build.fingerprint") && p.has("ro.boot.hardware.revision") && p.has("ro.soc.model"))
        for (k in listOf("ro.serialno", "ro.boot.serialno", "ro.product.serial", "persist.sys.device_name", "ro.ril.imei", "wifi.interface",
                "ro.product.vendor.bt.name", "net.hostname", "gsm.operator.alpha", "ro.product.system.model", "ro.product.model_for_attestation",
                "ro.build.version.known_codenames")) assertFalse(k, p.has(k))
        assertTrue(p.has("ro.product.vendor.model"))
    }

    @Test fun audioDumpKeepsModeRecordingsAndRoutesWithoutNames() {
        val dump = """
            AudioService Dumpsys
            ## RecordActivityMonitor


            ### Recording Activity
            11-25 15:14:51:358 rec stop riid:29503 uid:2000 session:33041 src:VOICE_COMMUNICATION not silenced pack:com.android.shell
            11-25 15:14:58:540 rec update riid:29519 uid:2000 session:33065 src:VOICE_DOWNLINK silenced pack:com.android.shell
            ## AudioDeviceBroker
                Connected devices:
                  [DeviceInfo: type:0x80 (bt_a2dp) name:Galaxy Buds2 Pro addr:AA:BB:CC:A1:9D:77 codec: 0]
            ## Audio mode:
            - Requested mode = MODE_IN_CALL
            - Actual mode = MODE_IN_CALL
            - Mode owner:
               [1000(telec)|ear_|a|e:o]

            ## Routing events
            ### Phone state changes
            11-25 15:17:30:959 setMode(MODE_RINGTONE) from package=com.android.server.telecom pid=1704
            ### Device connections
            11-25 15:15:41:764 setCommunicationRouteForClient for uid: 1000 device: AudioDeviceAttributes: role:output type:bt_sco addr:AA:BB:CC:A1:9D:77 name:Galaxy Buds2 Pro profiles:[{PCM}] descriptors:[] isPrivileged: true
            ### Other
            setMicrophoneMute use (logged after setMicrophoneMute() is executed)
            11-23 13:56:08:503 setMicrophoneMute() from package=com.android.server.telecom pid=1704 isisEnableMute=true
            Next section
        """.trimIndent().lines()
        val out = DiagFilter.audio(dump).joinToString("\n")
        assertTrue(out, "- Actual mode = MODE_IN_CALL" in out)
        assertTrue(out, "src:VOICE_DOWNLINK silenced" in out)
        assertTrue(out, "setMode(MODE_RINGTONE)" in out)
        assertTrue(out, "isisEnableMute=true" in out)
        assertTrue(out, "type:bt_sco" in out && "name:– isPrivileged" in out)
        assertTrue(out, "-- lines saying silenced" in out)
        assertFalse(out, "Buds" in out)
        assertFalse(out, "profiles:" in out)
    }

    @Test fun policyDumpKeepsInputsAndSilencedClients() {
        val dump = """
            AudioPolicyManager Dump: 0xb400007c1040f8a0
             Phone state: AUDIO_MODE_IN_CALL
             Force use for communications: 0
             Force use for record: 0
             Available input devices (3):
              1. Port ID: 14; "Built-In Mic"; {AUDIO_DEVICE_IN_BUILTIN_MIC, @:bottom}
                 Encapsulation modes: 0, metadata types: 0
              2. Port ID: 18; "Telephony Rx"; {AUDIO_DEVICE_IN_TELEPHONY_RX, @:}
              3. Port ID: 31; "Galaxy Buds2 Pro"; {AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET, @:AA:BB:CC:A1:9D:77}
             Outputs (1):
             Inputs (1):
              1. Port ID: 9; I/O handle: 1234; Latency: 0
                 Devices: {AUDIO_DEVICE_IN_TELEPHONY_RX, @:}
                 sampling rates: 8000, 16000
                 RecordClient: portId 77 uid 2000 session 33121 source AUDIO_SOURCE_VOICE_DOWNLINK silenced: true
             Total Effects CPU: 0.000000 MIPS
            CallStateManager:
                mMode=2
                mVoiceTxIncallMusic=0

            Record Silenced Logs : uid, portId, isAssistantOnTop
            2000, 77, 0
        """.trimIndent().lines()
        val out = DiagFilter.policy(dump).joinToString("\n")
        assertTrue(out, "Phone state: AUDIO_MODE_IN_CALL" in out)
        assertTrue(out, "Port ID: 14 AUDIO_DEVICE_IN_BUILTIN_MIC" in out)
        assertTrue(out, "Port ID: 31 AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET" in out)
        assertTrue(out, "silenced: true" in out)
        assertTrue(out, "mVoiceTxIncallMusic=0" in out)
        assertTrue(out, "2000, 77, 0" in out)
        assertFalse(out, "Buds" in out)
        assertFalse(out, "sampling rates" in out)
    }

    @Test fun names() {
        assertEquals("VOICE_DOWNLINK", DiagFilter.sourceName(3))
        assertEquals("telephony", DiagFilter.deviceName(18))
        assertTrue(DiagFilter.builtIn(15))
        assertFalse(DiagFilter.builtIn(7))
    }

    @Test fun logRingKeepsTheNewest() {
        repeat(450) { LogRing.add("line $it") }
        val t = LogRing.tail()
        assertEquals(400, t.size)
        assertTrue(t.last().endsWith("line 449"))
    }
}
