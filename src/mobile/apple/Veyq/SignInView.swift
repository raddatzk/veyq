import LocalAuthentication
import SwiftUI
import VeyqCore

struct SignInView: View {
    let working: Bool
    let error: Msg?
    /// Opened by "Add server": titled so, and left again with Cancel.
    var adding: Bool = false
    var onCancel: () -> Void = {}
    let onSignIn: (String, String, String, String) -> Void

    @State private var server: String
    @State private var email: String
    @State private var password = ""
    @State private var secretKey = ""

    init(
        working: Bool, error: Msg?, adding: Bool = false, server: String? = nil, email: String? = nil,
        onCancel: @escaping () -> Void = {}, onSignIn: @escaping (String, String, String, String) -> Void
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
                Text(adding ? say(.signinAddTitle) : say(.signinTitle)).font(.title2.bold()).foregroundStyle(Role.text)
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
                TextField(say(.signinEmail), text: $email)
                    .textContentType(.username)
                    .autocorrectionDisabled()
                #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                #endif
                SecureField(say(.signinPassword), text: $password)
                    .textContentType(.password)
                SecureField(say(.signinSecretKey), text: $secretKey)
            }
            if let error {
                Section { Text(say(error)).foregroundStyle(.red) }
            }
            Section {
                // Confirming is the web's green role (`--app-primary`), not the accent.
                Button {
                    onSignIn(server, email, password, secretKey)
                } label: {
                    Group { if working { ProgressView() } else { Text(say(.signinTitle)) } }.frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: Radius.control))
                .controlSize(.large)
                .tint(Role.primary)
                .foregroundStyle(Role.onFill)
                .disabled(working)
                if adding {
                    Button(say(.signinCancel), action: onCancel).frame(maxWidth: .infinity).disabled(working)
                }
            }
            .listRowBackground(Color.clear)
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .tint(Role.accent)
        .frame(maxWidth: 520)
        // the form a column of its own, the window around it the app's ground (an iPad, a Mac)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Role.bg)
    }
}

/// An account this device trusts (ADR 0123 decision 8): the password alone for a password-trusted one, a button that
/// asks for the person's authentication again for a remembered one; and the way to another account.
struct UnlockView: View {
    let record: TrustedDevice
    let working: Bool
    let error: Msg?
    let onUnlock: (String) -> Void
    let onOther: () -> Void

    @State private var password = ""

    private var remembered: Bool { record.method == .deviceKey }
    /// A remembered account at LOGIN asks nothing: its unlock shows only after a resume that failed, and says so (owner
    /// 2026-11-11, the desktops' words); at the presence level it asks again.
    private var login: Bool { remembered && record.unlockLevel == .login }

    var body: some View {
        Form {
            Section {
                Brand()
                Text(say(.unlockTitle)).font(.title2.bold()).foregroundStyle(Role.text)
                VStack(alignment: .leading, spacing: 2) {
                    Text(record.email)
                    Text(URL(string: record.server)?.host() ?? record.server).font(.caption).foregroundStyle(Role.muted)
                }
            }
            .listRowBackground(Color.clear)
            Section {
                if !remembered {
                    SecureField(say(.signinPassword), text: $password)
                        .textContentType(.password)
                        .onSubmit { onUnlock(password) }
                }
            } footer: {
                Text(login ? say(.unlockLoginHint) : remembered ? say(.unlockRememberedHint) : say(.unlockPasswordHint))
            }
            if let error {
                Section { Text(say(error)).foregroundStyle(.red) }
            }
            Section {
                Button {
                    onUnlock(password)
                } label: {
                    Group { if working { ProgressView() } else { Text(login ? say(.unlockRetry) : say(.unlockSubmit)) } }.frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: Radius.control))
                .controlSize(.large)
                .tint(Role.primary)
                .foregroundStyle(Role.onFill)
                .disabled(working)
                Button(say(.unlockOther), action: onOther).frame(maxWidth: .infinity).disabled(working)
            }
            .listRowBackground(Color.clear)
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .tint(Role.accent)
        .frame(maxWidth: 520)
        // the form a column of its own, the window around it the app's ground (an iPad, a Mac)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Role.bg)
    }
}

/// What the device trust's screens say on Apple (ADR 0123 decision 8): the presence level named
/// as this device asks — Face ID, Touch ID, or its passcode where it has neither —, the level without a check, how
/// "Manage servers" names a server's trust and which changes it lists (Windows' `TrustWords`, Linux's `manage::changes`).
/// No rule here: the levels are the core's; this picks their words.
enum TrustWords {
    enum Presence { case faceID, touchID, passcode }

    /// How this device's presence check asks: the biometry it has enrolled, else its passcode.
    static var presence: Presence {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return .passcode }
        switch context.biometryType {
        case .faceID: return .faceID
        case .touchID: return .touchID
        default: return .passcode
        }
    }

    /// A level's name and the line that says what it means.
    static func level(_ level: StaySignedIn) -> (title: String, hint: String) {
        guard level == .presence else { return (say(.signinRemember), say(.offerLoginHintPhone)) }
        switch presence {
        case .faceID: return (say(.offerPresence), say(.offerPresenceHint))
        case .touchID: return (say(.offerTouchId), say(.offerTouchIdHint))
        case .passcode: return (say(.offerPasscode), say(.offerPasscodeHint))
        }
    }

    /// The SF Symbol of a level: the biometry for the presence level, an open lock for the one without a check.
    static func symbol(_ level: StaySignedIn) -> String {
        guard level == .presence else { return "lock.open" }
        switch presence {
        case .faceID: return "faceid"
        case .touchID: return "touchid"
        case .passcode: return "lock"
        }
    }

    /// "Manage servers"' line for how this device trusts a server, by its method and, staying signed in, its level.
    static func trusted(_ method: TrustedDevice.Method, _ level: StaySignedIn?) -> String {
        guard method == .deviceKey else { return say(.manageTrustedPassword) }
        guard level != .login else { return say(.manageTrustedRemembered) }
        switch presence {
        case .faceID: return say(.manageTrustedPresence)
        case .touchID: return say(.manageTrustedTouchId)
        case .passcode: return say(.manageTrustedPasscode)
        }
    }

    /// The changes "Manage servers" lists for a server trusted by `method` at `level`, the device offering `levels`:
    /// each offered level it is not at, then the way back to the password (nil) from staying signed in.
    static func changes(_ method: TrustedDevice.Method, _ level: StaySignedIn?, _ levels: [StaySignedIn]) -> [StaySignedIn?] {
        let remembered = method == .deviceKey
        return levels.filter { !(remembered && $0 == level) }.map { Optional($0) } + (remembered ? [nil] : [])
    }
}

/// The offer to stay signed in after a sign-in (ADR 0123 decision 8): a sheet, as iOS apps ask whether to use Face ID once an account
/// signed in — each level the core offers (in its order; the first the prominent one) with the line that says what it
/// means, then "Not Now", and where to change it later. It is answered, never swiped away; a cancelled prompt keeps it.
struct OfferSheet: View {
    let levels: [StaySignedIn]
    let working: Bool
    let onAnswer: (StaySignedIn?) -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: TrustWords.symbol(levels.first ?? .presence))
                    .font(.system(size: 56, weight: .regular))
                    .foregroundStyle(Role.accent)
                    .padding(.top, 32)
                Text(say(.offerTitle)).font(.title2.bold()).multilineTextAlignment(.center).foregroundStyle(Role.text)
                VStack(spacing: 16) {
                    ForEach(Array(levels.enumerated()), id: \.offset) { i, level in
                        let words = TrustWords.level(level)
                        choice(words.title, words.hint, prominent: i == 0) { onAnswer(level) }
                    }
                    choice(say(.offerNotNow), say(.offerNotNowHint), prominent: false, plain: true) { onAnswer(nil) }
                }
                if working { ProgressView() }
                Text(say(.offerFooter, say(.switcherManage)))
                    .font(.footnote).foregroundStyle(Role.muted).multilineTextAlignment(.center)
            }
            .padding(24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .disabled(working)
        .background(Role.bg)
        .tint(Role.accent)
        .interactiveDismissDisabled()
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 480)
        #endif
    }

    /// One answer: its button, the line under it saying what it means.
    @ViewBuilder
    private func choice(_ title: String, _ hint: String, prominent: Bool, plain: Bool = false, action: @escaping () -> Void) -> some View {
        VStack(spacing: 6) {
            if plain {
                Button(title, action: action).frame(maxWidth: .infinity).controlSize(.large)
            } else if prominent {
                Button(action: action) { Text(title).frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).controlSize(.large)
            } else {
                Button(action: action) { Text(title).frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).controlSize(.large)
            }
            Text(hint).font(.footnote).foregroundStyle(Role.muted).multilineTextAlignment(.center)
        }
    }
}
