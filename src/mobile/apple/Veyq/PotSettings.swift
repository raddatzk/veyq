import SwiftUI
import VeyqCore

/// A refusal of a Pot write, in words.
func potRefusal(_ outcome: String) -> String { say(Notices.shared.potRefused(outcome: outcome)) }

/// An open Pot's Settings tab (the web's `SettingsPanel.vue` and `PeoplePanel.vue`): its base currency, chosen while it
/// holds no money entry; its people, each with their balance in short and what may be done to them
/// (`PotView.PersonEntry`); and its own categories, added and renamed. A Pot without settings shows the currency alone.
/// Drawn over core's `PotSettings` (held by the app model): what may be changed, the erase asked and a refusal are its.
/// Named apart from that class (a SwiftUI view must not shadow a core class).
struct PotSettingsTab: View {
    @Bindable var model: AppModel
    let view: PotView
    @State private var newName = ""
    @State private var newMember = ""
    @State private var renaming: PotView.PersonEntry?
    @State private var renameTo = ""
    @State private var newCategory = ""
    @State private var confirming = false
    @State private var renamingCategory: String?
    @State private var categoryName = ""

    private var saving: Bool { model.pot?.saving == true }

    /// The currencies offered, by code: core's one table (the web's `Intl`).
    private static var currencies: [String] { Currencies.shared.known }

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
        .task(id: model.pot?.ref.nodeId) { model.openSettings() }
        .alert(say(.peopleRename, renaming?.name ?? ""), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(say(.peopleName), text: $renameTo)
            Button(say(.entrySave)) {
                if let p = renaming { let name = renameTo; write { try await $0.rename(personId: p.id, name: name) } then: {} }
                renaming = nil
            }
            Button(say(.signinCancel), role: .cancel) { renaming = nil }
        }
        .alert(say(.peopleRename, renamingName), isPresented: Binding(get: { renamingCategory != nil }, set: { if !$0 { renamingCategory = nil } })) {
            TextField(say(.settingsCategoryName), text: $categoryName)
            Button(say(.entrySave)) {
                if let id = renamingCategory { let name = categoryName; write { try await $0.renameCategory(id: id, name: name) } then: {} }
                renamingCategory = nil
            }
            Button(say(.signinCancel), role: .cancel) { renamingCategory = nil }
        }
        // the erase asked is core's state. A dismissal's cancel runs BEFORE the button's action (src/mobile/CLAUDE.md
        // § Traps), and an iPhone's popover has no Cancel button but a tap outside: the dismissal cancels one turn
        // later, unless the destructive action confirmed meanwhile.
        .confirmationDialog(
            erasing.map { $0.named ? say(.peopleDelete, $0.name) : say(.peopleRemove, $0.name) } ?? "",
            isPresented: Binding(get: { erasing != nil && !confirming }, set: { shown in
                if !shown { DispatchQueue.main.async { if !confirming { model.cancelErase() } } }
            }),
            titleVisibility: .visible
        ) {
            if let p = erasing {
                Button(p.named ? say(.peopleDeleteAccept) : say(.peopleRemoveAccept), role: .destructive) {
                    // shown closed at once; core's question ends when the erase was sent
                    confirming = true
                    Task { _ = await model.settingsWrite { try await $0.erase() }; confirming = false }
                }
                Button(say(.signinCancel), role: .cancel) { model.cancelErase() }
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
            if view.currencyEditable {
                Picker(say(.potBaseCurrency), selection: Binding(
                    get: { view.currency ?? "" },
                    set: { code in
                        guard !code.isEmpty else { return }
                        write { try await $0.setBaseCurrency(code: code) } then: {}
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
                if !view.ready, let notice = model.settingsState?.notice { Text(say(notice: notice)).foregroundStyle(Role.danger) }
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
                    Button(say(.peopleAdd)) { let name = newName; write { try await $0.addPerson(name: name) } then: { newName = "" } }
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
                    Button(say(.peopleAddMember)) { let id = newMember; write { try await $0.addMember(userId: id) } then: { newMember = "" } }
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
                if let notice = model.settingsState?.notice { Text(say(notice: notice)).foregroundStyle(Role.danger) }
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
                Button(p.active ? say(.peopleDeactivate) : say(.peopleActivate)) { let id = p.id; write { try await $0.toggleActive(personId: id) } then: {} }
                    .buttonStyle(.borderless)
                    .disabled(saving)
            }
            if p.canErase {
                Button { model.askErase(p.id) } label: { Image(systemName: "trash") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(Role.danger)
                    .accessibilityLabel(p.named ? say(.peopleDelete, p.name) : say(.peopleRemove, p.name))
                    .disabled(saving)
            }
        }
    }

    private func facts(_ p: PotView.PersonEntry) -> String { view.personFacts(p: p).map { say(piece: $0) }.joined(separator: " · ") }

    // MARK: Own categories

    private var renamingName: String {
        view.ownCategories.first { ($0.first as String?) == renamingCategory }?.second as String? ?? ""
    }

    @ViewBuilder private var categories: some View {
        if view.categoriesShown {
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
                if view.canAddCategory {
                    HStack {
                        TextField(say(.settingsCategoryName), text: $newCategory)
                        Button(say(.settingsAddCategory)) { let name = newCategory; write { try await $0.addCategory(name: name) } then: { newCategory = "" } }
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

    private var erasing: PotSettings.Asked? { model.settingsState?.asking }

    private func write(_ op: @escaping @MainActor (PotSettings) async throws -> KotlinBoolean, then done: @escaping () -> Void) {
        Task { if await model.settingsWrite(op) { done() } }
    }
}
