import Perception
import SwiftUI
import TetherCore

struct RecentsView: View {
    @Environment(\.appModel) private var model
    @State private var missedOnly = false
    @State private var search = ""
    @State private var showSearch = false
    @State private var blocking: String?
    @State private var editMode: EditMode = .inactive

    private func message(_ num: String) {
        Router.shared.tab = .messages
        if let thread = model.thread(for: num) {
            Router.shared.openThread = thread
        } else {
            Router.shared.compose(to: num)
        }
    }

    var body: some View {
        WithPerceptionTracking {
            callsNavigationStack
        }
    }

    private var callsNavigationStack: some View {
        NavigationStack {
            callsScreen
        }
    }

    private var callsScreen: some View {
        callsPresentedContent
            .navigationTitle("Calls")
            .environment(\.editMode, $editMode)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar { callsToolbar }
    }

    private var callsPresentedContent: some View {
        callsLifecycleContent
            .confirmationDialog(
                blocking.map { model.displayName($0) } ?? "",
                isPresented: Binding(
                    get: { blocking != nil },
                    set: { if !$0 { blocking = nil } }
                ),
                titleVisibility: .visible
            ) {
                if let number = blocking {
                    Button("Block Contact", role: .destructive) {
                        model.setBlocked(number, true)
                    }
                }
            } message: {
                Text("You will not receive phone calls or messages from people on the block list.")
            }
    }

    private var callsLifecycleContent: some View {
        callsSearchContent
            .onAppear { model.markRecentsSeen() }
            .onChangeCompat(of: model.data.recents.first?.id) { _ in
                if Router.shared.tab == .recents && model.appActive {
                    model.markRecentsSeen()
                }
            }
            .onChangeCompat(of: model.appActive) { active in
                if active && Router.shared.tab == .recents {
                    model.markRecentsSeen()
                }
            }
    }

    private var callsSearchContent: some View {
        Group {
            if #available(iOS 17.0, *) {
                callsBaseContent
                    .searchable(
                        text: $search,
                        isPresented: $showSearch,
                        placement: .navigationBarDrawer(displayMode: .automatic),
                        prompt: Text("Search name or number")
                    )
            } else {
                callsBaseContent
                    .searchable(
                        text: $search,
                        placement: .navigationBarDrawer(displayMode: .automatic),
                        prompt: Text("Search name or number")
                    )
            }
        }
        .overlay { emptySearchOverlay }
        .overlay { emptyRecentsOverlay }
    }

    private var callsBaseContent: some View {
        callsList
            .listStyle(.plain)
            .safeAreaInset(edge: .top, spacing: 8) {
                favouritesStrip
            }
            .scrollDismissesKeyboard(.immediately)
            .refreshable {
                model.requestSync()
            }
    }

    private var callsList: some View {
        List {
            ForEach(entries) { entry in
                callRow(entry)
            }
            .onDelete { offsets in
                let ids = offsets.map { entries[$0].id }
                model.deleteRecents(ids)
            }
        }
    }

    @ToolbarContentBuilder
    private var callsToolbar: some ToolbarContent {
        ToolbarItem(placement: .navigationBarLeading) {
            Button(editMode.isEditing ? "Done" : "Edit") {
                withAnimation { editMode = editMode.isEditing ? .inactive : .active }
            }
            .disabled(entries.isEmpty)
        }
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            Button { showSearch = true } label: {
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

    @ViewBuilder private var emptySearchOverlay: some View {
        if !search.isEmpty && entries.isEmpty {
            EmptyStateView(searchText: search)
        }
    }

    @ViewBuilder private var emptyRecentsOverlay: some View {
        if model.data.recents.isEmpty {
            EmptyStateView("No recent calls", systemImage: "clock")
        }
    }

    private func callRow(_ entry: CallEntry) -> some View {
        HStack(spacing: 12) {
            Avatar(
                name: entry.name ?? model.displayName(entry.num),
                photo: model.photo(for: entry.num),
                size: 46
            )
            callIdentity(entry)
            Spacer(minLength: 8)
            callActions(entry)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .listRowInsets(EdgeInsets(top: 5, leading: 14, bottom: 5, trailing: 14))
        .onTapGesture { model.dial(entry.num, sub: entry.sub) }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) { model.deleteRecents([entry.id]) } label: {
                Label("Delete", systemImage: "trash")
            }
            Button { message(entry.num) } label: {
                Label("Message", systemImage: "message")
            }
            .tint(.teal)
        }
        .contextMenu { callContextMenu(entry) }
    }

    private func callIdentity(_ entry: CallEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.name ?? model.displayName(entry.num))
                .font(.body.weight(.semibold))
                .foregroundStyle(isMissed(entry.type) ? Color.red : Color.primary)
                .lineLimit(1)
            HStack(spacing: 6) {
                Image(systemName: icon(entry.type))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(isMissed(entry.type) ? Color.red : Color.secondary)
                Text(model.simName(entry.sub) ?? String(localized: "Cellular Audio"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if entry.dur > 0 {
                    Text(Duration.seconds(entry.dur).formatted(.time(pattern: .minuteSecond)))
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
        }
    }

    private func callActions(_ entry: CallEntry) -> some View {
        HStack(spacing: 10) {
            Text(shortTime(entry.date))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .monospacedDigit()
            Button { model.dial(entry.num, sub: entry.sub) } label: {
                Image(systemName: "phone.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color("AccentColor"))
                    .frame(width: 44, height: 44)
                    .background(Color(.tertiarySystemFill), in: Circle())
            }
            .buttonStyle(.plain)
            .disabled(!model.linkState.isConnected)
            .accessibilityLabel(Text("Call \(model.displayName(entry.num))"))
        }
    }

    @ViewBuilder private func callContextMenu(_ entry: CallEntry) -> some View {
        Button { model.dial(entry.num, sub: entry.sub) } label: {
            Label("Call", systemImage: "phone")
        }
        Button { message(entry.num) } label: {
            Label("Message", systemImage: "message")
        }
        Button { UIPasteboard.general.string = entry.num } label: {
            Label("Copy", systemImage: "doc.on.doc")
        }
        if model.isBlocked(entry.num) {
            Button { model.setBlocked(entry.num, false) } label: {
                Label("Unblock this Caller", systemImage: "hand.raised.slash")
            }
        } else {
            Button(role: .destructive) { blocking = entry.num } label: {
                Label("Block this Caller", systemImage: "hand.raised")
            }
        }
        Button(role: .destructive) { model.deleteRecents([entry.id]) } label: {
            Label("Delete from Recents", systemImage: "trash")
        }
    }

    /// The first three starred contacts synced from the Android phone: three equal tall cards, name below.
    @ViewBuilder private var favouritesStrip: some View {
        if search.isEmpty && !model.favourites.isEmpty {
            HStack(alignment: .top, spacing: 16) {
                ForEach(Array(model.favourites.prefix(3))) { contact in
                    favouriteCard(contact)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            .padding(.bottom, 8)
        }
    }

    private func favouriteCard(_ contact: Contact) -> some View {
        let letter = String(contact.name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased()
        return Button {
            guard let phone = contact.phones.first else { return }
            model.dial(phone.n, sub: model.preferredSim(for: phone.n))
        } label: {
            VStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 34, style: .continuous)
                    .fill(favouriteGradient)
                    .aspectRatio(0.70, contentMode: .fit)
                    .overlay {
                        ZStack {
                            if let photo = model.photo(for: contact),
                               let data = Data(base64Encoded: photo), let img = UIImage(data: data) {
                                Image(uiImage: img).resizable().scaledToFill()
                                    .frame(width: 70, height: 70).clipShape(Circle())
                            } else {
                                Circle().fill(.white.opacity(0.08))
                                Text(letter)
                                    .font(.system(size: 44, weight: .regular, design: .serif))
                                    .foregroundStyle(.white)
                            }
                            Circle().strokeBorder(.white.opacity(0.30), lineWidth: 1)
                        }
                        .frame(width: 70, height: 70)
                    }
                    .shadow(color: Color(red: 0.05, green: 0.10, blue: 0.40).opacity(0.25), radius: 10, y: 6)
                HStack(spacing: 5) {
                    Image(systemName: "phone.fill").font(.caption)
                    Text(contact.name).font(.subheadline).lineLimit(1)
                }
                .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .disabled(contact.phones.isEmpty || !model.linkState.isConnected)
    }

    private var favouriteGradient: LinearGradient {
        LinearGradient(
            colors: [
                Color(red: 0.27, green: 0.40, blue: 0.72),
                Color(red: 0.12, green: 0.22, blue: 0.55),
                Color(red: 0.03, green: 0.04, blue: 0.28)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    private var entries: [CallEntry] {
        var list = model.data.recents
        if missedOnly { list = list.filter { isMissed($0.type) } }
        if !search.isEmpty {
            list = list.filter { entry in
                SearchText.matches(
                    search,
                    names: [entry.name ?? "", model.name(for: entry.num) ?? ""],
                    numbers: [entry.num]
                )
            }
        }
        return Array(list.prefix(500))
    }

    private func isMissed(_ type: Int) -> Bool { type == 3 || type == 5 }

    private func icon(_ type: Int) -> String {
        switch type {
        case 1: "arrow.down.left"
        case 2: "arrow.up.right"
        case 3, 5: "arrow.down.left"
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
