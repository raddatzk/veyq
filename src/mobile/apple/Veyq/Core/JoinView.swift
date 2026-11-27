import SwiftUI
import VeyqCore

/// One invite's join on screen (core's `TenantJoin`): its state on the main actor. The join holds the invite's secret
/// parts, the typed password and, for a new account, its keys until it closes (`TenantJoin.close`, which zeroes them).
@MainActor
@Observable
final class JoinHeld: Identifiable {
    let join: TenantJoin
    let state: CoreState<TenantJoin.State>
    @ObservationIgnored private var closed = false

    init(_ join: TenantJoin) {
        self.join = join
        state = CoreState({ join.current }) { changed in
            ApplePlatform.shared.watch(flow: join.state) { @Sendable _ in changed() }
        }
    }

    /// The invite read once: its phase, or why it is none.
    func load() { run { try await $0.load() } }

    /// One of the join's steps, its state read again once it ended.
    func run(_ step: @escaping @MainActor (TenantJoin) async throws -> Void) {
        let join = join
        Task {
            try? await step(join)
            state.refresh()
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        state.stop()
        join.close()
    }
}

/// Joining a space from an invite (uc.spaces.add-member's invitee; the web's `TenantJoinView` over core's `TenantJoin`):
/// a screen above everything, as a received link's guest view is, drawing the join's phases — the invite read, the code
/// (or the owner's password) typed, a new account's details and its Emergency Kit or an existing account's sign-in, and
/// the outcome with *Open the space*. A password and the Secret Key are typed here, so the screen is `.secretOnScreen()`
/// as the sign-in is (§ Secrets on screen). Every word and every rule is the core's; *Close* lets the join go.
struct JoinView: View {
    let model: AppModel
    let held: JoinHeld
    @State private var code = ""
    @State private var username = ""
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var email = ""
    @State private var password = ""
    @State private var secretKey = ""
    @State private var kitReady = false
    /// The password the breach check last ran on: its verdict is shown only while the field still holds it.
    @State private var checkedPassword: String?
    @State private var opening = false
    @FocusState private var passwordFocused: Bool

    private var state: TenantJoin.State { held.state.value }
    private var working: Bool { state.busy || opening }

    var body: some View {
        NavigationStack {
            Form { content }
                .formStyle(.grouped)
                .disabled(working)
                .navigationTitle(say(.joinTitle))
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(say(.receiptsClose)) { model.guests.closeJoin(held) }
                            .accessibilityIdentifier("join-close")
                    }
                }
                .overlay { if working { ProgressView() } }
        }
        .tint(Role.accent)
        .secretOnScreen()
        .accessibilityIdentifier("join")
    }

    @ViewBuilder private var content: some View {
        let phase = state.phase
        if phase == .loading {
            Section { HStack(spacing: 12) { ProgressView(); Text(say(.joinLoading)) } }
        } else if phase == .invalid {
            Section { Text(say(.joinInvalid)).foregroundStyle(Role.danger).accessibilityIdentifier("join-invalid") }
        } else if phase == .expired {
            Section { Text(say(.joinExpired)).accessibilityIdentifier("join-expired") }
        } else if phase == .redeemed {
            Section { Text(say(.joinRedeemed)).accessibilityIdentifier("join-redeemed") }
        } else if phase == .code {
            codeStep
        } else if phase == .register_ {
            registerStep
        } else if phase == .kit {
            kitStep
        } else if phase == .signin {
            signInStep
        } else if phase == .done {
            doneStep
        }
    }

    // MARK: The steps

    @ViewBuilder private var codeStep: some View {
        Section {
            Text(say(notice: state.intro))
        } footer: {
            Text(say(state.codeSub))
        }
        Section {
            if state.codeMode == .code {
                TextField(say(state.codeLabel), text: $code, prompt: Text(say(state.codeLabel)))
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.characters)
                    #endif
                    .onSubmit(submitCode)
                    .accessibilityIdentifier("join-code")
            } else {
                SecureField(say(state.codeLabel), text: $code, prompt: Text(say(state.codeLabel)))
                    .onSubmit(submitCode)
                    .accessibilityIdentifier("join-code")
            }
            refusal
            Button(say(.joinContinue), action: submitCode)
                .disabled(code.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityIdentifier("join-code-continue")
        }
    }

    @ViewBuilder private var registerStep: some View {
        Section {
            Text(say(notice: state.intro))
        } footer: {
            Text(say(notice: state.registerSub))
        }
        details
        Section {
            SecureField(say(.signinPassword), text: $password, prompt: Text(say(.signinPassword)))
                .textContentType(.newPassword)
                .focused($passwordFocused)
                .onChange(of: passwordFocused) { was, now in
                    // checked once the field is left, never while typing (ADR 0151 decision 5)
                    guard was, !now, !password.isEmpty else { return }
                    let p = password
                    held.run { try await $0.checkPassword(password: p) }
                    checkedPassword = p
                }
                .accessibilityIdentifier("join-password")
            breach
            refusal
            Button(say(.joinContinue)) {
                let u = username, f = firstName, l = lastName, e = email, p = password
                checkedPassword = p
                held.run { try await $0.register(username: u, firstName: f, lastName: l, email: e, password: p) }
            }
            .disabled(username.trimmingCharacters(in: .whitespaces).isEmpty || password.count < 10 || (state.asksEmail && email.trimmingCharacters(in: .whitespaces).isEmpty))
            .accessibilityIdentifier("join-register")
        }
        if state.offersSwitch {
            Section {
                Button(say(.joinHaveAccount)) { choose(register: false) }.accessibilityIdentifier("join-have-account")
            }
        }
    }

    @ViewBuilder private var kitStep: some View {
        Section {
            Text(say(.joinSaveKitSub))
        } header: {
            Text(say(.joinSaveKitTitle))
        }
        if let kit = held.join.kit { KitReadyWatch(step: kit, ready: $kitReady) }
        Section {
            refusal
            Button(say(state.joinWord)) { held.run { try await $0.finishRegistration() } }
                .disabled(!kitReady)
                .accessibilityIdentifier("join-finish")
        }
    }

    @ViewBuilder private var signInStep: some View {
        Section {
            Text(say(notice: state.intro))
        } footer: {
            Text(say(notice: state.signinSub))
        }
        details
        Section {
            SecureField(say(.signinPassword), text: $password, prompt: Text(say(.signinPassword)))
                .textContentType(.password)
                .accessibilityIdentifier("join-password")
            SecureField(say(.signinSecretKey), text: $secretKey, prompt: Text(say(.signinSecretKey)))
                .accessibilityIdentifier("join-secret-key")
            if model.secretKeyProblem(secretKey) == .typo {
                Text(say(.secretKeyTypo)).foregroundStyle(Role.danger)
            }
            refusal
            Button(say(state.joinWord)) {
                let u = username, f = firstName, l = lastName, e = email, p = password, k = secretKey
                held.run { try await $0.signInAndAccept(username: u, firstName: f, lastName: l, email: e, password: p, secretKey: k) }
            }
            .disabled(
                (state.asksUsername && username.trimmingCharacters(in: .whitespaces).isEmpty) || password.isEmpty || secretKey.isEmpty
                    || (state.asksEmail && email.trimmingCharacters(in: .whitespaces).isEmpty)
            )
            .accessibilityIdentifier("join-sign-in")
        }
        if state.offersSwitch {
            Section {
                Button(say(.joinNewAccount)) { choose(register: true) }.accessibilityIdentifier("join-new-account")
            }
        }
    }

    @ViewBuilder private var doneStep: some View {
        Section {
            Label(say(notice: state.done), systemImage: "checkmark.circle").accessibilityIdentifier("join-done")
            if let to = state.confirmationSentTo { Text(say(.joinConfirmationSent, to)) }
            Text(say(state.doneNote)).foregroundStyle(Role.muted)
        }
        // the share extension signs in nowhere (ADR 0138 decision 5): the app opens the space
        if state.canOpen, !model.inExtension {
            Section {
                refusal
                Button(say(.joinOpenSpace)) { openSpace() }.accessibilityIdentifier("join-open-space")
            }
        } else if state.error != nil {
            Section { refusal }
        }
    }

    // MARK: Parts

    /// The handle in this space, the real name and — for a link invite — the address, as the step asks for them.
    @ViewBuilder private var details: some View {
        if state.asksUsername {
            Section {
                TextField(say(.joinUsername), text: $username, prompt: Text(say(.joinUsername)))
                    .textContentType(.nickname)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .accessibilityIdentifier("join-username")
            } footer: {
                Text(say(.joinUsernameHint))
            }
            Section {
                TextField(say(.profileFirstName), text: $firstName, prompt: Text(say(.profileFirstName)))
                    .textContentType(.givenName)
                    .accessibilityIdentifier("join-first-name")
                TextField(say(.profileLastName), text: $lastName, prompt: Text(say(.profileLastName)))
                    .textContentType(.familyName)
                    .accessibilityIdentifier("join-last-name")
            } footer: {
                Text(say(.joinNameHint))
            }
        }
        if state.asksEmail {
            Section {
                TextField(say(.signinEmail), text: $email, prompt: Text(say(.signinEmail)))
                    .textContentType(.emailAddress)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                    #endif
                    .accessibilityIdentifier("join-email")
            }
        }
    }

    /// The breach check's verdict on the password as checked, as the app's other password flows show it (`NewPasswordRows`).
    @ViewBuilder private var breach: some View {
        let b = state.breach
        if checkedPassword == password {
            if b.kind == .hit || b.kind == .accepted {
                Text(say(.passwordPwnedWarning, Int(b.count))).foregroundStyle(Role.danger)
                Toggle(say(.passwordPwnedAnyway), isOn: Binding(get: { b.kind == .accepted }, set: { on in
                    held.join.useAnyway(on: on)
                    held.state.refresh()
                }))
                .accessibilityIdentifier("join-use-anyway")
            } else if b.kind == .unavailable {
                Text(say(.passwordPwnedUnavailable)).foregroundStyle(Role.muted)
            }
        }
    }

    @ViewBuilder private var refusal: some View {
        if let error = state.error { Text(say(error)).foregroundStyle(Role.danger).accessibilityIdentifier("join-error") }
    }

    // MARK: Acts

    private func submitCode() {
        let typed = code
        guard !typed.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        held.run { try await $0.code(typed: typed) }
    }

    private func choose(register: Bool) {
        held.join.choose(register: register)
        held.state.refresh()
    }

    /// *Open the space*: the app signs in as any sign-in does, then the join goes.
    private func openSpace() {
        opening = true
        Task {
            let opened = await model.openJoined(held.join)
            opening = false
            held.state.refresh()
            if opened { model.guests.closeJoin(held) }
        }
    }
}
