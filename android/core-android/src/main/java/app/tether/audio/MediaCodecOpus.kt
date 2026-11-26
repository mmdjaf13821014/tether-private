package app.tether.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import app.tether.core.AudioFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opus through MediaCodec (Android's Codec2 software Opus encoder/decoder). Kept for comparison tests. */
class MediaCodecOpus(override val rate: Int = 16000, bitrate: Int = 24000) : VoiceCodec {
    override val id = AudioFrame.OPUS
    override val name = "opus"
    private val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val encInfo = MediaCodec.BufferInfo()
    private val decInfo = MediaCodec.BufferInfo()
    private var decPts = 0L
    private var pts = 0L
    private var decRate = rate

    init {
        val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1)
        ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, rate / 50 * 2 * 4)
        enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val df = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1)
        df.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(rate)))
        df.setByteBuffer("csd-1", le64(0))
        df.setByteBuffer("csd-2", le64(80_000_000))
        dec.configure(df, null, null, 0)
        dec.start()
    }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        val inIdx = enc.dequeueInputBuffer(20_000)
        if (inIdx >= 0) {
            val b = enc.getInputBuffer(inIdx)!!
            b.clear()
            b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
            enc.queueInputBuffer(inIdx, 0, pcm.size * 2, pts, 0)
            pts += pcm.size * 1_000_000L / rate
        }
        val out = ArrayList<ByteArray>()
        while (true) {
            val o = enc.dequeueOutputBuffer(encInfo, 0)
            if (o < 0) break
            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encInfo.size > 0) {
                val b = enc.getOutputBuffer(o)!!
                val bytes = ByteArray(encInfo.size)
                b.position(encInfo.offset); b.get(bytes)
                out.add(bytes)
            }
            enc.releaseOutputBuffer(o, false)
        }
        return out
    }

    override fun decode(frame: ByteArray): ShortArray? {
        val inIdx = dec.dequeueInputBuffer(20_000)
        if (inIdx >= 0) {
            dec.getInputBuffer(inIdx)!!.apply { clear(); put(frame) }
            dec.queueInputBuffer(inIdx, 0, frame.size, decPts, 0)
            decPts += 20_000
        }
        var result: ShortArray? = null
        while (true) {
            val o = dec.dequeueOutputBuffer(decInfo, 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                decRate = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                continue
            }
            if (o < 0) break
            if (decInfo.size > 0) {
                val sb = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                sb.position(decInfo.offset)
                val s = ShortArray(decInfo.size / 2)
                sb.asShortBuffer().get(s)
                result = if (decRate == rate) s else Resample.linear(s, decRate, rate)
            }
            dec.releaseOutputBuffer(o, false)
        }
        return result
    }

    override fun release() {
        runCatching { enc.stop(); enc.release() }
        runCatching { dec.stop(); dec.release() }
    }

    companion object MediaCodecSupport {
        fun available(): Boolean = try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 1)
            list.findEncoderForFormat(f) != null && list.findDecoderForFormat(f.apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(16000)))
            }) != null
        } catch (t: Throwable) {
            Log.w("Tether", "opus probe failed", t); false
        }

        fun opusHead(rate: Int): ByteArray {
            val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            b.put("OpusHead".toByteArray()); b.put(1); b.put(1); b.putShort(0); b.putInt(rate); b.putShort(0); b.put(0)
            return b.array()
        }

        private fun le64(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).also { it.flip() }
    }
}
