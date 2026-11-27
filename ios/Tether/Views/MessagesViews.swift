import Perception
import SwiftUI
import TetherCore

struct MessagesView: View {
    @Environment(\.appModel) private var model
    @Environment(\.router) private var router
    @State private var search = ""
    @State private var path: [Int64] = []
    @State private var composing = false
    @State private var composeTo = ""
    @State private var deleting: Conversation?
    @State private var blocking: String?
    @State private var senderFilter: SenderFilter = .all

    private enum SenderFilter: String {
        case all, known, unknown
    }

    private var filteredConversations: [Conversation] {
        model.conversations.filter { conversation in
            switch senderFilter {
            case .all: true
            case .known: model.name(for: conversation.address) != nil
            case .unknown: model.name(for: conversation.address) == nil
            }
        }
    }

    @ViewBuilder private func muteButton(_ c: Conversation) -> some View {
        let muted = model.isMuted(c.thread)
        Button { model.setMuted(c.thread, !muted) } label: {
            Label(muted ? "Show Alerts" : "Hide Alerts", systemImage: muted ? "bell" : "bell.slash")
        }
    }

    @ViewBuilder private func conversationMenu(_ c: Conversation) -> some View {
        muteButton(c)
        if model.isBlocked(c.address) {
            Button { model.setBlocked(c.address, false) } label: { Label("Unblock", systemImage: "hand.raised.slash") }
        } else {
            Button(role: .destructive) { blocking = c.address } label: { Label("Block", systemImage: "hand.raised") }
        }
        Button(role: .destructive) { deleting = c } label: { Label("Delete", systemImage: "trash") }
    }

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        NavigationStack(path: $path) {
            List {
                if !search.isEmpty {
                    let hits = searchHits
                    if hits.isEmpty { Text("No results").foregroundStyle(.secondary) }
                    ForEach(hits) { m in
                        NavigationLink(value: m.thread) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(model.displayName(m.addr)).font(.subheadline.weight(.semibold))
                                Text(MessageText.plain(m.body)).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                            }
                        }
                    }
                } else {
                    ForEach(filteredConversations) { c in
                        NavigationLink(value: c.thread) { ConversationRow(conversation: c) }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) { deleting = c } label: { Label("Delete", systemImage: "trash") }
                                muteButton(c).tint(.indigo)
                            }
                            .contextMenu { conversationMenu(c) }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if model.conversations.isEmpty && search.isEmpty {
                    EmptyStateView(model.syncing ? "Syncing…" : "No messages",
                                           systemImage: "message",
                                           description: Text(model.isPaired ? "" : "Pair with your Android phone in the Link tab."))
                } else if filteredConversations.isEmpty && search.isEmpty {
                    EmptyStateView(senderFilter == .unknown ? "No unknown senders" : "No saved contacts",
                                   systemImage: "person.crop.circle")
                }
            }
            .confirmationDialog(deleting.map { model.displayName($0.address) } ?? "",
                                isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                if let c = deleting {
                    Button("Delete Conversation", role: .destructive) { model.deleteConversation(c.thread) }
                }
            } message: {
                Text("This conversation will be deleted from your Android phone and all your devices.")
            }
            .confirmationDialog(blocking.map { model.displayName($0) } ?? "",
                                isPresented: Binding(get: { blocking != nil }, set: { if !$0 { blocking = nil } }), titleVisibility: .visible) {
                if let n = blocking {
                    Button("Block Contact", role: .destructive) { model.setBlocked(n, true) }
                }
            } message: {
                Text("You will not receive phone calls or messages from people on the block list.")
            }
            .searchable(text: $search, prompt: Text("Search messages"))
            .scrollDismissesKeyboard(.immediately)
            .refreshable { model.requestSync() }
            .navigationTitle("Messages")
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Menu {
                        Picker("Messages", selection: $senderFilter) {
                            Text("All Messages").tag(SenderFilter.all)
                            Text("Known Senders").tag(SenderFilter.known)
                            Text("Unknown Senders").tag(SenderFilter.unknown)
                        }
                    } label: {
                        Label("Filters", systemImage: "line.3.horizontal.decrease.circle")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { hideKeyboard(); composing = true } label: { Image(systemName: "square.and.pencil") }
                        .disabled(!model.linkState.isConnected)
                }
            }
            .navigationDestination(for: Int64.self) { ThreadView(thread: $0) }
            .sheet(isPresented: $composing, onDismiss: { composeTo = "" }) {
                ComposeView(initialTo: composeTo) { thread in
                    composing = false
                    if let thread { path = [thread] }
                }
                .environment(\.appModel, AppModel.shared).environment(\.router, Router.shared)
            }
            .onChangeCompat(of: router.openThread) { t in
                if let t { path = [t]; router.openThread = nil }
            }
            // "Send message" on a number inside a bubble: its conversation, or a new one.
            .onChangeCompat(of: router.composeTo) { n in
                guard let n else { return }
                router.composeTo = nil
                if let t = model.thread(for: n) { path = [t] } else { composeTo = n; path = []; composing = true }
            }
            // A notification tapped before this tab was ever shown sets the thread before we exist.
            .onAppear {
                if let t = router.openThread { path = [t]; router.openThread = nil }
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("-TetherDemoCompose") { composing = true }   // keyboard screenshots
                #endif
            }
        }
        // Keep the push/pop motion short and consistent; the tab bar is explicitly restored on the root.
        .animation(.easeInOut(duration: 0.18), value: path)
    }

    private var searchHits: [Sms] {
        let q = search.lowercased()
        _ = q
        return model.data.sms.filter { SearchText.matches(search, names: [$0.body, model.name(for: $0.addr) ?? ""], numbers: [$0.addr]) }
            .sorted { $0.date > $1.date }.prefix(200).map { $0 }
    }
}

struct ConversationRow: View {
    @Environment(\.appModel) private var model
    let conversation: Conversation

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        HStack(spacing: 9) {
            Circle()
                .fill(conversation.unread > 0 ? Color.blue : Color.clear)
                .frame(width: 7, height: 7)
            MessageAvatar(name: model.displayName(conversation.address),
                          photo: model.photo(for: conversation.address),
                          isKnown: model.name(for: conversation.address) != nil, size: 44)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 5) {
                    Text(model.displayName(conversation.address))
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    if model.isMuted(conversation.thread) {
                        Image(systemName: "moon.fill").font(.caption2).foregroundStyle(.secondary)
                            .accessibilityLabel(Text("Alerts hidden"))
                    }
                    if model.isBlocked(conversation.address) {
                        Image(systemName: "hand.raised.fill").font(.caption2).foregroundStyle(.secondary)
                            .accessibilityLabel(Text("Blocked"))
                    }
                    Spacer(minLength: 4)
                    Text(shortTime(conversation.last.date))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Text(MessageText.plain(conversation.last.body))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .padding(.vertical, 5)
        .contentShape(Rectangle())
        .listRowInsets(EdgeInsets(top: 7, leading: 10, bottom: 7, trailing: 14))
        .alignmentGuide(.listRowSeparatorLeading) { _ in 70 }
    }
}

struct ThreadView: View {
    @Environment(\.appModel) private var model
    let thread: Int64
    @State private var text = ""
    @State private var sub: Int?
    @State private var confirmDelete = false
    @State private var confirmBlock = false
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    private var address: String { model.messages(thread: thread).last?.addr ?? "" }

    /// One row of the transcript: the message plus what Messages derives from its neighbours.
    private struct Row: Identifiable {
        let message: Sms
        let header: String?      // centred day/time line above the bubble
        let tail: Bool           // last bubble of a run from the same side
        let status: String?      // under the newest outgoing bubble
        let failed: Bool
        var id: Int64 { message.id }
    }

    private func rows(_ msgs: [Sms]) -> [Row] {
        var out: [Row] = []
        let lastMine = msgs.last(where: { !$0.incoming })?.id
        for (i, m) in msgs.enumerated() {
            let prev = i > 0 ? msgs[i - 1] : nil
            let next = i + 1 < msgs.count ? msgs[i + 1] : nil
            let gap: Int64 = 15 * 60_000
            // Keep carrier / SIM metadata out of the transcript, matching the compact native Messages layout.
            let header = (prev == nil || m.date - prev!.date > gap)
                ? transcriptTime(m.date) : nil
            let tail = next == nil || next!.incoming != m.incoming || next!.date - m.date > gap
            var status: String?
            if m.box == 5 { status = String(localized: "Not Delivered") }
            else if m.box == 4 || m.box == 6 { status = String(localized: "Sending…") }

            out.append(Row(message: m, header: header, tail: tail, status: status, failed: m.box == 5))
        }
        return out
    }

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        let msgs = model.messages(thread: thread)
        let name = model.displayName(address)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(rows(msgs)) { r in
                        if let h = r.header {
                            Text(h).font(.caption2.weight(.medium)).foregroundStyle(.secondary)
                                .padding(.top, 8).padding(.bottom, 4)
                        }
                        Bubble(message: r.message, tail: r.tail)
                            .padding(.bottom, r.tail ? 4 : 0)
                            .id(r.message.id)
                        if let st = r.status {
                            Text(st).font(.caption2.weight(r.failed ? .semibold : .regular))
                                .foregroundStyle(r.failed ? Color.red : Color.secondary)
                                .frame(maxWidth: .infinity, alignment: .trailing)
                                .padding(.trailing, 6).padding(.top, -4).padding(.bottom, 8)
                        }
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
            }
            .bottomAnchoredCompat(proxy, last: msgs.last?.id)
            .scrollDismissesKeyboard(.interactively)
            .onChangeCompat(of: msgs.count) { _ in
                if let last = msgs.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                if model.appActive { model.markRead(thread: thread) }
            }
        }
        // Keep the conversation identity fixed above the transcript, like iOS Messages.
        .safeAreaInset(edge: .top, spacing: 0) {
            ThreadProfileHeader(
                name: name,
                number: model.formatted(address),
                photo: model.photo(for: address),
                isKnown: model.name(for: address) != nil
            )
        }
        .safeAreaInset(edge: .bottom) { composer }
        // Like Messages: no tab bar inside a conversation (it sat under the composer and the keyboard).
        .toolbar(.hidden, for: .tabBar)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { model.dial(address, sub: sub) } label: { Image(systemName: "phone") }
                    .disabled(!model.linkState.isConnected || address.isEmpty)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    let muted = model.isMuted(thread)
                    Color.clear.frame(width: 0, height: 0).onAppear { focused = false }
                    Button { model.setMuted(thread, !muted) } label: {
                        Label(muted ? "Show Alerts" : "Hide Alerts", systemImage: muted ? "bell" : "bell.slash")
                    }
                    if model.isBlocked(address) {
                        Button { model.setBlocked(address, false) } label: { Label("Unblock this Caller", systemImage: "hand.raised.slash") }
                    } else {
                        Button(role: .destructive) { confirmBlock = true } label: { Label("Block this Caller", systemImage: "hand.raised") }
                    }
                    Button(role: .destructive) { confirmDelete = true } label: { Label("Delete Conversation", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis.circle") }
                .disabled(!model.linkState.isConnected || address.isEmpty)
            }
        }
        .confirmationDialog(name, isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete Conversation", role: .destructive) { model.deleteConversation(thread); dismiss() }
        } message: {
            Text("This conversation will be deleted from your Android phone and all your devices.")
        }
        .confirmationDialog(name, isPresented: $confirmBlock, titleVisibility: .visible) {
            Button("Block Contact", role: .destructive) { model.setBlocked(address, true) }
        } message: {
            Text("You will not receive phone calls or messages from people on the block list.")
        }
        .onAppear {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-TetherDemoFocus") {   // keyboard screenshots
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { focused = true }
            }
            #endif
            model.openThread = thread
            model.markRead(thread: thread)
            text = model.draft(for: address)
            sub = model.preferredSim(for: address)
        }
        .onDisappear {
            // The view that replaced this one (the real thread after a first text) may already have set its own.
            if model.openThread == thread { model.openThread = nil }
            model.setDraft(text, for: address)
        }
    }

    private var canSend: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && model.linkState.isConnected && !address.isEmpty
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            // Like the Messages "+" control: SIM selection is a separate circular action.
            Menu {
                ForEach(model.sims) { sim in
                    Button {
                        sub = sim.sub
                        model.setPreferredSim(sim.sub, for: address)
                    } label: {
                        Label("\(sim.name) · \(sim.carrier)",
                              systemImage: sub == sim.sub ? "checkmark.circle.fill" : "simcard")
                    }
                }
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 21, weight: .medium))
                    .frame(width: 42, height: 42)
                    .contentShape(Circle())
                    .tetherLiquidGlassCircle()
            }
            .accessibilityLabel(Text("Choose SIM"))
            .padding(.bottom, 2)

            HStack(alignment: .bottom, spacing: 4) {
                TextField("Text Message · SMS", text: $text, axis: .vertical)
                    .lineLimit(1...6)
                    .focused($focused)
                    .textFieldStyle(.plain)
                    .tint(Color("AccentColor"))
                    .padding(.leading, 12).padding(.vertical, 8)
                Button {
                    model.sendSms(to: address, body: text, sub: sub)
                    text = ""
                } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 28))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, canSend ? Color.green : Color(.systemGray3))
                }
                .disabled(!canSend)
                .padding(.trailing, 3).padding(.bottom, 3)
                .accessibilityLabel(Text("Send"))
            }
            .padding(.leading, 2)
            .padding(.trailing, 3)
            .padding(.vertical, 3)
            .tetherLiquidGlass(cornerRadius: 25)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .padding(.bottom, 4)
    }
}


/// Saved contacts use their photo or initials; unsaved numbers use the blue generic-person avatar.
private struct MessageAvatar: View {
    let name: String
    let photo: String?
    let isKnown: Bool
    let size: CGFloat

    var body: some View {
        Group {
            if isKnown {
                Avatar(name: name, photo: photo, size: size)
            } else {
                ZStack {
                    Circle().fill(
                        LinearGradient(colors: [Color(red: 0.24, green: 0.60, blue: 1.0),
                                                Color(red: 0.08, green: 0.22, blue: 0.56)],
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    Image(systemName: "person.fill")
                        .font(.system(size: size * 0.43, weight: .medium))
                        .foregroundStyle(.white.opacity(0.96))
                }
                .frame(width: size, height: size)
                .clipShape(Circle())
            }
        }
        .accessibilityLabel(Text(isKnown ? name : "Unknown number"))
    }
}

/// Fixed, native-style conversation identity: the person stays visible while the transcript scrolls.
private struct ThreadProfileHeader: View {
    let name: String
    let number: String
    let photo: String?
    let isKnown: Bool
    private let avatarSize: CGFloat = 68

    /// Raised compact identity header, anchored close to the navigation bar.
    var body: some View {
        VStack(spacing: 0) {
            MessageAvatar(name: name, photo: photo, isKnown: isKnown, size: avatarSize)

            Text(isKnown ? name : number)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, 16)
                .padding(.vertical, 5)
                .tetherLiquidGlass(cornerRadius: 20)
                .fixedSize(horizontal: true, vertical: false)
        }
        .padding(.top, -25)
        .frame(maxWidth: .infinity, alignment: .top)
        .frame(height: 80, alignment: .top)
    }
}
/// "Today 13:20" / "Yesterday 09:05" / "Mon 13:20" / "12 Nov 2026 at 13:20", as Messages writes it.
func transcriptTime(_ ms: Int64) -> String {
    let d = Date(millis: ms)
    let cal = Calendar.current
    let time = d.formatted(date: .omitted, time: .shortened)
    if cal.isDateInToday(d) { return String(localized: "Today") + " " + time }
    if cal.isDateInYesterday(d) { return String(localized: "Yesterday") + " " + time }
    if let days = cal.dateComponents([.day], from: cal.startOfDay(for: d), to: cal.startOfDay(for: .now)).day, days < 7 {
        return d.formatted(.dateTime.weekday(.abbreviated)) + " " + time
    }
    return d.formatted(.dateTime.day().month(.abbreviated).year()) + " " + time
}

/// Message bubble outline: a rounded rectangle, with the small tail Messages draws on the last bubble of a run.
struct BubbleShape: Shape {
    var mine: Bool
    var tail: Bool

    func path(in rect: CGRect) -> Path {
        let r: CGFloat = min(18, rect.height / 2)
        var p = Path(roundedRect: rect, cornerRadius: r, style: .continuous)
        if tail {
            // Drawn for the trailing (outgoing) side, then mirrored for incoming bubbles.
            var t = Path()
            let x = rect.maxX, y = rect.maxY
            t.move(to: CGPoint(x: x - r, y: y))
            t.addLine(to: CGPoint(x: x - 4, y: y))
            t.addQuadCurve(to: CGPoint(x: x + 6, y: y), control: CGPoint(x: x + 1, y: y + 0.5))
            t.addQuadCurve(to: CGPoint(x: x, y: y - 9), control: CGPoint(x: x + 0.5, y: y - 2))
            t.addLine(to: CGPoint(x: x, y: y - r))
            t.closeSubpath()
            if !mine {
                t = t.applying(CGAffineTransform(translationX: rect.minX + rect.maxX, y: 0).scaledBy(x: -1, y: 1))
            }
            if #available(iOS 17, *) { p = p.union(t) } else { p = Path(p.cgPath.union(t.cgPath)) }   // not addPath: opposite winding would punch a notch where the two overlap
        }
        return p
    }
}

struct Bubble: View {
    @Environment(\.layoutDirection) private var direction
    @Environment(\.appModel) private var model
    let message: Sms
    var tail = true
    @State private var phone: String?
    @State private var confirmDelete = false

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        let mine = !message.incoming
        // The shape is drawn in left-to-right coordinates; in right-to-left layouts the sides swap.
        let onRight = (direction == .leftToRight) == mine
        let rich = RichTextCache.shared.entry(for: message, mine: mine)
        VStack(alignment: mine ? .trailing : .leading, spacing: 4) {
            HStack(spacing: 0) {
                if mine { Spacer(minLength: 60) }
                Text(rich.text)
                    // The text's own direction (Persian / English) decides its alignment, whatever the app language.
                    .environment(\.layoutDirection, rich.parsed.rightToLeft ? .rightToLeft : .leftToRight)
                    .multilineTextAlignment(.leading)
                    .tint(mine ? .white : Color("AccentColor"))
                    .padding(.horizontal, 13).padding(.vertical, 8)
                    .foregroundStyle(mine ? .white : .primary)
                    .background(mine ? Color.green : Color(.systemGray5), in: BubbleShape(mine: onRight, tail: tail))
                    .contentShape(.contextMenuPreview, BubbleShape(mine: onRight, tail: false))
                    .environment(\.openURL, OpenURLAction { url in handle(url) })
                    .contextMenu { menu(rich.parsed) }
                if message.box == 5 {
                    Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red).padding(.leading, 6)
                }
                if !mine { Spacer(minLength: 60) }
            }
            // One-time code: a one-tap copy button under the bubble, as Messages does.
            if message.incoming, let code = rich.parsed.code {
                Button { copy(code) } label: {
                    Label("Copy code \(code)", systemImage: "doc.on.doc")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(Color(.tertiarySystemFill), in: Capsule())
                }
                .buttonStyle(.plain)
                .padding(.leading, 8)
            }
        }
        .padding(.horizontal, 4)
        .confirmationDialog("Delete Message", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete Message", role: .destructive) { model.deleteMessages([message.id]) }
        } message: {
            Text("This message will be deleted from your Android phone and all your devices.")
        }
        .confirmationDialog(phone ?? "", isPresented: Binding(get: { phone != nil }, set: { if !$0 { phone = nil } }),
                            titleVisibility: .visible) {
            if let n = phone {
                Button("Call \(n)") { model.dial(n, sub: model.preferredSim(for: message.addr)) }
                Button("Send message") { Router.shared.compose(to: n) }
                Button("Copy") { UIPasteboard.general.string = n; model.flash(String(localized: "Copied")) }
            }
        }
    }

    private func copy(_ code: String) {
        UIPasteboard.general.string = code
        model.flash(String(localized: "Code \(code) copied"))
    }

    private func handle(_ url: URL) -> OpenURLAction.Result {
        switch RichTextAction(url) {
        case .copyCode(let c): copy(c); return .handled
        case .phone(let n): hideKeyboard(); phone = n; return .handled
        case .system(let u): return .systemAction(u)
        }
    }

    @ViewBuilder private func menu(_ p: ParsedMessage) -> some View {
        Button { UIPasteboard.general.string = message.body } label: { Label("Copy", systemImage: "doc.on.doc") }
            .onAppear { hideKeyboard() }
        ForEach(Array(p.entities.enumerated()), id: \.offset) { _, e in
            switch e.kind {
            case .code(let c):
                Button { copy(c) } label: { Label("Copy code \(c)", systemImage: "number") }
            case .link(let url):
                Button { UIPasteboard.general.url = url } label: { Label("Copy link", systemImage: "link") }
                ShareLink(item: url) { Label("Share link", systemImage: "square.and.arrow.up") }
            case .phone(let n):
                Button { model.dial(n, sub: model.preferredSim(for: message.addr)) } label: { Label("Call \(n)", systemImage: "phone") }
                Button { UIPasteboard.general.string = n } label: { Label("Copy \(n)", systemImage: "doc.on.doc") }
            case .email(let a):
                Button { UIPasteboard.general.string = a } label: { Label("Copy \(a)", systemImage: "envelope") }
            case .address:
                EmptyView()
            }
        }
        Divider()
        Button(role: .destructive) { hideKeyboard(); confirmDelete = true } label: { Label("Delete", systemImage: "trash") }
        Text(Date(millis: message.date).formatted(date: .abbreviated, time: .shortened))
    }
}

struct ComposeView: View {
    @Environment(\.appModel) private var model
    var initialTo = ""
    let done: (Int64?) -> Void
    @State private var to = ""
    @State private var text = ""
    @State private var sub: Int?
    private enum Field { case to, message }
    @FocusState private var field: Field?

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        NavigationStack {
            Form {
                Section {
                    // Name or number (suggestions search both); Return moves on to the message.
                    TextField("To", text: $to).keyboardType(.namePhonePad).textContentType(.telephoneNumber)
                        .autocorrectionDisabled().focused($field, equals: .to)
                        .submitLabel(.next).onSubmit { field = .message }
                    if !to.isEmpty {
                        ForEach(suggestions, id: \.1) { name, number in
                            Button { to = number; field = .message } label: {
                                VStack(alignment: .leading) { Text(name); Text(model.formatted(number)).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
                Section {
                    TextField("Message", text: $text, axis: .vertical).lineLimit(3...8).focused($field, equals: .message)
                    SimPicker(sub: $sub)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .keyboardDoneButton()
            .navigationTitle("New message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { done(nil) } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send") {
                        model.sendSms(to: to, body: text, sub: sub)
                        done(model.thread(for: to))
                    }
                    .disabled(to.isEmpty || text.isEmpty)
                }
            }
            .onAppear {
                sub = model.status?.defaultSub
                if to.isEmpty { to = initialTo }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { field = to.isEmpty ? .to : .message }
            }
        }
    }

    private var suggestions: [(String, String)] {
        let q = to.lowercased()
        var out: [(String, String)] = []
        for c in model.data.contacts where c.name.lowercased().contains(q) || c.phones.contains(where: { $0.n.contains(q) }) {
            for p in c.phones { out.append((c.name, p.n)) }
            if out.count > 6 { break }
        }
        return out
    }
}
