import SwiftUI
import VeyqCore

/// A refusal of a Pot write, in words.
func potRefusal(_ outcome: String) -> String { say(Notices.shared.potRefused(outcome: outcome)) }

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

    /// The currencies offered, by code: core's one table (the web's `Intl`).
    private static var currencies: [String] { Currencies.shared.known }

    /// The minor-unit digits of `code` (the euro 2, the yen 0, the dinar 3): core's one table.
    private static func minorUnits(_ code: String) -> Int32 { Currencies.shared.minorUnits(code: code) }

    var body: some View {
        List {
            NodeHeroRow()
            if !view.ready {
                Section {
                    Text(view.canChangeSettings
                        ? say(.settingsMissing)
                        : say(.settingsMissingReadOnly))
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
        .alert(say(.peopleRename, renaming?.name ?? ""), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(say(.peopleName), text: $renameTo)
            Button(say(.entrySave)) {
                if let p = renaming { write { try await $0.rename(participantId: p.id, name: renameTo) } then: {} }
                renaming = nil
            }
            Button(say(.signinCancel), role: .cancel) { renaming = nil }
        }
        .alert(say(.peopleRename, renamingName), isPresented: Binding(get: { renamingCategory != nil }, set: { if !$0 { renamingCategory = nil } })) {
            TextField(say(.settingsCategoryName), text: $categoryName)
            Button(say(.entrySave)) {
                if let id = renamingCategory { write { try await $0.renameCategory(id: id, name: categoryName) } then: {} }
                renamingCategory = nil
            }
            Button(say(.signinCancel), role: .cancel) { renamingCategory = nil }
        }
        .confirmationDialog(
            erasing.map { $0.named ? say(.peopleDelete, $0.name) : say(.peopleRemove, $0.name) } ?? "",
            isPresented: Binding(get: { erasing != nil }, set: { if !$0 { erasing = nil } }),
            titleVisibility: .visible
        ) {
            if let p = erasing {
                Button(p.named ? say(.peopleDeleteAccept) : say(.peopleRemoveAccept), role: .destructive) {
                    write { try await $0.erase(participantId: p.id) } then: {}
                    erasing = nil
                }
            }
        } message: {
            if let p = erasing {
                Text(p.named
                    ? say(.peopleDeleteConfirm)
                    : say(.peopleRemoveConfirm))
            }
        }
    }

    // MARK: Base currency

    @ViewBuilder private var currency: some View {
        Section {
            if view.canChangeSettings && !view.currencyFixed {
                Picker(say(.potBaseCurrency), selection: Binding(
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
            SectionLabel(title: say(.potBaseCurrency))
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if view.currencyFixed {
                    Text(say(.settingsCurrencyFixed))
                } else if view.canChangeSettings {
                    Text(say(.settingsCurrencyHint))
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
                    TextField(say(.peopleName), text: $newName)
                    Button(say(.peopleAdd)) { write { try await $0.addPerson(name: newName) } then: { newName = "" } }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty || saving)
                }
                .listRowBackground(Role.surface)
            }
            if view.canWrite && !view.addableMembers.isEmpty {
                HStack {
                    Picker(say(.peopleChooseMember), selection: $newMember) {
                        Text(say(.peopleChooseMember)).tag("")
                        ForEach(view.addableMembers, id: \.userId) { Text($0.name).tag($0.userId) }
                    }
                    Button(say(.peopleAddMember)) { write { try await $0.addMember(userId: newMember) } then: { newMember = "" } }
                        .disabled(newMember.isEmpty || saving)
                }
                .listRowBackground(Role.surface)
            }
        } header: {
            SectionLabel(title: say(.potPeople))
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if view.deletedPeople > 0 { Text(say(.peopleDeletedSummary, view.deletedPeople)) }
                if view.canWrite && view.anyUnsettled { Text(say(.peopleSettleFirst)) }
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
                    .accessibilityLabel(say(.peopleRename, p.name))
                    .disabled(saving)
            }
            if p.canToggle {
                Button(p.active ? say(.peopleDeactivate) : say(.peopleActivate)) { write { try await $0.setActive(participantId: p.id, active: !p.active) } then: {} }
                    .buttonStyle(.borderless)
                    .disabled(saving)
            }
            if p.canErase {
                Button { erasing = p } label: { Image(systemName: "trash") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(Role.danger)
                    .accessibilityLabel(p.named ? say(.peopleDelete, p.name) : say(.peopleRemove, p.name))
                    .disabled(saving)
            }
        }
    }

    private func facts(_ p: PotView.PersonEntry) -> String {
        var parts = [p.settled ? say(.potSettled) : money(p.balance, view.currency, view.minorUnits)]
        if !p.active { parts.append(say(.peopleInactive)) }
        if p.standing == .former { parts.append(say(.peopleFormerMember)) }
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
                                .accessibilityLabel(say(.settingsRenameCategory, c.second as String? ?? ""))
                                .disabled(saving)
                        }
                    }
                    .listRowBackground(Role.surface)
                }
                if view.canChangeSettings && view.ownCategories.count < 200 {
                    HStack {
                        TextField(say(.settingsCategoryName), text: $newCategory)
                        Button(say(.settingsAddCategory)) { write { try await $0.addCategory(name: newCategory) } then: { newCategory = "" } }
                            .disabled(newCategory.trimmingCharacters(in: .whitespaces).isEmpty || saving)
                    }
                    .listRowBackground(Role.surface)
                }
            } header: {
                SectionLabel(title: say(.potOwnCategories))
            } footer: {
                if view.canChangeSettings { Text(say(.settingsCategoriesHint)) }
            }
        }
    }

    private func write(_ op: @escaping @MainActor (PotSession) async throws -> PotSession.WriteOutcome, then done: @escaping () -> Void) {
        Task { if await model.potWrite(op) { done() } }
    }
}
