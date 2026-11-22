import SwiftUI
import VeyqCore

/// "New pot" (the web's `PotCreateDialog.vue`), drawn over core's `PotCreateSheet` (held by the app model): a name, the
/// space it goes to, the base currency — sealed into the node's meta at creation, fixed once the first entry exists — and
/// its first people, names typed or members of that space chosen (a member at most once, a name at most once, case
/// aside). The space, currency, people, the members read and the enable rule are core's; the name and a typed person
/// stay in their fields. Named apart from that class (a SwiftUI view must not shadow a core class).
struct NewPotSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var typed = ""

    /// The currencies offered, by code: core's one table (the web's `Intl`).
    private static var currencies: [String] { Currencies.shared.known }

    var body: some View {
        NavigationStack {
            Form {
                if let state = model.expenses.createState {
                    TextField(say(.peopleName), text: $name)
                    if state.spaces.count > 1 {
                        Picker(say(.createPotSpace), selection: Binding(get: { state.scopeId ?? "" }, set: { model.expenses.createSpace($0) })) {
                            ForEach(state.spaces, id: \.scopeId) { Text(Names.spaceName($0)).tag($0.scopeId) }
                        }
                    }
                    Section {
                        Picker(say(.potBaseCurrency), selection: Binding(get: { state.currency }, set: { model.expenses.createCurrency($0) })) {
                            ForEach(Self.currencies, id: \.self) { Text($0).tag($0) }
                        }
                    } footer: { Text(say(.settingsCurrencyHint)) }
                    Section {
                        ForEach(Array(state.people.enumerated()), id: \.offset) { i, p in
                            HStack {
                                Text(p.name)
                                Spacer()
                                Button { model.expenses.createRemove(i) } label: { Image(systemName: "xmark.circle.fill") }
                                    .buttonStyle(.borderless)
                                    .accessibilityLabel(say(.peopleRemove, p.name))
                            }
                        }
                        HStack {
                            TextField(say(.peopleName), text: $typed)
                            Button(say(.peopleAdd)) { if model.expenses.createAddTyped(typed) { typed = "" } }
                                .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                        ForEach(state.offered, id: \.userId) { m in
                            Button { model.expenses.createAddMember(m.userId) } label: {
                                Label(m.displayName, systemImage: "person.badge.plus")
                            }
                        }
                    } header: { Text(say(.potPeople)) }
                    if let notice = state.notice {
                        Section { Text(say(notice: notice)).foregroundStyle(Role.danger) }
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.createPot))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.createPotCreate)) {
                        let (n, t) = (name, typed)
                        typed = ""
                        Task { if await model.expenses.createPot(name: n, typed: t) { dismiss() } }
                    }
                    .disabled(!model.expenses.canCreatePot(name) || model.expenses.createState?.saving != false)
                }
            }
        }
        .tint(Role.accent)
        .sheetFrame(minWidth: 460, minHeight: 520)
        .onAppear { model.expenses.openCreateSheet() }
        .onDisappear { model.expenses.closeCreateSheet() }
    }
}
