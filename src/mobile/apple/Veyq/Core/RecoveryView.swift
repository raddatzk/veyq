import SwiftUI
import VeyqCore

/// One recovery's finish on screen (core's `RecoveryFinish`): its state on the main actor. The finish holds the link's
/// token, the Secret Key and the Master Key until it closes (`RecoveryFinish.close`, which zeroes them).
@MainActor
@Observable
final class RecoveryHeld: Identifiable {
    let finish: RecoveryFinish
    let state: CoreState<RecoveryFinish.State>
    @ObservationIgnored private var closed = false

    init(_ finish: RecoveryFinish) {
        self.finish = finish
        state = CoreState({ finish.current }) { changed in
            ApplePlatform.shared.watch(flow: finish.state) { @Sendable _ in changed() }
        }
    }

    /// The link's material released once: the key step, or why the link no longer works.
    func load() { run { try await $0.load() } }

    /// One of the finish's steps, its state read again once it ended.
    func run(_ step: @escaping @MainActor (RecoveryFinish) async throws -> Void) {
        let finish = finish
        Task {
            try? await step(finish)
            state.refresh()
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        state.stop()
        finish.close()
    }
}

/// Finishing a password recovery from its mailed link (uc.account.recover-access; the web's `RecoverView` over core's
/// `RecoveryFinish`): a screen above everything, as a join is, drawing the web's steps — the link checked or why not, the
/// Secret Key (the flows' key section: typed, a pasted kit token, a kit file, a scan), a new password with its
/// breached-password check, and done, whose *Sign in* opens the sign-in filled with the account. The Secret Key and a
/// password are typed here, so the screen is `.secretOnScreen()` (§ Secrets on screen). *Close* lets the finish go.
struct RecoveryView: View {
    let model: AppModel
    let held: RecoveryHeld
    @State private var secretKey = ""
    @State private var kitHeld = false
    @State private var next = ""
    @State private var confirm = ""

    private var state: RecoveryFinish.State { held.state.value }

    var body: some View {
        NavigationStack {
            Form { content }
                .formStyle(.grouped)
                .disabled(state.working)
                .navigationTitle(say(state.title))
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(say(.receiptsClose)) { model.guests.closeRecovery(held) }
                            .accessibilityIdentifier("recovery-close")
                    }
                }
                .overlay { if state.working { ProgressView() } }
        }
        .tint(Role.accent)
        .secretOnScreen()
        .accessibilityIdentifier("recovery")
    }

    @ViewBuilder private var content: some View {
        let phase = state.phase
        if phase == .loading {
            Section { HStack(spacing: 12) { ProgressView(); Text(say(.recoveryChecking)) } }
        } else if phase == .spent {
            Section { refusal.accessibilityIdentifier("recovery-spent") }
        } else if phase == .key {
            keyStep
        } else if phase == .password {
            passwordStep
        } else if phase == .done {
            Section {
                Label(say(.recoveryDone), systemImage: "checkmark.circle").accessibilityIdentifier("recovery-done")
                Button(say(.signinSubmit)) {
                    model.recovered(held.finish)
                    model.guests.closeRecovery(held)
                }
                .accessibilityIdentifier("recovery-sign-in")
            }
        }
        Section { } footer: { Text(say(.recoveryNoEscrow)) }
    }

    @ViewBuilder private var refusal: some View {
        if let msg = state.refusal { Text(say(msg)).foregroundStyle(Role.danger) }
    }

    @ViewBuilder private var keyStep: some View {
        Section {
            if let line = state.forAccount { Text(say(notice: line)) }
        } footer: {
            Text(say(.recoveryStepKeyIntro))
        }
        FlowKeySection(
            intake: held.finish.intake, text: $secretKey, holds: $kitHeld, label: say(.signinSecretKey),
            hint: say(.recoverySecretKeyHint), working: state.working, model: model
        )
        Section {
            refusal
            Button(say(.joinContinue)) {
                let key = secretKey
                secretKey = ""
                held.run { try await $0.unlock(secretKey: key) }
            }
            .disabled(secretKey.isEmpty && !kitHeld)
            .accessibilityIdentifier("recovery-continue")
        }
    }

    @ViewBuilder private var passwordStep: some View {
        Section {
            if let line = state.forAccount { Text(say(notice: line)) }
        }
        Section {
            NewPasswordRows(breach: held.finish.breach, next: $next, confirm: $confirm) { n in _ = try? await held.finish.check(newPassword: n) }
        } footer: {
            Text(say(.recoveryDeviceNote))
        }
        Section {
            refusal
            Button(say(.recoverySubmit)) {
                let (n, c) = (next, confirm)
                held.run { f in
                    try await f.finish(next: n, confirm: c)
                    if f.current.phase == .done {
                        next = ""
                        confirm = ""
                    }
                }
            }
            .disabled(next.isEmpty || confirm.isEmpty)
            .accessibilityIdentifier("recovery-submit")
        }
    }
}
