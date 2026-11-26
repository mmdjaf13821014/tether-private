package app.tether.audio

import app.tether.helper.UplinkRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which uplink injection routes a Qualcomm phone's mixer configuration wires up (Redmi Note 8, custom Android 16). */
class UplinkRoutesTest {
    // The Redmi Note 8's mixer_paths_idp.xml: in-call music is wired, the AFE proxy playback is not.
    private val redmi = """
        <mixer>
          <path name="deep-buffer-playback afe-proxy"><ctl name="AFE_PCM_RX Audio Mixer MultiMedia1" value="1" /></path>
          <path name="incall_music_uplink"><ctl name="Incall_Music Audio Mixer MultiMedia9" value="1" /></path>
          <path name="incall_music_uplink afe-proxy"><path name="incall_music_uplink" /></path>
        </mixer>
    """.trimIndent()
    private val wired = """
        <mixer>
          <path name="afe-proxy-playback afe-proxy"><ctl name="AFE_PCM_RX Audio Mixer MultiMedia9" value="1" /></path>
          <path name="incall_music_uplink"><ctl name="Incall_Music Audio Mixer MultiMedia9" value="1" /></path>
        </mixer>
    """.trimIndent()
    // Newer Qualcomm PAL / AudioReach phones define only codec device paths in mixer_paths*.xml (no usecase paths).
    private val pal = """
        <mixer>
          <path name="speaker"><ctl name="RX_MACRO RX0 MUX" value="AIF1_PB" /></path>
          <path name="handset"><ctl name="EAR_RDAC Switch" value="1" /></path>
        </mixer>
    """.trimIndent()
    private val all = listOf("intercept", "telephony_tx", "policy_mix")

    @Test fun aMixerWithoutTheAfeProxyPathLeavesTheInterceptionTrackUnwired() {
        assertTrue(UplinkRoutes.afeProxyMissing(listOf(redmi)))
        assertEquals("in-call music first", listOf("telephony_tx", "policy_mix"), UplinkRoutes.injectOrder(all, interceptDead = true))
    }

    @Test fun anyFileWithThePathOrWithoutLegacyUsecasesKeepsTheInterceptionApi() {
        assertFalse(UplinkRoutes.afeProxyMissing(listOf(wired)))
        assertFalse("one of several configurations has it", UplinkRoutes.afeProxyMissing(listOf(redmi, wired)))
        assertFalse("nothing readable: no change", UplinkRoutes.afeProxyMissing(emptyList()))
        assertFalse("PAL mixer config has no legacy usecase paths: keep intercept", UplinkRoutes.afeProxyMissing(listOf(pal)))
        assertTrue("a lookalike name is not the path", UplinkRoutes.afeProxyMissing(listOf("""<path name="incall_music_uplink"><path name="afe-proxy-playbackx">""")))
        assertEquals(all, UplinkRoutes.injectOrder(all, interceptDead = false))
    }

    @Test fun aSavedInterceptChoiceIsIgnoredWhereItGoesNowhere() {
        // The self-test used to choose intercept here (its check reads the same proxy) and the app kept that choice.
        val live = UplinkRoutes.injectOrder(all, interceptDead = true)
        assertEquals(live, UplinkRoutes.fixedOrAuto("intercept", live))
        assertEquals(listOf("telephony_tx"), UplinkRoutes.fixedOrAuto("telephony_tx", live))
        assertEquals(live, UplinkRoutes.fixedOrAuto("auto", live))
        assertEquals(listOf("intercept"), UplinkRoutes.fixedOrAuto("intercept", all))
    }
}
