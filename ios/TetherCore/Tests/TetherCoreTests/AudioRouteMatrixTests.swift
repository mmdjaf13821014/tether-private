import Foundation
import XCTest
@_spi(TetherApp) @testable import TetherCore

/// The call screen's audio menu for every combination of accessories, and the engine rules that once looped.
final class AudioRouteMatrixTests: XCTestCase {
    private let mic = RoutePort(type: "MicrophoneBuiltIn", uid: "Built-In Microphone", name: "iPhone Microphone")
    private let accessories = [
        RoutePort(type: "BluetoothHFP", uid: "11:22:33:44:55:66-tsco", name: "Headset A"),
        RoutePort(type: "CarAudio", uid: "CarAudioPort-0000000001", name: "Car"),
        RoutePort(type: "HeadsetMicrophone", uid: "Wired Headset Microphone", name: "Wired"),
        RoutePort(type: "USBAudio", uid: "AppleUSBAudioEngine:0001", name: "USB"),
        RoutePort(type: "BluetoothLE", uid: "AA:BB:CC:DD:EE:01-le", name: "Earbuds"),
    ]

    /// Every subset of accessories: iPhone and Speaker first, then each accessory once in the session's order;
    /// picking any option and reading it back selects that option.
    func testEveryPortCombination() {
        for mask in 0..<(1 << accessories.count) {
            let present = accessories.indices.filter { mask & (1 << $0) != 0 }.map { accessories[$0] }
            for hasReceiver in [true, false] {
                let opts = AudioRoutes.options(inputs: [mic] + present + present, hasReceiver: hasReceiver)
                let builtIns = hasReceiver ? ["iphone", "speaker"] : ["speaker"]
                XCTAssertEqual(opts.map(\.id), builtIns + present.map(\.uid), "mask \(mask)")
                XCTAssertEqual(Set(opts.map(\.id)).count, opts.count)
                for o in opts {
                    let plan = AudioRoutes.plan(for: o.id, builtInMicUID: mic.uid)
                    XCTAssertEqual(plan.speakerOverride, o.id == "speaker")
                    // What the session reports after the plan is applied.
                    let (outs, ins): ([RoutePort], [RoutePort])
                    switch o.id {
                    case "speaker": (outs, ins) = ([RoutePort(type: "Speaker", uid: "Speaker", name: "")], [mic])
                    case "iphone": (outs, ins) = ([RoutePort(type: "Receiver", uid: "Built-In Receiver", name: "")], [mic])
                    default:
                        XCTAssertEqual(plan.preferredInputUID, o.id)
                        let p = present.first { $0.uid == o.id }!
                        (outs, ins) = ([RoutePort(type: p.type == "HeadsetMicrophone" ? "Headphones" : p.type, uid: p.uid, name: "")], [p])
                    }
                    XCTAssertEqual(AudioRoutes.selected(outputs: outs, inputs: ins, options: opts), o.id, "mask \(mask) pick \(o.id)")
                    XCTAssertEqual(AudioRoutes.speakerOn(outputs: outs.map(\.type)), o.id == "speaker")
                }
            }
        }
    }

    /// A hands-free device's output UID differs from its input's after the address: still the same option.
    func testSameDeviceByAddressPrefix() {
        let opts = AudioRoutes.options(inputs: [mic, accessories[0]])
        let out = RoutePort(type: "BluetoothHFP", uid: "11:22:33:44:55:66-output", name: "")
        XCTAssertEqual(AudioRoutes.selected(outputs: [out], inputs: [mic], options: opts), accessories[0].uid)
        XCTAssertFalse(AudioRoutes.sameDevice("short-a", "short-b"), "short UIDs never match by prefix")
    }

    /// AirPlay (output only, not in the menu) and an iPad without a receiver.
    func testOutputOnlyAccessoryAndNoReceiver() {
        let opts = AudioRoutes.options(inputs: [mic])
        let air = RoutePort(type: "AirPlay", uid: "AirPlay-1", name: "")
        XCTAssertEqual(AudioRoutes.selected(outputs: [air], inputs: [mic], options: opts), "airPlay")
        let ipad = AudioRoutes.options(inputs: [mic], hasReceiver: false)
        XCTAssertEqual(AudioRoutes.selected(outputs: [], inputs: [mic], options: ipad), "speaker")
        XCTAssertEqual(AudioRoutes.current(outputs: ["Speaker", "BluetoothA2DPOutput"]), .bluetooth, "an accessory wins")
    }

    func testEveryPortTypeClassifies() {
        let table: [String: RouteKind] = ["Receiver": .receiver, "MicrophoneBuiltIn": .receiver, "Speaker": .speaker,
            "BluetoothHFP": .bluetooth, "BluetoothA2DPOutput": .bluetooth, "BluetoothLE": .bluetooth, "CarAudio": .car,
            "Headphones": .wired, "HeadsetMicrophone": .wired, "LineIn": .wired, "LineOut": .wired, "USBAudio": .usb,
            "AirPlay": .airPlay, "HDMIOutput": .other, "": .other]
        for (port, kind) in table { XCTAssertEqual(RouteKind(port: port), kind, port) }
        XCTAssertEqual(RouteKind.allCases.filter(\.isBuiltIn), [.receiver, .speaker])
    }

    // MARK: engine (2026-11-23 regression)

    private let f48 = EngineFormat(inputRate: 48000, inputChannels: 1, outputRate: 48000, outputChannels: 1)
    private let f16 = EngineFormat(inputRate: 16000, inputChannels: 1, outputRate: 16000, outputChannels: 1)

    private func decide(changed: Bool, built: EngineFormat?, now: EngineFormat, engineRunning: Bool, rebuilds: Int = 0, running: Bool = true) -> EngineRebuild {
        EngineDecision.onRouteEvent(sessionActive: true, running: running, configurationChanged: changed, built: built,
                                    now: now, engineRunning: engineRunning, recentRebuilds: rebuilds)
    }

    /// A configuration change with unchanged formats restarts the same engine, never builds a new one (a new
    /// voice-processing engine posts another change when it starts: that looped every ~0.4 s with no uplink).
    func testConfigurationChangeWithSameFormatsRestartsNeverRebuilds() {
        for rebuilds in 0...10 {
            XCTAssertEqual(decide(changed: true, built: f48, now: f48, engineRunning: false, rebuilds: rebuilds), .restart)
            XCTAssertEqual(decide(changed: true, built: f48, now: f48, engineRunning: true, rebuilds: rebuilds), .keep)
            XCTAssertEqual(decide(changed: false, built: f48, now: f48, engineRunning: false, rebuilds: rebuilds), .keep)
        }
    }

    /// The rebuild cap: past maxRebuilds in a few seconds, a configuration change or a still-running engine never
    /// builds a new engine (only an engine that stopped for good is rebuilt, below).
    func testRebuildCap() {
        let cap = EngineDecision.maxRebuilds
        XCTAssertEqual(decide(changed: true, built: f48, now: f16, engineRunning: true, rebuilds: cap), .keep)
        XCTAssertEqual(decide(changed: false, built: f48, now: f16, engineRunning: true, rebuilds: cap), .keep)
        XCTAssertEqual(decide(changed: true, built: f48, now: f16, engineRunning: false, rebuilds: cap), .restart)
        XCTAssertEqual(decide(changed: true, built: f48, now: f16, engineRunning: false, rebuilds: cap - 1), .rebuild)
        XCTAssertEqual(decide(changed: true, built: f48, now: f16, engineRunning: true, rebuilds: cap - 1), .rebuild)
        // A route change (no configuration change) to new formats with the engine stopped still rebuilds.
        XCTAssertEqual(decide(changed: false, built: f48, now: f16, engineRunning: false, rebuilds: cap), .rebuild)
        // …and with no engine running the call at all, one is always built.
        XCTAssertEqual(decide(changed: true, built: nil, now: f48, engineRunning: false, rebuilds: 99, running: false), .rebuild)
    }

    /// No usable input (route settling) is never a rebuild, whatever else is true; an inactive session is left alone.
    func testUnusableFormatsRetry() {
        let bad = [EngineFormat(inputRate: 0, inputChannels: 1, outputRate: 48000, outputChannels: 1),
                   EngineFormat(inputRate: 48000, inputChannels: 0, outputRate: 48000, outputChannels: 1),
                   EngineFormat(inputRate: 48000, inputChannels: 1, outputRate: 0, outputChannels: 1)]
        for now in bad {
            for running in [true, false] {
                XCTAssertEqual(decide(changed: true, built: f48, now: now, engineRunning: false, running: running), .retryLater)
                XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: false, running: running, configurationChanged: true, built: f48, now: now), .keep)
            }
        }
    }

    /// Interruptions and media-services resets keep the call and bring its audio back (#15).
    func testInterruptionsAndMediaReset() {
        for running in [false, true] { for engine in [false, true] {
            XCTAssertEqual(EngineDecision.onInterruption(began: true, sessionActive: true, running: running, engineRunning: engine), .keep, "began: wait for the end")
            XCTAssertEqual(EngineDecision.onInterruption(began: false, sessionActive: false, running: running, engineRunning: engine), .keep, "no call")
        } }
        XCTAssertEqual(EngineDecision.onInterruption(began: false, sessionActive: true, running: true, engineRunning: false), .restart, "the same engine again")
        XCTAssertEqual(EngineDecision.onInterruption(began: false, sessionActive: true, running: true, engineRunning: true), .keep)
        XCTAssertEqual(EngineDecision.onInterruption(began: false, sessionActive: true, running: false, engineRunning: false), .rebuild)
        XCTAssertEqual(EngineDecision.onMediaServicesReset(sessionActive: true), .rebuild)
        XCTAssertEqual(EngineDecision.onMediaServicesReset(sessionActive: false), .keep)
    }

    /// Formats read from the engine the same way at build and at the event: an unchanged engine is never rebuilt
    /// for a configuration change, only started again; a new rate (hands-free) is.
    func testEngineFormatsFromTheSameSource() {
        let vp = EngineFormat(inputRate: 48000, inputChannels: 3, outputRate: 48000, outputChannels: 2)   // voice processing reports more channels
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: vp, now: vp, engineRunning: false), .restart)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: false, built: vp, now: vp, engineRunning: true), .keep)
        // The same engine reporting another channel count (running vs stopped, voice processing): no rebuild loop.
        let vpRunning = EngineFormat(inputRate: 48000, inputChannels: 1, outputRate: 48000, outputChannels: 1)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: false, built: vp, now: vpRunning, engineRunning: true), .keep)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: vp, now: vpRunning, engineRunning: false), .restart)
        let hfp = EngineFormat(inputRate: 16000, inputChannels: 1, outputRate: 16000, outputChannels: 1)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: vp, now: hfp, engineRunning: false), .rebuild)
    }
}
