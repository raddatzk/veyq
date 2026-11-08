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
                TextField("Name", text: $name)
                if model.spaces.count > 1 {
                    Picker("Space", selection: $scopeId) {
                        ForEach(model.spaces, id: \.scopeId) { Text(Names.spaceName($0)).tag($0.scopeId) }
                    }
                }
                Section {
                    Picker("Base currency", selection: $currency) {
                        ForEach(Self.currencies, id: \.self) { Text($0).tag($0) }
                    }
                } footer: { Text("Every balance is in this currency. It is fixed once the first entry is saved.") }
                Section {
                    ForEach(Array(people.enumerated()), id: \.offset) { _, p in
                        HStack {
                            Text(p.name)
                            Spacer()
                            Button { people.removeAll { key($0) == key(p) } } label: { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.borderless)
                                .accessibilityLabel("Remove \(p.name)")
                        }
                    }
                    HStack {
                        TextField("Name", text: $typed)
                        Button("Add person", action: takeTyped)
                            .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    ForEach(members.filter { m in !people.contains { $0.userId == m.userId } }, id: \.userId) { m in
                        Button { add(PotCreate.NewPerson.companion.member(userId: m.userId, displayName: m.displayName)) } label: {
                            Label(m.displayName, systemImage: "person.badge.plus")
                        }
                    }
                } header: { Text("People") }
                if let message = model.creatingPot.message {
                    Section { Text(Self.explain(message)).foregroundStyle(Role.danger) }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("New pot")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { model.clearCreatingPot(); dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
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

    static func explain(_ outcome: String) -> LocalizedStringKey {
        switch outcome {
        case "NAME_TAKEN": "There is already a pot with this name here."
        case "PEOPLE_NOT_ADDED": "The pot was created, its people were not. Add them in its settings."
        case "BUSY": "The pot’s key is being renewed. Try again in a moment."
        case "BLOCKED": "The operator blocked this pot."
        case "INVALID": "Check the name and the people: 1 to 100 characters each."
        default: "The pot was not created. Please try again."
        }
    }
}
