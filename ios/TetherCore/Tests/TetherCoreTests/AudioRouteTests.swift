import XCTest
@_spi(TetherApp) @testable import TetherCore

final class AudioRouteTests: XCTestCase {
    let mic = RoutePort(type: "MicrophoneBuiltIn", uid: "Built-In Microphone", name: "iPhone Microphone")
    let hfp = RoutePort(type: "BluetoothHFP", uid: "AA:BB:CC:DD:EE:FF-tsco", name: "Headset")
    let car = RoutePort(type: "CarAudio", uid: "CarUID-1234567890", name: "Car")

    func testClassification() {
        XCTAssertEqual(RouteKind(port: "Receiver"), .receiver)
        XCTAssertEqual(RouteKind(port: "Speaker"), .speaker)
        XCTAssertEqual(RouteKind(port: "BluetoothHFP"), .bluetooth)
        XCTAssertEqual(RouteKind(port: "BluetoothLE"), .bluetooth)
        XCTAssertEqual(RouteKind(port: "CarAudio"), .car)
        XCTAssertEqual(RouteKind(port: "HeadsetMicrophone"), .wired)
        XCTAssertEqual(RouteKind(port: "Something"), .other)
    }

    func testOptionsListBuiltInsThenEachAccessoryOnce() {
        let o = AudioRoutes.options(inputs: [mic, hfp, car, hfp])
        XCTAssertEqual(o.map(\.id), ["iphone", "speaker", hfp.uid, car.uid])
        XCTAssertEqual(o[2].kind, .bluetooth)
        XCTAssertEqual(AudioRoutes.options(inputs: [mic], hasReceiver: false).map(\.id), ["speaker"])
    }

    func testCurrentRouteAndSpeakerButton() {
        XCTAssertEqual(AudioRoutes.current(outputs: ["Receiver"]), .receiver)
        XCTAssertEqual(AudioRoutes.current(outputs: []), .receiver)
        XCTAssertTrue(AudioRoutes.speakerOn(outputs: ["Speaker"]))
        // A car taking the call turns the speaker button off.
        XCTAssertFalse(AudioRoutes.speakerOn(outputs: ["CarAudio"]))
        XCTAssertEqual(AudioRoutes.current(outputs: ["BluetoothHFP"]), .bluetooth)
    }

    func testSelectedOption() {
        let opts = AudioRoutes.options(inputs: [mic, hfp])
        let out = RoutePort(type: "BluetoothHFP", uid: "AA:BB:CC:DD:EE:FF-tsco", name: "Headset")
        XCTAssertEqual(AudioRoutes.selected(outputs: [out], inputs: [out], options: opts), hfp.uid)
        let spk = RoutePort(type: "Speaker", uid: "Speaker", name: "Speaker")
        XCTAssertEqual(AudioRoutes.selected(outputs: [spk], inputs: [mic], options: opts), "speaker")
        let rcv = RoutePort(type: "Receiver", uid: "Built-In Receiver", name: "Receiver")
        XCTAssertEqual(AudioRoutes.selected(outputs: [rcv], inputs: [mic], options: opts), "iphone")
    }

    func testPlans() {
        // Back to the iPhone from a headset: prefer the built-in mic, no override.
        XCTAssertEqual(AudioRoutes.plan(for: "iphone", builtInMicUID: mic.uid), RoutePlan(preferredInputUID: mic.uid, speakerOverride: false))
        XCTAssertEqual(AudioRoutes.plan(for: "speaker", builtInMicUID: mic.uid), RoutePlan(preferredInputUID: mic.uid, speakerOverride: true))
        XCTAssertEqual(AudioRoutes.plan(for: hfp.uid, builtInMicUID: mic.uid), RoutePlan(preferredInputUID: hfp.uid, speakerOverride: false))
    }

    func testSpeakerIsNotForcedOverAnAccessory() {
        XCTAssertTrue(AudioRoutes.reapplySpeaker(wanted: true, outputs: ["Receiver"]))
        XCTAssertFalse(AudioRoutes.reapplySpeaker(wanted: true, outputs: ["CarAudio"]))
        XCTAssertFalse(AudioRoutes.reapplySpeaker(wanted: true, outputs: ["BluetoothHFP"]))
        XCTAssertFalse(AudioRoutes.reapplySpeaker(wanted: false, outputs: ["Receiver"]))
    }

    /// Speaker pressed while the call is still connecting (before CallKit activates the session): the route
    /// notifications of that moment show the receiver, and must not reset the choice; didActivate applies it (#15).
    func testSpeakerChosenBeforeTheSessionIsActiveSurvives() {
        var wanted = true   // the user pressed Speaker while dialing
        for outs in [["Receiver"], [], ["Speaker"], ["Receiver"]] {
            wanted = AudioRoutes.speakerWanted(previous: wanted, sessionActive: false, outputs: outs)
        }
        XCTAssertTrue(wanted, "kept until the session is active")
        XCTAssertTrue(AudioRoutes.reapplySpeaker(wanted: wanted, outputs: ["Receiver"]), "and applied at activation")
        XCTAssertFalse(AudioRoutes.speakerWanted(previous: false, sessionActive: false, outputs: ["Speaker"]), "nor turned on by another app's route")
    }

    /// Once the session is active, the real route is the choice: CallKit's own button or a headset taking the call.
    func testSpeakerFollowsTheRealRouteOnceActive() {
        XCTAssertTrue(AudioRoutes.speakerWanted(previous: false, sessionActive: true, outputs: ["Speaker"]))
        XCTAssertFalse(AudioRoutes.speakerWanted(previous: true, sessionActive: true, outputs: ["Receiver"]))
        XCTAssertFalse(AudioRoutes.speakerWanted(previous: true, sessionActive: true, outputs: ["BluetoothHFP"]))
    }

    /// The uplink waits for a connected call: nothing while dialing, ringing or on hold only.
    func testUplinkOnlyWhileACallIsConnected() {
        XCTAssertFalse(CallUplink.open(callStates: []))
        XCTAssertFalse(CallUplink.open(callStates: ["dialing"]))
        XCTAssertFalse(CallUplink.open(callStates: ["connecting"]))
        XCTAssertFalse(CallUplink.open(callStates: ["ringing"]))
        XCTAssertFalse(CallUplink.open(callStates: ["holding"]))
        XCTAssertTrue(CallUplink.open(callStates: ["active"]))
        XCTAssertTrue(CallUplink.open(callStates: ["holding", "active"]), "call waiting: the active one talks")
        XCTAssertTrue(CallUplink.open(callStates: ["active", "ringing"]))
    }

    func testEngineDecisions() {
        let builtIn = EngineFormat(inputRate: 48000, inputChannels: 1, outputRate: 48000, outputChannels: 1)
        let hfp16 = EngineFormat(inputRate: 16000, inputChannels: 1, outputRate: 16000, outputChannels: 1)
        let none = EngineFormat(inputRate: 0, inputChannels: 0, outputRate: 16000, outputChannels: 1)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: false, running: true, configurationChanged: true, built: builtIn, now: hfp16), .keep)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: false, built: builtIn, now: builtIn), .keep)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: false, built: builtIn, now: hfp16), .rebuild)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: builtIn, now: builtIn), .restart)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: false, configurationChanged: false, built: nil, now: builtIn), .rebuild)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: false, configurationChanged: true, built: builtIn, now: none), .retryLater)
        // A voice-processing engine that still runs after its own start-up events, in the same formats: left alone.
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: builtIn, now: builtIn, engineRunning: true), .keep)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: false, built: builtIn, now: builtIn, engineRunning: true), .keep)
        // A real format change (hands-free mic) still rebuilds while the engine runs.
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: builtIn, now: hfp16, engineRunning: true), .rebuild)
        // Loop guard: after maxRebuilds in a few seconds a running engine is kept; a stopped one is still rebuilt.
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: builtIn, now: hfp16, engineRunning: true, recentRebuilds: EngineDecision.maxRebuilds), .keep)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: builtIn, now: hfp16, engineRunning: false, recentRebuilds: EngineDecision.maxRebuilds), .restart)
        // The engine stopped itself on a configuration change with nothing changed: the same engine starts again.
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: builtIn, now: builtIn, engineRunning: false), .restart)
        XCTAssertEqual(EngineDecision.onRouteEvent(sessionActive: true, running: true, configurationChanged: true, built: builtIn, now: hfp16, engineRunning: false), .rebuild)
    }
}
