import Perception
import SwiftUI
import TetherCore

struct RecentsView: View {
    @Environment(\.appModel) private var model
    @State private var missedOnly = false
    @State private var search = ""
    @State private var showSearch = false
    @State private var blocking: String?
    @State private var clearing = false

    private func message(_ num: String) {
        Router.shared.tab = .messages
        if let t = model.thread(for: num) { Router.shared.openThread = t } else { Router.shared.compose(to: num) }
    }

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        NavigationStack {
            List(entries) { e in
                HStack(spacing: 12) {
                    Avatar(name: e.name ?? model.displayName(e.num),
                           photo: model.photo(for: e.num), size: 46)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(e.name ?? model.displayName(e.num))
                            .font(.body.weight(.semibold))
                            .foregroundStyle(e.type == 3 || e.type == 5 ? .red : .primary)
                            .lineLimit(1)
                        HStack(spacing: 6) {
                            Image(systemName: icon(e.type))
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(e.type == 3 || e.type == 5 ? .red : .secondary)
                            Text(model.formatted(e.num))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            if let sim = model.simName(e.sub) {
                                Text(sim)
                                    .font(.caption2)
                                    .padding(.horizontal, 5)
                                    .background(Color(.tertiarySystemFill), in: Capsule())
                            }
                            if e.dur > 0 {
                                Text(Duration.seconds(e.dur).formatted(.time(pattern: .minuteSecond)))
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                    }
                    Spacer(minLength: 8)
                    VStack(alignment: .trailing, spacing: 9) {
                        Text(shortTime(e.date))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                        Button { model.dial(e.num, sub: e.sub) } label: {
                            Image(systemName: "phone.fill")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Color("AccentColor"))
                                .frame(width: 32, height: 32)
                                .background(Color(.tertiarySystemFill), in: Circle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!model.linkState.isConnected)
                        .accessibilityLabel(Text("Call \\(model.displayName(e.num))"))
                    }
                }
                .padding(.vertical, 6)
                .contentShape(Rectangle())
                .listRowInsets(EdgeInsets(top: 5, leading: 14, bottom: 5, trailing: 14))
                .onTapGesture { model.dial(e.num, sub: e.sub) }
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    // Like Phone: a swipe deletes the entry at once (from the Android phone and every device).
                    Button(role: .destructive) { model.deleteRecents([e.id]) } label: { Label("Delete", systemImage: "trash") }
                    Button { message(e.num) } label: { Label("Message", systemImage: "message") }.tint(.teal)
                }
                .contextMenu {
                    Button { model.dial(e.num, sub: e.sub) } label: { Label("Call", systemImage: "phone") }
                    Button { message(e.num) } label: { Label("Message", systemImage: "message") }
                    Button { UIPasteboard.general.string = e.num } label: { Label("Copy", systemImage: "doc.on.doc") }
                    if model.isBlocked(e.num) {
                        Button { model.setBlocked(e.num, false) } label: { Label("Unblock this Caller", systemImage: "hand.raised.slash") }
                    } else {
                        Button(role: .destructive) { blocking = e.num } label: { Label("Block this Caller", systemImage: "hand.raised") }
                    }
                    Button(role: .destructive) { model.deleteRecents([e.id]) } label: { Label("Delete from Recents", systemImage: "trash") }
                }
            }
            .listStyle(.plain)
            .safeAreaInset(edge: .top, spacing: 8) { favouritesStrip }
            .scrollDismissesKeyboard(.immediately)
            .refreshable { model.requestSync() }
            .onAppear { model.markRecentsSeen() }
            // Seen only while the user can see it: a call missed in the background keeps its badge.
            .onChangeCompat(of: model.data.recents.first?.id) { _ in if Router.shared.tab == .recents && model.appActive { model.markRecentsSeen() } }
            .onChangeCompat(of: model.appActive) { on in if on && Router.shared.tab == .recents { model.markRecentsSeen() } }
            .searchable(text: $search, isPresented: $showSearch, placement: .navigationBarDrawer(displayMode: .automatic), prompt: Text("Search name or number"))
            .overlay {
                if !search.isEmpty && entries.isEmpty { EmptyStateView(searchText: search) }
            }
            .overlay {
                if model.data.recents.isEmpty { EmptyStateView("No recent calls", systemImage: "clock") }
            }
            .navigationTitle("Calls")
            .confirmationDialog(blocking.map { model.displayName($0) } ?? "",
                                isPresented: Binding(get: { blocking != nil }, set: { if !$0 { blocking = nil } }), titleVisibility: .visible) {
                if let n = blocking { Button("Block Contact", role: .destructive) { model.setBlocked(n, true) } }
            } message: {
                Text("You will not receive phone calls or messages from people on the block list.")
            }
            .confirmationDialog("Clear All Recents", isPresented: $clearing, titleVisibility: .hidden) {
                Button("Clear All Recents", role: .destructive) { model.clearRecents() }
            } message: {
                Text("The call history will be deleted from your Android phone and all your devices.")
            }
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Clear") { clearing = true }
                        .disabled(model.data.recents.isEmpty || !model.linkState.isConnected)
                }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button {
                        showSearch = true
                    } label: {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 34, height: 34)
                            .background(.thinMaterial, in: Circle())
                            .overlay(Circle().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.7))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Search Calls"))

                    Menu {
                        Picker("Show Calls", selection: $missedOnly) {
                            Text("All").tag(false)
                            Text("Missed").tag(true)
                        }
                    } label: {
                        Image(systemName: "line.3.horizontal.decrease")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 34, height: 34)
                            .background(.thinMaterial, in: Circle())
                            .overlay(Circle().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.7))
                    }
                    .accessibilityLabel(Text("Filter Calls"))
                }

            }
        }
    }

    /// The first three starred contacts synced from the Android phone, matching the reference Calls screen.
    @ViewBuilder private var favouritesStrip: some View {
        if !search.isEmpty {
            EmptyView()
        } else if !model.favourites.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(Array(model.favourites.prefix(3))) { contact in
                        Button {
                            if let phone = contact.phones.first {
                                model.dial(phone.n, sub: model.preferredSim(for: phone.n))
                            }
                        } label: {
                            VStack(spacing: 9) {
                                Avatar(name: contact.name, photo: model.photo(for: contact), size: 58)
                                    .padding(5)
                                    .background(.white.opacity(0.16), in: Circle())
                                Text(contact.name)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                                    .frame(maxWidth: .infinity)
                                Image(systemName: "phone.fill")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .frame(width: 34, height: 34)
                                    .background(.white.opacity(0.18), in: Circle())
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 13)
                            .frame(width: 142, height: 154)
                            .background(
                                LinearGradient(colors: [Color(red: 0.20, green: 0.52, blue: 0.96),
                                                        Color(red: 0.08, green: 0.17, blue: 0.39)],
                                               startPoint: .topLeading, endPoint: .bottomTrailing),
                                in: RoundedRectangle(cornerRadius: 22, style: .continuous)
                            )
                            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .strokeBorder(.white.opacity(0.16), lineWidth: 1))
                            .shadow(color: .blue.opacity(0.16), radius: 8, y: 4)
                        }
                        .buttonStyle(.plain)
                        .disabled(contact.phones.isEmpty || !model.linkState.isConnected)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 4)
            }
            .background(.clear)
        }
    }

    private var entries: [CallEntry] {
        var list = model.data.recents
        if missedOnly { list = list.filter { $0.type == 3 || $0.type == 5 } }
        if !search.isEmpty {
            list = list.filter { e in SearchText.matches(search, names: [e.name ?? "", model.name(for: e.num) ?? ""], numbers: [e.num]) }
        }
        return Array(list.prefix(500))
    }

    private func icon(_ type: Int) -> String {
        switch type {
        case 1: "phone.arrow.down.left"
        case 2: "phone.arrow.up.right"
        case 3, 5: "phone.down"
        case 6: "nosign"
        default: "phone"
        }
    }
}

struct KeypadView: View {
    @Environment(\.appModel) private var model
    @Environment(\.router) private var router
    @State private var number = ""
    @State private var longPressedZero = false
    @State private var sub: Int?

    private let keys: [[(String, String)]] = [
        [("1", ""), ("2", "ABC"), ("3", "DEF")],
        [("4", "GHI"), ("5", "JKL"), ("6", "MNO")],
        [("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ")],
        [("*", ""), ("0", "+"), ("#", "")],
    ]

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        VStack(spacing: 18) {
            Spacer()
            Text(number.isEmpty ? " " : model.formatted(number))
                .font(.system(size: 36, weight: .regular, design: .rounded))
                .lineLimit(1).minimumScaleFactor(0.5)
                .environment(\.layoutDirection, .leftToRight)
                .contextMenu {
                    Button { if let s = UIPasteboard.general.string { number = PhoneNumber.digits(s) } } label: {
                        Label("Paste", systemImage: "doc.on.clipboard")
                    }
                }
            if let name = model.name(for: number), !number.isEmpty {
                Text(name).font(.subheadline).foregroundStyle(.secondary)
            } else {
                Button { if let s = UIPasteboard.general.string { number = PhoneNumber.digits(s) } } label: {
                    Label("Paste", systemImage: "doc.on.clipboard").font(.caption)
                }
            }
            SimPicker(sub: $sub)
            VStack(spacing: 14) {
                ForEach(keys.indices, id: \.self) { r in
                    HStack(spacing: 24) {
                        ForEach(keys[r], id: \.0) { k in
                            Button { if k.0 == "0" && longPressedZero { longPressedZero = false } else { longPressedZero = false; number.append(k.0) } } label: {
                                VStack(spacing: 0) {
                                    Text(k.0)
                                        .font(.system(size: 32, weight: .regular, design: .rounded))
                                        .frame(maxWidth: .infinity, alignment: .center)
                                    if !k.1.isEmpty {
                                        Text(k.1)
                                            .font(.system(size: 9, weight: .semibold))
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                 .frame(width: 76, height: 76, alignment: .center)
                                .contentShape(Circle())
                                .tetherLiquidGlassCircle()
                            }
                            .buttonStyle(.plain)
                            // Hold 0 for "+" (the tap that ends the hold must not add a 0 as well).
                            .simultaneousGesture(LongPressGesture().onEnded { _ in if k.0 == "0" { number.append("+"); longPressedZero = true } })
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .center)
                }
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 8)
            .environment(\.layoutDirection, .leftToRight)
            HStack(spacing: 24) {
                Color.clear.frame(width: 76, height: 76)
                Button { model.dial(number, sub: sub) } label: {
                    Image(systemName: "phone.fill")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(.white)
                        .accessibilityLabel(Text("Call"))
                        .frame(width: 76, height: 76)
                        .tetherLiquidGlassCircle(tint: .green)
                }
                .disabled(number.isEmpty || !model.linkState.isConnected)
                Button {
                    guard !number.isEmpty else { return }
                    number.removeLast()
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                } label: {
                    Image(systemName: "delete.left")
                        .font(.system(size: 23, weight: .medium))
                        .frame(width: 76, height: 76)
                        .contentShape(Circle())
                        .tetherLiquidGlassCircle()
                        .accessibilityLabel(Text("Delete"))
                }
                .buttonStyle(.plain)
                .opacity(number.isEmpty ? 0 : 1)
            }
            .environment(\.layoutDirection, .leftToRight)
            Spacer()
        }
        .onAppear {
            if sub == nil { sub = model.status?.defaultSub }
            #if DEBUG
            // -TetherDemoKeypad <digits>: a typed number, to check the as-you-type grouping.
            let args = ProcessInfo.processInfo.arguments
            if number.isEmpty, let i = args.firstIndex(of: "-TetherDemoKeypad"), i + 1 < args.count { number = args[i + 1] }
            #endif
            takeRouted()
        }
        .onChangeCompat(of: router.keypadNumber) { _ in takeRouted() }
    }

    /// A number from a link the owner did not call yet (cancelled, or the Android phone was not connected).
    private func takeRouted() {
        guard let n = router.keypadNumber else { return }
        number = n
        router.keypadNumber = nil
    }
}
