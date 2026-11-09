import SwiftUI
import VeyqCore

/// The shell's uploads (the web's `UploadStatusMenu.vue`), as the system's own downloads button is: a toolbar button
/// while the app's upload queue holds a batch — a ring filling as the files go up, a check once all are in, a mark where
/// one did not go up — that shows every batch with each file's state, its cancel while it may still go up and its retry
/// where one helps. A batch all in leaves on its own after a moment; one with a failure stays until it is closed.
struct UploadsButton: View {
    let model: AppModel
    @State private var open = false

    var body: some View {
        if let state = model.uploadState, !state.batches.isEmpty {
            let items = state.batches.flatMap { $0.items }.filter { $0.status != .cancelled }
            let failed = items.filter { $0.status == .failed }.count
            Button { open = true } label: { glyph(state: state, items: items, failed: failed) }
                .accessibilityLabel("Uploads")
                .accessibilityValue(state.active > 0 ? String(localized: "\(state.active) active") : "")
                .popover(isPresented: $open) {
                    UploadsList(model: model)
                        .frame(minWidth: 340, minHeight: 360)
                        .presentationDetents([.medium, .large])
                }
                // a batch all in is acknowledged after a moment; one with a failure waits for its close
                .task(id: state.batches.map { "\($0.id)/\($0.finished)" }.joined(separator: ",")) {
                    let clean = state.batches.filter { b in b.finished && !b.items.contains { $0.status == .failed } }
                    guard state.active == 0, !clean.isEmpty else { return }
                    try? await Task.sleep(for: .seconds(3))
                    guard !Task.isCancelled else { return }
                    for b in clean { model.uploads.acknowledge(batchId: b.id) }
                }
        }
    }

    @ViewBuilder private func glyph(state: UploadQueue.State, items: [UploadQueue.Item], failed: Int) -> some View {
        if state.active > 0 {
            let total = items.reduce(0.0) { $0 + Double(max($1.sizeBytes, 1)) }
            let done = items.reduce(0.0) { $0 + Double(max($1.sizeBytes, 1)) * $1.progress }
            ZStack {
                Circle().stroke(Color.secondary.opacity(0.3), lineWidth: 2.5)
                Circle().trim(from: 0, to: total > 0 ? done / total : 0)
                    .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Image(systemName: "arrow.up").font(.system(size: 10, weight: .bold))
            }
            .frame(width: 22, height: 22)
        } else if failed > 0 {
            Image(systemName: "exclamationmark.circle").foregroundStyle(.red)
        } else {
            Image(systemName: "checkmark.circle")
        }
    }
}

/// Every batch of the queue, each file with its state, its cancel and its retry.
private struct UploadsList: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(model.uploadState?.batches ?? [], id: \.id) { batch in
                    Section {
                        ForEach(batch.items, id: \.id) { item in UploadRow(model: model, item: item) }
                    } header: {
                        HStack {
                            Text(batch.label)
                            Spacer()
                            if batch.finished {
                                Button("Close") { model.uploads.acknowledge(batchId: batch.id) }.font(.footnote)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Uploads")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if (model.uploadState?.active ?? 0) > 0 {
                        Button("Cancel All", role: .destructive) { model.uploads.cancelAll() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onChange(of: model.uploadState?.batches.isEmpty ?? true) { _, empty in if empty { dismiss() } }
        }
    }
}

private struct UploadRow: View {
    let model: AppModel
    let item: UploadQueue.Item

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name).lineLimit(1).truncationMode(.middle)
                if item.status == .uploading {
                    ProgressView(value: item.progress)
                } else if item.status == .preparing {
                    ProgressView().progressViewStyle(.linear)
                }
                Text(status).font(.footnote).foregroundStyle(item.status == .failed ? Color.red : Color.secondary)
            }
            Spacer()
            if item.active {
                Button { model.uploads.cancel(id: item.id) } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Cancel")
            } else if item.retryable {
                Button { model.uploads.retry(id: item.id) } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Retry")
            }
        }
    }

    private var status: String {
        switch item.status {
        case .queued: String(localized: "Queued")
        case .preparing: String(localized: "Preparing…")
        case .uploading: String(localized: "Uploading…")
        case .done: String(localized: "Done")
        case .failed: model.uploadProblem(item) ?? String(localized: "Failed")
        case .cancelled: String(localized: "Cancelled")
        default: ""
        }
    }
}
