import LocalAuthentication
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

struct SignInView: View {
    let working: Bool
    let error: Msg?
    /// Opened by "Add server": titled so, and left again with Cancel.
    var adding: Bool = false
    var onCancel: () -> Void = {}
    /// An Emergency Kit read: its Secret Key held by core, never here; the row says where it came from.
    var kit: SessionModelKitShown?
    /// The picked kit file's URL (security-scoped, opened in place) and the server as typed now.
    var onKit: (URL, String) -> Void = { _, _ in }
    var onClearKit: () -> Void = {}
    /// The kit's scan core makes for the server as typed now; nil where the app offers none.
    var onScanKit: ((String) -> QrScan<AnyObject>)?
    /// The two linking entries (ADR 0059 §1) over the server as typed now; nil where the app offers none (the extension).
    var onScanFromTrusted: ((String) -> Void)?
    var onShowForSignIn: ((String) -> Void)?
    /// *Open a link* (ADR 0150 decision 4): a received link opened as its guest, no session needed; nil where the app
    /// offers none (the extension).
    var onOpenLink: (() -> Void)?
    /// Why the Secret Key as typed is none (core's `SessionModel.secretKeyProblem`, inbox #1058): a typo is said under the
    /// field as it is typed, a key that is no key at all says nothing until the sign-in refuses it.
    var secretKeyProblem: (String) -> SecretKeyProblem? = { _ in nil }
    /// What the way here left to say that is no error (every device signed out, uc.account.sessions E3).
    var notice: Msg?
    /// The locked sign-in's way back: this sign-in keeps nothing on the device, and says so.
    var keepsNothing = false
    /// *I don't have my Secret Key with me* (uc.account.sessions E3) over the server, email and password as typed; nil in
    /// the share extension (the app signs in there).
    var onLocked: ((String, String, String) -> Void)?
    /// *Forgot your password?* (uc.account.recover-access) over the server and email as typed; nil in the share extension.
    var onForgot: ((String, String) -> Void)?
    let onSignIn: (String, String, String, String) -> Void
    /// The tray over the app's queue alone: a link guest's uploads left running signed out (inbox #1250); nil in the share
    /// extension.
    var tray: SignedOutTray?

    @State private var server: String
    @State private var email: String
    @State private var password = ""
    @State private var secretKey = ""
    @State private var pickingKit = false
    /// The kit's scan while its sheet shows.
    @State private var scan: QrScan<AnyObject>?
    /// A scan found no camera after all, or the device has none: no scan entry on this screen.
    @State private var noCamera = !QrCamera.available

    init(
        working: Bool, error: Msg?, adding: Bool = false, server: String? = nil, email: String? = nil,
        kit: SessionModelKitShown? = nil, notice: Msg? = nil, keepsNothing: Bool = false, onCancel: @escaping () -> Void = {},
        onLocked: ((String, String, String) -> Void)? = nil, onForgot: ((String, String) -> Void)? = nil,
        onKit: @escaping (URL, String) -> Void = { _, _ in }, onClearKit: @escaping () -> Void = {},
        onScanKit: ((String) -> QrScan<AnyObject>)? = nil,
        onScanFromTrusted: ((String) -> Void)? = nil, onShowForSignIn: ((String) -> Void)? = nil,
        onOpenLink: (() -> Void)? = nil,
        tray: SignedOutTray? = nil,
        secretKeyProblem: @escaping (String) -> SecretKeyProblem? = { _ in nil },
        onSignIn: @escaping (String, String, String, String) -> Void
    ) {
        self.tray = tray
        self.working = working
        self.error = error
        self.adding = adding
        self.kit = kit
        self.notice = notice
        self.keepsNothing = keepsNothing
        self.onLocked = onLocked
        self.onForgot = onForgot
        self.onCancel = onCancel
        self.onKit = onKit
        self.onClearKit = onClearKit
        self.onScanKit = onScanKit
        self.onScanFromTrusted = onScanFromTrusted
        self.onShowForSignIn = onShowForSignIn
        self.onOpenLink = onOpenLink
        self.secretKeyProblem = secretKeyProblem
        self.onSignIn = onSignIn
        _server = State(initialValue: server.map(Self.shortServer) ?? Self.prefill)
        _email = State(initialValue: email ?? "")
    }

    /// The server field's start where no record names one: the dev host in a debug build only, empty in a release
    /// (ADR 0154 decision 1, as the desktops).
    #if DEBUG
    private static let prefill = "veyq.localhost:8080"
    #else
    private static let prefill = ""
    #endif

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
            if let tray { SignedOutUploadsSection(tray: tray) }
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
                if kit != nil {
                    // the kit's Secret Key is core's, never shown: the row says where it came from; its clear goes back to typing
                    LabeledContent(say(.signinSecretKey)) {
                        HStack(spacing: 8) {
                            Text(say(.signinKitHeld)).foregroundStyle(Role.muted)
                            Button(action: onClearKit) { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.borderless)
                                .foregroundStyle(Role.muted)
                                .accessibilityLabel(say(.signinKitClear))
                                .help(say(.signinKitClear))
                                .disabled(working)
                        }
                    }
                } else {
                    SecureField(say(.signinSecretKey), text: $secretKey)
                    if secretKeyProblem(secretKey) == .typo {
                        Text(say(.secretKeyTypo)).foregroundStyle(Role.danger)
                            .accessibilityIdentifier("signin-secret-key-typo")
                    }
                }
            } footer: {
                if let kit, let before = kit.typedServer {
                    Text(say(.signinKitOtherServer, kit.server ?? "", before))
                }
            }
            if let error {
                Section { Text(say(error)).foregroundStyle(.red) }
            }
            if let notice {
                Section { Text(say(notice)).foregroundStyle(Role.primary).accessibilityIdentifier("signin-notice") }
            }
            if keepsNothing {
                // the locked sign-in's way back keeps nothing on this device, and the form says so (uc.account.sessions E3)
                Section { Text(say(.lockedBackHint)).foregroundStyle(Role.muted) }
            }
            // the ways in, core's (`SignInPlan.entries`, ADR 0154 decision 2: the web's — Submit first, then Cancel while
            // adding, the device links, the kit, *Open a link*), whole and in their order under the fields
            Section {
                ForEach(SignInPlan.shared.entries(adding: adding, camera: !noCamera), id: \.self) { e in entry(e) }
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
        // Files and iCloud Drive on iOS, the open panel on macOS: the file opened in place, never imported as a copy;
        // the completion reads only its URL and the server typed (the binding is cleared before it runs)
        .fileImporter(isPresented: $pickingKit, allowedContentTypes: [.pdf, .image]) { result in
            if case .success(let url) = result { onKit(url, server) }
        }
        .onAppear {
            #if DEBUG
            // a pass where nothing may tap the entry (`DevLogin`'s `VEYQ_DEV_KIT_SCAN`): the scan opens as the button opens it
            if ProcessInfo.processInfo.environment["VEYQ_DEV_KIT_SCAN"] == "1", let onScanKit, !noCamera, scan == nil { scan = onScanKit(server) }
            #endif
        }
        .sheet(isPresented: Binding(get: { scan != nil }, set: { if !$0 { scan = nil } })) {
            if let scan {
                KitScannerView(scan: scan, onClose: { self.scan = nil }, onNoCamera: { noCamera = true })
            }
        }
        // a kit read fills the email and the server it gave (core's gate); both stay editable
        .onChange(of: kit) {
            guard let kit else { return }
            email = kit.email
            if let origin = kit.server { server = origin }
            secretKey = ""
        }
    }
}

private extension SignInView {
    /// Entry `e` of the sign-in's plan. An entry whose door this target has not (the share extension links no device and
    /// opens no link) is drawn as nothing there.
    @ViewBuilder func entry(_ e: SignInEntry) -> some View {
        switch e.entry {
        case .submit:
            // Confirming is the web's green role (`--app-primary`), not the accent.
            Button {
                onSignIn(server, email, password, kit != nil ? "" : secretKey)
            } label: {
                Group { if working { ProgressView() } else { Text(say(.signinSubmit)) } }.frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: Radius.control))
            .controlSize(.large)
            .tint(Role.primary)
            .foregroundStyle(Role.onFill)
            .disabled(working)
        case .cancel:
            Button(say(.signinCancel), action: onCancel).frame(maxWidth: .infinity).disabled(working)
        case .linkShow:
            // ADR 0059 §1: another device lets this one in over the server above (nothing in a scan changes it)
            if let onShowForSignIn {
                Button { onShowForSignIn(server) } label: { Label(say(.signinLinkShow), systemImage: "qrcode").frame(maxWidth: .infinity) }
                    .disabled(working)
            }
        case .linkScan:
            if let onScanFromTrusted {
                Button { onScanFromTrusted(server) } label: { Label(say(.signinLinkScan), systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity) }
                    .disabled(working)
            }
        case .kitOpen:
            Button { pickingKit = true } label: { Label(say(.signinKitOpen), systemImage: "folder").frame(maxWidth: .infinity) }
                .disabled(working)
        case .kitScan:
            if let onScanKit {
                Button { scan = onScanKit(server) } label: { Label(say(.signinKitScan), systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity) }
                    .disabled(working)
            }
        case .locked:
            // uc.account.sessions E3: the login alone, to sign every device out — the email and the password needed
            if let onLocked {
                Button { onLocked(server, email, password) } label: { Label(say(.signinLocked), systemImage: "lock").frame(maxWidth: .infinity) }
                    .disabled(working || email.isEmpty || password.isEmpty)
                    .accessibilityIdentifier("signin-locked")
            }
        case .forgot:
            if let onForgot {
                Button { onForgot(server, email) } label: { Text(say(.signinForgot)).frame(maxWidth: .infinity) }
                    .disabled(working)
                    .accessibilityIdentifier("signin-forgot")
            }
        case .openLink:
            // *Open a link* (owner 2026-11-19): a received link needs no session
            if let onOpenLink {
                Button(action: onOpenLink) { Label(say(.linkOpen), systemImage: "link").frame(maxWidth: .infinity) }
                    .disabled(working)
                    .accessibilityIdentifier("signin-open-link")
            }
        }
    }
}

/// The sign-in's "Scan Emergency Kit" sheet over `QrCameraView`, whose scan core made with the kit's parser as its
/// `accepts`: a code that is not a kit is skipped by core and the camera goes on; a kit is taken by core (the form fills
/// from the gate) and the sheet closes. Where the device turns out to have no camera, `onNoCamera` drops the sign-in's
/// entry and the sheet closes; a refused camera is said here, the kit's file and the typed key still on the form beneath.
/// The scan closes with the sheet. Its states go to the unified log (`veyq-scan:`): where the found kit's server came
/// from, never a code's text.
struct KitScannerView: View {
    let scan: QrScan<AnyObject>
    let onClose: () -> Void
    let onNoCamera: () -> Void
    @State private var watch: QrScanWatch?

    var body: some View {
        NavigationStack {
            QrCameraView(scan: scan) { state in
                if state is QrScanStateDenied {
                    Text(say(.signinKitScanDenied))
                        .multilineTextAlignment(.center)
                        .padding(24)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            // the camera shows the kit's code — the Secret Key: no system snapshot holds a frame of it
            .secretOnScreen()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Role.bg)
            .safeAreaInset(edge: .bottom) {
                Text(say(.signinKitScanHint))
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Role.muted)
                    .padding()
                    .frame(maxWidth: .infinity)
                    .background(Role.bg)
            }
            .navigationTitle(say(.signinKitScan))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(say(.signinCancel), action: onClose)
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 520, minHeight: 460)
        #endif
        .onAppear {
            // core calls the watch on its own threads; the sheet's closures run back on the main actor
            nonisolated(unsafe) let close = onClose
            nonisolated(unsafe) let noCamera = onNoCamera
            watch = scan.watch { @Sendable state in
                NSLog("veyq-scan: kit scan: state %@", String(describing: state))
                let found = state is QrScanStateFound<AnyObject>
                let none = state is QrScanStateNoCamera
                guard found || none else { return }
                Task { @MainActor in
                    if none { noCamera() }
                    close()
                }
            }
        }
        .onDisappear {
            watch?.stop()
            watch = nil
            scan.close()
        }
    }
}

/// An account this device trusts (ADR 0123 decision 8): the password alone for a password-trusted one, a button that
/// asks for the person's authentication again for a remembered one; then the core's entries (`UnlockPlan.entries`,
/// ADR 0154 decision 2), whole and in their order — the way to another account, *Open a link*, and *Stop trusting this
/// device* (inbox #1052), destructive, always asked first by the core.
struct UnlockView: View {
    let record: TrustedDevice
    let working: Bool
    let error: Msg?
    /// The share extension's unlock: the app keeps this device's trust, so the plan lists no forget there.
    let inExtension: Bool
    let onUnlock: (String) -> Void
    let onOther: () -> Void
    let onForget: () -> Void
    /// *Open a link* under the unlock (ADR 0150 decision 4); nil where the app offers none.
    var onOpenLink: (() -> Void)?
    /// The tray over the app's queue alone: a link guest's uploads left running signed out (inbox #1250); nil in the share
    /// extension.
    var tray: SignedOutTray?

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
            if let tray { SignedOutUploadsSection(tray: tray) }
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
                ForEach(UnlockPlan.shared.entries(inExtension: inExtension), id: \.self) { e in entry(e) }
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

private extension UnlockView {
    /// Entry `e` of the unlock's plan. *Open a link* is drawn as nothing where the target has no door for it (the share
    /// extension opens no link).
    @ViewBuilder func entry(_ e: UnlockEntry) -> some View {
        switch e.entry {
        case .submit:
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
        case .otherAccount:
            Button(say(.unlockOther), action: onOther).frame(maxWidth: .infinity).disabled(working)
        case .openLink:
            if let onOpenLink {
                Button(action: onOpenLink) { Label(say(.linkOpen), systemImage: "link").frame(maxWidth: .infinity) }
                    .disabled(working)
                    .accessibilityIdentifier("unlock-open-link")
            }
        case .forget:
            Button(role: .destructive, action: onForget) { Label(say(.manageForgetDevice), systemImage: "lock.slash").frame(maxWidth: .infinity) }
                .disabled(working)
                .accessibilityIdentifier("unlock-forget")
        }
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

    /// The changes "Manage servers" and Settings list for a server trusted by `method` at `level`, the device offering
    /// `levels` — core's `trustChanges` rule for rule: each offered level it is not at, then the way back to the password
    /// (nil) from staying signed in; trusted not at all (a password change took the saved key, inbox #1009), each level
    /// and the password trust.
    static func changes(_ method: TrustedDevice.Method?, _ level: StaySignedIn?, _ levels: [StaySignedIn]) -> [StaySignedIn?] {
        let remembered = method == .deviceKey
        return levels.filter { !(remembered && $0 == level) }.map { Optional($0) } + (method != .device ? [nil] : [])
    }
}

/// The offer to stay signed in after a sign-in (ADR 0123 decision 8): a sheet, as iOS apps ask whether to use Face ID once an account
/// signed in — each level the core offers (in its order; the first the prominent one) with the line that says what it
/// means, then "Not Now", and where to change it later. It is answered, never swiped away; a cancelled prompt keeps it.
struct OfferSheet: View {
    let levels: [StaySignedIn]
    /// The sign-in was a link's (ADR 0059 §10): no password reached this device, so "Not Now" says what the next start needs.
    var linked = false
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
                    choice(say(.offerNotNow), linked ? say(.offerNotNowLinkedHint) : say(.offerNotNowHint), prominent: false, plain: true) { onAnswer(nil) }
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
