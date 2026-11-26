import Perception
import SwiftUI
@_spi(TetherApp) import TetherCore

/// Full-screen call UI in the style of the iOS Phone app: caller and status on top, a 3×2 grid of
/// round controls, the red end button centred below. Actions go through CallKit so the system
/// call state (lock screen, Dynamic Island, AirPods buttons) stays in sync.
struct InCallView: View {
    @Environment(\.appModel) private var model
    #if DEBUG
    @State private var keypad = ProcessInfo.processInfo.arguments.contains("-TetherDemoKeypad")
    #else
    @State private var keypad = false
    #endif
    @State private var typed = ""

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        if let call = model.callScreen {
            ZStack {
                background(call)
                VStack(spacing: 0) {
                    header(call)
                        .padding(.top, 60)
                    Spacer()
                    if keypad {
                        KeypadPad(typed: $typed) { d in model.calls.dtmf(call.uuid, d) }
                            .transition(.opacity.combined(with: .scale(scale: 0.96)))
                    } else if call.state == "ringing" {
                        incomingControls(call)
                    } else {
                        controls(call)
                    }
                    Spacer()
                    bottomRow(call)
                        .padding(.bottom, 48)
                }
                .padding(.horizontal, 32)
            }
            .foregroundStyle(.white)
            .preferredColorScheme(.dark)
            .animation(.easeInOut(duration: 0.2), value: keypad)
            .onChangeCompat(of: call.uuid) { _ in keypad = false; typed = "" }
        }
    }

    private func background(_ call: CallScreen) -> some View {
        ZStack {
            Color.black
            if let img = photo(call) {
                // Like a contact poster: the caller's photo fills the screen, softened so the controls stay readable.
                GeometryReader { geo in
                    Image(uiImage: img).resizable().scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height).clipped()
                        .blur(radius: 28, opaque: true)
                }
                LinearGradient(colors: [.black.opacity(0.25), .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
            } else {
                LinearGradient(colors: [Color(red: 0.05, green: 0.22, blue: 0.21), .black], startPoint: .top, endPoint: .bottom)   // theme: ok, the accent darkened to black behind the call
                    .opacity(0.9)
            }
        }
        .ignoresSafeArea()
    }

    private func photo(_ call: CallScreen) -> UIImage? {
        guard let b64 = model.photo(for: call.number), let data = Data(base64Encoded: b64) else { return nil }
        return UIImage(data: data)
    }

    private func header(_ call: CallScreen) -> some View {
        VStack(spacing: 8) {
            HStack {
                Button { model.showCallScreen = false; model.callScreenDismissed = true } label: {
                    Image(systemName: "chevron.down").font(.system(size: 18, weight: .semibold))
                        .frame(width: 36, height: 36).background(.white.opacity(0.12), in: Circle())
                }
                .accessibilityLabel(Text("Hide call screen"))
                Spacer()
                if let sim = model.simName(call.sub) {
                    Label(sim, systemImage: "simcard").font(.footnote.weight(.medium)).opacity(0.7)
                }
            }
            if !keypad, let img = photo(call) {
                Image(uiImage: img).resizable().scaledToFill()
                    .frame(width: 112, height: 112).clipShape(Circle())
                    .shadow(color: .black.opacity(0.35), radius: 12, y: 4)
                    .padding(.top, 4).padding(.bottom, 6)
            }
            if keypad && !typed.isEmpty {
                Text(typed).font(.system(size: 34, weight: .light)).lineLimit(1).minimumScaleFactor(0.5)
                    .environment(\.layoutDirection, .leftToRight)
            } else {
                Text(call.name ?? model.formatted(call.number))
                    .font(.system(size: 34, weight: .regular))
                    .lineLimit(1).minimumScaleFactor(0.5)
            }
            CallStatusText(call: call)
                .font(.system(size: 17))
                .opacity(0.75)
            if let w = model.micWarning, call.state != "ended" {
                // The other side cannot hear this iPhone (#14): say it where the call is, not only in the Link tab.
                Label(Microphone.text(w), systemImage: "mic.slash.fill")
                    .font(.footnote.weight(.medium)).foregroundStyle(.yellow)
                    .multilineTextAlignment(.center).padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func controls(_ call: CallScreen) -> some View {
        let enabled = call.state != "ended"
        return Grid(horizontalSpacing: 26, verticalSpacing: 26) {
            GridRow {
                if call.routes.contains(where: { !$0.kind.isBuiltIn }) {
                    AudioRouteMenu(call: call) { model.calls.selectRoute($0) }
                } else {
                    RoundControl(icon: call.speaker ? "speaker.wave.3.fill" : "speaker.wave.2.fill", label: "Speaker", on: call.speaker) {
                        model.calls.setSpeaker(!call.speaker)
                    }
                }
                RoundControl(icon: call.held ? "pause.fill" : "pause", label: "Hold", on: call.held) {
                    model.calls.setHeld(call.uuid, !call.held)
                }
                .disabled(call.state != "active" && call.state != "holding")
                RoundControl(icon: call.muted ? "mic.slash.fill" : "mic.slash", label: "Mute", on: call.muted) {
                    model.calls.setMuted(call.uuid, !call.muted)
                }
            }
            GridRow {
                RoundControl(icon: "person.badge.plus", label: "Add", on: false) {}
                    .disabled(true).opacity(0.4)
                RoundControl(icon: "circle.grid.3x3.fill", label: "Keypad", on: false) { keypad = true }
                RoundControl(icon: "person.crop.circle", label: "Contacts", on: false) {
                    model.showCallScreen = false; model.callScreenDismissed = true; Router.shared.tab = .contacts
                }
            }
        }
        .disabled(!enabled)
    }

    private func incomingControls(_ call: CallScreen) -> some View {
        HStack {
            VStack(spacing: 10) {
                CallButton(color: .red, icon: "phone.down.fill") { model.calls.end(call.uuid) }
                Text("Decline").font(.footnote)
            }
            Spacer()
            VStack(spacing: 10) {
                CallButton(color: .green, icon: "phone.fill") { model.calls.answer(call.uuid) }
                Text("Accept").font(.footnote)
            }
        }
        .padding(.horizontal, 12)
    }

    @ViewBuilder private func bottomRow(_ call: CallScreen) -> some View {
        if call.state == "ringing" {
            EmptyView()
        } else {
            ZStack {
                CallButton(color: .red, icon: "phone.down.fill") { model.calls.end(call.uuid) }
                    .disabled(call.state == "ended")
                    .opacity(call.state == "ended" ? 0.5 : 1)
                if keypad {
                    HStack {
                        Spacer()
                        Button("Hide") { keypad = false }.font(.system(size: 17)).padding(.trailing, 8)
                    }
                }
            }
        }
    }
}

/// "calling…", "00:42", "on hold", "Call ended" – ticking once a second.
struct CallStatusText: View {
    let call: CallScreen

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            Text(status(at: ctx.date)).monospacedDigit()
        }
    }

    private func status(at now: Date) -> String {
        switch call.state {
        case "dialing", "connecting": String(localized: "calling…")
        case "ringing": String(localized: "Incoming call")
        case "holding": String(localized: "On hold")
        case "ended": String(localized: "Call ended")
        default:
            if let t = call.connectedAt {
                Duration.seconds(Int(now.timeIntervalSince(t))).formatted(.time(pattern: .minuteSecond))
            } else { "" }
        }
    }
}

/// The call's audio menu once a headset, car or other accessory is available (like the Phone app's "Audio"):
/// iPhone, Speaker and each accessory, with the current one checked. It follows routes chosen elsewhere.
struct AudioRouteMenu: View {
    let call: CallScreen
    let select: (String) -> Void
    @Environment(\.isEnabled) private var enabled

    static func icon(_ k: RouteKind) -> String {
        switch k {
        case .receiver: "iphone"
        case .speaker: "speaker.wave.3.fill"
        case .bluetooth: "headphones"
        case .car: "car.fill"
        case .wired: "headphones"
        case .usb: "cable.connector"
        case .airPlay: "airplayaudio"
        case .other: "speaker.wave.2.fill"
        }
    }

    var body: some View {
        let onAccessory = !call.route.isBuiltIn
        Menu {
            ForEach(call.routes) { r in
                Button { select(r.id) } label: {
                    if r.id == call.selectedRoute { Label(r.id == RouteOption.speakerID ? String(localized: "Speaker") : r.name, systemImage: "checkmark") }
                    else { Label(r.id == RouteOption.speakerID ? String(localized: "Speaker") : r.name, systemImage: Self.icon(r.kind)) }
                }
            }
        } label: {
            VStack(spacing: 8) {
                Image(systemName: Self.icon(call.route == .receiver ? .speaker : call.route))
                    .font(.system(size: 28, weight: .regular))
                    .frame(width: 78, height: 78)
                    .foregroundStyle(onAccessory || call.speaker ? Color.black : Color.white)
                    .background(onAccessory || call.speaker ? Color.white : Color.white.opacity(0.16), in: Circle())
                Text("Audio").font(.system(size: 13, weight: .medium)).foregroundStyle(.white)
            }
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityLabel(Text("Audio"))
    }
}

struct RoundControl: View {
    let icon: String
    let label: LocalizedStringKey
    let on: Bool
    let action: () -> Void
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 28, weight: .regular))
                    .frame(width: 78, height: 78)
                    .foregroundStyle(on ? Color.black : Color.white)
                    .background(on ? Color.white : Color.white.opacity(0.16), in: Circle())
                Text(label).font(.system(size: 13, weight: .medium))
            }
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

struct CallButton: View {
    let color: Color
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 32, weight: .medium))
                .foregroundStyle(.white)
                .frame(width: 78, height: 78)
                .background(color, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(icon.contains("down") ? "End" : icon.contains("xmark") ? "Decline" : "Answer"))
    }
}

/// DTMF keypad shown inside the call screen.
struct KeypadPad: View {
    @Binding var typed: String
    let send: (String) -> Void
    private let keys: [[(String, String)]] = [
        [("1", ""), ("2", "ABC"), ("3", "DEF")],
        [("4", "GHI"), ("5", "JKL"), ("6", "MNO")],
        [("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ")],
        [("*", ""), ("0", "+"), ("#", "")],
    ]

    var body: some View {
        VStack(spacing: 16) {
            ForEach(keys.indices, id: \.self) { r in
                HStack(spacing: 26) {
                    ForEach(keys[r], id: \.0) { k in
                        Button {
                            typed.append(k.0)
                            send(k.0)
                        } label: {
                            VStack(spacing: 0) {
                                Text(k.0).font(.system(size: 34, weight: .regular))
                                if !k.1.isEmpty { Text(k.1).font(.system(size: 10, weight: .semibold)).opacity(0.7) }
                            }
                            .frame(width: 78, height: 78)
                            .background(Color.white.opacity(0.16), in: Circle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .environment(\.layoutDirection, .leftToRight)
    }
}

/// Green strip on top of the app while a call runs with the call screen hidden.
struct ReturnToCallBanner: View {
    @Environment(\.appModel) private var model

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        if let call = model.callScreen, !model.showCallScreen, call.state != "ended" {
            Button {
                model.callScreenDismissed = false
                model.showCallScreen = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "phone.fill")
                    Text("Touch to return to call").font(.footnote.weight(.semibold))
                    Spacer()
                    CallStatusText(call: call).font(.footnote)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Color.green)
            }
            .buttonStyle(.plain)
        }
    }
}
