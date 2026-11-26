import COpus
import Foundation

/// Mono Opus decoder on libopus (vendored in the COpus target). Not thread safe: use from one queue.
public final class LibOpusDecoder {
    public let rate: Int
    private var state: OpaquePointer?
    /// Samples of the last frame, used to size concealment and as the longest frame accepted (120 ms).
    public private(set) var lastFrameSamples: Int

    public init?(rate: Int = 16000) {
        guard [8000, 12000, 16000, 24000, 48000].contains(rate) else { return nil }
        var err: Int32 = 0
        guard let st = opus_decoder_create(Int32(rate), 1, &err), err == OPUS_OK else { return nil }
        state = st
        self.rate = rate
        lastFrameSamples = rate / 50
    }

    deinit { if let state { opus_decoder_destroy(state) } }

    /// Decodes one packet to 16-bit mono PCM; empty on a corrupt packet.
    public func decode(_ packet: Data) -> [Int16] {
        guard let state, !packet.isEmpty else { return [] }
        let maxSamples = rate * 120 / 1000
        var out = [Int16](repeating: 0, count: maxSamples)
        let n = packet.withUnsafeBytes { raw -> Int32 in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return -1 }
            return opus_decode(state, base, Int32(packet.count), &out, Int32(maxSamples), 0)
        }
        guard n > 0 else { return [] }
        lastFrameSamples = Int(n)
        return Array(out.prefix(Int(n)))
    }

    /// Packet-loss concealment: `samples` of extrapolated audio (a multiple of 2.5 ms; opus_decode with no data).
    public func conceal(samples: Int) -> [Int16] {
        guard let state, samples > 0 else { return [] }
        var out = [Int16](repeating: 0, count: samples)
        let n = opus_decode(state, nil, 0, &out, Int32(samples), 0)
        guard n > 0 else { return [] }
        return Array(out.prefix(Int(n)))
    }
}

/// Mono Opus encoder (VOIP application) on libopus. Not thread safe.
public final class LibOpusEncoder {
    public let rate: Int
    private var state: OpaquePointer?

    public init?(rate: Int = 16000, bitrate: Int = 32000, complexity: Int = 3) {
        guard [8000, 12000, 16000, 24000, 48000].contains(rate) else { return nil }
        var err: Int32 = 0
        guard let st = opus_encoder_create(Int32(rate), 1, OPUS_APPLICATION_VOIP, &err), err == OPUS_OK else { return nil }
        state = st
        self.rate = rate
        _ = copus_encoder_set_bitrate(st, Int32(bitrate))
        _ = copus_encoder_set_complexity(st, Int32(min(max(complexity, 0), 10)))
        _ = copus_encoder_set_signal_voice(st)
    }

    deinit { if let state { opus_encoder_destroy(state) } }

    /// Encodes one frame (2.5, 5, 10, 20, 40, 60 ms of samples); empty on an invalid frame size.
    public func encode(_ pcm: [Int16]) -> Data {
        guard let state, !pcm.isEmpty else { return Data() }
        var out = [UInt8](repeating: 0, count: 1500)
        let n = opus_encode(state, pcm, Int32(pcm.count), &out, Int32(out.count))
        guard n > 0 else { return Data() }
        return Data(out.prefix(Int(n)))
    }
}
