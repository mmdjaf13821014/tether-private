import XCTest
#if canImport(AVFoundation)
import AVFoundation
import AudioToolbox
#endif
@testable import TetherCore

final class LibOpusTests: XCTestCase {
    private let rate = 16000
    private let frame = 320

    private func tone(frames: Int, amplitude: Double = 12000) -> [Int16] {
        (0..<frames * frame).map { Int16(amplitude * sin(2 * .pi * 1000 * Double($0) / Double(rate))) }
    }

    /// Best SNR in dB of `got` against `ref` over small alignments (codec delay), skipping the start-up.
    private func snr(ref: [Int16], got: [Int16], skip: Int = 1600) -> Double {
        var best = -Double.infinity
        for lag in 0...600 {
            var sig = 0.0, noise = 0.0
            var i = skip
            while i + lag < got.count && i < ref.count {
                let r = Double(ref[i]), g = Double(got[i + lag])
                sig += r * r; noise += (r - g) * (r - g)
                i += 1
            }
            guard noise > 0, sig > 0 else { continue }
            best = max(best, 10 * log10(sig / noise))
        }
        return best
    }

    func testToneRoundTrip() throws {
        let enc = try XCTUnwrap(LibOpusEncoder(rate: rate, bitrate: 32000, complexity: 3))
        let dec = try XCTUnwrap(LibOpusDecoder(rate: rate))
        let pcm = tone(frames: 50)
        var out: [Int16] = []
        for f in 0..<50 {
            let packet = enc.encode(Array(pcm[f * frame..<(f + 1) * frame]))
            XCTAssertFalse(packet.isEmpty)
            let d = dec.decode(packet)
            XCTAssertEqual(d.count, frame)
            out += d
        }
        XCTAssertGreaterThan(snr(ref: pcm, got: out), 20)
    }

    func testConcealReturnsFrameAndDecays() throws {
        let enc = try XCTUnwrap(LibOpusEncoder(rate: rate))
        let dec = try XCTUnwrap(LibOpusDecoder(rate: rate))
        let pcm = tone(frames: 10)
        for f in 0..<10 { _ = dec.decode(enc.encode(Array(pcm[f * frame..<(f + 1) * frame]))) }
        func rms(_ a: [Int16]) -> Double { sqrt(a.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(max(a.count, 1))) }
        var levels: [Double] = []
        for _ in 0..<12 {
            let c = dec.conceal(samples: frame)
            XCTAssertEqual(c.count, 320)
            levels.append(rms(c))
        }
        XCTAssertGreaterThan(levels[0], 100)
        XCTAssertLessThan(levels.last!, levels[0] * 0.2)
        XCTAssertTrue(dec.decode(Data()).isEmpty)
    }

    #if canImport(AVFoundation)
    private func avFormats() throws -> (pcm: AVAudioFormat, opus: AVAudioFormat) {
        let pcm = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(rate), channels: 1, interleaved: true))
        var asbd = AudioStreamBasicDescription(mSampleRate: Double(rate), mFormatID: kAudioFormatOpus, mFormatFlags: 0, mBytesPerPacket: 0,
                                               mFramesPerPacket: UInt32(frame), mBytesPerFrame: 0, mChannelsPerFrame: 1,
                                               mBitsPerChannel: 0, mReserved: 0)
        guard let opus = AVAudioFormat(streamDescription: &asbd) else { throw XCTSkip("kAudioFormatOpus unavailable") }
        return (pcm, opus)
    }

    private func audioToolboxDecode(_ packet: Data, _ conv: AVAudioConverter, pcm: AVAudioFormat, opus: AVAudioFormat) -> [Int16] {
        let cb = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: packet.count)
        packet.withUnsafeBytes { cb.data.copyMemory(from: $0.baseAddress!, byteCount: packet.count) }
        cb.packetDescriptions?[0] = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count))
        cb.packetCount = 1
        cb.byteLength = UInt32(packet.count)
        guard let out = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(frame * 3)) else { return [] }
        var fed = false
        var err: NSError?
        _ = conv.convert(to: out, error: &err) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true; status.pointee = .haveData; return cb
        }
        return Array(UnsafeBufferPointer(start: out.int16ChannelData![0], count: Int(out.frameLength)))
    }

    private func audioToolboxEncode(_ pcmFrame: [Int16], _ conv: AVAudioConverter, pcm: AVAudioFormat, opus: AVAudioFormat) -> Data? {
        guard let buf = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(pcmFrame.count)) else { return nil }
        buf.frameLength = AVAudioFrameCount(pcmFrame.count)
        pcmFrame.withUnsafeBufferPointer { buf.int16ChannelData![0].update(from: $0.baseAddress!, count: pcmFrame.count) }
        let out = AVAudioCompressedBuffer(format: opus, packetCapacity: 2, maximumPacketSize: 1500)
        var fed = false
        var err: NSError?
        _ = conv.convert(to: out, error: &err) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true; status.pointee = .haveData; return buf
        }
        guard out.packetCount > 0, let desc = out.packetDescriptions?[0] else { return nil }
        return Data(bytes: out.data.advanced(by: Int(desc.mStartOffset)), count: Int(desc.mDataByteSize))
    }

    func testInteropLibOpusToAudioToolbox() throws {
        let (pcm, opus) = try avFormats()
        let conv = try XCTUnwrap(AVAudioConverter(from: opus, to: pcm))
        let enc = try XCTUnwrap(LibOpusEncoder(rate: rate, bitrate: 32000, complexity: 3))
        let src = tone(frames: 50)
        var out: [Int16] = []
        for f in 0..<50 {
            out += audioToolboxDecode(enc.encode(Array(src[f * frame..<(f + 1) * frame])), conv, pcm: pcm, opus: opus)
        }
        XCTAssertGreaterThan(out.count, 40 * frame)
        XCTAssertGreaterThan(snr(ref: src, got: out), 20)
    }

    func testInteropAudioToolboxToLibOpus() throws {
        let (pcm, opus) = try avFormats()
        let conv = try XCTUnwrap(AVAudioConverter(from: pcm, to: opus))
        conv.bitRate = 32000
        let dec = try XCTUnwrap(LibOpusDecoder(rate: rate))
        let dconv = try XCTUnwrap(AVAudioConverter(from: opus, to: pcm))
        let src = tone(frames: 50)
        var out: [Int16] = []
        var base: [Int16] = []
        var packets = 0
        for f in 0..<50 {
            guard let packet = audioToolboxEncode(Array(src[f * frame..<(f + 1) * frame]), conv, pcm: pcm, opus: opus) else { continue }
            packets += 1
            base += audioToolboxDecode(packet, dconv, pcm: pcm, opus: opus)
            out += dec.decode(packet)
        }
        XCTAssertEqual(packets, 50)
        XCTAssertGreaterThan(out.count, 40 * frame)
        // AudioToolbox's own encoder is coarser than libopus at 32 kbps (about 16 dB on this tone):
        // what matters is that libopus decodes its packets like AudioToolbox does.
        XCTAssertGreaterThan(snr(ref: src, got: out), 12)
        XCTAssertGreaterThan(snr(ref: base, got: out), 40)
    }
    #endif
}
