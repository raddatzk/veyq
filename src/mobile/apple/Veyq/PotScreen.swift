import SwiftUI
import VeyqCore

/// An open Pot (ADR 0122 decision 4: Expenses' bar is its open Pot's Entries, Overview and Settings, as the web's Pot has
/// them). What it shows comes from core's `PotView`; this only lays it out and formats money.
struct PotScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    let tab: String
    /// The editor's sheet: a new entry (`entryId` nil) or one being edited; core's editor is the app model's
    /// (`openEditor`), opened before the sheet shows.
    struct Editing: Identifiable {
        let id = UUID()
        let entryId: String?
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
                            if model.openEditor(nil) { editing = Editing(entryId: nil) }
                        }, onOpen: { id in
                            if model.openEditor(id) { editing = Editing(entryId: id) }
                        }, onOpenDeleted: { viewing = $0 })
                        }
                    }
                }
            } else if let state = model.pot, state.ref == ref, state.failed {
                ContentUnavailableView(say(.potOpenFailed), systemImage: "exclamationmark.triangle")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(ref.name)
        // An open Pot says its currency under its name (the web's header subtitle).
        .navigationSubtitle(model.pot?.ref == ref ? model.pot?.view?.currency.map { say(.potInCurrency, $0) } ?? "" : "")
        .task(id: ref.nodeId) { model.loadPot(ref) }
        // Live while the Pot is on screen and the app active: what another device writes shows up (core's `LivePoll`).
        .task(id: LiveKey(nodeId: ref.nodeId, loaded: model.pot?.view != nil, active: phase == .active)) {
            if model.pot?.view != nil, phase == .active { await model.livePot() }
        }
        .refreshable { await model.refreshPot() }
        // Files shared to veyq and placed here: a new expense with them as its receipts, where entries may be added.
        .task(id: ShareKey(waiting: model.incoming != nil, loaded: model.pot?.ref == ref && model.pot?.view != nil)) {
            // Only while the shell still shows this Pot: files given back (its editor closed unsaved) move the shell Home first.
            guard model.incoming != nil, model.shell.openObject == ref, model.pot?.ref == ref, let view = model.pot?.view, model.inbox.goesInto(view: view),
                  view.currency != nil, let files = model.takeShare() else { return }
            guard model.openEditor(nil, pending: files) else { model.returnShare(); return }
            editing = Editing(entryId: nil, fromShare: true)
            sharing = true
        }
        .sheet(item: $editing, onDismiss: {
            model.closeEditor()
            // A share's editor closed without its save: its files wait again in the pick mode (a save ended the share).
            if sharing { sharing = false; model.returnShare() }
        }) { e in
            if let view = model.pot?.view {
                EntryEditorSheet(model: model, view: view, entryId: e.entryId, onSaved: e.fromShare ? { sharing = false; model.shareSaved() } : nil)
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
    guard let id else { return "" }
    return say(piece: view.categoryLabel(id: id))
}

/// One piece of a row's line (core's `Piece`), written Apple's way.
func say(piece: Piece) -> String {
    switch piece {
    case let p as Piece.Said: say(notice: p.notice)
    case let p as Piece.Plain: p.text
    case let p as Piece.Day: localDay(p.iso)
    case let p as Piece.Size: ByteCountFormatter.string(fromByteCount: p.bytes, countStyle: .file)
    case let p as Piece.Money: money(p.amount, p.currency, p.minorUnits)
    default: ""
    }
}

func personName(_ name: String?) -> String { say(piece: PotView.companion.person(name: name)) }

/// The ledger check's notice (ADR 0108 decision 5): what the read found missing or altered, so the balances may be wrong.
private struct LedgerNotice: View {
    let view: PotView

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(say(.potLedgerMissing)).font(.subheadline)
            ForEach(Array(view.missing.enumerated()), id: \.offset) { _, m in
                Text(verbatim: "• \(Self.name(m, view))").font(.caption)
            }
        }
        .foregroundStyle(Role.danger)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    static func name(_ m: PotView.Missing, _ view: PotView) -> String { say(piece: view.missingName(m: m)) }
}

private struct EntriesList: View {
    let view: PotView
    let onAdd: () -> Void
    let onOpen: (String) -> Void
    let onOpenDeleted: (PotView.EntryRow) -> Void

    var body: some View {
        List {
            NodeHeroRow()
            if view.entries.isEmpty {
                Text(view.canWrite ? say(.potNoEntries) : say(.potNoEntriesReadonly)).foregroundStyle(Role.muted)
            }
            ForEach(view.entries, id: \.id) { e in
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(e.title ?? say(.potTransfer)).fontWeight(.semibold)
                        Text(subtitle(e)).font(.caption).foregroundStyle(Role.muted)
                        if let badge = view.badge(e: e) { Text(say(notice: badge.notice)).font(.caption2).foregroundStyle(badge.danger ? Role.danger : Role.muted) }
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
                                Text(e.title ?? say(.potTransfer))
                                Text(say(.entriesDeletedBadge)).font(.caption2)
                            }
                            Spacer()
                            if let amount = e.amount { Text(money(amount.int64Value, view.currency, view.minorUnits)) }
                        }
                        .foregroundStyle(Role.muted)
                        .listRowBackground(Role.surface)
                        .contentShape(Rectangle())
                        .onTapGesture { onOpenDeleted(e) }
                    }
                } header: { SectionLabel(title: say(.entriesDeletedTitle)) }
            }
        }
        .scrollContentBackground(.hidden)
        // beside the Pot's heart, share and menu
        .createAction(say(.potAdd), shown: view.canWrite && !view.people.isEmpty, action: onAdd)
    }

    private func subtitle(_ e: PotView.EntryRow) -> String {
        view.line(e: e).map { say(piece: $0) }.joined(separator: " · ")
    }

}

private struct OverviewList: View {
    @Bindable var model: AppModel
    let view: PotView

    private func record(_ payments: [PotView.SettleRow]) {
        let day = model.today
        Task { await model.potWrite { try await $0.recordPayments(payments: payments, date: day) } }
    }


    var body: some View {
        List {
            NodeHeroRow()
            Section {
                ForEach(view.balances, id: \.id) { p in
                    HStack(spacing: 12) {
                        PersonAvatar(name: personName(p.name), userId: p.userId)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(personName(p.name)).fontWeight(.semibold)
                            Text(view.balanceFacts(p: p).map { say(piece: $0) }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(Role.muted)
                        }
                        Spacer()
                        let b = view.balance(p: p)
                        Text(say(notice: b.notice)).foregroundStyle(b.tone == .owes ? Role.danger : b.tone == .owed ? Role.primary : Role.muted)
                    }
                    .listRowBackground(Role.surface)
                }
            } header: { SectionLabel(title: say(.potBalances)) }
            if !view.settleUp.isEmpty {
                Section {
                    ForEach(Array(view.settleUp.enumerated()), id: \.offset) { _, t in
                        HStack {
                            Text(say(notice: view.pays(t: t)))
                            Spacer()
                            Text(money(t.amount, view.currency, view.minorUnits)).fontWeight(.semibold)
                            if view.canWrite {
                                Button(say(.balancesRecord)) { record([t]) }.buttonStyle(.borderless).disabled(model.pot?.saving == true)
                            }
                        }
                        .listRowBackground(Role.surface)
                    }
                    if view.canRecordAll {
                        Button(say(.balancesRecordAll)) { record(view.settleUp) }.disabled(model.pot?.saving == true).listRowBackground(Role.surface)
                    }
                } header: {
                    SectionLabel(title: say(.potSettleUp))
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
                    HStack { Text(say(.potTotal)).fontWeight(.semibold); Spacer(); Text(money(view.total, view.currency, view.minorUnits)).fontWeight(.semibold) }
                        .listRowBackground(Role.surface)
                } header: { SectionLabel(title: say(.potByCategory)) }
            }
        }
        .scrollContentBackground(.hidden)
    }
}
