#if os(macOS)
import SwiftUI
import VeyqCore

/// A download's import on a Mac (ADR 0152 decision 5; core's `ImportDialog`, the web's `ImportSubtreeDialog.vue`): offered
/// by an upload's screen — *Upload files*, *Upload folder*, a drop onto the level, *Add here* — never from an entry of its
/// own. A sheet: reading, then what would be created and where, what is left out per application, the room it needs, then
/// the run in nodes done of total; *Import* (or *Import the rest*) its confirming action, *Upload as a file* beside it for a
/// ZIP, *Cancel* stopping a run and trashing what it made. Once done the sheet closes and the shell says the imported line.
/// Every rule is the core's; the phones offer no import (owner 2026-11-19).
struct ImportSheet: View {
    let model: AppModel
    let dialog: ImportDialog
    @State private var state: CoreState<ImportDialog.State>?

    var body: some View {
        let st = state?.value ?? dialog.current
        NavigationStack {
            Form { content(st) }
                .formStyle(.grouped)
                .navigationTitle(say(.importTitle))
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dialog.cancel() } }
                    if st.canAsFile {
                        ToolbarItem(placement: .automatic) {
                            Button(say(.importUploadAsFile)) { dialog.uploadAsFile() }.disabled(st.step == .running)
                        }
                    }
                    if offersImport(st) {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(say(st.importWord)) { dialog.`import`() }.disabled(!st.canImport)
                        }
                    }
                }
        }
        // the sheet closes by its buttons only: Cancel (Esc) and a run's end go through the core
        .interactiveDismissDisabled()
        // a Mac's sheet takes its content's ideal size, which a Form has none of
        .frame(minWidth: 460, minHeight: 360)
        .task {
            guard state == nil else { return }
            let d = dialog
            state = CoreState({ d.current }) { changed in AppleMemories.shared.watch(flow: d.state) { @Sendable _ in changed() } }
        }
        .onChange(of: st.step) { _, step in
            // done: the imported line is the shell's to say once the sheet is gone (the web's toast)
            if step == .done {
                model.importedLine = st.said.map { say(notice: $0) }.joined(separator: "\n")
                dialog.close()
            }
        }
        .onChange(of: st.closed) { _, closed in
            if closed { model.importing = nil }
        }
        .onDisappear {
            state?.stop()
            if !dialog.current.closed { dialog.close() }
        }
    }

    /// *Import* shows while the download reads and wherever something may be imported (the web's `step === 'reading' ||
    /// canImport`), shut while it reads, runs or does not fit.
    private func offersImport(_ st: ImportDialog.State) -> Bool {
        switch st.step {
        case .reading: true
        case .ready, .running: st.nodes > 0
        default: false
        }
    }

    @ViewBuilder
    private func content(_ st: ImportDialog.State) -> some View {
        switch st.step {
        case .reading:
            Section {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(say(.importReading))
                }
            }
        case .invalid:
            Section { said(st) }
        default:
            Section {
                Text(st.root).bold()
                if let summary = st.summary, st.fits?.boolValue != false { Text(say(notice: summary)) }
            }
            if !st.leftOut.isEmpty {
                Section(say(.importSkipped)) {
                    ForEach(Array(st.leftOut.enumerated()), id: \.offset) { _, n in
                        Text(say(notice: n)).foregroundStyle(Role.muted)
                    }
                }
            }
            if st.step == .running, let p = st.progress {
                Section {
                    Text(say(.importImporting, Int(p.nodesDone), Int(p.nodesTotal)))
                    ProgressView(value: Double(p.nodesDone), total: Double(max(1, p.nodesTotal)))
                }
            } else if st.canImport {
                Section { Text(say(.importNotice)).font(.callout).foregroundStyle(Role.muted) }
            }
            if !st.said.isEmpty, st.step != .done { Section { said(st) } }
        }
    }

    /// What the dialog says: a refusal, a failure, no room or nothing to import.
    private func said(_ st: ImportDialog.State) -> some View {
        ForEach(Array(st.said.enumerated()), id: \.offset) { _, n in
            Text(say(notice: n)).foregroundStyle(st.step == .invalid || n.msg == .importNothing ? Role.text : Role.danger)
        }
    }
}
#endif
