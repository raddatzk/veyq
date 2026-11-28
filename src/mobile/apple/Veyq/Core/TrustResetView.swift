import SwiftUI
import VeyqCore

/// *Start a list over* (core's `TrustReset`, ADR 0028 decision 7; the web's `TrustResetPanel` and `TrustResetDialog`): each
/// list this device refuses — its name, why, *Start Over…* — and the question before it is started over, a confirmation
/// dialog with what it costs, that the server can wait, the acknowledgement (unticked at every open) and the destructive
/// *Start Over*. Core decides every row, word and outcome; no password or key is asked.
struct TrustResetView: View {
    let model: AppModel
    @State private var held: HeldFlow<TrustReset, TrustReset.State>?

    private var state: TrustReset.State? { held?.value }

    var body: some View {
        Form {
            Section {
                if let said = state?.said { Text(say(said)).accessibilityIdentifier("trust-reset-said") }
                if state == nil || state?.loading == true {
                    ProgressView()
                } else if let failed = state?.loadFailed {
                    Text(say(failed)).foregroundStyle(Role.danger)
                } else if state?.none == true {
                    Text(say(.trustResetNone))
                }
            } footer: {
                Text(say(.trustResetSub))
            }
            ForEach(state?.rows ?? [], id: \.document) { row in
                Section {
                    LabeledContent(say(row.title)) { EmptyView() }
                    Text(say(row.why)).foregroundStyle(Role.muted)
                    Button(say(.trustResetAction), role: .destructive) {
                        held?.model.ask(document: row.document)
                        held?.state.refresh()
                    }
                    .accessibilityIdentifier("trust-reset-ask")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(say(.trustResetTitle))
        .sheet(isPresented: Binding(get: { state?.asking != nil }, set: { if !$0 { cancel() } })) {
            if let ask = state?.asking { question(ask) }
        }
        .onAppear {
            guard held == nil, let m = model.trustReset() else { return }
            let h = HeldFlow(m, current: { $0.current }, flow: { $0.state })
            held = h
            Task {
                try? await m.load()
                h.state.refresh()
            }
        }
        .onDisappear {
            held?.stop()
            held = nil
            model.trustResetClosed()
        }
    }

    private func cancel() {
        held?.model.cancel()
        held?.state.refresh()
    }

    private func question(_ ask: TrustReset.ResetQuestion) -> some View {
        NavigationStack {
            Form {
                Section {
                    Text(say(ask.cost))
                    Text(say(.trustResetServerCanWait)).foregroundStyle(Role.muted)
                    Toggle(say(.trustResetAcknowledge), isOn: Binding(get: { ask.acknowledged }, set: { on in
                        held?.model.acknowledge(on: on)
                        held?.state.refresh()
                    }))
                    .disabled(ask.working)
                    .accessibilityIdentifier("trust-reset-acknowledge")
                    if let failed = ask.failed { Text(say(failed)).foregroundStyle(Role.danger) }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(ask.title))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(say(.signinCancel)) { cancel() }.disabled(ask.working)
                }
                ToolbarItem(placement: .destructiveAction) {
                    Button(say(.trustResetConfirm), role: .destructive) {
                        guard let h = held else { return }
                        Task {
                            try? await h.model.confirm()
                            h.state.refresh()
                        }
                    }
                    .disabled(!ask.acknowledged || ask.working)
                    .accessibilityIdentifier("trust-reset-confirm")
                }
            }
            .overlay { if ask.working { ProgressView() } }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 320)
        #endif
        .interactiveDismissDisabled(ask.working)
    }
}
