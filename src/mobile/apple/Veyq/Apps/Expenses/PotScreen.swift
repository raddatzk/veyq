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
            if let state = model.expenses.pot, state.ref == ref, let view = state.view {
                if !view.ready {
                    PotSettingsTab(model: model, view: view)
                } else {
                    VStack(spacing: 0) {
                        if !view.missing.isEmpty { LedgerNotice(view: view) }
                        switch tab {
                        case "expenses.overview": OverviewList(model: model, view: view)
                        case "expenses.settings": PotSettingsTab(model: model, view: view)
                        default: EntriesList(view: view, onAdd: {
                            if model.expenses.openEditor(nil) { editing = Editing(entryId: nil) }
                        }, onOpen: { id in
                            if model.expenses.openEditor(id) { editing = Editing(entryId: id) }
                        }, onOpenDeleted: { viewing = $0 })
                        }
                    }
                }
            } else if let state = model.expenses.pot, state.ref == ref, state.failed {
                // offline, a Pot this device does not keep says so (no failure: the cloud crossed out), else the open
                // failed (core's words)
                let why = state.failure ?? .potOpenFailed
                ContentUnavailableView(say(why), systemImage: why == .offlineNotKept ? "icloud.slash" : "exclamationmark.triangle")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(ref.name)
        // An open Pot says its currency under its name (the web's header subtitle).
        .navigationSubtitle(model.expenses.pot?.ref == ref ? model.expenses.pot?.view?.currency.map { say(.potInCurrency, $0) } ?? "" : "")
        .task(id: ref.nodeId) { model.expenses.loadPot(ref) }
        // Live while the Pot is on screen and the app active: what another device writes shows up (core's `LivePoll`).
        .task(id: LiveKey(nodeId: ref.nodeId, loaded: model.expenses.pot?.view != nil, active: phase == .active)) {
            if model.expenses.pot?.view != nil, phase == .active { await model.expenses.livePot() }
        }
        .refreshable { await model.expenses.refreshPot() }
        // Files shared to veyq and placed here: a new expense with them as its receipts, where entries may be added.
        .task(id: ShareKey(waiting: model.incoming != nil, loaded: model.expenses.pot?.ref == ref && model.expenses.pot?.view != nil)) {
            // Only while the shell still shows this Pot: files given back (its editor closed unsaved) move the shell Home first.
            guard model.incoming != nil, model.shell.openObject == ref, model.expenses.pot?.ref == ref, let view = model.expenses.pot?.view, PotShare.shared.goesInto(view: view),
                  view.currency != nil, let taken = await model.expenses.takeShare() else { return }
            guard model.expenses.openEditor(nil, pending: taken.pending) else { model.returnShare(); return }
            editing = Editing(entryId: nil, fromShare: true)
            sharing = true
        }
        // the tray's *Open* of a held entry: its editor over the newer version, once this Pot is read
        .task(id: HeldKey(write: model.expenses.heldOpen?.write, loaded: model.expenses.pot?.ref == ref && model.expenses.pot?.view != nil)) {
            guard let held = model.expenses.heldOpen, held.pot == ref.nodeId, model.expenses.pot?.ref == ref, model.expenses.pot?.view != nil else { return }
            model.expenses.heldOpen = nil
            if await model.expenses.openHeldEditor(held.write) { editing = Editing(entryId: held.entry) }
        }
        .sheet(item: $editing, onDismiss: {
            model.expenses.closeEditor()
            // A share's editor closed without its save: its files wait again in the pick mode (a save ended the share).
            if sharing { sharing = false; model.returnShare() }
        }) { e in
            if let view = model.expenses.pot?.view {
                EntryEditorSheet(model: model, view: view, entryId: e.entryId, onSaved: e.fromShare ? { sharing = false; model.shareSaved() } : nil)
            }
        }
        .sheet(item: $viewing) { row in
            if let view = model.expenses.pot?.view { DeletedEntrySheet(model: model, view: view, row: row) }
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

func categoryName(_ id: String?, _ view: PotView) -> String {
    guard let id else { return "" }
    return say(piece: view.categoryLabel(id: id))
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
                        // made offline, not sent yet (core's `unsentMark`)
                        if let unsent = view.unsentMark(row: e) { Text(say(notice: unsent)).font(.caption2).foregroundStyle(Role.muted) }
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
        // *Add entry* where the core offers it (`PotView.offersEntries`: a writer, or a READ_WRITE guest not named yet, asked
        // its name at the save), never by `canWrite`
        .createAction(say(.potAdd), shown: view.offersEntries, action: onAdd)
    }

    private func subtitle(_ e: PotView.EntryRow) -> String {
        pieceLine(view.line(e: e))
    }

}

private struct OverviewList: View {
    @Bindable var model: AppModel
    let view: PotView

    private func record(_ payments: [PotView.SettleRow]) {
        // dated the core's today (`PotModel.record`)
        Task { await model.expenses.recordSettleUp(payments) }
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
                            Text(pieceLine(view.balanceFacts(p: p)))
                                .font(.caption).foregroundStyle(Role.muted)
                        }
                        Spacer()
                        let b = view.balance(p: p)
                        Text(say(notice: b.notice)).foregroundStyle(b.tone == .owes ? Role.danger : b.tone == .owed ? Role.primary : Role.muted)
                    }
                    .listRowBackground(Role.surface)
                }
            } header: {
                SectionLabel(title: say(.potBalances))
            } footer: {
                // the balances count entries made offline and not sent yet (core's `balancesUnsent`)
                if let unsent = view.balancesUnsent { Text(say(notice: unsent)).foregroundStyle(Role.muted) }
            }
            if !view.settleUp.isEmpty {
                Section {
                    ForEach(Array(view.settleUp.enumerated()), id: \.offset) { _, t in
                        HStack {
                            Text(say(notice: view.pays(t: t)))
                            Spacer()
                            Text(money(t.amount, view.currency, view.minorUnits)).fontWeight(.semibold)
                            // a payment never queues: *Record* where the core offers it (`canRecord`), never by `canWrite`
                            if view.canRecord {
                                Button(say(.balancesRecord)) { record([t]) }.buttonStyle(.borderless).disabled(model.expenses.pot?.saving == true)
                            }
                        }
                        .listRowBackground(Role.surface)
                    }
                    if view.canRecordAll {
                        Button(say(.balancesRecordAll)) { record(view.settleUp) }.disabled(model.expenses.pot?.saving == true).listRowBackground(Role.surface)
                    }
                } header: {
                    SectionLabel(title: say(.potSettleUp))
                } footer: {
                    if let message = model.expenses.pot?.message { Text(potRefusal(message)).foregroundStyle(Role.danger) }
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

/// What the held editor's opening waits on: the write asked for and whether the Pot is read.
private struct HeldKey: Hashable {
    let write: String?
    let loaded: Bool
}
