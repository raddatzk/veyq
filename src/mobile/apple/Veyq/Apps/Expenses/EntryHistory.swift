import SwiftUI
import VeyqCore

/// An entry's history (`EntryHistory.vue`): every revision the Pot holds, newest first, what it said, when and by whom.
struct EntryHistory: View {
    let view: PotView
    let entryId: String
    @State private var open = false

    var body: some View {
        Button(open ? say(.historyHide) : say(.historyShow)) { open.toggle() }
        if open {
            ForEach(view.history(entryId: entryId), id: \.revision) { r in
                VStack(alignment: .leading, spacing: 2) {
                    Text(what(r))
                    Text(verbatim: "\(r.writer ?? say(.historyUnknownWriter)), \(shownMoment(r.at))").font(.caption).foregroundStyle(Role.muted)
                }
            }
        }
    }

    private func what(_ r: PotView.HistoryRow) -> String {
        pieceLine(view.historyLine(r: r))
    }
}

/// A deleted entry (`EntryEditor.vue` on a tombstone): it counts nowhere; restorable until its receipts are erased, then
/// a line in the history only.
struct DeletedEntrySheet: View {
    @Bindable var model: AppModel
    let view: PotView
    let row: PotView.EntryRow
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        // the window on the core's clock (`PotModel.restorable`), never the platform's
        let restorable = model.expenses.restorable(row.id)
        let purge = view.purgeAfter(entryId: row.id)?.int64Value
        NavigationStack {
            Form {
                Section {
                    if let amount = row.amount { Text(money(amount.int64Value, view.currency, view.minorUnits)).fontWeight(.semibold) }
                    Text(say(.entryDeletedHint))
                    if let purge {
                        if restorable {
                            Text(say(.entryRestorableUntil, shownMoment(purge)))
                        } else {
                            Text(say(.entryNotRestorable))
                        }
                    }
                }
                Section { EntryHistory(view: view, entryId: row.id) }
                if let message = model.expenses.pot?.message { Section { Text(potRefusal(message)).foregroundStyle(Role.danger) } }
            }
            .formStyle(.grouped)
            .navigationTitle(row.title ?? say(.potTransfer))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { model.expenses.clearPotMessage(); dismiss() } }
                if restorable {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(say(.entryRestore)) {
                            let id = row.id
                            Task { if await model.expenses.restoreEntry(id) { dismiss() } }
                        }
                        .disabled(model.expenses.pot?.saving == true)
                    }
                }
            }
        }
        .tint(Role.accent)
        .sheetFrame(minWidth: 460, minHeight: 440)
    }
}

extension PotView.EntryRow: @retroactive Identifiable {}
