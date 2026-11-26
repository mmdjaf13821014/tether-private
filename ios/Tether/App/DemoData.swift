#if DEBUG
import AVFoundation
import Foundation
@_spi(TetherApp) import TetherCore

/// Debug builds only: `-TetherDemo` fills the UI with made-up data and `-TetherDemoCall` opens the call
/// screen, so the interface can be checked in the Simulator (which has no Bluetooth).
extension AppModel {
    func loadDemoIfRequested() {
        let args = ProcessInfo.processInfo.arguments
        guard args.contains("-TetherDemo") || args.contains("-TetherDemoCall") else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let people = [("Sara Ahmadi", "+15550101"), ("Daniel Brooks", "+15550102"), ("Nima", "+15550103"), ("Bank", "7000")]
        var sms: [Sms] = []
        var id: Int64 = 1
        let chat: [(Bool, String, Int)] = [   // (incoming, text, minutes ago)
            (true, "Hey, are you still coming tonight?", 190), (false, "Yes! Leaving work around six.", 188),
            (false, "Want me to bring anything?", 187), (true, "Just yourself 😄", 60), (true, "We booked the table for 7:30", 59),
            (false, "Perfect, see you there", 2)]
        for (i, p) in people.enumerated() {
            if i == 0 {
                for c in chat {
                    sms.append(Sms(id: id, thread: 1, addr: p.1, body: c.1, date: now - Int64(c.2) * 60_000, box: c.0 ? 1 : 2, read: true, sub: 3))
                    id += 1
                }
                continue
            }
            for j in 0..<3 {
                sms.append(Sms(id: id, thread: Int64(i + 1), addr: p.1, body: j == 2 && p.1 == "7000" ? "Your verification code is 482913" : "Demo message \(j + 1)",
                               date: now - Int64((i * 3 + (2 - j)) * 600_000), box: j % 2 == 0 ? 1 : 2, read: j < 2, sub: 3))
                id += 1
            }
        }
        // Thread 5: rich text cases (links, codes, numbers, formatting, Persian and mixed direction).
        let rich: [(Bool, String, String)] = [   // (incoming, sender, text)
            (true, "Bank Mellat", "رمز پویا: ۸۲۷۱۶۳\nمبلغ: ۱,۲۵۰,۰۰۰ ریال\nکارت: ۶۰۳۷**۱۲۳۴\nزمان اعتبار: ۱۲۰ ثانیه"),
            (true, "Bank Mellat", "G-7741 is your Google verification code."),
            (true, "Bank Mellat", "سلام! لینک پرداخت قبض شما: https://shaparak.ir/pay?id=88213 تا ساعت ۲۰ معتبر است."),
            (false, "Bank Mellat", "Thanks, see www.example.com and mail support@example.com"),
            (true, "Bank Mellat", "*Sale:* 50% off today only, _online_ at digikala.com. Code `SAVE10`. Dial *140*1# for balance."),
            (true, "Bank Mellat", "برای پشتیبانی با ۰۲۱-۸۸۷۷۶۶۵۵ یا ۰۹۱۲۱۲۳۴۵۶۷ تماس بگیرید."),
            (false, "Bank Mellat", "Call me at +1 (555) 010-0123 تا ساعت ۵ — ok?"),
        ]
        for (k, r) in rich.enumerated() {
            sms.append(Sms(id: id, thread: 5, addr: "+15550105", body: r.2, date: now - Int64((rich.count - k) * 60_000),
                           box: r.0 ? 1 : 2, read: true, sub: k == 6 ? 3 : 2))
            id += 1
        }
        let contacts = [Contact(id: 50, name: "Bank Mellat", phones: [Phone(n: "+15550105")])] + people.enumerated().map { Contact(id: Int64($0.offset), name: $0.element.0, phones: [Phone(n: $0.element.1)], starred: $0.offset < 2) }
        // Unknown numbers (not in contacts) show grouped: a test number, a short code, a USSD code.
        let unknown = ["+10000000001", "+10000000002", "*140#"]
        let recents = people.enumerated().map { CallEntry(id: Int64($0.offset), num: $0.element.1, date: now - Int64($0.offset) * 3_600_000, dur: 95, type: [1, 2, 3, 1][$0.offset], sub: $0.offset % 2 == 0 ? 3 : 2) }
            + unknown.enumerated().map { CallEntry(id: Int64(10 + $0.offset), num: $0.element, date: now - Int64(5 + $0.offset) * 3_600_000, dur: 30, type: 2, sub: 3) }
        sms.append(Sms(id: id, thread: 6, addr: "+10000000003", body: "Demo message from an unknown number", date: now - 30 * 60_000, box: 1, read: true, sub: 3))
        id += 1
        injectDemo(sms: sms, contacts: contacts, recents: recents,
                   status: StatusBody(device: "Android phone", battery: 82, charging: false,
                                      sims: [SimInfo(slot: 0, sub: 3, name: "Personal", carrier: "Carrier A", signal: 4, net: "LTE", service: "in"),
                                             SimInfo(slot: 1, sub: 2, name: "Work", carrier: "Carrier B", signal: 2, net: "LTE+", service: "in")],
                                      defaultSub: 3, dialer: true, helper: HelperInfo(state: "running", method: "intercept", capture: "intercept", up: true, down: true,
                                                                                       silent: "full", micMute: false, uplink: "ok", mic: "auto", micPath: "off"),
                                      wifi: true, adbWifi: true,
                                      tools: .init(ussd: true, find: true, ringer: true), ringer: "vibrate", dnd: false),
                   lists: BlockLists(blocked: ["7000", "+15550199"], muted: [3], system: true))
        // -TetherDemoMessages opens the Messages tab, -TetherDemoThread the first conversation,
        // -TetherDemoNotify posts a sample SMS notification.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {   // after the root view has picked its first tab
            if args.contains("-TetherDemoRich") { Router.shared.open(thread: 5) }
            else if args.contains("-TetherDemoThread") { Router.shared.open(thread: 1) }
            else if args.contains("-TetherDemoMessages") { Router.shared.tab = .messages }
            else if args.contains("-TetherDemoContacts") { Router.shared.tab = .contacts }
            else if args.contains("-TetherDemoKeypad") { Router.shared.tab = .keypad }
            else if args.contains("-TetherDemoRecents") { Router.shared.tab = .recents }
            else if args.contains("-TetherDemoPair") || args.contains("-TetherDemoPairManual") || args.contains("-TetherDemoPhoneTools") || args.contains("-TetherDemoReport") { Router.shared.tab = .link }
        }
        // -TetherDemoReport opens Link › Send a problem report; the log lines hold numbers and addresses to show them removed.
        if args.contains("-TetherDemoReport") {
            for line in ["session up over l2cap", "call from +15550101 ended (peer 00:11:22:33:44:55)", "proxy 192.168.44.1:8118",
                         "call audio: link round trip 118 ms, playout delay 46 ms (target 65, arrival spread asks 44), concealed 12 ms, underruns 0, drained 40 ms (speech 0 ms), added 20 ms, lost 0 of 959 frames, arrival jitter 3.1 ms, worst gap 87 ms, drift -12 ppm, io 5.3 ms, out latency 4.2 ms",
                         "disconnected: The connection has timed out unexpectedly.", "session up over l2cap"] {
                LinkJournal.shared.add(line)
            }
            AppEvents.shared.add("link state: connected over l2cap")
        }
        // -TetherDemoOpen <url> opens a link as if another app had (tel:, tether://dial?number=…); with
        // -TetherDemoOpenCancel the question is cancelled after 3 s, which leaves the number in the Keypad.
        if let i = args.firstIndex(of: "-TetherDemoOpen"), i + 1 < args.count, let url = URL(string: args[i + 1]) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                Router.shared.open(url: url)
                if args.contains("-TetherDemoOpenCancel") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                        Router.shared.keypadNumber = Router.shared.dialRequest?.number
                        Router.shared.dialRequest = nil
                    }
                }
            }
        }
        if args.contains("-TetherDemoNotify") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                Notifications.shared.showSms(Sms(id: 9001, thread: 1, addr: "+15550101", body: "Running five minutes late, order for me?",
                                                 date: now, box: 1, read: false, sub: 3), from: "Sara Ahmadi")
                Notifications.shared.showSms(Sms(id: 9002, thread: 4, addr: "7000", body: "Your verification code is 482913",
                                                 date: now, box: 1, read: false, sub: 3), from: "Bank")
            }
        }
        net = NetState(data: true, hotspot: true, bt: false, vpn: true, share: true,
                       proxy: ShareProxyInfo(port: 8118, addrs: [.init(iface: "swlan0", ip: "10.123.181.251", kind: "hotspot",
                                                                      pac: "http://10.123.181.251:8118/proxy.pac")]))
        if args.contains("-TetherDemoPhoneTools") {
            ussdResult = UssdResult(ok: true, text: "Your balance is 12.40. Valid until 30 Dec.", err: nil)
        }
        if args.contains("-TetherDemoCall") {
            let dialing = args.contains("-TetherDemoDialing")
            callScreen = CallScreen(uuid: UUID(), number: "+15550101", name: "Sara Ahmadi", state: dialing ? "dialing" : "active",
                                    incoming: !dialing, connectedAt: dialing ? nil : Date().addingTimeInterval(-42), sub: 3)
            showCallScreen = !args.contains("-TetherDemoBanner")
            if args.contains("-TetherDemoRoutes") {   // the audio menu with a headset and a car (no Bluetooth in the simulator)
                callScreen?.routes = AudioRoutes.options(inputs: [
                    RoutePort(type: "BluetoothHFP", uid: "00:11:22:33:44:55-tsco", name: "Headset"),
                    RoutePort(type: "CarAudio", uid: "00:11:22:33:44:66-car", name: "Car")])
                callScreen?.route = .bluetooth
                callScreen?.selectedRoute = "00:11:22:33:44:55-tsco"
            }
        }
    }
}
#endif

#if DEBUG
/// Debug launch options for on-device tests (never in release builds):
///   -TetherPairCode tether1:…  pair at launch (unless already paired with that phone)
///   -TetherAutoAnswer          answer ringing calls after a second (test calls from the Android phone's fake line)
///   -TetherEcho                send received call audio straight back instead of the mic (round-trip timing)
extension AppModel {
    func applyDebugLaunchOptions() {
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-TetherPairCode"), i + 1 < args.count, let code = PairingCode.parse(args[i + 1]),
           identity.peerKey != code.peripheralKey {
            pair(code)
        }
    }

    func debugAutoAnswer(_ c: CallInfo) {
        let args = ProcessInfo.processInfo.arguments
        // -TetherAutoHangup N: an active call is ended from this iPhone after N seconds (tests without a tap).
        if c.state == "active", let i = args.firstIndex(of: "-TetherAutoHangup"), i + 1 < args.count, let n = Double(args[i + 1]),
           !autoHangupArmed.contains(c.cid) {
            autoHangupArmed.insert(c.cid)
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(n))
                self.calls.end(cid: c.cid)
            }
        }
        guard args.contains("-TetherAutoAnswer"), c.state == "ringing", c.isIncoming else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1))
            self.calls.answer(cid: c.cid)
        }
    }
}
#endif


#if DEBUG
extension AppModel {
    /// -TetherAudioTest: runs the call-audio start path (session, engine, voice processing) without a call, twice.
    func debugAudioTest() {
        guard ProcessInfo.processInfo.arguments.contains("-TetherAudioTest") else { return }
        Task { @MainActor in
            for round in 1...2 {
                try? await Task.sleep(for: .seconds(3))
                NSLog("[audiotest] round %d", round)
                CallAudio.configureSession()
                try? AVAudioSession.sharedInstance().setActive(true)
                audio.configure(AudioStart(codec: "opus", rate: 16000, ms: 20, up: true, bitrate: 32000))
                audio.sessionActivated()
                try? await Task.sleep(for: .seconds(3))
                audio.stop()
                try? AVAudioSession.sharedInstance().setActive(false)
            }
        }
    }
}
#endif
