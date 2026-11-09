import SwiftUI
import VeyqCore

/// An open Pot (ADR 0122 decision 4: Expenses' bar is its open Pot's Entries, Overview and Settings, as the web's Pot has
/// them). What it shows comes from core's `PotView`; this only lays it out and formats money.
struct PotScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    let tab: String
    /// The editor's sheet: a new entry (`entryId` nil) or one being edited.
    struct Editing: Identifiable {
        let id = UUID()
        let entryId: String?
        let basedOn: Int64?
        let people: [PotView.Person]
        let form: EntryForm
        var invalid = false
        /// What another app shared, as the new expense's receipts (ADR 0124).
        var pending: [PotSession.PendingReceipt] = []
        var fromShare = false
    }

    @State private var editing: Editing?
    /// The editor shows what another app shared: its close ends the share.
    @State private var sharing = false
    @State private var viewing: PotView.EntryRow?
    @Environment(\.scenePhase) private var phase

    var body: some View {
        Group {
            if let state = model.pot, state.ref == ref, let view = state.view {
                if !view.ready {
                    PotSettings(model: model, view: view)
                } else {
                    VStack(spacing: 0) {
                        if !view.missing.isEmpty { LedgerNotice(view: view) }
                        switch tab {
                        case "expenses.overview": OverviewList(model: model, view: view)
                        case "expenses.settings": PotSettings(model: model, view: view)
                        default: EntriesList(view: view, onAdd: {
                            if let f = view.blankForm(date: FormState.dayString(Date()), separator: decimalSeparator) {
                                editing = Editing(entryId: nil, basedOn: nil, people: view.people, form: f)
                            }
                        }, onOpen: { id in
                            if let e = view.editing(entryId: id, separator: decimalSeparator, today: FormState.dayString(Date())) {
                                editing = Editing(entryId: id, basedOn: e.basedOn, people: e.people, form: e.form, invalid: e.status == .invalid)
                            }
                        }, onOpenDeleted: { viewing = $0 })
                        }
                    }
                }
            } else if let state = model.pot, state.ref == ref, state.failed {
                ContentUnavailableView("This pot did not open", systemImage: "exclamationmark.triangle")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(ref.name)
        // An open Pot says its currency under its name (the web's header subtitle).
        .navigationSubtitle(model.pot?.ref == ref ? model.pot?.view?.currency.map { String(localized: "In \($0)") } ?? "" : "")
        .task(id: ref.nodeId) { model.loadPot(ref) }
        // Live while the Pot is on screen and the app active: what another device writes shows up (core's `LivePoll`).
        .task(id: LiveKey(nodeId: ref.nodeId, loaded: model.pot?.view != nil, active: phase == .active)) {
            if model.pot?.view != nil, phase == .active { await model.livePot() }
        }
        .refreshable { await model.refreshPot() }
        // Files shared to veyq and placed here: a new expense with them as its receipts, where entries may be added.
        .task(id: ShareKey(waiting: model.incoming != nil, loaded: model.pot?.ref == ref && model.pot?.view != nil)) {
            // Only while the shell still shows this Pot: files given back (its editor closed unsaved) move the shell Home first.
            guard model.incoming != nil, model.shell.openObject == ref, model.pot?.ref == ref, let view = model.pot?.view, view.canWrite, !view.people.isEmpty,
                  let f = view.blankForm(date: FormState.dayString(Date()), separator: decimalSeparator), let files = model.takeShare() else { return }
            editing = Editing(entryId: nil, basedOn: nil, people: view.people, form: f, pending: files, fromShare: true)
            sharing = true
        }
        .sheet(item: $editing, onDismiss: {
            // A share's editor closed without its save: its files wait again in the pick mode (a save ended the share).
            if sharing { sharing = false; model.returnShare() }
        }) { e in
            if let view = model.pot?.view {
                EntryEditor(
                    model: model, view: view, entryId: e.entryId, basedOn: e.basedOn, people: e.people, form: FormState(e.form), invalid: e.invalid,
                    initialPending: e.pending, onSaved: e.fromShare ? { sharing = false; model.shareSaved() } : nil
                )
            }
        }
        .sheet(item: $viewing) { row in
            if let view = model.pot?.view { DeletedEntrySheet(model: model, view: view, row: row) }
        }
    }
}

/// What starts a shared file's new expense: files waiting, and this Pot read.
private struct ShareKey: Equatable {
    let waiting: Bool
    let loaded: Bool
}

/// What restarts a Pot's live poll: another Pot, its first read, the app becoming active or not.
private struct LiveKey: Equatable {
    let nodeId: String
    let loaded: Bool
    let active: Bool
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

/// The ledger check's notice (ADR 0108 decision 5): what the read found missing or altered, so the balances may be wrong.
private struct LedgerNotice: View {
    let view: PotView

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Changes to these entries are missing, so the balances may be wrong:").font(.subheadline)
            ForEach(Array(view.missing.enumerated()), id: \.offset) { _, m in
                Text("• \(Self.name(m))").font(.caption)
            }
        }
        .foregroundStyle(Role.danger)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    static func name(_ m: PotView.Missing) -> String {
        switch m.kind {
        case .transfer: String(localized: "Transfer")
        case .erasedPerson: String(localized: "deleted person")
        case .unknown: String(localized: "An entry this pot no longer shows")
        default: m.name
        }
    }
}

private struct EntriesList: View {
    let view: PotView
    let onAdd: () -> Void
    let onOpen: (String) -> Void
    let onOpenDeleted: (PotView.EntryRow) -> Void

    var body: some View {
        List {
            if view.entries.isEmpty {
                Text(view.canWrite ? "No entries yet. Add the first one." : "No entries yet.").foregroundStyle(Role.muted)
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
                .contentShape(Rectangle())
                // Every viewer opens an entry: a writer to edit it, a reader to read it (one dialog, inbox #401).
                .onTapGesture { onOpen(e.id) }
            }
            if !view.deletedEntries.isEmpty {
                Section {
                    ForEach(view.deletedEntries, id: \.id) { e in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(e.title ?? String(localized: "Transfer"))
                                Text("deleted").font(.caption2)
                            }
                            Spacer()
                            if let amount = e.amount { Text(money(amount.int64Value, view.currency, view.minorUnits)) }
                        }
                        .foregroundStyle(Role.muted)
                        .listRowBackground(Role.surface)
                        .contentShape(Rectangle())
                        .onTapGesture { onOpenDeleted(e) }
                    }
                } header: { SectionLabel(title: "Deleted entries") }
            }
        }
        .scrollContentBackground(.hidden)
        // The view's primary action in the navigation bar (Apple's HIG: iOS has no floating action button; Notes, Reminders
        // and Mail put "new" in a bar), beside the Pot's heart, share and menu; the web's and Android's green button is theirs.
        .toolbar {
            if view.canWrite && !view.people.isEmpty {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: onAdd) { Image(systemName: "plus") }
                        .accessibilityLabel("Add entry")
                }
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
    @Bindable var model: AppModel
    let view: PotView

    private func record(_ payments: [PotView.SettleRow]) {
        let day = FormState.dayString(Date())
        Task { await model.potWrite { try await $0.recordPayments(payments: payments, date: day) } }
    }

    /// What transfers moved, where there were any: " · transferred …" or " · received …".
    private func transfers(_ p: PotView.PersonRow) -> String {
        if p.settled > 0 { return " · " + String(localized: "transferred \(money(p.settled, view.currency, view.minorUnits))") }
        if p.settled < 0 { return " · " + String(localized: "received \(money(-p.settled, view.currency, view.minorUnits))") }
        return ""
    }

    var body: some View {
        List {
            Section {
                ForEach(view.balances, id: \.id) { p in
                    HStack(spacing: 12) {
                        PersonAvatar(name: personName(p.name), userId: p.userId)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(personName(p.name)).fontWeight(.semibold)
                            Text("paid \(money(p.paid, view.currency, view.minorUnits)) · consumed \(money(p.share, view.currency, view.minorUnits))" + transfers(p))
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
                            if view.canWrite {
                                Button("Record") { record([t]) }.buttonStyle(.borderless).disabled(model.pot?.saving == true)
                            }
                        }
                        .listRowBackground(Role.surface)
                    }
                    if view.canWrite && view.settleUp.count > 1 {
                        Button("Record all") { record(view.settleUp) }.disabled(model.pot?.saving == true).listRowBackground(Role.surface)
                    }
                } header: {
                    SectionLabel(title: "Settle up")
                } footer: {
                    if let message = model.pot?.message { Text(potRefusal(message)).foregroundStyle(Role.danger) }
                }
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
