import SwiftUI
import VeyqCore

/// Signed in, still locked (uc.account.sessions E3, core's `Gate.Locked`): what this state is, that this device keeps
/// nothing, then core's entries (`LockedPlan`) whole and in their order — *Sign out everywhere, including here* in the
/// destructive role, asked first with the web's question, and the way back with its hint.
struct LockedView: View {
    let server: String
    let email: String
    let working: Bool
    let error: Msg?
    let onSignOutEverywhere: () -> Void
    let onBack: () -> Void
    @State private var asking = false

    var body: some View {
        Form {
            Section {
                Brand()
                Text(say(.lockedTitle)).font(.title2.bold()).foregroundStyle(Role.text)
                Text(email).foregroundStyle(Role.text)
            }
            .listRowBackground(Color.clear)
            Section {
                Text(say(.lockedNote)).foregroundStyle(Role.text)
            } footer: {
                Text(say(.lockedScopeNote))
            }
            if let error {
                Section { Text(say(error)).foregroundStyle(.red).accessibilityIdentifier("locked-error") }
            }
            Section {
                ForEach(LockedPlan.shared.entries(), id: \.self) { e in entry(e) }
            }
            .listRowBackground(Color.clear)
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .tint(Role.accent)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Role.bg)
        .alert(say(.lockedConfirmTitle), isPresented: $asking) {
            Button(say(.lockedAction), role: .destructive, action: onSignOutEverywhere)
            Button(say(.signinCancel), role: .cancel) {}
        } message: {
            Text(say(.lockedConfirm))
        }
    }

    @ViewBuilder private func entry(_ e: LockedEntry) -> some View {
        switch e.entry {
        case .signOutEverywhere:
            Button(role: .destructive) { asking = true } label: {
                Group { if working { ProgressView() } else { Text(say(.lockedAction)) } }.frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: Radius.control))
            .controlSize(.large)
            .tint(Role.danger)
            .disabled(working)
            .accessibilityIdentifier("locked-sign-out-everywhere")
        case .back:
            VStack(spacing: 6) {
                Button(action: onBack) { Label(say(.lockedBack), systemImage: "key").frame(maxWidth: .infinity) }
                    .disabled(working)
                    .accessibilityIdentifier("locked-back")
                Text(say(.lockedBackHint)).font(.footnote).foregroundStyle(Role.muted).multilineTextAlignment(.center)
            }
        }
    }
}

/// *Reset your password* (uc.account.recover-access steps 1–2, core's `Gate.Recover`): the web's intro, the server and the
/// address, the answer, that the mailed link opens the web's page which finishes the reset, core's entries (`RecoverPlan`
/// — Send until the link was asked for, then Back), and that nothing helps without the kit.
struct RecoverView: View {
    let working: Bool
    let sent: Bool
    let error: Msg?
    let onSend: (String, String) -> Void
    let onBack: () -> Void
    @State private var server: String
    @State private var email: String

    init(server: String?, email: String?, working: Bool, sent: Bool, error: Msg?, onSend: @escaping (String, String) -> Void, onBack: @escaping () -> Void) {
        self.working = working
        self.sent = sent
        self.error = error
        self.onSend = onSend
        self.onBack = onBack
        _server = State(initialValue: server.map { $0.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "") } ?? "")
        _email = State(initialValue: email ?? "")
    }

    var body: some View {
        Form {
            Section {
                Brand()
                Text(say(.recoveryTitle)).font(.title2.bold()).foregroundStyle(Role.text)
                Text(say(.recoveryIntro)).foregroundStyle(Role.text)
            }
            .listRowBackground(Color.clear)
            Section {
                TextField(say(.signinServer), text: $server)
                    .textContentType(.URL)
                    .autocorrectionDisabled()
                #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                #endif
                TextField(say(.recoveryEmail), text: $email)
                    .textContentType(.username)
                    .autocorrectionDisabled()
                #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                #endif
            }
            .disabled(sent)
            if sent {
                Section {
                    Text(say(.recoverySent)).foregroundStyle(Role.primary).accessibilityIdentifier("recover-sent")
                    Text(say(.recoveryLinkNote)).foregroundStyle(Role.text)
                }
            }
            if let error {
                Section { Text(say(error)).foregroundStyle(.red).accessibilityIdentifier("recover-error") }
            }
            Section {
                ForEach(RecoverPlan.shared.entries(sent: sent), id: \.self) { e in entry(e) }
            } footer: {
                Text(say(.recoveryNoEscrow))
            }
            .listRowBackground(Color.clear)
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .tint(Role.accent)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Role.bg)
    }

    @ViewBuilder private func entry(_ e: RecoverEntry) -> some View {
        switch e.entry {
        case .send:
            Button { onSend(server, email) } label: {
                Group { if working { ProgressView() } else { Text(say(.recoverySend)) } }.frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: Radius.control))
            .controlSize(.large)
            .tint(Role.primary)
            .foregroundStyle(Role.onFill)
            .disabled(working || server.isEmpty || email.isEmpty)
            .accessibilityIdentifier("recover-send")
        case .back:
            Button(say(.back), action: onBack).frame(maxWidth: .infinity).disabled(working)
                .accessibilityIdentifier("recover-back")
        }
    }
}
