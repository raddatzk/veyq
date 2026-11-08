import SwiftUI
import VeyqCore

/// A refusal of a Pot write, in words.
func potRefusal(_ outcome: String) -> LocalizedStringKey {
    switch outcome {
    case "STALE": "Someone else changed the pot meanwhile. It was read again; save once more."
    case "NOT_SETTLED": "A person can be deactivated or deleted once their balance is settled."
    case "BUSY": "The pot’s key is being renewed. Try again in a moment."
    case "BLOCKED": "The operator blocked this pot."
    case "INVALID": "Check the name: 1 to 100 characters."
    default: "The change was not saved. Please try again."
    }
}

/// An open Pot's Settings tab (the web's `SettingsPanel.vue` and `PeoplePanel.vue`): its base currency, chosen while it
/// holds no money entry; its people, each with their balance in short and what may be done to them
/// (`PotView.PersonEntry`); and its own categories, added and renamed. A Pot without settings shows the currency alone.
struct PotSettings: View {
    @Bindable var model: AppModel
    let view: PotView
    @State private var newName = ""
    @State private var newMember = ""
    @State private var renaming: PotView.PersonEntry?
    @State private var renameTo = ""
    @State private var erasing: PotView.PersonEntry?
    @State private var newCategory = ""
    @State private var renamingCategory: String?
    @State private var categoryName = ""

    private var saving: Bool { model.pot?.saving == true }

    /// The currencies this device knows, by code (the web asks `Intl` the same).
    private static let currencies: [String] = Array(Set(Locale.commonISOCurrencyCodes.filter { $0.count == 3 && $0 == $0.uppercased() })).sorted()

    /// The minor-unit digits of `code` (the euro 2, the yen 0, the dinar 3), within the format's 0...4.
    private static func minorUnits(_ code: String) -> Int32 {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = code
        return Int32(min(4, max(0, f.maximumFractionDigits)))
    }

    var body: some View {
        List {
            if !view.ready {
                Section {
                    Text(view.canChangeSettings
                        ? "This pot has no base currency yet. Pick one to record entries."
                        : "This pot has no base currency yet, so it shows no entries.")
                        .listRowBackground(Role.surface)
                }
            }
            currency
            if view.ready {
                people
                categories
            }
        }
        .scrollContentBackground(.hidden)
        .alert("Rename \(renaming?.name ?? "")", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $renameTo)
            Button("Save") {
                if let p = renaming { write { try await $0.rename(participantId: p.id, name: renameTo) } then: {} }
                renaming = nil
            }
            Button("Cancel", role: .cancel) { renaming = nil }
        }
        .alert("Rename \(renamingName)", isPresented: Binding(get: { renamingCategory != nil }, set: { if !$0 { renamingCategory = nil } })) {
            TextField("Category name", text: $categoryName)
            Button("Save") {
                if let id = renamingCategory { write { try await $0.renameCategory(id: id, name: categoryName) } then: {} }
                renamingCategory = nil
            }
            Button("Cancel", role: .cancel) { renamingCategory = nil }
        }
        .confirmationDialog(
            erasing.map { $0.named ? "Delete \($0.name)" : "Remove \($0.name)" } ?? "",
            isPresented: Binding(get: { erasing != nil }, set: { if !$0 { erasing = nil } }),
            titleVisibility: .visible
        ) {
            if let p = erasing {
                Button(p.named ? "Delete" : "Remove", role: .destructive) {
                    write { try await $0.erase(participantId: p.id) } then: {}
                    erasing = nil
                }
            }
        } message: {
            if let p = erasing {
                Text(p.named
                    ? "Their name is erased. Entries show them as “deleted people”. This can’t be undone."
                    : "No entry names them, so they disappear from the pot. This can’t be undone.")
            }
        }
    }

    // MARK: Base currency

    @ViewBuilder private var currency: some View {
        Section {
            if view.canChangeSettings && !view.currencyFixed {
                Picker("Base currency", selection: Binding(
                    get: { view.currency ?? "" },
                    set: { code in
                        guard !code.isEmpty, code != view.currency else { return }
                        write { try await $0.setBaseCurrency(currency: code, minorUnits: Self.minorUnits(code)) } then: {}
                    }
                )) {
                    if view.currency == nil { Text("—").tag("") }
                    ForEach(Self.currencies, id: \.self) { Text($0).tag($0) }
                }
                .disabled(saving)
                .listRowBackground(Role.surface)
            } else {
                Text(view.currency ?? "").listRowBackground(Role.surface)
            }
        } header: {
            SectionLabel(title: "Base currency")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if view.currencyFixed {
                    Text("Fixed: the pot holds entries.")
                } else if view.canChangeSettings {
                    Text("Every balance is in this currency. It is fixed once the first entry is saved.")
                }
                if !view.ready, let message = model.pot?.message { Text(potRefusal(message)).foregroundStyle(Role.danger) }
            }
        }
    }

    // MARK: People

    @ViewBuilder private var people: some View {
        Section {
            ForEach(view.personEntries, id: \.id) { p in row(p).listRowBackground(Role.surface) }
            if view.canWrite {
                HStack {
                    TextField("Name", text: $newName)
                    Button("Add person") { write { try await $0.addPerson(name: newName) } then: { newName = "" } }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty || saving)
                }
                .listRowBackground(Role.surface)
            }
            if view.canWrite && !view.addableMembers.isEmpty {
                HStack {
                    Picker("A member of this space", selection: $newMember) {
                        Text("A member of this space").tag("")
                        ForEach(view.addableMembers, id: \.userId) { Text($0.name).tag($0.userId) }
                    }
                    Button("Add member") { write { try await $0.addMember(userId: newMember) } then: { newMember = "" } }
                        .disabled(newMember.isEmpty || saving)
                }
                .listRowBackground(Role.surface)
            }
        } header: {
            SectionLabel(title: "People")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if view.deletedPeople > 0 { Text("\(view.deletedPeople) deleted people") }
                if view.canWrite && view.anyUnsettled { Text("A person can be deactivated or deleted once their balance is settled.") }
                if let message = model.pot?.message { Text(potRefusal(message)).foregroundStyle(Role.danger) }
            }
        }
    }

    private func row(_ p: PotView.PersonEntry) -> some View {
        HStack(spacing: 12) {
            PersonAvatar(name: p.name, userId: p.userId)
            VStack(alignment: .leading, spacing: 2) {
                Text(p.name).fontWeight(.semibold)
                Text(facts(p)).font(.caption).foregroundStyle(Role.muted)
            }
            Spacer()
            if p.renamable {
                Button { renameTo = p.name; renaming = p } label: { Image(systemName: "pencil") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Rename \(p.name)")
                    .disabled(saving)
            }
            if p.canToggle {
                Button(p.active ? "Deactivate" : "Activate") { write { try await $0.setActive(participantId: p.id, active: !p.active) } then: {} }
                    .buttonStyle(.borderless)
                    .disabled(saving)
            }
            if p.canErase {
                Button { erasing = p } label: { Image(systemName: "trash") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(Role.danger)
                    .accessibilityLabel(p.named ? "Delete \(p.name)" : "Remove \(p.name)")
                    .disabled(saving)
            }
        }
    }

    private func facts(_ p: PotView.PersonEntry) -> String {
        var parts = [p.settled ? String(localized: "settled") : money(p.balance, view.currency, view.minorUnits)]
        if !p.active { parts.append(String(localized: "deactivated")) }
        if p.standing == .former { parts.append(String(localized: "former member")) }
        return parts.joined(separator: " · ")
    }

    // MARK: Own categories

    private var renamingName: String {
        view.ownCategories.first { ($0.first as String?) == renamingCategory }?.second as String? ?? ""
    }

    @ViewBuilder private var categories: some View {
        if view.canChangeSettings || !view.ownCategories.isEmpty {
            Section {
                ForEach(Array(view.ownCategories.enumerated()), id: \.offset) { _, c in
                    HStack {
                        Text(c.second as String? ?? "")
                        Spacer()
                        if view.canChangeSettings {
                            Button { categoryName = c.second as String? ?? ""; renamingCategory = c.first as String? } label: { Image(systemName: "pencil") }
                                .buttonStyle(.borderless)
                                .accessibilityLabel("Rename \(c.second as String? ?? "")")
                                .disabled(saving)
                        }
                    }
                    .listRowBackground(Role.surface)
                }
                if view.canChangeSettings && view.ownCategories.count < 200 {
                    HStack {
                        TextField("Category name", text: $newCategory)
                        Button("Add category") { write { try await $0.addCategory(name: newCategory) } then: { newCategory = "" } }
                            .disabled(newCategory.trimmingCharacters(in: .whitespaces).isEmpty || saving)
                    }
                    .listRowBackground(Role.surface)
                }
            } header: {
                SectionLabel(title: "Own categories")
            } footer: {
                if view.canChangeSettings { Text("Added here, they can be picked for every entry of this pot. They can be renamed, not removed.") }
            }
        }
    }

    private func write(_ op: @escaping @MainActor (PotSession) async throws -> PotSession.WriteOutcome, then done: @escaping () -> Void) {
        Task { if await model.potWrite(op) { done() } }
    }
}
