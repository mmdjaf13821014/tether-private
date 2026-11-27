import Perception
import SwiftUI
import TetherCore

struct RootView: View {
    @Environment(\.appModel) private var model
    @Environment(\.router) private var router
    @AppStorage("tether.appearance") private var appearanceMode = "system"

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        @Perception.Bindable var router = router
        @Perception.Bindable var model = model
        VStack(spacing: 0) {
            ReturnToCallBanner()
            RemoteCallBanner()
            TabView(selection: $router.tab) {
                RecentsView()
                    .tabItem { Label("Recents", systemImage: "clock") }
                    .badge(model.missedCount)
                    .tag(Router.Tab.recents)
                ContactsView()
                    .tabItem { Label("Contacts", systemImage: "person.crop.circle") }
                    .tag(Router.Tab.contacts)
                KeypadView()
                    .tabItem { Label("Keypad", systemImage: "circle.grid.3x3.fill") }
                    .tag(Router.Tab.keypad)
                MessagesView()
                    .tabItem { Label("Messages", systemImage: "message") }
                    .badge(model.unreadTotal)
                    .tag(Router.Tab.messages)
                LinkView()
                    .tabItem { Label("Link", systemImage: "antenna.radiowaves.left.and.right") }
                    .tag(Router.Tab.link)
            }
            // Keep the native iOS 26 tab bar visible so the system renders its Liquid Glass surface.
            .toolbarBackground(.visible, for: .tabBar)
        }
        .overlay(alignment: .bottom) {
            if let err = model.lastError {
                Text(err)
                    .font(.footnote)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(.thinMaterial, in: Capsule())
                    .tetherLiquidGlass(cornerRadius: 24)
                    .padding(.bottom, 64)
                    .onTapGesture { model.lastError = nil }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .overlay(alignment: .bottom) {
            if let n = model.notice {
                Label(n, systemImage: "checkmark.circle.fill")
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 64)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .alert(item: $router.dialRequest) { req in
            let linked = model.linkState.isConnected
            let who = model.name(for: req.number)
            return Alert(
                title: Text("Call \(who ?? model.formatted(req.number))?"),
                message: Text(linked
                    ? (who == nil ? "The call goes through your Android phone." : "\(model.formatted(req.number)) · through your Android phone.")
                    : "The Android phone is not connected. Keep it nearby and open the Link tab, then call from the Keypad."),
                primaryButton: .default(Text(linked ? "Call" : "Show in Keypad")) {
                    if linked { model.dial(req.number, sub: nil) } else { router.keypadNumber = req.number }
                },
                secondaryButton: .cancel { router.keypadNumber = req.number })
        }
        .alert(item: $router.pairRequest) { req in
            let name = req.code.deviceName.isEmpty ? String(localized: "the Android phone") : req.code.deviceName
            return Alert(
                title: Text("Pair with \(name)?"),
                message: Text(model.isPaired
                    ? "This replaces the current pairing and removes the synced data from this iPhone."
                    : "Calls, texts, contacts and call history of that phone will come to this iPhone over Bluetooth."),
                primaryButton: .default(Text("Pair")) { model.pair(req.code) },
                secondaryButton: .cancel())
        }
        // First launch: nothing to show until paired, so start on the Link tab with the setup steps.
        .onAppear {
            if !model.isPaired && router.pairRequest == nil && router.dialRequest == nil { router.tab = .link }
            #if DEBUG
            // -TetherOpenURL <url>: open a link as if from outside (screenshots of the pairing question).
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-TetherOpenURL"), i + 1 < args.count, let u = URL(string: args[i + 1]) { router.open(url: u) }
            #endif
        }
        .animation(.default, value: model.lastError)
        .animation(.default, value: model.notice)
        .fullScreenCover(isPresented: Binding(get: { model.showCallScreen && model.callScreen != nil },
                                              set: { model.showCallScreen = $0 })) {
            // Presented content does not always inherit environment objects: pass them explicitly.
            InCallView().environment(\.appModel, AppModel.shared).environment(\.router, Router.shared)
        }
        .tint(Color("AccentColor"))
        .preferredColorScheme(appearanceMode == "dark" ? .dark : (appearanceMode == "light" ? .light : nil))
        .task {
            if !model.isPaired { router.tab = .link }
        }
    }
}

/// A call whose audio is on the other device (answered or dialed there): one tap moves it here.
struct RemoteCallBanner: View {
    @Environment(\.appModel) private var model

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        ForEach(model.remoteCalls, id: \.cid) { c in
            HStack(spacing: 8) {
                Image(systemName: "arrow.left.arrow.right")
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.displayName(c.num)).font(.footnote.weight(.semibold)).lineLimit(1)
                    Text("On \(c.at ?? String(localized: "another device"))").font(.caption2).opacity(0.85)
                }
                Spacer()
                Button {
                    model.calls.take(c.cid)
                } label: {
                    Text("Move here").font(.footnote.weight(.semibold))
                        .padding(.horizontal, 12).padding(.vertical, 5)
                        .background(.white.opacity(0.22), in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14).padding(.vertical, 7)
            .background(Color.indigo)
        }
    }
}

/// Thin strip at the top: link state, battery and carrier of the Android phone.
struct StatusStrip: View {
    @Environment(\.appModel) private var model
    @AppStorage("tether.appearance") private var appearanceMode = "system"

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        HStack(spacing: 10) {
            Circle().fill(dotColor).frame(width: 8, height: 8)
            Text(stateText).font(.caption.weight(.semibold))
            Spacer(minLength: 4)
            if let s = model.status, model.linkState.isConnected || AppModel.demoMode {
                ForEach(s.sims) { sim in SimStatus(sim: sim, showName: s.sims.count > 1) }
                HStack(spacing: 2) {
                    Image(systemName: s.charging ? "battery.100.bolt" : batteryIcon(s.battery))
                    Text("\(s.battery)%").font(.caption2.monospacedDigit())
                }
                .foregroundStyle(s.battery <= 15 && !s.charging ? .red : .secondary)
            }
            Menu {
                Picker("Appearance", selection: $appearanceMode) {
                    Label("System", systemImage: "iphone").tag("system")
                    Label("Light", systemImage: "sun.max").tag("light")
                    Label("Dark", systemImage: "moon").tag("dark")
                }
            } label: {
                Image(systemName: appearanceMode == "dark" ? "moon.fill" : (appearanceMode == "light" ? "sun.max.fill" : "circle.lefthalf.filled"))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color("AccentColor"))
                    .frame(width: 28, height: 28)
                    .contentShape(Circle())
            }
            .accessibilityLabel("Appearance")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(stripBackground.opacity(0.48), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .tetherLiquidGlass(cornerRadius: 14)
        .padding(.horizontal, 8)
        .padding(.top, 4)
    }

    private var dotColor: Color {
        switch model.linkState {
        case .connected: .green
        case .connecting, .handshaking: .orange
        default: model.isPaired ? .red : .gray
        }
    }

    private var stripBackground: Color {
        if model.isPaired, !model.linkState.isConnected { return Color.red.opacity(0.12) }
        return Color(.secondarySystemBackground)
    }

    private var stateText: LocalizedStringKey {
        switch model.linkState {
        case .connected: "Connected"
        case .connecting, .handshaking: "Connecting…"
        case .poweredOff: "Bluetooth is off"
        case .unauthorized: "Bluetooth not allowed"
        case .unpaired: "Not paired"
        case .searching: model.isPaired ? "Out of reach" : "Not paired"
        }
    }

    private func batteryIcon(_ p: Int) -> String {
        switch p {
        case ..<13: "battery.0"
        case ..<38: "battery.25"
        case ..<63: "battery.50"
        case ..<88: "battery.75"
        default: "battery.100"
        }
    }
}

/// One SIM in the status strip: bars, network type (5G / LTE+ / H+ …), roaming, or "No service".
struct SimStatus: View {
    let sim: SimInfo
    var showName = true

    var body: some View {
        HStack(spacing: 3) {
            if showName { Text(sim.name).font(.caption2).lineLimit(1) }
            if sim.hasService {
                SignalBars(level: sim.signal)
                if let n = sim.net { Text(n).font(.caption2.weight(.semibold)) }
                if sim.roaming == true { Text("R").font(.caption2.weight(.bold)).foregroundStyle(.orange) }
            } else {
                Text(sim.service == "emergency" ? "SOS" : String(localized: "No service")).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct SignalBars: View {
    let level: Int
    var body: some View {
        HStack(alignment: .bottom, spacing: 1) {
            ForEach(0..<4, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i < level ? Color.primary : Color.secondary.opacity(0.3))
                    .frame(width: 3, height: CGFloat(4 + i * 2))
            }
        }
        .accessibilityLabel(Text("Signal \(level) of 4"))
    }
}

struct Avatar: View {
    let name: String
    var photo: String? = nil
    var size: CGFloat = 40

    /// Initials are shared by every avatar in the app; real contact photos always take precedence.
    private var initials: String {
        let words = name.split(whereSeparator: { $0.isWhitespace })
        guard let first = words.first, let firstCharacter = first.first else { return "?" }
        if words.count > 1, let lastCharacter = words.last?.first {
            return String([firstCharacter, lastCharacter]).uppercased()
        }
        return String(firstCharacter).uppercased()
    }

    var body: some View {
        Group {
            if let photo, let data = Data(base64Encoded: photo), let img = UIImage(data: data) {
                Image(uiImage: img).resizable().scaledToFill()
            } else {
                ZStack {
                    Circle().fill(LinearGradient(
                        colors: [Color(red: 0.69, green: 0.76, blue: 0.89),
                                 Color(red: 0.45, green: 0.48, blue: 0.73)],
                        startPoint: .top, endPoint: .bottom))
                    Text(initials)
                        .font(.system(size: size * (initials.count > 1 ? 0.40 : 0.46),
                                      weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityLabel(Text(name))
    }
}
/// SIM choice for a call or a conversation.
struct SimPicker: View {
    @Environment(\.appModel) private var model
    @Binding var sub: Int?

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        if model.sims.count > 1 {
            Menu {
                ForEach(model.sims) { sim in
                    Button {
                        sub = sim.sub
                    } label: {
                        Label("\(sim.name) · \(sim.carrier)", systemImage: sub == sim.sub ? "checkmark" : "simcard")
                    }
                }
                .onAppear { hideKeyboard() }   // the menu must not open under / over the keyboard
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "simcard")
                    Text(label).font(.caption.weight(.medium))
                }
                .padding(.horizontal, 10).padding(.vertical, 6)
                .tetherLiquidGlass(cornerRadius: 18)
            }
        }
    }

    private var label: String {
        guard let sim = model.sims.first(where: { $0.sub == sub }) else { return String(localized: "SIM") }
        return sim.name.isEmpty ? "SIM \(sim.slot + 1)" : sim.name
    }
}

extension Date {
    init(millis: Int64) { self.init(timeIntervalSince1970: Double(millis) / 1000) }
}

func shortTime(_ ms: Int64) -> String {
    let d = Date(millis: ms)
    if Calendar.current.isDateInToday(d) { return d.formatted(date: .omitted, time: .shortened) }
    if Calendar.current.isDateInYesterday(d) { return String(localized: "Yesterday") }
    return d.formatted(.dateTime.day().month(.abbreviated))
}


/// Puts the keyboard away (before a menu, sheet or dialog opens over a text field).
@MainActor func hideKeyboard() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}

extension View {
    /// A "Done" button above number / phone pads, which have no return key to close them.
    func keyboardDoneButton() -> some View {
        toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { hideKeyboard() }.fontWeight(.semibold)
            }
        }
    }
}
