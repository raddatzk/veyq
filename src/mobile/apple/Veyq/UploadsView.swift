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
        if let state = model.depotState?.queue, !state.batches.isEmpty {
            Button { open = true } label: { glyph(state: state, failed: Int(state.failed)) }
                .accessibilityLabel(say(.uploadTitle))
                .accessibilityValue(state.active > 0 ? say(.uploadActive, state.active) : "")
                .popover(isPresented: $open) {
                    UploadsList(model: model)
                        .frame(minWidth: 340, minHeight: 360)
                        .presentationDetents([.medium, .large])
                }
        }
    }

    @ViewBuilder private func glyph(state: UploadQueue.State, failed: Int) -> some View {
        if state.active > 0 {
            ZStack {
                Circle().stroke(Color.secondary.opacity(0.3), lineWidth: 2.5)
                Circle().trim(from: 0, to: state.progress)
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
                ForEach(model.depotState?.queue.batches ?? [], id: \.id) { batch in
                    Section {
                        ForEach(batch.items, id: \.id) { item in UploadRow(model: model, item: item) }
                    } header: {
                        HStack {
                            Text(batch.label)
                            Spacer()
                            if batch.finished {
                                Button(say(.uploadClose)) { model.depot?.acknowledge(batchId: batch.id) }.font(.footnote)
                            }
                        }
                    }
                }
            }
            .navigationTitle(say(.uploadTitle))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if (model.depotState?.queue.active ?? 0) > 0 {
                        Button(say(.uploadCancelAll), role: .destructive) { model.depot?.cancelAll() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { dismiss() } }
            }
            .onChange(of: model.depotState?.queue.batches.isEmpty ?? true) { _, empty in if empty { dismiss() } }
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
                Button { model.depot?.cancel(id: item.id) } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(say(.signinCancel))
            } else if item.retryable {
                Button { model.depot?.retry(id: item.id) } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(say(.uploadRetry))
            }
        }
    }

    private var status: String { say(notice: Notices.shared.uploadStatus(item: item)) }
}
