import Perception
import SwiftUI
import TetherCore

struct ContactsView: View {
    @Environment(\.appModel) private var model
    @State private var search = ""
    @State private var adding = false

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        NavigationStack {
            List {
                // Favourites first, as in the Phone app (the Android phone's starred contacts).
                if search.isEmpty && !model.favourites.isEmpty {
                    Section("Favourites") {
                        ForEach(model.favourites) { c in FavouriteRow(contact: c) }
                    }
                }
                ForEach(groupedContacts, id: \.letter) { group in
                    Section {
                        ForEach(group.contacts) { c in
                            NavigationLink {
                                ContactDetailView(contact: c)
                            } label: {
                                HStack(spacing: 12) {
                                    Avatar(name: c.name, photo: model.photo(for: c), size: 36)
                                    contactName(c.name).lineLimit(1)
                                    if c.starred {
                                        Image(systemName: "star.fill")
                                            .font(.caption2)
                                            .foregroundStyle(.yellow)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(.vertical, 2)
                                .contentShape(Rectangle())
                            }
                        }
                    } header: {
                        Text(group.letter)
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .listStyle(.plain)
            .alignmentGuide(.listRowSeparatorLeading) { _ in 66 }
            .overlay {
                if model.data.contacts.isEmpty { EmptyStateView("No contacts", systemImage: "person.crop.circle") }
            }
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search contacts"))
            .scrollDismissesKeyboard(.immediately)
            .refreshable { model.requestSync() }
            .navigationTitle("Contacts")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { hideKeyboard(); adding = true } label: { Image(systemName: "plus") }.disabled(!model.linkState.isConnected)
                }
            }
            .sheet(isPresented: $adding) { AddContactView().environment(\.appModel, AppModel.shared).environment(\.router, Router.shared) }
        }
    }

    /// Keep given names regular and emphasize the family name in the contact list.
    private func contactName(_ full: String) -> Text {
        let parts = full.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count > 1, let last = parts.last else { return Text(full).font(.body) }
        let first = parts.dropLast().joined(separator: " ")
        return Text(first + " ").font(.body) + Text(String(last)).font(.body.weight(.semibold))
    }

    private var groupedContacts: [(letter: String, contacts: [Contact])] {
        let sorted = filtered.sorted {
            $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
        let groups = Dictionary(grouping: sorted) { contact -> String in
            let first = String(contact.name.prefix(1)).uppercased()
            return first.rangeOfCharacter(from: .letters) == nil ? "#" : first
        }
        return groups.keys.sorted {
            if $0 == "#" { return false }
            if $1 == "#" { return true }
            return $0.localizedStandardCompare($1) == .orderedAscending
        }.map { (letter: $0, contacts: groups[$0] ?? []) }
    }

    private var filtered: [Contact] {
        guard !search.isEmpty else { return model.data.contacts }
        return model.data.contacts.filter { c in
            SearchText.matches(search, names: [c.name] + c.emails, numbers: c.phones.map(\.n))
        }
    }
}

/// A favourite: tap to call (its first number, on the SIM last used with it), ⓘ for the contact.
struct FavouriteRow: View {
    @Environment(\.appModel) private var model
    let contact: Contact

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        HStack(spacing: 12) {
            Avatar(name: contact.name, photo: model.photo(for: contact), size: 36)
            VStack(alignment: .leading, spacing: 1) {
                Text(contact.name)
                if let p = contact.phones.first {
                    Text(LocalizedStringKey(p.type.capitalized)).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            NavigationLink { ContactDetailView(contact: contact) } label: { Image(systemName: "info.circle") }
                .buttonStyle(.borderless).fixedSize()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if let p = contact.phones.first { model.dial(p.n, sub: model.preferredSim(for: p.n)) }
        }
        .swipeActions {
            Button { model.setStarred(contact.id, false) } label: { Label("Remove", systemImage: "star.slash") }.tint(.orange)
        }
    }
}

struct ContactDetailView: View {
    @Environment(\.appModel) private var model
    /// The contact as it was when the row was tapped; the live copy follows edits and re-syncs.
    let original: Contact
    @State private var editing = false
    @State private var confirmBlock = false

    init(contact: Contact) { original = contact }
    private var contact: Contact { model.data.contacts.first { $0.id == original.id } ?? original }

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    Avatar(name: contact.name, photo: model.photo(for: contact), size: 96)
                    Text(contact.name).font(.title2.weight(.semibold))
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            Section {
                ForEach(contact.phones, id: \.self) { p in
                    HStack {
                        VStack(alignment: .leading) {
                            Group {
                                if let label = p.label, !label.trimmingCharacters(in: .whitespaces).isEmpty { Text(verbatim: label) }
                                else { Text(LocalizedStringKey(p.type.capitalized)) }
                            }
                            .font(.caption).foregroundStyle(.secondary)
                            Text(model.formatted(p.n)).environment(\.layoutDirection, .leftToRight)
                        }
                        Spacer()
                        Button { openThread(p.n) } label: { Image(systemName: "message.fill").accessibilityLabel(Text("Message")) }.buttonStyle(.borderless)
                        Button { model.dial(p.n, sub: model.preferredSim(for: p.n)) } label: { Image(systemName: "phone.fill").accessibilityLabel(Text("Call")) }
                            .buttonStyle(.borderless).padding(.leading, 12)
                    }
                }
                ForEach(contact.emails, id: \.self) { e in
                    VStack(alignment: .leading) {
                        Text("Email").font(.caption).foregroundStyle(.secondary)
                        Text(e)
                    }
                }
            }
            if !contact.phones.isEmpty {
                Section {
                    // Like Contacts: one row for the whole contact (all of its numbers).
                    let blocked = contact.phones.contains { model.isBlocked($0.n) }
                    Button(role: blocked ? nil : .destructive) {
                        if blocked { for p in contact.phones where model.isBlocked(p.n) { model.setBlocked(p.n, false) } }
                        else { confirmBlock = true }
                    } label: {
                        Text(blocked ? "Unblock this Caller" : "Block this Caller").foregroundStyle(blocked ? Color.accentColor : .red)
                    }
                    .disabled(!model.linkState.isConnected)
                }
            }
        }
        .confirmationDialog(contact.name, isPresented: $confirmBlock, titleVisibility: .visible) {
            Button("Block Contact", role: .destructive) { for p in contact.phones { model.setBlocked(p.n, true) } }
        } message: {
            Text("You will not receive phone calls or messages from people on the block list.")
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { model.setStarred(contact.id, !contact.starred) } label: {
                    Image(systemName: contact.starred ? "star.fill" : "star").foregroundStyle(contact.starred ? .yellow : .accentColor)
                }
                .accessibilityLabel(Text(contact.starred ? "Remove from Favourites" : "Add to Favourites"))
                .disabled(!model.linkState.isConnected)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("Edit") { editing = true }.disabled(!model.linkState.isConnected)
            }
        }
        .sheet(isPresented: $editing) {
            EditContactView(contact: contact).environment(\.appModel, AppModel.shared).environment(\.router, Router.shared)
        }
    }

    private func openThread(_ number: String) {
        if let t = model.thread(for: number) {
            Router.shared.open(thread: t)
        } else {
            Router.shared.compose(to: number)   // no conversation yet: a new message to this number
        }
    }
}

struct AddContactView: View {
    @Environment(\.appModel) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var phone = ""
    @State private var type = "mobile"
    @State private var email = ""

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        NavigationStack {
            Form {
                Section { TextField("Name", text: $name) }
                Section {
                    TextField("Phone", text: $phone).keyboardType(.phonePad)
                    Picker("Type", selection: $type) {
                        Text("Mobile").tag("mobile"); Text("Home").tag("home"); Text("Work").tag("work"); Text("Other").tag("other")
                    }
                    TextField("Email", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                }
                Section { Text("The contact is saved on your Android phone.").font(.footnote).foregroundStyle(.secondary) }
            }
            .scrollDismissesKeyboard(.interactively)
            .keyboardDoneButton()
            .navigationTitle("New contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.addContact(name: name, phones: phone.isEmpty ? [] : [Phone(n: phone, type: type)],
                                         emails: email.isEmpty ? [] : [email])
                        dismiss()
                    }
                    .disabled(name.isEmpty || (phone.isEmpty && email.isEmpty))
                }
            }
        }
    }
}

/// Edit an existing contact, laid out like the iOS Contacts editor: name, then phone and e-mail rows that
/// can be added, retyped and removed. Saving rewrites the contact on the Android phone.
struct EditContactView: View {
    @Environment(\.appModel) private var model
    @Environment(\.dismiss) private var dismiss
    let contact: Contact

    /// `label`: the number's custom kind from the Android phone ("Satellite"); kept unless another kind is picked.
    struct PhoneRow: Identifiable { let id = UUID(); var number: String; var type: String; var label: String? = nil }
    private struct EmailRow: Identifiable { let id = UUID(); var address: String }
    @State private var name = ""
    @State private var phones: [PhoneRow] = []
    @State private var emails: [EmailRow] = []
    private let types = ["mobile", "home", "work", "main", "other"]

    /// Picker tag for "keep the custom kind" (never sent: it goes out as type "other" with the label).
    static let custom = "custom"

    /// What the Android phone stores: a kept custom kind is sent with its label; picking a standard kind for a
    /// custom one sends an empty label (clears it); otherwise no label (protocol §5 contact.update).
    static func phone(from row: PhoneRow) -> Phone {
        let n = row.number.trimmingCharacters(in: .whitespaces)
        if row.type == custom, let label = row.label { return Phone(n: n, type: "other", label: label) }
        return Phone(n: n, type: row.type, label: row.label != nil ? "" : nil)
    }

    var body: some View { WithPerceptionTracking { bodyContent } }

    @ViewBuilder private var bodyContent: some View {
        NavigationStack {
            Form {
                Section {
                    HStack { Spacer(); Avatar(name: name.isEmpty ? contact.name : name, photo: model.photo(for: contact), size: 80); Spacer() }
                        .listRowBackground(Color.clear)
                    TextField("Name", text: $name)
                }
                Section {
                    ForEach($phones) { $p in
                        HStack {
                            Picker("", selection: $p.type) {
                                if let label = p.label { Text(verbatim: label).tag(Self.custom) }
                                ForEach(types, id: \.self) { Text(LocalizedStringKey($0.capitalized)).tag($0) }
                            }
                            .labelsHidden().fixedSize()
                            TextField("Phone", text: $p.number).keyboardType(.phonePad)
                                .environment(\.layoutDirection, .leftToRight)
                        }
                    }
                    .onDelete { phones.remove(atOffsets: $0) }
                    Button { phones.append(PhoneRow(number: "", type: "mobile")) } label: {
                        Label("Add phone", systemImage: "plus.circle.fill")
                    }
                }
                Section {
                    ForEach($emails) { $e in
                        TextField("Email", text: $e.address).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                    }
                    .onDelete { emails.remove(atOffsets: $0) }
                    Button { emails.append(EmailRow(address: "")) } label: {
                        Label("Add email", systemImage: "plus.circle.fill")
                    }
                }
                Section { Text("Changes are saved on your Android phone.").font(.footnote).foregroundStyle(.secondary) }
            }
            .scrollDismissesKeyboard(.interactively)
            .keyboardDoneButton()
            .navigationTitle("Edit contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        let ps = phones.map { Self.phone(from: $0) }.filter { !$0.n.isEmpty }
                        let es = emails.map { $0.address.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                        model.updateContact(id: contact.id, name: name.trimmingCharacters(in: .whitespaces), phones: ps, emails: es)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || !model.linkState.isConnected)
                }
            }
            .onAppear {
                name = contact.name
                phones = contact.phones.map { p in
                    let label = p.label.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
                    return PhoneRow(number: p.n, type: label != nil ? Self.custom : (types.contains(p.type) ? p.type : "other"), label: label)
                }
                emails = contact.emails.map { EmailRow(address: $0) }
            }
        }
    }
}
