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
    @State private var currency = Locale.current.language.languageCode?.identifier == "de" ? "EUR" : "USD"
    @State private var typed = ""
    @State private var people: [PotCreate.NewPerson] = []
    @State private var members: [Person] = []

    private static let currencies: [String] = Array(Set(Locale.commonISOCurrencyCodes.filter { $0.count == 3 && $0 == $0.uppercased() })).sorted()

    private static func minorUnits(_ code: String) -> Int32 {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = code
        return Int32(min(4, max(0, f.maximumFractionDigits)))
    }

    private func key(_ p: PotCreate.NewPerson) -> String { p.userId.map { "member:\($0)" } ?? "name:\(p.name.lowercased())" }

    private func add(_ p: PotCreate.NewPerson) {
        if !people.contains(where: { key($0) == key(p) }) { people.append(p) }
    }

    private func takeTyped() {
        let t = typed.trimmingCharacters(in: .whitespaces)
        if !t.isEmpty { add(PotCreate.NewPerson.companion.name(name: t)) }
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
                    ForEach(Array(people.enumerated()), id: \.offset) { _, p in
                        HStack {
                            Text(p.name)
                            Spacer()
                            Button { people.removeAll { key($0) == key(p) } } label: { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(say(.peopleRemove, p.name))
                        }
                    }
                    HStack {
                        TextField(say(.peopleName), text: $typed)
                        Button(say(.peopleAdd), action: takeTyped)
                            .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    ForEach(members.filter { m in !people.contains { $0.userId == m.userId } }, id: \.userId) { m in
                        Button { add(PotCreate.NewPerson.companion.member(userId: m.userId, displayName: m.displayName)) } label: {
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
                                scopeId: scopeId, name: name, currency: currency, minorUnits: Self.minorUnits(currency), people: people
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
                people.removeAll { p in p.userId != nil && !members.contains { $0.userId == p.userId } }
            }
        }
        .tint(Role.accent)
    }

    static func explain(_ outcome: String) -> String {
        switch outcome {
        case "NAME_TAKEN": say(.createPotNameTaken)
        case "PEOPLE_NOT_ADDED": say(.createPotPeopleFailed)
        case "BUSY": say(.potBusy)
        case "BLOCKED": say(.potBlocked)
        case "INVALID": say(.createPotInvalid)
        default: say(.createPotFailed)
        }
    }
}
