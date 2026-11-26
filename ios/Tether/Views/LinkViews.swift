import AVFoundation
import Perception
import SwiftUI
import TetherCore

struct LinkView: View {
    @Environment(\.appModel) private var model
    @State private var scanning = false
    @State private var confirmUnpair = false
    @State private var reporting = false

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        NavigationStack {
            VStack(spacing: 0) {
                StatusStrip()
                ScrollViewReader { scroller in
            List {
                if !model.isPaired {
                    SetupGuideSection(
                        status: stateLine,
                        linkState: model.linkState
                    ) { scanning = true }
                } else {
                    Section {
                        HStack(spacing: 14) {
                            Image(systemName: model.linkState.isConnected ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                                .font(.system(size: 34))
                                .foregroundStyle(model.linkState.isConnected ? .teal : .secondary)
                                .frame(width: 48)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(model.peerName).font(.headline)
                                Text(stateLine).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                        .padding(.vertical, 6)
                    }

                    Section {
                        Label {
                            Text("Tether keeps the link in the background and when the screen is locked. Swiping Tether away in the app switcher stops it until you open it again: iOS does not relaunch a force-quit app for Bluetooth.")
                                .font(.footnote)
                        } icon: {
                            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.yellow)
                        }
                    }
                }

                SigningSection()

                if let s = model.status {
                    Section("Phone") {
                        LabeledContent("Battery", value: "\(s.battery)%" + (s.charging ? " ⚡︎" : ""))
                        ForEach(s.sims) { sim in
                            LabeledContent {
                                HStack(spacing: 6) { SimStatus(sim: sim, showName: false); Text(sim.carrier) }
                            } label: {
                                Text("SIM \(sim.slot + 1) · \(sim.name)")
                            }
                        }
                        LabeledContent("Default phone app", value: s.dialer ? String(localized: "Tether") : String(localized: "Not set"))
                        LabeledContent("Wireless debugging", value: s.adbWifi ? String(localized: "On") : String(localized: "Off"))
                    }
                    Section {
                        NetToggle(title: "Mobile data", icon: "antenna.radiowaves.left.and.right", what: "data", value: model.net?.data)
                        if s.sims.count > 1, let current = model.net?.dataSub {
                            Picker(selection: Binding(get: { current }, set: { model.setDataSim($0) })) {
                                ForEach(s.sims) { sim in
                                    Text("SIM \(sim.slot + 1) · \(sim.name)").tag(sim.sub)
                                }
                            } label: {
                                Label {
                                    HStack(spacing: 8) {
                                        Text("Data SIM")
                                        if model.netPending == "dataSub" { ProgressView().controlSize(.small) }
                                    }
                                } icon: {
                                    Image(systemName: "simcard.2")
                                }
                            }
                            .disabled(model.netPending != nil || !model.linkState.isConnected)
                        }
                        // Only what this Android phone can do (older Android builds of Tether do not say: shown).
                        if model.net?.can?.hotspot != false {
                            NetToggle(title: "Wi-Fi hotspot", icon: "personalhotspot", what: "hotspot", value: model.net?.hotspot)
                        }
                        if model.net?.can?.bt != false {
                            NetToggle(title: "Bluetooth tethering", icon: "dot.radiowaves.left.and.right", what: "bt", value: model.net?.bt)
                        }
                        NetToggle(title: "Airplane mode", icon: "airplane", what: "airplane", value: model.net?.airplane)
                        ForEach(s.sims) { sim in
                            if let mode = model.net?.mode(slot: sim.slot) {
                                Picker(selection: Binding(get: { mode }, set: { model.setNetworkMode(slot: sim.slot, $0) })) {
                                    ForEach(["5G", "4G", "3G", "2G"], id: \.self) { Text($0).tag($0) }
                                } label: {
                                    Label {
                                        HStack(spacing: 8) {
                                            Text("Network · \(sim.name)")
                                            if model.netPending == "rat\(sim.slot)" { ProgressView().controlSize(.small) }
                                        }
                                    } icon: { Image(systemName: "cellularbars") }
                                }
                                .disabled(model.netPending != nil || !model.linkState.isConnected)
                            }
                        }
                        if let e = model.net?.err {
                            Text(e).font(.footnote).foregroundStyle(.red)
                        }
                    } header: {
                        Text("Internet")
                    } footer: {
                        Text("Turn on the hotspot and join it in Settings › Wi-Fi to use the Android phone's internet here. Bluetooth tethering is slower: after switching it on, tap the Android phone in Settings › Bluetooth. Airplane mode keeps Bluetooth on so this link stays up; if the link is not back within 90 seconds it switches itself off. The hotspot and Bluetooth tethering come back on after it.")
                    }
                    VPNSection().id("vpn")
                    if let t = s.tools, t.ussd == true || t.find == true || t.ringer == true {
                        PhoneToolsSection(status: s, tools: t).id("phonetools")
                    }
                    Section {
                        LabeledContent("Audio helper", value: s.helper.state == "running" ? String(localized: "Running") : String(localized: "Stopped"))
                        if s.helper.audioUnavailable {
                            Text("Call audio is not available on this Android phone: it needs Android 11 or later. Calls are still answered, placed and ended here, but you hear and speak on the Android phone.")
                                .font(.footnote).foregroundStyle(.orange)
                        } else if s.helper.audioBlocked {
                            Text("This Android phone's software gives Tether no call audio (the call sounds like silence to it). Calls are still answered, placed and ended here, but you hear and speak on the Android phone. A system update, or a self-test during a call that hears the call, turns call audio back on. If calls on the phone itself have no sound either, restart the phone once.")
                                .font(.footnote).foregroundStyle(.orange)
                        }
                        if let m = s.helper.method { LabeledContent("Inject method", value: m) }
                        if let m = s.helper.capture { LabeledContent("Capture method", value: m) }
                        if let q = s.helper.silent {
                            LabeledContent("Android phone silent", value: q == "full" ? String(localized: "Yes") :
                                q == "earpiece" ? String(localized: "Earpiece only") : String(localized: "No"))
                        }
                        if let mic = s.helper.mic {
                            Picker("Android phone's microphone", selection: Binding(get: { CallAudioRows.micChoice(mic) }, set: { model.setPhoneMic($0) })) {
                                Text("Off during calls").tag("auto")
                                Text("Muted").tag("mute")
                                Text("On").tag("open")
                            }
                            .disabled(!model.linkState.isConnected)
                            if let p = s.helper.micPath {
                                LabeledContent("In this call", value: CallAudioRows.micPathText(p))
                            }
                        } else if CallAudioRows.showsMicToggle(s), let on = s.helper.micMute {
                            Toggle("Mute the Android phone's microphone", isOn: Binding(get: { on }, set: { model.setPhoneMicMute($0) }))
                                .tint(.green).disabled(!model.linkState.isConnected)
                        }
                        let micWarning = CallAudioRows.micWarning(denied: CallAudioRows.microphoneDenied(), last: model.micWarning)
                        if let u = s.helper.uplink {
                            LabeledContent("Your voice in the call", value: CallAudioRows.voiceText(u, mic: micWarning))
                        }
                        if let w = micWarning {
                            Text(Microphone.text(w)).font(.footnote).foregroundStyle(.red)
                            if w == .denied, let url = URL(string: UIApplication.openSettingsURLString) {
                                Link("Open Settings", destination: url).font(.footnote)
                            }
                        }
                        if let a = model.lastAudioStats {
                            LabeledContent("Last call") {
                                Text(String(format: "buffer %.0f ms · jitter %.1f ms · gaps %d", a.delayMs, a.jitterMs, a.underruns))
                                    .font(.caption.monospacedDigit())
                            }
                        }
                        Button("Run call-audio self-test") { model.runSelftest() }
                            .disabled(!model.linkState.isConnected)
                        if let st = model.selftest {
                            ForEach(st.methods, id: \.self) { m in
                                HStack(alignment: .top) {
                                    Image(systemName: m.ok ? "checkmark.circle.fill" : "xmark.circle").foregroundStyle(m.ok ? .green : .secondary)
                                    VStack(alignment: .leading) {
                                        Text(m.name).font(.subheadline)
                                        if let n = m.note { Text(n).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
                                    }
                                }
                            }
                        }
                    } header: {
                        Text("Call audio")
                    } footer: {
                        if let mic = s.helper.mic {
                            Text(CallAudioRows.micFooter(mic))
                        } else if CallAudioRows.showsMicToggle(s) {
                            Text("Off is safest: on some Android phones muting their microphone also mutes your voice for the other side. When it is on, Tether checks during each call and turns the mute off if your voice does not get through.")
                        }
                    }
                }

                PhoneLinksSection()

                Section {
                    NavigationLink {
                        BlockedListView()
                    } label: {
                        LabeledContent {
                            Text("\(model.blockLists.blocked.count)")
                        } label: {
                            Label("Blocked Contacts", systemImage: "hand.raised")
                        }
                    }
                } footer: {
                    Text("Kept on the Android phone and shared by all your devices.")
                }

                Section {
                    if let last = model.lastSync {
                        LabeledContent("Last sync", value: last.formatted(date: .omitted, time: .shortened))
                    }
                    Button("Sync everything again") { model.requestSync(full: true) }.disabled(!model.linkState.isConnected)
                    NavigationLink("Link log") { LinkLogView() }
                    NavigationLink("Send a problem report") { ProblemReportView() }
                    if model.isPaired {
                        Button("Pair again") { scanning = true }
                        Button("Unpair", role: .destructive) { confirmUnpair = true }
                    }
                }
                #if DEBUG
                DeveloperSection()
                #endif
            }
            #if DEBUG
            // -TetherDemoVPN: start scrolled to the VPN section (screenshots).
            .onAppear {
                if ProcessInfo.processInfo.arguments.contains("-TetherDemoPair") || ProcessInfo.processInfo.arguments.contains("-TetherDemoPairManual") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { scanning = true }
                }
                if ProcessInfo.processInfo.arguments.contains("-TetherDemoReport") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) { reporting = true }
                }
                if ProcessInfo.processInfo.arguments.contains("-TetherDemoPhoneTools") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1) { scroller.scrollTo("phonetools", anchor: .top) }
                }
                if ProcessInfo.processInfo.arguments.contains("-TetherDemoVPN") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1) { scroller.scrollTo("vpn", anchor: .top) }
                }
            }
            #endif
            }
            .navigationTitle("Link")
            .navigationDestination(isPresented: $reporting) { ProblemReportView() }
            .onAppear { model.refreshNet() }
            .onChangeCompat(of: model.linkState.isConnected) { up in if up { model.refreshNet() } }
            // Full screen: nothing of the Link tab shows around or behind the camera.
            .fullScreenCover(isPresented: $scanning) {
                PairingView { code in
                    scanning = false
                    if let code { model.pair(code) }
                }
                .environment(\.appModel, AppModel.shared)
            }
            .confirmationDialog("Forget the Android phone and delete synced data from this iPhone?", isPresented: $confirmUnpair,
                                titleVisibility: .visible) {
                Button("Unpair", role: .destructive) { model.unpair() }
            }
            }
        }
    }

    private var stateLine: String {
        switch model.linkState {
        case .connected(let t): String(localized: "Connected over \(t == "l2cap" ? "L2CAP" : "GATT")")
        case .connecting, .handshaking: String(localized: "Connecting…")
        case .searching: model.isPaired ? String(localized: "Out of reach, will reconnect automatically") : String(localized: "Not paired")
        case .poweredOff: String(localized: "Bluetooth is off")
        case .unauthorized: String(localized: "Allow Bluetooth for Tether in Settings")
        case .unpaired: String(localized: "Not paired")
        }
    }
}

/// First run (not paired): what to install on the Android phone, how to show and scan the code, and which
/// permissions the iPhone will ask for and why.
private struct SetupGuideSection: View {
    let status: String
    let linkState: TetherLink.State
    let scan: () -> Void

    /// The app's own teal (Assets › AccentColor), as on every other screen.
    private let accent = Color("AccentColor")

    private var bluetoothUnavailable: Bool { linkState == .poweredOff || linkState == .unauthorized }

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 14) {
                Image(systemName: "personalhotspot")
                    .font(.title2.weight(.semibold))
                    .imageScale(.large)
                    .foregroundStyle(accent)
                    .padding(12)
                    .background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                    .accessibilityHidden(true)

                Text("Connect your Android phone")
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)

                Text("Use your Android SIM for calls and texts on this device.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Label(status, systemImage: bluetoothUnavailable ? "exclamationmark.circle.fill" : "link")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(bluetoothUnavailable ? Color.orange : accent)

                if linkState == .poweredOff {
                    Text("Turn on Bluetooth in Settings on this device before pairing.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Button(action: scan) {
                    Label("Scan Pairing Code", systemImage: "qrcode.viewfinder")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.black)
                        .multilineTextAlignment(.center)
                        .padding(.vertical, 6)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(accent)

                if linkState == .unauthorized, let settingsURL = URL(string: UIApplication.openSettingsURLString) {
                    Link("Open Settings", destination: settingsURL)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(accent)
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
            .listRowBackground(Color.clear)
        }

        Section {
            step(1, title: "Set up Tether on Android",
                 detail: "On the phone with the SIM, install Tether and complete its setup.")
            step(2, title: "Show the pairing code",
                 detail: "In Tether on Android, tap Pair iPhone. The QR code is available for five minutes.")
            step(3, title: "Scan with this device",
                 detail: "Keep both phones nearby. Scan it here, or point this iPhone's Camera at it and tap Open in Cellular. You can also enter the code manually from the scanner.")
        } header: {
            Text("Set up")
        } footer: {
            Label("Calls and texts stay on your devices and travel over an encrypted Bluetooth link.",
                  systemImage: "lock.shield")
        }
    }

    private func step(_ number: Int, title: LocalizedStringKey, detail: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number.formatted())
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(accent)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(accent.opacity(0.12), in: Capsule())

            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

#if DEBUG
/// Debug builds only: try the extensions (protocol §7) against the linked Android phone. Not translated on purpose.
struct DeveloperSection: View {
    @Environment(\.appModel) private var model
    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        Section {
            Button { model.echoTest() } label: { Text(verbatim: "Echo round trip (x.tether.echo)") }
                .disabled(!model.linkState.isConnected)
            if let line = model.extensionLine { Text(verbatim: line).font(.footnote) }
            Text(verbatim: model.extensions.summary).font(.caption2.monospaced()).foregroundStyle(.secondary)
        } header: {
            Text(verbatim: "Developer")
        } footer: {
            Text(verbatim: "Debug builds only. Add your own feature: docs/EXTENDING.md.")
        }
    }
}
#endif

/// The Android phone's VPN (any VPN app) and "Share VPN": its proxy lets this device use that VPN.
struct VPNSection: View {
    @Environment(\.appModel) private var model

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        Section {
            LabeledContent {
                Text(model.net?.vpn == true ? String(localized: "On") : model.net?.vpn == false ? String(localized: "Off") : "–")
                    .foregroundStyle(model.net?.vpn == true ? Color.green : Color.secondary)
            } label: {
                Label("VPN on the Android phone", systemImage: "lock.shield")
            }
            NetToggle(title: "Share VPN", icon: "network.badge.shield.half.filled", what: "share", value: model.net?.share)
            if model.net?.share == true {
                IPhoneProxySetup(proxy: model.net?.proxy)
            }
        } header: {
            Text("VPN")
        } footer: {
            Text("Turn the VPN on in its own app on the Android phone. The phone's hotspot and Bluetooth tethering do not go through it (Android sends tethered traffic straight out), so Share VPN runs a proxy on the phone whose traffic does. This iPhone can use it over the hotspot (one-time proxy setting); over Bluetooth tethering only with a proxy app.")
        }
    }
}


/// One-time steps: iOS lets no app set the system proxy, but the hotspot network can use the phone's PAC file.
struct IPhoneProxySetup: View {
    @Environment(\.appModel) private var model
    let proxy: ShareProxyInfo?

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        DisclosureGroup {
            if let a = proxy?.addrs.first(where: { $0.kind == "hotspot" }) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("1. Join the Android phone's hotspot in Settings › Wi-Fi.")
                    Text("2. Tap ⓘ next to it › Configure Proxy › Automatic.")
                    Text("3. Paste this URL and tap Save:")
                    HStack {
                        Text(a.pac).font(.callout.monospaced()).textSelection(.enabled)
                            .environment(\.layoutDirection, .leftToRight)
                        Spacer()
                        Button { UIPasteboard.general.string = a.pac; model.flash(String(localized: "Copied")) } label: {
                            Image(systemName: "doc.on.doc")
                        }.buttonStyle(.borderless)
                    }
                    Text("Once is enough: with Share VPN on, apps on this iPhone use the phone's VPN over the hotspot; with it off they go direct. If the hotspot address ever changes, update the URL.")
                        .foregroundStyle(.secondary)
                    BluetoothProxyNote(proxy: proxy)
                }
                .font(.footnote)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Turn on the Wi-Fi hotspot to see the address to use.")
                    BluetoothProxyNote(proxy: proxy)
                }
                .font(.footnote).foregroundStyle(.secondary)
            }
        } label: {
            Label("Use it on this iPhone", systemImage: "iphone")
        }
    }
}

/// Bluetooth tethering and the VPN: iOS has no proxy setting for it, so only a proxy app can use the Android phone's proxy there.
struct BluetoothProxyNote: View {
    let proxy: ShareProxyInfo?

    var body: some View {
        let bt = proxy?.addrs.first(where: { $0.kind == "bt" })
        let port = proxy?.port ?? 8118
        VStack(alignment: .leading, spacing: 4) {
            Text("Bluetooth tethering does not use the VPN on its own: iPhone has no proxy setting for it. Use the hotspot for the VPN.")
                .fontWeight(.semibold)
            Text("Only a proxy app on this iPhone (for example V2Box) can use it over Bluetooth: add a SOCKS5 server \(bt?.ip ?? "192.168.44.1"):\(String(port)) there and turn it on.")
                .foregroundStyle(.secondary)
        }
        .font(.footnote)
    }
}

/// Recent link events, newest first (drops and their reasons), with a button to share them.
struct LinkLogView: View {
    @State private var text = LinkJournal.shared.text

    var body: some View {
        ScrollView {
            Text(text.isEmpty ? String(localized: "No events yet.") : text)
                .font(.caption.monospaced()).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading).padding()
                .environment(\.layoutDirection, .leftToRight)
        }
        .navigationTitle("Link log")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) { ShareLink(item: text) }
        }
        .refreshable { text = LinkJournal.shared.text }
    }
}

/// One text with what a problem report needs (device, setup, link, call audio, the Android phone's last status, the
/// link log), scrubbed of numbers, names, addresses and keys, shared as a .txt file or copied.
struct ProblemReportView: View {
    @Environment(\.appModel) private var model
    @State private var report: String?
    @State private var file: URL?

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        Group {
            if let report {
                ScrollView {
                    Text(report)
                        .font(.caption2.monospaced()).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading).padding()
                        .environment(\.layoutDirection, .leftToRight)
                }
            } else {
                ProgressView("Building the report…")
            }
        }
        .navigationTitle("Problem report")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                if let report {
                    Button {
                        UIPasteboard.general.string = report
                        model.flash(String(localized: "Copied"))
                    } label: { Label("Copy", systemImage: "doc.on.doc") }
                    if let file {
                        ShareLink(item: file, preview: SharePreview(file.lastPathComponent)) { Label("Share", systemImage: "square.and.arrow.up") }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if report != nil {
                Text("Phone numbers, names, message text, e-mail, addresses and keys are removed. Read it before you post it.")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    .padding(.horizontal).padding(.vertical, 8).frame(maxWidth: .infinity)
                    .background(Color(.systemGroupedBackground))
            }
        }
        .task { await build() }
        .refreshable { await build() }
    }

    private func build() async {
        let (text, created) = await ProblemReportBuilder.text(model: model)
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd-HHmm"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Tether problem report \(f.string(from: created)).txt")
        try? text.write(to: url, atomically: true, encoding: .utf8)
        report = text
        file = url
    }
}

/// Blocked numbers (the Android phone's list), with swipe to unblock, as in Settings › Phone › Blocked Contacts.
struct BlockedListView: View {
    @Environment(\.appModel) private var model

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        List {
            Section {
                ForEach(model.blockLists.blocked, id: \.self) { n in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.name(for: n) ?? n)
                        if model.name(for: n) != nil {
                            Text(n).font(.caption).foregroundStyle(.secondary).environment(\.layoutDirection, .leftToRight)
                        }
                    }
                    .swipeActions {
                        Button("Unblock") { model.setBlocked(n, false) }.tint(.red)
                    }
                    .contextMenu {
                        Button { model.setBlocked(n, false) } label: { Label("Unblock", systemImage: "hand.raised.slash") }
                    }
                }
            } footer: {
                if model.blockLists.system == true {
                    Text("This is the Android phone's own block list: it refuses these calls and messages itself, whichever device you use.")
                } else {
                    Text("Tether rejects these calls and does not forward these messages to your devices.")
                }
            }
        }
        .overlay {
            if model.blockLists.blocked.isEmpty { EmptyStateView("No Blocked Contacts", systemImage: "hand.raised") }
        }
        .navigationTitle("Blocked Contacts")
    }
}

/// Calling numbers from other apps (tel: links, Shortcuts, the Phone app's Recents); docs/USAGE.md.
struct PhoneLinksSection: View {
    @Environment(\.appModel) private var model
    @AppStorage(CallController.phoneRecentsKey) private var inPhoneRecents = false

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        Section {
            Toggle(isOn: $inPhoneRecents) { Label("Show calls in the Phone app", systemImage: "clock.arrow.circlepath") }
                .onChangeCompat(of: inPhoneRecents) { _ in model.calls.applyRecentsSetting() }
        } header: {
            Text("Numbers in other apps")
        } footer: {
            Text("Calls made here also appear in the Phone app's Recents; tapping one calls back through the Android phone. To call numbers you tap in other apps, choose this app in Settings › Apps › Default Apps › Calling (needs a paid Apple developer account), or use the \"Call with\" shortcut in the Shortcuts app.")
        }
    }
}

/// How long this build's signature still lasts (refreshed in the sideloading tool, or tools/reinstall-ios.sh for own builds).
struct SigningSection: View {
    var body: some View {
        if let expiry = Signing.expiry, let days = Signing.daysLeft {
            Section {
                LabeledContent("App expires") {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(days == 0 ? String(localized: "Today") : String(localized: "in \(days) days"))
                            .foregroundStyle(days <= 2 ? Color.red : (days <= 4 ? Color.orange : Color.primary))
                        Text(expiry.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("Renewal")
            } footer: {
                Text("Refresh the app before this date in the tool you installed it with (SideStore, AltStore or Sideloadly). A free Apple account's signature lasts 7 days.")
            }
        }
    }
}

/// What the Call audio section shows of the Android phone's microphone mute and the uplink check.
enum CallAudioRows {
    /// The switch only when the Android phone reports the setting (older Android builds do not: hidden).
    static func showsMicToggle(_ s: StatusBody?) -> Bool { s?.helper.micMute != nil }

    /// The picker's value: an unknown setting from a newer Android build reads as the default.
    static func micChoice(_ mic: String) -> String { ["auto", "mute", "open"].contains(mic) ? mic : "auto" }

    static func micPathText(_ p: String) -> String {
        switch p {
        case "off": return String(localized: "Microphone out of the call")
        case "muted": return String(localized: "Microphone muted")
        default: return String(localized: "Microphone on")
        }
    }

    static func micFooter(_ mic: String) -> String {
        switch micChoice(mic) {
        case "mute": return String(localized: "Muting works on some phones only: on others it also mutes your voice for the other side. Tether checks during each call and turns the mute off if your voice does not get through.")
        case "open": return String(localized: "The Android phone's microphone stays in the call: the other side may hear your voice twice, and the room around the phone.")
        default: return String(localized: "Tether puts the call in Android's redirect audio mode: the Android phone's own microphone and earpiece leave the call, so the other side hears only this iPhone. Tether checks that both directions still work and keeps the microphone on where the phone does not allow it.")
        }
    }

    static func uplinkText(_ u: String) -> String {
        switch u {
        case "ok": return String(localized: "Reaches the other side")
        case "checking": return String(localized: "Checking…")
        case "muted-locally-fixed": return String(localized: "Was muted by the phone; fixed")
        case "off": return String(localized: "Not muted by the phone")
        default: return String(localized: "Not checked")
        }
    }

    /// The Android phone's uplink check sees only that audio reaches the network; it cannot hear that what this iPhone
    /// sends is silence. With the microphone refused or silent the row never says the voice gets through (#14).
    static func voiceText(_ u: String, mic: MicWarning?) -> String {
        switch mic {
        case .denied: return String(localized: "Does not reach: microphone off on this iPhone")
        case .silent: return String(localized: "Does not reach: no sound from this iPhone's microphone")
        case nil: return uplinkText(u)
        }
    }

    /// The warning to show: the permission as it is now, else what the last call found.
    static func micWarning(denied: Bool, last: MicWarning?) -> MicWarning? {
        denied ? .denied : last == .silent ? .silent : nil
    }

    /// This iPhone refused Tether the microphone: every call's uplink is silent.
    static func microphoneDenied() -> Bool {
        if #available(iOS 17, *) { return AVAudioApplication.shared.recordPermission == .denied }
        return AVAudioSession.sharedInstance().recordPermission == .denied
    }
}

/// Link tab › Phone tools: carrier codes (balance etc.) on a SIM, "find my phone" and the ringer mode. Each part shows
/// only when the Android phone's status says it can (older Android builds of Tether do not say: the section is hidden).
struct PhoneToolsSection: View {
    @Environment(\.appModel) private var model
    let status: StatusBody
    let tools: StatusBody.PhoneTools
    @State private var code = ""
    @State private var sub: Int?

    private var chosenSub: Int? { sub ?? status.defaultSub ?? status.sims.first?.sub }

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        Section {
            if tools.ussd == true {
                HStack {
                    TextField("*123#", text: $code)
                        .keyboardType(.numbersAndPunctuation).autocorrectionDisabled().textInputAutocapitalization(.never)
                        .font(.body.monospaced())
                    Button("Send") { model.sendUssd(code, sub: chosenSub) }
                        .disabled(!UssdCode.isValid(code.trimmingCharacters(in: .whitespaces)) || model.ussdPending || !model.linkState.isConnected)
                }
                if status.sims.count > 1 {
                    Picker("SIM", selection: Binding(get: { chosenSub ?? -1 }, set: { sub = $0 })) {
                        ForEach(status.sims) { sim in Text("SIM \(sim.slot + 1) · \(sim.name)").tag(sim.sub) }
                    }
                }
                if model.ussdPending {
                    HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Waiting for the carrier…").foregroundStyle(.secondary) }
                } else if let r = model.ussdResult {
                    Text(r.ok ? (r.text ?? "") : (r.err ?? String(localized: "No answer")))
                        .font(.callout).foregroundStyle(r.ok ? Color.primary : Color.red).textSelection(.enabled)
                }
            }
            if tools.find == true {
                Toggle(isOn: Binding(get: { model.finding }, set: { model.setFinding($0) })) {
                    Label("Find phone", systemImage: "bell.and.waves.left.and.right")
                }
                .tint(.green).disabled(!model.linkState.isConnected)
            }
            if tools.ringer == true, let mode = status.ringer {
                Picker(selection: Binding(get: { mode }, set: { model.setRinger($0) })) {
                    Text("Ring").tag("normal"); Text("Vibrate").tag("vibrate"); Text("Silent").tag("silent")
                } label: { Label("Ringer", systemImage: "bell") }
                .disabled(!model.linkState.isConnected)
                if status.dnd == false {
                    Text("Silent needs Do Not Disturb access for Tether on the Android phone.").font(.footnote).foregroundStyle(.secondary)
                }
            }
            if let e = model.phoneToolError { Text(e).font(.footnote).foregroundStyle(.red) }
        } header: {
            Text("Phone tools")
        } footer: {
            Text("Carrier codes are sent from the Android phone's SIM and may cost money. Find phone rings the Android phone loudly for up to 30 seconds, until it is unlocked or you switch it off.")
        }
    }
}

/// One switch in the Internet section: shows the phone's state, greys out while a change is in flight.
struct NetToggle: View {
    @Environment(\.appModel) private var model
    let title: LocalizedStringKey
    let icon: String
    let what: String
    let value: Bool?

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        Toggle(isOn: Binding(get: { value ?? false }, set: { model.setNet(what, on: $0) })) {
            Label {
                HStack(spacing: 8) {
                    Text(title)
                    if model.netPending == what { ProgressView().controlSize(.small) }
                }
            } icon: {
                Image(systemName: icon)
            }
        }
        .tint(.green)
        .disabled(value == nil || model.netPending != nil || !model.linkState.isConnected)
    }
}

/// Presents the camera and the manual code entry as separate screens.
struct PairingView: View {
    @Environment(\.scenePhase) private var scenePhase
    let done: (PairingCode?) -> Void
    @State private var error: String?
    @State private var manual = false
    @State private var cameraState: QRScanner.State = .starting
    @State private var completed = false

    /// The app's own teal (Assets › AccentColor), as on every other screen.
    private let accent = Color("AccentColor")

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("Scan the code shown in Tether on the Android phone.")
                        .font(.body)
                        .foregroundStyle(.secondary)

                    cameraPanel

                    if let error {
                        Label(error, systemImage: "exclamationmark.circle")
                            .font(.callout)
                            .foregroundStyle(.orange)
                    }

                    if cameraState == .permissionDenied, let settingsURL = URL(string: UIApplication.openSettingsURLString) {
                        Link("Open Settings", destination: settingsURL).font(.body.weight(.semibold))
                    }

                    Button { openManualEntry() } label: {
                        Label("Enter Code Manually", systemImage: "keyboard")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.black)
                            .multilineTextAlignment(.center)
                            .padding(.vertical, 6)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(20)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity, alignment: .top)
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle("Scan Pairing Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { done(nil) } }
            }
        }
        .tint(accent)
        .environment(\.colorScheme, .dark)
        .onChangeCompat(of: scenePhase) { phase in
            if phase == .active, cameraState == .permissionDenied,
               AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
                cameraState = .starting
            }
        }
        .sheet(isPresented: $manual) {
            ManualPairingView { code in
                manual = false
                if let code { finish(code) }
            }
            .tint(accent)
        }
        #if DEBUG
        .onAppear {
            if ProcessInfo.processInfo.arguments.contains("-TetherDemoPairManual") { openManualEntry() }
        }
        #endif
    }

    @ViewBuilder private var cameraPanel: some View {
        switch cameraState {
        case .starting, .ready:
            Color.black
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    if !manual && !completed {
                        QRScanner(onCode: scan, onStateChange: { cameraState = $0 })
                            .accessibilityHidden(true)
                        if cameraState == .ready {
                            RoundedRectangle(cornerRadius: 24)
                                .strokeBorder(accent, lineWidth: 3)
                                .aspectRatio(1, contentMode: .fit)
                                .padding(36)
                                .allowsHitTesting(false)
                                .accessibilityHidden(true)
                        }
                    }
                    if cameraState == .starting {
                        ProgressView("Starting camera…").tint(accent)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 24))
        case .permissionDenied:
            cameraMessage("Camera access is off",
                          detail: "Allow camera access in Settings, or enter the code by hand.")
        case .unavailable:
            cameraMessage("Camera not available",
                          detail: "You can still pair by entering the code manually.")
        }
    }

    private func cameraMessage(_ title: LocalizedStringKey, detail: LocalizedStringKey) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "camera.fill").font(.title2).foregroundStyle(accent).accessibilityHidden(true)
            Text(title).font(.headline)
            Text(detail).font(.callout).foregroundStyle(.secondary)
        }
        .multilineTextAlignment(.center)
        .padding(24)
        .frame(maxWidth: .infinity, minHeight: 200)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 24))
    }

    private func openManualEntry() {
        error = nil
        if cameraState == .ready { cameraState = .starting }
        manual = true
    }

    private func scan(_ text: String) {
        guard !manual && !completed else { return }
        guard let code = PairingCode.parse(text) else {
            error = String(localized: "That is not a Tether pairing code.")
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            return
        }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        finish(code)
    }

    private func finish(_ code: PairingCode) {
        guard !completed else { return }
        completed = true
        done(code)
    }
}

struct ManualPairingView: View {
    let done: (PairingCode?) -> Void
    @State private var typed = ""
    @State private var error: String?
    @FocusState private var focused: Bool

    private var trimmedCode: String { typed.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("tether1:…", text: $typed, axis: .vertical)
                        .accessibilityLabel(Text("Pairing code"))
                        .environment(\.layoutDirection, .leftToRight)
                        .lineLimit(1...4)
                        .keyboardType(.asciiCapable)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.body.monospaced())
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit(use)

                    HStack {
                        Text("Paste pairing code")
                            .accessibilityHidden(true)
                        Spacer()
                        PasteButton(payloadType: String.self) { items in
                            if let code = items.first { typed = code.trimmingCharacters(in: .whitespacesAndNewlines) }
                        }
                        .labelStyle(.iconOnly)
                        .accessibilityLabel(Text("Paste pairing code"))
                    }
                } footer: {
                    if let error {
                        Label(error, systemImage: "exclamationmark.circle").foregroundStyle(.orange)
                    } else {
                        Text("Paste a pairing link or type the code from Tether on your Android phone.")
                    }
                }
            }
            .navigationTitle("Enter Code Manually")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { done(nil) } }
                ToolbarItem(placement: .confirmationAction) { Button("Pair", action: use).disabled(trimmedCode.isEmpty) }
            }
            .onAppear { focused = true }
            .onChangeCompat(of: typed) { _ in error = nil }
        }
    }

    private func use() {
        if let code = PairingCode.parse(trimmedCode) { done(code) }
        else { error = String(localized: "That is not a Tether pairing code.") }
    }
}

private struct QRScanner: UIViewControllerRepresentable {
    enum State {
        case starting, ready, permissionDenied, unavailable
    }

    let onCode: (String) -> Void
    let onStateChange: (State) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        ScannerController()
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {
        controller.onCode = onCode
        controller.onStateChange = onStateChange
    }

    static func dismantleUIViewController(_ controller: ScannerController, coordinator: ()) {
        controller.onCode = nil
        controller.onStateChange = nil
        controller.stop()
    }

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        var onStateChange: ((State) -> Void)?
        private let session = AVCaptureSession()
        private let sessionQueue = DispatchQueue(label: "tether.qr-capture")
        private var preview: AVCaptureVideoPreviewLayer?
        private var visible = false
        private var last: String?

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            visible = true
            guard AVCaptureDevice.default(for: .video) != nil else {
                onStateChange?(.unavailable)
                return
            }
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                start()
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                    DispatchQueue.main.async {
                        guard let self, self.visible else { return }
                        if granted { self.start() }
                        else { self.onStateChange?(.permissionDenied) }
                    }
                }
            case .denied, .restricted:
                onStateChange?(.permissionDenied)
            @unknown default:
                onStateChange?(.unavailable)
            }
        }

        private func start() {
            if preview == nil {
                let output = AVCaptureMetadataOutput()
                guard let camera = AVCaptureDevice.default(for: .video),
                      let input = try? AVCaptureDeviceInput(device: camera),
                      session.canAddInput(input) else {
                    onStateChange?(.unavailable)
                    return
                }
                session.addInput(input)
                guard session.canAddOutput(output) else {
                    session.removeInput(input)
                    onStateChange?(.unavailable)
                    return
                }
                session.addOutput(output)
                guard output.availableMetadataObjectTypes.contains(.qr) else {
                    session.removeOutput(output)
                    session.removeInput(input)
                    onStateChange?(.unavailable)
                    return
                }
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
                let layer = AVCaptureVideoPreviewLayer(session: session)
                layer.videoGravity = .resizeAspectFill
                layer.frame = view.bounds
                view.layer.addSublayer(layer)
                preview = layer
            }
            sessionQueue.async { [weak self] in
                guard let self else { return }
                self.session.startRunning()
                let state: State = self.session.isRunning ? .ready : .unavailable
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.visible else { return }
                    self.onStateChange?(state)
                }
            }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.bounds
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            stop()
        }

        func stop() {
            visible = false
            let session = session
            sessionQueue.async { if session.isRunning { session.stopRunning() } }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard visible, let value = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue,
                  value != last else { return }
            last = value
            onCode?(value)
        }
    }
}
