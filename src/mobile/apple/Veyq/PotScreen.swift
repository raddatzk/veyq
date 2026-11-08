import SwiftUI
import VeyqCore

/// An open Pot (ADR 0122 decision 4: Expenses' bar is its open Pot's Entries, Overview and Settings, as the web's Pot has
/// them). What it shows comes from core's `PotView`; this only lays it out and formats money.
struct PotScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    let tab: String
    @State private var adding = false

    var body: some View {
        Group {
            if let state = model.pot, state.ref == ref, let view = state.view {
                if !view.ready {
                    ContentUnavailableView("No base currency yet", systemImage: "wallet.bifold", description: Text("Set one for this pot on the web."))
                } else {
                    switch tab {
                    case "expenses.overview": OverviewList(view: view)
                    case "expenses.settings": SettingsList(view: view)
                    default: EntriesList(view: view, onAdd: { adding = true })
                    }
                }
            } else if let state = model.pot, state.ref == ref, state.failed {
                ContentUnavailableView("This pot did not open", systemImage: "exclamationmark.triangle")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(ref.name)
        .task(id: ref.nodeId) { model.loadPot(ref) }
        .sheet(isPresented: $adding) {
            if let view = model.pot?.view { AddEntrySheet(model: model, view: view) }
        }
    }
}

/// Minor units of `currency` as the person's locale writes money.
func money(_ minor: Int64, _ currency: String?, _ minorUnits: Int32) -> String {
    let value = Decimal(minor) / pow(Decimal(10), Int(minorUnits))
    return value.formatted(.currency(code: currency ?? "EUR").precision(.fractionLength(Int(minorUnits))))
}

/// An entry's `YYYY-MM-DD` as the person's locale writes a day.
func localDay(_ iso: String) -> String {
    let parse = DateFormatter()
    parse.locale = Locale(identifier: "en_US_POSIX")
    parse.dateFormat = "yyyy-MM-dd"
    guard let date = parse.date(from: iso) else { return iso }
    return date.formatted(date: .abbreviated, time: .omitted)
}

func categoryName(_ id: String?, _ view: PotView) -> String {
    switch id {
    case "food": String(localized: "Food & drink")
    case "groceries": String(localized: "Groceries")
    case "accommodation": String(localized: "Accommodation")
    case "transport": String(localized: "Transport")
    case "leisure": String(localized: "Leisure")
    case "household": String(localized: "Household")
    case "other": String(localized: "Other")
    case nil: ""
    default: view.ownCategories.first { $0.first as String? == id }?.second as String? ?? String(localized: "Unknown category")
    }
}

func personName(_ name: String?) -> String {
    guard let name, !name.isEmpty else { return String(localized: "deleted person") }
    return name
}

private struct EntriesList: View {
    let view: PotView
    let onAdd: () -> Void

    var body: some View {
        List {
            if view.entries.isEmpty {
                Text("No entries yet. Add the first one.").foregroundStyle(Role.muted)
            }
            ForEach(view.entries, id: \.id) { e in
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(e.title ?? String(localized: "Transfer")).fontWeight(.semibold)
                        Text(subtitle(e)).font(.caption).foregroundStyle(Role.muted)
                        if let badge = badge(e) { Text(badge).font(.caption2).foregroundStyle(e.status == .live ? Role.muted : Role.danger) }
                    }
                    Spacer()
                    if let amount = e.amount { Text(money(amount.int64Value, view.currency, view.minorUnits)).fontWeight(.semibold) }
                }
                .listRowBackground(Role.surface)
            }
        }
        .scrollContentBackground(.hidden)
        .overlay(alignment: .bottomTrailing) {
            if view.canWrite && !view.people.isEmpty {
                // Creating is the web's green role (`--app-primary`).
                Button(action: onAdd) { Label("Add entry", systemImage: "plus") }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                    .tint(Role.primary)
                    .foregroundStyle(Role.onFill)
                    .padding()
            }
        }
    }

    private func subtitle(_ e: PotView.EntryRow) -> String {
        let who = e.who.map(personName)
        var parts: [String] = []
        if let date = e.date { parts.append(localDay(date)) }
        if let category = e.category { parts.append(categoryName(category, view)) }
        switch e.kind {
        case .expense: parts.append(String(localized: "Expense by \(who.joined(separator: ", "))"))
        case .refund: parts.append(String(localized: "Refund to \(who.joined(separator: ", "))"))
        case .transfer: parts.append(who.joined(separator: " → "))
        default: break
        }
        if let foreign = e.foreignAmount { parts.append(money(foreign.int64Value, e.foreignCurrency, e.foreignMinorUnits)) }
        return parts.joined(separator: " · ")
    }

    private func badge(_ e: PotView.EntryRow) -> String? {
        switch e.status {
        case .invalid: String(localized: "invalid")
        case .unreadable: String(localized: "unreadable")
        default: e.edited ? String(localized: "edited") : nil
        }
    }
}

private struct OverviewList: View {
    let view: PotView

    var body: some View {
        List {
            Section {
                ForEach(view.balances, id: \.id) { p in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(personName(p.name)).fontWeight(.semibold)
                            Text("paid \(money(p.paid, view.currency, view.minorUnits)) · consumed \(money(p.share, view.currency, view.minorUnits))")
                                .font(.caption).foregroundStyle(Role.muted)
                        }
                        Spacer()
                        if p.balance > 0 {
                            Text("is owed \(money(p.balance, view.currency, view.minorUnits))").foregroundStyle(Role.primary)
                        } else if p.balance < 0 {
                            Text("owes \(money(-p.balance, view.currency, view.minorUnits))").foregroundStyle(Role.danger)
                        } else {
                            Text("settled").foregroundStyle(Role.muted)
                        }
                    }
                    .listRowBackground(Role.surface)
                }
            } header: { SectionLabel(title: "Balances") }
            if !view.settleUp.isEmpty {
                Section {
                    ForEach(Array(view.settleUp.enumerated()), id: \.offset) { _, t in
                        HStack {
                            Text("\(personName(t.from)) pays \(personName(t.to))")
                            Spacer()
                            Text(money(t.amount, view.currency, view.minorUnits)).fontWeight(.semibold)
                        }
                        .listRowBackground(Role.surface)
                    }
                } header: { SectionLabel(title: "Settle up") }
            }
            if !view.byCategory.isEmpty {
                Section {
                    ForEach(view.byCategory, id: \.id) { c in
                        HStack { Text(categoryName(c.id, view)); Spacer(); Text(money(c.amount, view.currency, view.minorUnits)) }
                            .listRowBackground(Role.surface)
                    }
                    HStack { Text("Total").fontWeight(.semibold); Spacer(); Text(money(view.total, view.currency, view.minorUnits)).fontWeight(.semibold) }
                        .listRowBackground(Role.surface)
                } header: { SectionLabel(title: "By category") }
            }
        }
        .scrollContentBackground(.hidden)
    }
}

private struct SettingsList: View {
    let view: PotView

    var body: some View {
        List {
            Section { Text(view.currency ?? "").listRowBackground(Role.surface) } header: { SectionLabel(title: "Base currency") }
            Section {
                ForEach(view.people, id: \.id) { Text($0.name).listRowBackground(Role.surface) }
            } header: { SectionLabel(title: "People") }
            if !view.ownCategories.isEmpty {
                Section {
                    ForEach(Array(view.ownCategories.enumerated()), id: \.offset) { _, c in Text(c.second as String? ?? "").listRowBackground(Role.surface) }
                } header: { SectionLabel(title: "Own categories") }
            }
            Section { } footer: { Text("People and categories are changed on the web for now.") }
        }
        .scrollContentBackground(.hidden)
    }
}

/// A plain expense in the Pot's base currency: title, amount, who paid, the category, and who it is for (equally).
private struct AddEntrySheet: View {
    @Bindable var model: AppModel
    let view: PotView
    @Environment(\.dismiss) private var dismiss

    @State private var title = ""
    @State private var amount = ""
    @State private var payer = ""
    @State private var category = "other"
    @State private var forIds: Set<String> = []

    private static let defaults = ["food", "groceries", "accommodation", "transport", "leisure", "household", "other"]

    var body: some View {
        NavigationStack {
            Form {
                TextField("Title", text: $title)
                TextField("Amount", text: $amount)
                #if os(iOS)
                    .keyboardType(.decimalPad)
                #endif
                Picker("Paid by", selection: $payer) {
                    ForEach(view.people, id: \.id) { Text($0.name).tag($0.id) }
                }
                Picker("Category", selection: $category) {
                    ForEach(Self.defaults, id: \.self) { Text(categoryName($0, view)).tag($0) }
                    ForEach(Array(view.ownCategories.enumerated()), id: \.offset) { _, c in
                        Text(c.second as String? ?? "").tag(c.first as String? ?? "")
                    }
                }
                Section {
                    ForEach(view.people, id: \.id) { p in
                        Toggle(p.name, isOn: Binding(
                            get: { forIds.contains(p.id) },
                            set: { if $0 { forIds.insert(p.id) } else { forIds.remove(p.id) } }
                        ))
                    }
                } header: { Text("For") }
                if let message = model.pot?.message {
                    Section {
                        Text(Self.explain(message)).foregroundStyle(.red)
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Add entry")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            let ids = view.people.map(\.id).filter { forIds.contains($0) }
                            if await model.addExpense(title: title, amount: amount, payer: payer, beneficiaries: ids, category: category) { dismiss() }
                        }
                    }
                    .disabled(model.pot?.saving == true)
                }
            }
            .onAppear {
                payer = view.people.first?.id ?? ""
                forIds = Set(view.people.map(\.id))
            }
        }
        .tint(Role.accent)
    }

    static func explain(_ outcome: String) -> LocalizedStringKey {
        switch outcome {
        case "STALE": "Someone else changed the pot meanwhile. It was read again; save once more."
        case "REFUSED": "The server did not take the entry."
        default: "Check the title, the amount and who it is for."
        }
    }
}
