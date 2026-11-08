import SwiftUI
import VeyqCore

struct SignInView: View {
    let working: Bool
    let error: String?
    /// Opened by "Add server": titled so, and left again with Cancel.
    var adding: Bool = false
    var onCancel: () -> Void = {}
    let onSignIn: (String, String, String, String, Bool) -> Void

    @State private var server: String
    @State private var email: String
    @State private var password = ""
    @State private var secretKey = ""
    @State private var remember = false

    init(
        working: Bool, error: String?, adding: Bool = false, server: String? = nil, email: String? = nil,
        onCancel: @escaping () -> Void = {}, onSignIn: @escaping (String, String, String, String, Bool) -> Void
    ) {
        self.working = working
        self.error = error
        self.adding = adding
        self.onCancel = onCancel
        self.onSignIn = onSignIn
        _server = State(initialValue: server.map(Self.shortServer) ?? "veyq.localhost:8080")
        _email = State(initialValue: email ?? "")
    }

    /// A record's origin as typed: without the scheme the sign-in adds back.
    private static func shortServer(_ origin: String) -> String {
        origin.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")
    }

    var body: some View {
        Form {
            Section {
                Brand()
                Text(adding ? "Add a server" : "Sign in").font(.title2.bold()).foregroundStyle(Role.text)
            }
            .listRowBackground(Color.clear)
            Section {
                TextField("Server", text: $server)
                    .textContentType(.URL)
                    .autocorrectionDisabled()
                #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                #endif
                TextField("Email", text: $email)
                    .textContentType(.username)
                    .autocorrectionDisabled()
                #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                #endif
                SecureField("Password", text: $password)
                    .textContentType(.password)
                SecureField("Secret Key", text: $secretKey)
            }
            // Staying signed in is an explicit choice, never the default (ADR 0005 § 13): it says what it costs.
            Section {
                Toggle(isOn: $remember) { Text("Stay signed in") }
                    .disabled(working)
            } footer: {
                Text("Opens with Face ID, Touch ID or the device passcode. Anyone who can unlock this device can open your content. Off, the app asks for your password, never again for the Secret Key.")
            }
            if let error {
                Section { Text(Self.message(error)).foregroundStyle(.red) }
            }
            Section {
                // Confirming is the web's green role (`--app-primary`), not the accent.
                Button {
                    onSignIn(server, email, password, secretKey, remember)
                } label: {
                    Group { if working { ProgressView() } else { Text("Sign in") } }.frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: Radius.control))
                .controlSize(.large)
                .tint(Role.primary)
                .foregroundStyle(Role.onFill)
                .disabled(working)
                if adding {
                    Button("Cancel", action: onCancel).frame(maxWidth: .infinity).disabled(working)
                }
            }
            .listRowBackground(Color.clear)
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(Role.bg)
        .tint(Role.accent)
        .frame(maxWidth: 520)
    }

    static func message(_ reason: String) -> LocalizedStringKey {
        switch reason {
        case "UNREACHABLE": "The server could not be reached."
        case "INCOMPATIBLE": "This app cannot sign in to this server yet."
        case "CREDENTIALS": "Email or password is not right."
        case "SECRET_KEY": "The Secret Key is not right."
        case "NO_TENANT": "This server has no space at this address."
        case "DEVICE_NOT_TRUSTED": "This device is no longer trusted with this account. Sign in with your Secret Key."
        case "SESSION_ENDED": "You were signed out. Please sign in again."
        default: "Signing in did not work."
        }
    }
}

/// An account this device trusts (ADR 0123 decision 8): the password alone for a password-trusted one, a button that
/// asks for the person's authentication again for a remembered one; and the way to another account.
struct UnlockView: View {
    let record: TrustedDevice
    let working: Bool
    let error: String?
    let onUnlock: (String) -> Void
    let onOther: () -> Void

    @State private var password = ""

    private var remembered: Bool { record.method == .deviceKey }

    var body: some View {
        Form {
            Section {
                Brand()
                Text("Welcome back").font(.title2.bold()).foregroundStyle(Role.text)
                VStack(alignment: .leading, spacing: 2) {
                    Text(record.email)
                    Text(URL(string: record.server)?.host() ?? record.server).font(.caption).foregroundStyle(Role.muted)
                }
            }
            .listRowBackground(Color.clear)
            Section {
                if !remembered {
                    SecureField("Password", text: $password)
                        .textContentType(.password)
                        .onSubmit { onUnlock(password) }
                }
            } footer: {
                Text(remembered ? "Confirm it is you to open your spaces." : "This device is trusted: your password is enough.")
            }
            if let error {
                Section { Text(SignInView.message(error)).foregroundStyle(.red) }
            }
            Section {
                Button {
                    onUnlock(password)
                } label: {
                    Group { if working { ProgressView() } else { Text("Unlock") } }.frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: Radius.control))
                .controlSize(.large)
                .tint(Role.primary)
                .foregroundStyle(Role.onFill)
                .disabled(working)
                Button("Use another account", action: onOther).frame(maxWidth: .infinity).disabled(working)
            }
            .listRowBackground(Color.clear)
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(Role.bg)
        .tint(Role.accent)
        .frame(maxWidth: 520)
    }
}
