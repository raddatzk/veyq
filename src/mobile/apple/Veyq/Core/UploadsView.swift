import Observation
import SwiftUI
import VeyqCore

/// The shell's uploads (the web's `UploadStatusMenu.vue`), as the system's own downloads button is: a toolbar button
/// while the app's upload queue holds a batch — a ring filling as the files go up, a check once all are in, a mark where
/// one did not go up — that shows every batch with each file's state, its cancel while it may still go up and its retry
/// where one helps. A batch all in leaves on its own after a moment; one with a failure stays until it is closed.
struct UploadsButton: View {
    let model: AppModel
    @State private var open = false

    /// The writes made offline the tray lists beside the batches (core's `QueuedTray`).
    private var queued: [QueuedItem] { model.queued?.items.value ?? [] }

    var body: some View {
        if let source = model.levelUploads, let state = source.tray?.queue, !state.batches.isEmpty || !queued.isEmpty {
            Button { open = true } label: { UploadsGlyph(state: state, waiting: !queued.isEmpty) }
                .accessibilityLabel(say(.uploadTitle))
                .accessibilityValue(state.active > 0 ? say(.uploadActive, state.active) : "")
                .popover(isPresented: $open) {
                    UploadsList(source: source, model: model)
                        .frame(minWidth: 340, minHeight: 360)
                        .presentationDetents([.medium, .large])
                }
        }
    }
}

/// The tray on the sign-in and the unlock (ADR 0145 decision 6, inbox #1250): the app's one queue alone (core's
/// `UploadsTray`), where a link guest's uploads left running signed out show — as a form's row with the shell's glyph and
/// the queue's one line, which opens the shell's list. With nothing listed it takes no room.
struct SignedOutUploadsSection: View {
    let tray: SignedOutTray
    @State private var open = false

    var body: some View {
        if let state = tray.tray?.queue, !state.batches.isEmpty {
            Section {
                Button { open = true } label: {
                    HStack(spacing: 12) {
                        UploadsGlyph(state: state, waiting: false)
                        Text(Notices.shared.uploadSummary(q: state).map { say(notice: $0) } ?? say(.uploadTitle)).foregroundStyle(Role.text)
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Role.muted)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(say(.uploadTitle))
                .accessibilityValue(state.active > 0 ? say(.uploadActive, state.active) : "")
                .accessibilityIdentifier("signed-out-uploads")
                .sheet(isPresented: $open) {
                    UploadsList(source: tray, model: nil)
                        .frame(minWidth: 340, minHeight: 360)
                        .presentationDetents([.medium, .large])
                }
            }
        }
    }
}

/// The tray's glyph: a ring filling as the files go up, a mark where one did not go up, an arrow while writes made offline
/// wait ([waiting]), else a check.
private struct UploadsGlyph: View {
    let state: UploadQueue.State
    let waiting: Bool

    var body: some View {
        if state.active > 0 {
            ZStack {
                Circle().stroke(Color.secondary.opacity(0.3), lineWidth: 2.5)
                Circle().trim(from: 0, to: state.progress)
                    .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Image(systemName: "arrow.up").font(.system(size: 10, weight: .bold))
            }
            .frame(width: 22, height: 22)
        } else if state.failed > 0 {
            Image(systemName: "exclamationmark.circle").foregroundStyle(.red)
        } else if waiting {
            // writes made offline wait to be sent
            Image(systemName: "arrow.up.circle")
        } else {
            Image(systemName: "checkmark.circle")
        }
    }
}

/// Every batch of the queue, each file with its state, its cancel and its retry; in the shell ([model] given) the writes
/// made offline above them.
private struct UploadsList: View {
    let source: any TrayActions
    let model: AppModel?
    @Environment(\.dismiss) private var dismiss

    private var queued: [QueuedItem] { model?.queued?.items.value ?? [] }

    var body: some View {
        NavigationStack {
            List {
                // the writes made offline, each under the place it writes into (ADR 0153 decision 5)
                if let model { QueuedTraySections(model: model, items: queued) }
                ForEach(source.tray?.queue.batches ?? [], id: \.id) { batch in
                    Section {
                        ForEach(batch.items, id: \.id) { item in UploadRow(source: source, item: item) }
                    } header: {
                        HStack {
                            Text(batch.label)
                            Spacer()
                            if batch.finished {
                                Button(say(.uploadClose)) { source.acknowledge(batch.id) }.font(.footnote)
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
                    if (source.tray?.queue.active ?? 0) > 0 {
                        Button(say(.uploadCancelAll), role: .destructive) { source.cancelAll() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { dismiss() } }
            }
            .onChange(of: (source.tray?.queue.batches.isEmpty ?? true) && queued.isEmpty) { _, empty in if empty { dismiss() } }
        }
    }
}

private struct UploadRow: View {
    let source: any TrayActions
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
                Button { source.cancel(item.id) } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(say(.signinCancel))
            } else if item.retryable {
                Button { source.retry(item.id) } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(say(.uploadRetry))
            }
        }
    }

    private var status: String { say(notice: Notices.shared.uploadStatus(item: item)) }
}

/// The tray over the app's one queue alone (core's `UploadsTray`), no application's: the sign-in's and the unlock's
/// (inbox #1250), its state on the main actor. Nothing it says, no folder it prepares. The process's, as the queue is.
@MainActor
@Observable
final class SignedOutTray: TrayActions {
    @ObservationIgnored private let core: UploadsTray
    @ObservationIgnored private var watch: UploadsTray.Watch?
    private(set) var state: UploadQueue.State

    init(queue: UploadQueue) {
        let core = UploadsTray(queue: queue)
        self.core = core
        state = core.current
        watch = core.watch { @Sendable [weak self] _ in
            Task { @MainActor in self?.sync() }
        }
    }

    /// The queue's state on the main actor.
    private func sync() { state = core.current }

    var tray: TrayShown? { TrayShown(queue: state, preparing: nil) }
    func cancelAll() { core.cancelAll() }
    func acknowledge(_ batchId: String) { core.acknowledge(batchId: batchId) }
    func cancel(_ id: String) { core.cancel(id: id) }
    func retry(_ id: String) { core.retry(id: id) }
}
