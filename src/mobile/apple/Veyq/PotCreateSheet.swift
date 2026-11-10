import SwiftUI
import VeyqCore

/// "New pot" (the web's `PotCreateDialog.vue`): a name, the space it goes to the root of, the base currency — sealed into
/// the node's meta at creation, fixed once the first entry exists — and its first people, names typed or members of that
/// space chosen (a member at most once, a name at most once, case aside).
struct PotCreateSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var scopeId = ""
    @State private var currency = PotCreate.shared.defaultCurrency(language: Locale.current.language.languageCode?.identifier ?? "")
    @State private var typed = ""
    @State private var people = PotCreate.People()
    @State private var members: [Person] = []

    /// The currencies offered, by code: core's one table (the web's `Intl`).
    private static var currencies: [String] { Currencies.shared.known }

    /// The minor-unit digits of `code` (the euro 2, the yen 0, the dinar 3): core's one table.
    private static func minorUnits(_ code: String) -> Int32 { Currencies.shared.minorUnits(code: code) }

    private func takeTyped() {
        people = people.typed(text: typed)
        typed = ""
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField(say(.peopleName), text: $name)
                if model.spaces.count > 1 {
                    Picker(say(.createPotSpace), selection: $scopeId) {
                        ForEach(model.spaces, id: \.scopeId) { Text(Names.spaceName($0)).tag($0.scopeId) }
                    }
                }
                Section {
                    Picker(say(.potBaseCurrency), selection: $currency) {
                        ForEach(Self.currencies, id: \.self) { Text($0).tag($0) }
                    }
                } footer: { Text(say(.settingsCurrencyHint)) }
                Section {
                    ForEach(Array(people.list.enumerated()), id: \.offset) { _, p in
                        HStack {
                            Text(p.name)
                            Spacer()
                            Button { people = people.remove(p: p) } label: { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(say(.peopleRemove, p.name))
                        }
                    }
                    HStack {
                        TextField(say(.peopleName), text: $typed)
                        Button(say(.peopleAdd), action: takeTyped)
                            .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    ForEach(people.offered(members: members), id: \.userId) { m in
                        Button { people = people.add(p: PotCreate.NewPerson.companion.member(userId: m.userId, displayName: m.displayName)) } label: {
                            Label(m.displayName, systemImage: "person.badge.plus")
                        }
                    }
                } header: { Text(say(.potPeople)) }
                if let message = model.creatingPot.message {
                    Section { Text(Self.explain(message)).foregroundStyle(Role.danger) }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.createPot))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { model.clearCreatingPot(); dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.createPotCreate)) {
                        takeTyped()
                        Task {
                            if await model.createPot(
                                scopeId: scopeId, name: name, currency: currency, minorUnits: Self.minorUnits(currency), people: people.list
                            ) { dismiss() }
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || scopeId.isEmpty || model.creatingPot.saving)
                }
            }
            .task(id: scopeId) {
                if scopeId.isEmpty { scopeId = model.shell.space ?? model.spaces.first?.scopeId ?? "" }
                members = scopeId.isEmpty ? [] : await model.membersOf(scopeId)
                // A member chosen from another space is no member here.
                people = people.keep(members: members)
            }
        }
        .tint(Role.accent)
    }

    static func explain(_ outcome: String) -> String { say(Notices.shared.potCreateRefused(outcome: outcome)) }
}
