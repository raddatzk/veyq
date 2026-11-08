import SwiftUI
import VeyqCore

private func localDateTime(_ epochMs: Int64) -> String {
    Date(timeIntervalSince1970: Double(epochMs) / 1000).formatted(date: .abbreviated, time: .shortened)
}

/// An entry's history (`EntryHistory.vue`): every revision the Pot holds, newest first, what it said, when and by whom.
struct EntryHistory: View {
    let view: PotView
    let entryId: String
    @State private var open = false

    var body: some View {
        Button(open ? "Hide history" : "History") { open.toggle() }
        if open {
            ForEach(view.history(entryId: entryId), id: \.revision) { r in
                VStack(alignment: .leading, spacing: 2) {
                    Text(what(r))
                    Text("\(r.writer ?? String(localized: "Someone")), \(localDateTime(r.at))").font(.caption).foregroundStyle(Role.muted)
                }
            }
        }
    }

    private func what(_ r: PotView.HistoryRow) -> String {
        switch r.kind {
        case .money: [r.title, r.amount.map { money($0.int64Value, view.currency, view.minorUnits) }].compactMap { $0 }.joined(separator: " · ")
        case .deleted: String(localized: "Deleted")
        case .unreadable: String(localized: "A version this pot cannot read")
        default: String(localized: "Changed")
        }
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
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let restorable = view.restorable(entryId: row.id, nowMs: now)
        let purge = view.purgeAfter(entryId: row.id)?.int64Value
        NavigationStack {
            Form {
                Section {
                    if let amount = row.amount { Text(money(amount.int64Value, view.currency, view.minorUnits)).fontWeight(.semibold) }
                    Text("This entry is deleted and counts nowhere. Restoring it counts it again.")
                    if let purge {
                        if restorable {
                            Text("It can be restored until \(localDateTime(purge)); then its receipts are erased.")
                        } else {
                            Text("Its receipts are erased, so it can no longer be restored.")
                        }
                    }
                }
                Section { EntryHistory(view: view, entryId: row.id) }
                if let message = model.pot?.message { Section { Text(potRefusal(message)).foregroundStyle(Role.danger) } }
            }
            .formStyle(.grouped)
            .navigationTitle(row.title ?? String(localized: "Transfer"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { model.clearPotMessage(); dismiss() } }
                if restorable {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Restore") {
                            let id = row.id
                            let based = view.latestRevision(entryId: id)
                            Task { if await model.potWrite({ try await $0.restoreEntry(entryId: id, basedOn: based) }) { dismiss() } }
                        }
                        .disabled(model.pot?.saving == true)
                    }
                }
            }
        }
        .tint(Role.accent)
    }
}

extension PotView.EntryRow: @retroactive Identifiable {}
