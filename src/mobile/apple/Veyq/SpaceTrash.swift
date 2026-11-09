import SwiftUI
import VeyqCore

/// The space's trash (the web's `SpaceTrashDialog.vue`): the root of each deleted subtree, with the day it was deleted and
/// the day it goes for good; Restore brings it back complete — where something took its name meanwhile, the row asks for
/// another and restores under it — and "Delete forever" erases it now, after the question that names it. A row someone
/// else restored or purged meanwhile leaves through a re-read. A kind the app cannot name offers no Restore.
struct SpaceTrashSheet: View {
    let model: AppModel
    let scopeId: String
    @Environment(\.dismiss) private var dismiss
    @State private var rows: [NodeTrash.Trashed]?
    @State private var failed = false
    @State private var busy: String?
    @State private var renaming: String?
    @State private var retryName = ""
    @State private var rowError: (id: String, message: String)?
    @State private var purging: NodeTrash.Trashed?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(say(.trashHint))
                        .font(.subheadline).foregroundStyle(Role.muted)
                        .listRowBackground(Color.clear)
                }
                if failed {
                    Text(say(.trashLoadFailed)).foregroundStyle(Role.danger)
                } else if let rows {
                    if rows.isEmpty {
                        Text(say(.trashEmpty)).foregroundStyle(Role.muted)
                    } else {
                        ForEach(rows, id: \.id) { row in rowView(row) }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .navigationTitle(say(.trashTitle))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { dismiss() } } }
            .task { await load() }
            .confirmationDialog(
                say(.trashPurgeConfirmTitle),
                isPresented: Binding(get: { purging != nil }, set: { if !$0 { purging = nil } }),
                titleVisibility: .visible,
                presenting: purging
            ) { row in
                Button(say(.trashPurge), role: .destructive) { Task { await purge(row) } }
                Button(say(.signinCancel), role: .cancel) {}
            } message: { row in
                Text(say(.trashPurgeConfirm, row.name))
            }
        }
    }

    @ViewBuilder private func rowView(_ row: NodeTrash.Trashed) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Label { Text(row.name).fontWeight(.semibold) } icon: { Tile(symbol: Names.kindSymbol(row.kind)) }
            Text(say(.trashRowDates, Self.day(row.deletedAt), Self.day(row.purgeAfter)))
                .font(.caption).foregroundStyle(Role.muted)
            if let e = rowError, e.id == row.id { Text(e.message).font(.caption).foregroundStyle(Role.danger) }
            if renaming == row.id {
                TextField(say(.trashRetryName), text: $retryName).textFieldStyle(.roundedBorder).disabled(busy == row.id)
                HStack {
                    Spacer()
                    Button(say(.signinCancel)) { renaming = nil }.disabled(busy == row.id)
                    Button(say(.entryRestore)) { Task { await restore(row, newName: retryName) } }
                        .disabled(busy == row.id || retryName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .buttonStyle(.borderless)
            } else {
                HStack {
                    Spacer()
                    if model.canRestore(row) {
                        Button(say(.entryRestore)) { Task { await restore(row, newName: nil) } }.disabled(busy == row.id)
                    }
                    Button(say(.trashPurge), role: .destructive) { purging = row }.disabled(busy == row.id)
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 4)
    }

    private func load() async {
        if let read = await model.trashOf(scopeId) { rows = read; failed = false } else { failed = true }
    }

    private func restore(_ row: NodeTrash.Trashed, newName: String?) async {
        rowError = nil
        busy = row.id
        defer { busy = nil }
        switch await model.restoreFromTrash(row, newName: newName) {
        case .restored:
            renaming = nil
            rows?.removeAll { $0.id == row.id }
            await load() // a child whose own name an earlier delete freed stays: a root of its own now
        case .nameTaken:
            rowError = (row.id, say(.nodeSlugTaken))
            renaming = row.id
            retryName = newName ?? row.name
        default:
            rowError = (row.id, say(.trashRestoreFailed))
        }
    }

    private func purge(_ row: NodeTrash.Trashed) async {
        rowError = nil
        busy = row.id
        defer { busy = nil }
        switch await model.purge(row) {
        case .purged: rows?.removeAll { $0.id == row.id }
        case .gone: await load()
        default: rowError = (row.id, say(.trashPurgeFailed))
        }
    }

    /// The day of a server instant (ISO 8601), in the device's words.
    private static func day(_ instant: String) -> String {
        let parse = ISO8601DateFormatter()
        parse.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = parse.date(from: instant) ?? ISO8601DateFormatter().date(from: instant)
        return date.map { $0.formatted(date: .abbreviated, time: .omitted) } ?? String(instant.prefix(10))
    }
}
