package app.tether.audio

import app.tether.helper.CallAudio
import app.tether.helper.CallAudio.Silence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The helper's choice of how a bridged call silences the Android phone itself (CallAudio.Silence). */
class SilenceTest {
    @Test fun samsungAlwaysUsesItsOwnKeys() {
        assertEquals(Silence.SAMSUNG, CallAudio.pickSilence("samsung", "Samsung", "s5e8835", "s5e8835"))
        assertEquals(Silence.SAMSUNG_QUALCOMM, CallAudio.pickSilence("samsung", "QTI", "kalama", "qcom"))
        assertEquals("Galaxy A71 / A73 (Snapdragon 730 / 778G)", Silence.SAMSUNG_QUALCOMM, CallAudio.pickSilence("samsung", null, "sm7150", "qcom"))
        assertTrue(Silence.SAMSUNG.vendorMicKeys)
        // Issue #13 (Galaxy A73, Android 16): the Text Call keys left the voice PCM held and the phone's own calls silent
        // until a reboot; on Samsung's Qualcomm models they are never written.
        assertFalse(Silence.SAMSUNG_QUALCOMM.vendorMicKeys)
    }

    @Test fun samsungOnQualcommAlsoMutesTheVoiceStream() {
        // Issue #15: where Samsung's keys do nothing on a Qualcomm HAL, the earpiece must still go quiet.
        assertTrue(Silence.SAMSUNG_QUALCOMM.voiceStream)
        assertFalse(Silence.SAMSUNG.voiceStream)
        assertFalse(Silence.MEDIATEK.voiceStream)
        assertTrue(Silence.QUALCOMM.voiceStream && Silence.OTHER.voiceStream)
    }

    @Test fun onlyTheVerifiedKeyIsTrustedWithABlindUplink() {
        // Samsung's tx key on the reference phone (Exynos): real calls heard both ways with it. Not on Samsung's
        // Qualcomm models (issue #2's Fold 6) nor MediaTek's UL key: never seen on a real call.
        assertEquals(listOf(Silence.SAMSUNG), Silence.entries.filter { it.blindSafe })
    }

    @Test fun qualcommFromSocOrPlatform() {
        assertEquals(Silence.QUALCOMM, CallAudio.pickSilence("Xiaomi", "QTI", "taro", "qcom"))
        assertEquals(Silence.QUALCOMM, CallAudio.pickSilence("OnePlus", null, "lahaina", ""))
        assertEquals(Silence.QUALCOMM, CallAudio.pickSilence("motorola", null, "sm6150", null))
    }

    @Test fun mediatekNeverUsesTheAndroidMicMute() {
        val s = CallAudio.pickSilence("Xiaomi", "Mediatek", "mt6893", "mt6893")
        assertEquals(Silence.MEDIATEK, s)
        assertTrue("its own UL key instead", s.vendorMicKeys)
        assertEquals(Silence.MEDIATEK, CallAudio.pickSilence("TECNO", null, "mt6789", null))
    }

    @Test fun onlySamsungAndMediatekHaveTheirOwnMicKeys() {
        assertTrue(Silence.SAMSUNG.vendorMicKeys)
        assertFalse(Silence.QUALCOMM.vendorMicKeys)
        assertFalse(Silence.OTHER.vendorMicKeys)
    }

    @Test fun theEarpieceNoteFollowsTheMicKey() {
        assertEquals("samsung rx=1 tx=1", CallAudio.mergeLocal("samsung rx=1 tx=0", "samsung tx=1"))
        assertEquals("samsung rx=1 tx=0, voice stream muted", CallAudio.mergeLocal("samsung rx=1 tx=1, voice stream muted", "samsung tx=0"))
        assertEquals("mediatek dl=1 ul=1", CallAudio.mergeLocal("mediatek dl=1 ul=0", "mediatek ul=1"))
        assertEquals("samsung tx=1", CallAudio.mergeLocal("failed", "samsung tx=1"))
    }

    @Test fun othersFallBack() {
        assertEquals(Silence.OTHER, CallAudio.pickSilence("Google", "Google", "zuma", "zuma"))
        assertEquals(Silence.OTHER, CallAudio.pickSilence(null, null, null, null))
    }
}
