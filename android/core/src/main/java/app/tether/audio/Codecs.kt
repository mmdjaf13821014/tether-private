package app.tether.audio

import app.tether.core.AudioFrame
import app.tether.core.G711

/** Encodes / decodes 20 ms mono PCM16 frames. */
interface VoiceCodec {
    val id: Int
    val name: String
    val rate: Int
    fun encode(pcm: ShortArray): List<ByteArray>
    fun decode(frame: ByteArray): ShortArray?
    /** Packet-loss concealment: a synthetic frame for a missing one (null = play nothing). */
    fun conceal(): ShortArray? = null
    fun release() {}
}

class PcmuCodec : VoiceCodec {
    override val id = AudioFrame.PCMU
    override val name = "pcmu"
    override val rate = 8000
    override fun encode(pcm: ShortArray) = listOf(G711.encode(pcm))
    override fun decode(frame: ByteArray) = G711.decode(frame)
}

/**
 * Opus through Concentus (a pure-Java port of libopus): encode/decode are synchronous calls on the audio
 * thread, so a frame leaves the moment it is encoded. MediaCodec added 5–6 ms of IPC per frame and could
 * hand back a frame one call late (a full extra 20 ms), and it exposes none of the voice settings.
 *
 * Settings: VOIP application (speech-tuned, high-pass + noise shaping), voice signal hint, wideband
 * (the cellular codec is AMR-WB / EVS-WB: nothing above 8 kHz comes down the line), 32 kbps constrained
 * VBR (transparent for wideband speech), complexity 3: paced like a call (one frame per 20 ms, release build compiled ahead of time) it costs 3.7 ms per frame on the Android phone vs 5.2 ms at 5 (codec_bench_paced); speech quality at 32 kbps wideband barely changes. No DTX (a playout buffer reads DTX gaps as underruns)
 * and no in-band FEC: L2CAP is a reliable channel, frames only go missing when we drop them for lateness.
 */
class OpusCodec(override val rate: Int = 16000, val bitrate: Int = 32000, complexity: Int = 3) : VoiceCodec {
    override val id = AudioFrame.OPUS
    override val name = "opus"
    private val frame = rate / 50
    private val enc = io.github.jaredmdobson.concentus.OpusEncoder(rate, 1, io.github.jaredmdobson.concentus.OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(bitrate)
        setComplexity(complexity)
        setSignalType(io.github.jaredmdobson.concentus.OpusSignal.OPUS_SIGNAL_VOICE)
        setMaxBandwidth(io.github.jaredmdobson.concentus.OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND)
        setUseVBR(true); setUseConstrainedVBR(true)
        setUseDTX(false); setUseInbandFEC(false)
    }
    private val dec = io.github.jaredmdobson.concentus.OpusDecoder(rate, 1)
    private val out = ByteArray(1275)

    override fun encode(pcm: ShortArray): List<ByteArray> {
        val res = ArrayList<ByteArray>(1)
        var i = 0
        while (i + frame <= pcm.size) {
            val n = enc.encode(pcm, i, frame, out, 0, out.size)
            if (n > 0) res.add(out.copyOf(n))
            i += frame
        }
        return res
    }

    override fun decode(frame: ByteArray): ShortArray? = try {
        val pcm = ShortArray(this.frame * 3)
        val n = dec.decode(frame, 0, frame.size, pcm, 0, this.frame * 3, false)
        if (n > 0) pcm.copyOf(n) else null
    } catch (e: Exception) { null }

    override fun conceal(): ShortArray? = try {
        val pcm = ShortArray(frame)
        val n = dec.decode(null, 0, 0, pcm, 0, frame, false)
        if (n > 0) pcm.copyOf(n) else null
    } catch (e: Exception) { null }

    companion object {
        fun available() = true
    }
}

object Resample {
    fun linear(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || input.isEmpty()) return input
        val n = (input.size.toLong() * to / from).toInt()
        val out = ShortArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * step
            val j = p.toInt()
            val f = p - j
            val a = input[minOf(j, input.size - 1)]
            val b = input[minOf(j + 1, input.size - 1)]
            out[i] = (a + (b - a) * f).toInt().toShort()
        }
        return out
    }
}
