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
    @State private var box = TrashBox()
    private var sheet: TrashSheet? { box.sheet }
    private var st: TrashSheet.State { box.state }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(say(.trashHint))
                        .font(.subheadline).foregroundStyle(Role.muted)
                        .listRowBackground(Color.clear)
                }
                if st.failed {
                    Text(say(.trashLoadFailed)).foregroundStyle(Role.danger)
                } else if let rows = st.rows {
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
            .task {
                guard box.sheet == nil, let made = model.trashSheet(scopeId) else { return }
                box.start(made)
                try? await made.load()
            }
            .onDisappear { box.stop() }
            .confirmationDialog(
                say(.trashPurgeConfirmTitle),
                isPresented: Binding(get: { st.purging != nil }, set: { if !$0 { sheet?.cancelPurge() } }),
                titleVisibility: .visible,
                presenting: st.purging
            ) { row in
                // the row is handed in: the dialog's binding clears the question before this action runs
                Button(say(.trashPurge), role: .destructive) {
                    Task { try? await sheet?.purge(row: row) }
                }
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
            if st.errorRow == row.id, let error = st.error { Text(say(error)).font(.caption).foregroundStyle(Role.danger) }
            let busy = st.busy == row.id
            if st.renaming == row.id {
                TextField(say(.trashRetryName), text: Binding(get: { st.retryName }, set: { sheet?.type(name: $0) }))
                    .textFieldStyle(.roundedBorder).disabled(busy)
                HStack {
                    Spacer()
                    Button(say(.signinCancel)) { sheet?.cancelRename() }.disabled(busy)
                    Button(say(.entryRestore)) { Task { try? await sheet?.retry(row: row) } }.disabled(!st.retryable(row: row))
                }
                .buttonStyle(.borderless)
            } else {
                HStack {
                    Spacer()
                    if sheet?.canRestore(row: row) == true {
                        Button(say(.entryRestore)) { Task { try? await sheet?.restore(row: row, newName: nil) } }.disabled(busy)
                    }
                    Button(say(.trashPurge), role: .destructive) { sheet?.askPurge(row: row) }.disabled(busy)
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 4)
    }

    /// The day of a server instant (ISO 8601), in the device's words.
    private static func day(_ instant: String) -> String {
        let parse = ISO8601DateFormatter()
        parse.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = parse.date(from: instant) ?? ISO8601DateFormatter().date(from: instant)
        return date.map { $0.formatted(date: .abbreviated, time: .omitted) } ?? String(instant.prefix(10))
    }
}

/// Core's trash sheet held for SwiftUI: its state mirrored on the main actor as it changes.
@MainActor
@Observable
final class TrashBox {
    private(set) var sheet: TrashSheet?
    private(set) var state = TrashSheet.State(rows: nil, failed: false, busy: nil, errorRow: nil, error: nil, renaming: nil, retryName: "", purging: nil)
    @ObservationIgnored private var watch: TrashSheet.Watch?

    func start(_ sheet: TrashSheet) {
        self.sheet = sheet
        watch = sheet.watch { @Sendable _ in
            Task { @MainActor [weak self] in
                if let current = self?.sheet?.current { self?.state = current }
            }
        }
    }

    func stop() { watch?.stop() }
}
