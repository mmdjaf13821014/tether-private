package app.tether.client.calls

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import app.tether.audio.OpusCodec
import app.tether.audio.PcmuCodec
import app.tether.audio.VoiceCodec
import app.tether.client.model.AudioPort
import app.tether.core.AudioFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Two-way call audio, the Android counterpart of the iPhone's CallAudio: the microphone (VOICE_COMMUNICATION, with
 * the platform's echo canceller and noise suppressor) → codec → link, and link → codec → [PlayoutBuffer] → AudioTrack.
 * It runs while a local call is up ([engine]); until the far end is heard on an outgoing call, a local ringback plays
 * (on VoLTE the downlink is silent while dialing). The codec comes from the Android phone's audio.start.
 */
class CallAudio(private val send: (AudioFrame) -> Boolean, private val log: (String) -> Unit,
                private val hasMicPermission: () -> Boolean = { true }, private val onMic: () -> Unit = {}) : AudioPort {
    private val gate = UplinkGate { log(it) }
    /** The recorder could not be created or did not initialise (cleared when the call audio stops). */
    @Volatile var recorderFailed = false; private set
    /** The recorder delivered only digital silence in the connected call (cleared when sound comes or the audio stops). */
    @Volatile var captureSilent = false; private set
    @Volatile private var codec: VoiceCodec? = null
    @Volatile private var playRate = 16000
    @Volatile private var playout = PlayoutBuffer(16000)
    @Volatile private var running = false
    @Volatile private var wanted = false
    @Volatile var muted = false
    @Volatile private var ringbackOn = false
    @Volatile private var heardRemote = false
    private var seq = 0
    private var lastSeq = -1
    private var playThread: Thread? = null
    private var recThread: Thread? = null
    private val lock = Any()
    /** Decodes downlink frames off the link thread (the iPhone's audio queue); frames are stamped before they get here. */
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "call-decode").apply { isDaemon = true } }

    // Statistics (logged at stop, in the Link log).
    @Volatile private var frames = 0
    @Volatile private var lost = 0
    private var lastArrival = 0L
    private var jitterSum = 0.0
    private var worstGap = 0.0

    /** While dialing: our own ringback until the far end is audible. */
    var ringback: Boolean
        get() = ringbackOn && !heardRemote
        set(v) { ringbackOn = v; if (v) heardRemote = false }

    /** From the call tracker after every call update: the microphone goes out only while a call here is connected. */
    fun setUplink(open: Boolean) = gate.set(open)

    /** For a problem report: the uplink gate (wanted, open) and the last call's playout figures. */
    val uplinkWanted get() = gate.isWanted
    val uplinkOpen get() = gate.isOpen
    @Volatile var lastStats: String? = null; private set

    /** The microphone permission was just granted (call screen): a recorder that was not started comes up now. */
    fun micPermissionChanged() = synchronized(lock) {
        if (running && recThread?.isAlive != true && hasMicPermission()) { recorderFailed = false; startRecorderLocked(playRate, playout) }
    }

    /** A local call is up (dialing, ringing answered, active, held): the engine runs. */
    fun engine(on: Boolean) = synchronized(lock) {
        wanted = on
        if (on && !running) startLocked() else if (!on && running) stopLocked()
    }

    override fun configure(codec: String, rate: Int, bitrate: Int) = synchronized(lock) {
        val c: VoiceCodec = try { if (codec == "opus") OpusCodec(rate, bitrate) else PcmuCodec() } catch (t: Throwable) { log("opus unavailable: $t"); PcmuCodec() }
        this.codec?.release()
        this.codec = c
        lastSeq = -1
        log("audio start: ${c.name} @ ${c.rate} Hz")
        // A different rate needs a new player and recorder.
        if (running && c.rate != playRate) { stopLocked(); startLocked() }
    }

    override fun remoteStopped() = synchronized(lock) { codec?.release(); codec = null }

    /** Call over: everything back to the start (the sequence number counts for a whole call only). */
    fun stop() = synchronized(lock) {
        wanted = false
        if (running) stopLocked()
        codec?.release(); codec = null
        ringbackOn = false; muted = false; seq = 0
        gate.reset(); captureSilent = false; recorderFailed = false
    }

    /** Link thread: the arrival is stamped as the frame comes off the link, not when the decode thread gets to it. */
    override fun frame(f: AudioFrame) {
        if (!running) return
        val at = System.nanoTime()
        decoder.execute { synchronized(lock) { receive(f, at) } }
    }

    private fun receive(f: AudioFrame, now: Long) {
        val k = codec ?: return
        if (!running || f.codec != k.id) return
        var missing = 0
        if (lastSeq >= 0) {
            val gap = ((f.seq - lastSeq) and 0xffff).let { if (it > 0x8000) it - 0x10000 else it }
            if (gap <= 0) return                  // late / duplicate: drop
            if (gap > 1) { lost += gap - 1; missing = minOf(gap - 1, 50) }
        }
        lastSeq = f.seq
        if (lastArrival != 0L && now > lastArrival) { val g = (now - lastArrival) / 1e6; jitterSum += abs(g - 20); worstGap = maxOf(worstGap, g) }
        lastArrival = now
        frames++
        val buffer = playout
        val sameRate = k.rate == playRate
        // Frames the Android phone dropped for lateness: the decoder's concealment takes their place (up to two, as the
        // Android phone does for the uplink). It runs before this frame is decoded: concealment continues from the last
        // decoded frame, and the decoder then moves smoothly into this one.
        var filled = 0
        if (missing in 1..2) repeat(missing) { k.conceal()?.let { plc -> if (sameRate) buffer.push(plc, at = now, concealed = true); filled++ } }
        val pcm = k.decode(f.payload) ?: return
        if (ringback) {
            // Network ringback or the answer is audible: stop the local tone.
            var e = 0.0
            for (s in pcm) e += s.toDouble() * s
            if (sqrt(e / maxOf(1, pcm.size)) > 300) { heardRemote = true; ringbackOn = false; log("far end audible: local ringback off") }
        }
        // Arrival time and lost frames feed the playout's jitter statistics (its target follows the arrival spread).
        if (sameRate) buffer.push(pcm, at = now, missing = missing - filled)
    }

    @SuppressLint("MissingPermission")
    private fun startLocked() {
        val rate = codec?.rate ?: 16000
        playRate = rate
        val buffer = PlayoutBuffer(rate)
        playout = buffer
        frames = 0; lost = 0; lastArrival = 0; jitterSum = 0.0; worstGap = 0.0
        running = true
        val tenMs = rate / 100
        playThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(min, tenMs * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } catch (t: Throwable) { log("player not created: $t"); return@Thread }
            track.play()
            val f = FloatArray(tenMs); val out = ShortArray(tenMs)
            var phase = 0.0; var clock = 0
            try {
                while (running && playout === buffer) {
                    buffer.pull(f, tenMs)
                    if (ringback) {
                        // 425 Hz, 1 s on / 4 s off, 5 ms ramps (the ITU ringback most networks use).
                        for (i in 0 until tenMs) {
                            val pos = clock % (rate * 5); val edge = rate / 200
                            val env = if (pos >= rate) 0.0 else minOf(pos, rate - pos, edge).toDouble() / edge
                            f[i] = (0.12 * env * sin(phase)).toFloat()
                            phase += 2 * PI * 425 / rate; if (phase > 2 * PI) phase -= 2 * PI
                            clock++
                        }
                    } else clock = 0
                    for (i in 0 until tenMs) out[i] = (f[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                    track.write(out, 0, tenMs)   // blocking: paces this thread
                }
            } finally { runCatching { track.stop() }; track.release() }
        }, "call-play").apply { start() }
        startRecorderLocked(rate, buffer)
        log("call audio started @ $rate Hz")
    }

    private fun startRecorderLocked(rate: Int, buffer: PlayoutBuffer) {
        if (!hasMicPermission()) {
            log("microphone: permission not granted at call start: the other side will hear nothing")
            onMic(); return
        }
        recorderFailed = false
        recThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frame = rate / 50
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 2 * 4))
            } catch (t: Throwable) { log("microphone not opened: $t"); recorderFailed = true; onMic(); return@Thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { log("microphone not available"); recorderFailed = true; onMic(); rec.release(); return@Thread }
            val fx = listOfNotNull(
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null,
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null,
                if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId) else null,
            ).onEach { runCatching { it.enabled = true } }
            log("microphone open @ $rate Hz; effects: ${fx.joinToString { it.javaClass.simpleName }.ifEmpty { "none" }}")
            val pcm = ShortArray(frame)
            val detector = SilenceDetector(rate)
            try {
                rec.startRecording()
                while (running && playout === buffer) {
                    var got = 0
                    while (got < frame && running) { val n = rec.read(pcm, got, frame - got); if (n <= 0) break; got += n }
                    if (got < frame) continue
                    val k = codec ?: continue
                    if (k.rate != rate) continue
                    // Not connected yet (dialing, ringing, held): the microphone is read and dropped, nothing is sent.
                    when (gate.admit()) {
                        UplinkGate.Admit.HOLD -> continue
                        UplinkGate.Admit.OPENED -> detector.reset()
                        UplinkGate.Admit.SEND -> Unit
                    }
                    when (detector.feed(pcm, frame)) {
                        SilenceDetector.Event.SILENT -> {
                            captureSilent = true
                            log("microphone: only silence for ${MicPolicy.SILENT_AFTER_SECONDS.toInt()} s of the connected call (permission granted): the other side hears nothing")
                            onMic()
                        }
                        SilenceDetector.Event.SOUND -> { captureSilent = false; log("microphone: sound again"); onMic() }
                        null -> Unit
                    }
                    val input = if (muted) ShortArray(frame) else pcm
                    // `seq` keeps counting across restarts (rate changes): the Android phone drops frames whose number goes back.
                    for (payload in k.encode(input)) send(AudioFrame(k.id, seq++ and 0xffff, payload))
                }
            } finally { runCatching { rec.stop() }; fx.forEach { runCatching { it.release() } }; rec.release() }
        }, "call-rec").apply { start() }
    }

    private fun stopLocked() {
        running = false
        playThread?.join(500); recThread?.join(500)
        playThread = null; recThread = null
        val b = playout
        val ms = 1000.0 / playRate
        lastStats = String.format(Locale.ROOT, "playout delay %.0f ms (target %.0f, arrival spread asks %.0f), underruns %d, " +
            "concealed %.0f ms (%d frames by the decoder), trimmed %.0f ms (speech %.0f ms), added %.0f ms, lost %d of %d frames, " +
            "arrival jitter %.1f ms, worst gap %.0f ms, drift %.0f ppm",
            b.meanBuffered * ms, b.target * ms, b.jitterFloor * ms, b.underruns, b.concealedSamples * ms, b.concealedFrames,
            b.trimmed * ms, b.speechTrimmed * ms, b.inserted * ms, lost, frames, if (frames > 1) jitterSum / (frames - 1) else 0.0, worstGap, b.driftPpm)
        log("call audio: $lastStats")
    }
}
