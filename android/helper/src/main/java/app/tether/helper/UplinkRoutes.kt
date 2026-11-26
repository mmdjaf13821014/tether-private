package app.tether.helper

/**
 * Which routes into the call's uplink this phone's audio configuration actually wires up.
 *
 * On a Qualcomm HAL the interception API's uplink track (`getCallUplinkInjectionAudioTrack`) plays on the AFE proxy
 * (usecase `afe-proxy-playback`, device `voice-tx`). A vendor mixer configuration without an `afe-proxy-playback` path
 * leaves that track connected to nothing: the HAL logs "unable to find path 'afe-proxy-playback afe-proxy'" and the far
 * end hears none of the iPhone, while VOICE_UPLINK, which reads the same proxy, still "hears" it, so neither the
 * self-test nor the in-call check notices. Seen on a Redmi Note 8 (trinket, custom Android 16, 2026-11-25), where the
 * in-call music route (`incall_music_uplink`, used by the `telephony_tx` method) does reach the far end.
 */
object UplinkRoutes {
    private val afeProxyPlayback = Regex("""<path\s+name\s*=\s*"afe-proxy-playback[ "]""")
    private val incallMusicUplink = Regex("""<path\s+name\s*=\s*"incall[_-]music[_-]uplink[2 "]""")

    /**
     * True only when the mixer configurations define the legacy Qualcomm in-call music usecase (`incall_music_uplink`,
     * used by `telephony_tx`) and none of them defines an `afe-proxy-playback` path. Requiring `incall_music_uplink`
     * avoids false positives on newer Qualcomm PAL / AudioReach phones whose `mixer_paths*.xml` defines only codec
     * device paths and no usecase paths at all (nothing read, or any file with `afe-proxy-playback`, also counts as
     * wired: the interception API is then used as before).
     */
    fun afeProxyMissing(mixerPaths: List<String>): Boolean =
        mixerPaths.any { incallMusicUplink.containsMatchIn(it) } && mixerPaths.none { afeProxyPlayback.containsMatchIn(it) }

    /** The inject methods to try, in order: the interception API is left out where its route is not wired. */
    fun injectOrder(methods: List<String>, interceptDead: Boolean): List<String> =
        if (interceptDead) methods - "intercept" else methods

    /** A fixed method from the app (the self-test's earlier choice): ignored when it is not among [methods]. */
    fun fixedOrAuto(fixed: String, methods: List<String>): List<String> =
        if (fixed == "auto" || fixed !in methods) methods else listOf(fixed)
}
