package app.tether.audio

import app.tether.helper.LocalMutes
import app.tether.helper.MicPath
import app.tether.helper.MicPath.Mode
import app.tether.helper.MicPath.Redirect
import app.tether.helper.MicPath.Setting
import app.tether.helper.MicPath.Verdict
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which way keeps the Android phone's mic out of a bridged call (issue #2): the setting, the mode ladder, the memory. */
class MicPathTest {
    private fun choose(sdk: Int = 34, setting: Setting = Setting.AUTO, mode: Int = Mode.IN_CALL, inject: String? = "intercept",
                       screening: Boolean = false, memory: Map<Redirect, Verdict> = emptyMap()) =
        MicPath.choose(sdk, setting, mode, inject, screening, memory)

    @Test fun settingFromTheWireAndFromOldClients() {
        assertEquals(Setting.OPEN, Setting.of("open"))
        assertEquals(Setting.MUTE, Setting.of("mute", legacyMicMute = false))
        assertEquals(Setting.MUTE, Setting.of(null, legacyMicMute = true))
        assertEquals("an old client's switch off means the default, not \"open\"", Setting.AUTO, Setting.of(null, legacyMicMute = false))
        assertEquals(Setting.AUTO, Setting.of(null))
        assertEquals("an unknown value from a newer client", Setting.AUTO, Setting.of("something-new"))
    }

    @Test fun neverOnSamsung() {
        // Issue #13: on a Galaxy A73 the call's audio stayed broken until a reboot after call_redirect; on the A54 the
        // downlink went silent in it. Samsung phones never get a redirect mode automatically.
        assertNull(MicPath.choose(34, Setting.AUTO, Mode.IN_CALL, "intercept", true, emptyMap(), samsung = true))
        assertEquals("no redirect mode on Samsung phones (it silenced their call audio)",
            MicPath.noRedirectWhy(34, "intercept", Mode.IN_CALL, emptyMap(), samsung = true))
    }

    @Test fun aPstnCallGetsCallRedirectByDefault() {
        assertEquals(Redirect.CALL_REDIRECT, choose())
        assertEquals(Redirect.COMMUNICATION_REDIRECT, choose(mode = Mode.IN_COMMUNICATION))
    }

    @Test fun onlyWithTheDefaultSettingOnAndroid13WithTheInterceptionApi() {
        assertNull(choose(setting = Setting.MUTE))
        assertNull(choose(setting = Setting.OPEN))
        assertNull("Android 11-12: the incall-music path needs the call's own routing", choose(sdk = 32))
        assertNull(choose(inject = "telephony_tx"))
        assertNull("no uplink injection at all", choose(inject = null))
        assertNull("not a call's mode (still ringing, or ended)", choose(mode = Mode.NORMAL))
        assertNull(choose(mode = Mode.RINGTONE))
    }

    @Test fun aFailedModeIsNotUsedAgainTheNextRungIs() {
        val broke = mapOf(Redirect.CALL_REDIRECT to Verdict.BREAKS_UPLINK)
        assertNull("call screening mode not declared by the phone", choose(memory = broke))
        assertEquals(Redirect.CALL_SCREENING, choose(screening = true, memory = broke))
        for (v in listOf(Verdict.BREAKS_DOWNLINK, Verdict.REFUSED, Verdict.NO_EFFECT))
            assertEquals(v.wire, Redirect.CALL_SCREENING, choose(screening = true, memory = mapOf(Redirect.CALL_REDIRECT to v)))
        assertNull(choose(screening = true, memory = broke + (Redirect.CALL_SCREENING to Verdict.NO_EFFECT)))
        assertEquals("a verdict for VoIP says nothing about real calls", Redirect.CALL_REDIRECT,
            choose(memory = mapOf(Redirect.COMMUNICATION_REDIRECT to Verdict.BREAKS_DOWNLINK)))
    }

    @Test fun workingModesAreKeptAndOnlyAnUntestedOneIsCheckedWithSpeech() {
        assertEquals(Redirect.CALL_REDIRECT, choose(memory = mapOf(Redirect.CALL_REDIRECT to Verdict.OK)))
        assertTrue(MicPath.wantSpeechCheck(null))
        assertFalse(MicPath.wantSpeechCheck(Verdict.OK))
    }

    @Test fun aModeWhoseUplinkCouldNotBeSeenIsNotUsedAutomatically() {
        // Issue #15: "unverified" used to be usable, so a phone with a blind VOICE_UPLINK went into the mode on every
        // later call without any check; had the mode silenced the uplink, nobody would have heard the iPhone.
        assertFalse(Verdict.UNVERIFIED.usable)
        assertNull(choose(memory = mapOf(Redirect.CALL_REDIRECT to Verdict.UNVERIFIED)))
        // The phone's uplink cannot be seen: the next untested mode could not be checked either (Poco F3 / X3 Pro).
        assertNull(choose(screening = true, memory = mapOf(Redirect.CALL_REDIRECT to Verdict.UNVERIFIED)))
        assertNull(choose(screening = true, memory = mapOf(Redirect.CALL_SCREENING to Verdict.UNVERIFIED)))
        assertEquals("a mode that passed a check stays usable", Redirect.CALL_SCREENING,
            choose(screening = true, memory = mapOf(Redirect.CALL_REDIRECT to Verdict.UNVERIFIED, Redirect.CALL_SCREENING to Verdict.OK)))
        assertEquals("a blind uplink on the VoIP test line says nothing about real calls", Redirect.CALL_REDIRECT,
            choose(memory = mapOf(Redirect.COMMUNICATION_REDIRECT to Verdict.UNVERIFIED)))
        assertTrue(MicPath.noRedirectWhy(34, "intercept", Mode.IN_CALL, mapOf(Redirect.CALL_REDIRECT to Verdict.UNVERIFIED))
            .contains("call_screening not tried (the uplink cannot be checked)"))
    }

    @Test fun whyNoRedirectModeIsSaid() {
        assertEquals("no redirect mode before Android 13", MicPath.noRedirectWhy(32, "telephony_tx", Mode.IN_CALL, emptyMap()))
        assertTrue(MicPath.noRedirectWhy(34, "telephony_tx", Mode.IN_CALL, emptyMap()).contains("without the interception API"))
        assertEquals("redirect modes not used on this phone: call_redirect breaks-downlink, call_screening breaks-downlink",
            MicPath.noRedirectWhy(34, "intercept", Mode.IN_CALL, mapOf(Redirect.CALL_REDIRECT to Verdict.BREAKS_DOWNLINK, Redirect.CALL_SCREENING to Verdict.BREAKS_DOWNLINK)))
    }

    @Test fun callModes() {
        for (m in listOf(Mode.IN_CALL, Mode.IN_COMMUNICATION, Mode.CALL_SCREENING, Mode.CALL_REDIRECT, Mode.COMMUNICATION_REDIRECT)) assertTrue(Mode.isCall(m))
        for (m in listOf(Mode.NORMAL, Mode.RINGTONE, -1)) assertFalse(Mode.isCall(m))
    }

    @Test fun memoryRoundTripSkipsWhatItDoesNotKnow() {
        val m = mapOf(Redirect.CALL_REDIRECT to Verdict.BREAKS_UPLINK, Redirect.CALL_SCREENING to Verdict.OK)
        assertEquals(m, MicPath.memoryOf(MicPath.memoryJson(m)))
        assertEquals(mapOf(Redirect.CALL_REDIRECT to Verdict.OK),
            MicPath.memoryOf(JSONObject("""{"call_redirect":"ok","future_mode":"ok","call_screening":"maybe"}""")))
        assertEquals(emptyMap<Redirect, Verdict>(), MicPath.memoryOf(null))
    }

    @Test fun modeNumbersAreAndroidsOwn() {
        // AudioManager.MODE_*: CALL_SCREENING 4 (API 30), CALL_REDIRECT 5 and COMMUNICATION_REDIRECT 6 (API 33).
        assertEquals(listOf(4, 5, 6), listOf(Redirect.CALL_SCREENING.audioMode, Redirect.CALL_REDIRECT.audioMode, Redirect.COMMUNICATION_REDIRECT.audioMode))
        assertEquals(Mode.IN_CALL, Redirect.CALL_SCREENING.callMode)
        assertEquals("call_redirect", Mode.name(5))
    }

    // ------------------------------------------------------------------ the self-test's verdict

    private fun verdict(accepted: Boolean = true, down0: Boolean = true, down1: Boolean = true, tone0: Boolean = true, tone1: Boolean = true,
                        floor0: Double = 120.0, floor1: Double = 4.0) = MicPath.selftestVerdict(accepted, down0, down1, tone0, tone1, floor0, floor1).first

    @Test fun selftestIsolatingMode() {
        assertEquals(Verdict.OK, verdict())
        assertEquals("exactly 10 dB lower", Verdict.OK, verdict(floor0 = 100.0, floor1 = 100.0 / MicPath.ISOLATION))
    }

    @Test fun selftestFailures() {
        assertEquals(Verdict.REFUSED, verdict(accepted = false))
        assertEquals(Verdict.BREAKS_DOWNLINK, verdict(down1 = false))
        assertEquals("a silent downlink before says nothing", Verdict.OK, verdict(down0 = false, down1 = false))
        assertEquals(Verdict.BREAKS_UPLINK, verdict(tone1 = false))
        assertEquals("the mic still heard in the mode", Verdict.NO_EFFECT, verdict(floor1 = 60.0))
    }

    @Test fun selftestQuietRoomOrBlindUplink() {
        assertEquals("room too quiet, but the tone gets through", Verdict.OK, verdict(floor0 = 5.0, floor1 = 4.0))
        assertEquals(Verdict.UNVERIFIED, verdict(tone0 = false, tone1 = false, floor0 = 3.0, floor1 = 2.0))
        assertEquals("a blind VOICE_UPLINK still shows the mic leaving", Verdict.OK, verdict(tone0 = false, tone1 = false, floor0 = 90.0, floor1 = 1.0))
    }

    @Test fun silentStatusWithARedirectMode() {
        assertEquals("full", LocalMutes.silent(LocalMutes.Plan.NONE, ok = true, redirected = true))
        assertEquals("full", LocalMutes.silent(LocalMutes.Plan.NONE, ok = false, redirected = true))
        assertEquals("earpiece", LocalMutes.silent(LocalMutes.Plan.NONE, ok = true))
    }
}
