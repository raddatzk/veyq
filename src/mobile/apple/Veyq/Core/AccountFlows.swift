import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore
#if os(macOS)
import AppKit
#endif

/// The account's flows on Apple (ADR 0148 decision 10; uc.account.change-password, .change-email, .rotate-keys,
/// .erase-account, uc.spaces.edit-profile, .set-profile-picture): each a sheet with a `NavigationStack` over core's model
/// (`PasswordChange`, `AddressChange`, `KeyRotation`, `AccountErasure`, `ProfileModel`), which decides everything — a
/// sheet draws its state and calls it. Every step where a password or the Secret Key is typed is a `SecureField` and the
/// sheet is `.secretOnScreen()` (§ Secrets on screen). A kit is never on screen: it is saved through the system's dialog
/// (`KitSaveSection`). The danger step is a destructive `.confirmationDialog` on iOS, a destructive `.alert` on a Mac.
enum AccountFlow: String, Identifiable {
    case profile, password, email, security, erase, kit

    var id: String { rawValue }

    /// The section's rubric on the Settings page (the web's).
    var title: String {
        switch self {
        case .profile: say(.profileTitle)
        case .password: say(.passwordChangeTitle)
        case .email: say(.accountSettingsEmail)
        case .security: say(.rotationTitle)
        case .erase: say(.erasureTitle)
        case .kit: say(.kitRenewTitle)
        }
    }

    var sub: String {
        switch self {
        case .profile: say(.profileSub)
        case .password: say(.passwordChangeSub)
        case .email: say(.emailChangeFormSub)
        case .security: say(.rotationSub)
        case .erase: say(.erasureSub)
        case .kit: say(.kitRenewSub)
        }
    }

    /// The button that opens the flow.
    var action: String {
        switch self {
        case .profile: say(.profileEdit)
        case .password: say(.passwordChangeTitle)
        case .email: say(.emailChangeAction)
        case .security: say(.rotationAction)
        case .erase: say(.erasureAction)
        case .kit: say(.kitStatusMake)
        }
    }

    var symbol: String {
        switch self {
        case .profile: "person.crop.circle"
        case .password: "key"
        case .email: "envelope"
        case .security: "lock.rotation"
        case .erase: "person.crop.circle.badge.xmark"
        case .kit: "doc.badge.plus"
        }
    }
}

/// The sheet of `flow`.
struct AccountFlowSheet: View {
    let flow: AccountFlow
    let model: AppModel

    var body: some View {
        switch flow {
        case .profile: ProfileSheet(model: model)
        case .password: PasswordChangeSheet(model: model)
        case .email: AddressChangeSheet(model: model)
        case .security: KeyRotationSheet(model: model)
        case .erase: ErasureSheet(model: model)
        case .kit: KitRenewalSheet(model: model)
        }
    }
}

/// A core model and its state as the sheet observes it (core's watch, on core's threads, re-read on the main actor).
@MainActor
final class HeldFlow<M: AnyObject, S> {
    let model: M
    let state: CoreState<S>

    init(_ model: M, current: @escaping (M) -> S, flow: (M) -> any Kotlinx_coroutines_coreStateFlow) {
        self.model = model
        let stateFlow = flow(model)
        state = CoreState({ current(model) }) { changed in ApplePlatform.shared.watch(flow: stateFlow) { @Sendable _ in changed() } }
    }

    var value: S { state.value }

    func stop() { state.stop() }
}

/// A sheet's frame: its title, Cancel (or Done once nothing is left to do), its own confirming action.
private struct FlowFrame<Content: View>: View {
    let title: String
    var done = false
    var working = false
    var confirm: (label: String, enabled: Bool, run: () -> Void)? = nil
    @ViewBuilder let content: () -> Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form { content() }
                .formStyle(.grouped)
                .navigationTitle(title)
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    if done {
                        ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { dismiss() } }
                    } else {
                        ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() }.disabled(working) }
                        if let confirm {
                            ToolbarItem(placement: .confirmationAction) {
                                Button(confirm.label, action: confirm.run).disabled(!confirm.enabled || working)
                            }
                        }
                    }
                }
                .overlay { if working { ProgressView() } }
        }
        .tint(Role.accent)
        #if os(macOS)
        .frame(minWidth: 460, idealWidth: 520, minHeight: 440)
        #endif
    }
}

/// The danger step's confirmation, as each platform's guidelines ask: a destructive confirmation dialog on iOS, a
/// destructive alert on a Mac.
private extension View {
    func dangerConfirm(_ shown: Binding<Bool>, title: String, message: String, action: String, run: @escaping () -> Void) -> some View {
        #if os(iOS)
        confirmationDialog(title, isPresented: shown, titleVisibility: .visible) {
            Button(action, role: .destructive, action: run)
            Button(say(.signinCancel), role: .cancel) {}
        } message: {
            Text(message)
        }
        #else
        alert(title, isPresented: shown) {
            Button(action, role: .destructive, action: run)
            Button(say(.signinCancel), role: .cancel) {}
        } message: {
            Text(message)
        }
        #endif
    }
}

/// A refusal of the flow, in the danger colour.
private struct Refusal: View {
    let msg: Msg?
    var body: some View {
        if let msg { Text(say(msg)).foregroundStyle(Role.danger) }
    }
}

/// The person's language and local date for a kit (ADR 0148 decision 5): the app's language as the catalog chose it,
/// today's date where the person is.
enum KitDay {
    static var language: String { Bundle.main.preferredLocalizations.first.map { String($0.prefix(2)) } ?? "en" }

    static var epochDay: Int64 {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        guard let day = utc.date(from: parts) else { return Int64(Date().timeIntervalSince1970 / 86_400) }
        return Int64((day.timeIntervalSince1970 / 86_400).rounded(.down))
    }
}

// MARK: The Emergency Kit (D5)

/// The kit's step (the web's `EmergencyKitSave`): *Save your Emergency Kit* → the system's save dialog → the core writes
/// the PDF straight into the file chosen → "I saved it", enabled only once a file was written. The Secret Key is never
/// on screen. On a Mac the save panel hands a path the core writes to (`KitStep.save`); on iOS `fileExporter` takes a
/// document, whose bytes the core writes into memory only when the exporter asks for them (`AppleAccount.kitData`) and
/// which are zeroed once the export ended — no copy in the app's own folders first (R13's `fileMover` path keeps one).
struct KitSaveSection: View {
    let step: KitStep
    @State private var held: HeldFlow<KitStep, KitStep.State>?
    #if os(iOS)
    @State private var exporting = false
    @State private var document: KitDocument?
    /// The exporter came back with a file written: "I saved it" may be checked (core's `saved` is set when the bytes are
    /// made, which the exporter may do before the person chose a place).
    @State private var exported = false
    #endif

    private var state: KitStep.State? { held?.value }

    private var saved: Bool {
        #if os(iOS)
        (state?.saved ?? false) && exported
        #else
        state?.saved ?? false
        #endif
    }

    var body: some View {
        Section {
            Button { save() } label: { Label(say(.kitSaveTitle), systemImage: "square.and.arrow.down") }
            Toggle(say(.kitSavedConfirm), isOn: Binding(get: { state?.confirmed ?? false }, set: { on in
                step.confirm(checked: on && saved)
                held?.state.refresh()
            }))
            .disabled(!saved)
            Refusal(msg: state?.refusal)
        }
        .onAppear {
            if held == nil { held = HeldFlow(step, current: { $0.current }, flow: { $0.state }) }
        }
        .onDisappear {
            held?.stop()
            held = nil
        }
        #if os(iOS)
        .fileExporter(isPresented: $exporting, document: document, contentType: .pdf, defaultFilename: state?.fileName) { result in
            if case .success = result { exported = true }
            document?.clear()
            document = nil
            KitDocument.sweep(state?.fileName)
            held?.state.refresh()
        }
        #endif
    }

    private func save() {
        guard let name = state?.fileName else { return }
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        panel.allowedContentTypes = [.pdf]
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        _ = step.save(path: url.path, language: KitDay.language, createdEpochDay: KitDay.epochDay)
        held?.state.refresh()
        #else
        exported = false
        document = KitDocument(step: step)
        exporting = true
        #endif
    }
}

#if os(iOS)
/// The kit as `fileExporter` takes it: the bytes made by the core only when the exporter asks for them, kept in one
/// buffer this side zeroes once the export ended.
struct KitDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.pdf] }

    /// The step and the bytes it wrote, handed to the exporter's thread (core's step is called once, from there).
    private final class Buffer: @unchecked Sendable {
        let step: KitStep?
        var data: NSMutableData?
        init(_ step: KitStep?) { self.step = step }
    }

    private let buffer: Buffer

    init(step: KitStep) { buffer = Buffer(step) }

    init(configuration: ReadConfiguration) throws { throw CocoaError(.featureUnsupported) }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        guard let step = buffer.step, let data = buffer.data ?? AppleAccount.shared.kitData(step: step, language: KitDay.language, createdEpochDay: KitDay.epochDay) else {
            throw CocoaError(.fileWriteUnknown)
        }
        buffer.data = data
        return FileWrapper(regularFileWithContents: Data(bytesNoCopy: data.mutableBytes, count: data.length, deallocator: .none))
    }

    /// Zeroes the bytes once the export ended.
    func clear() {
        if let data = buffer.data, data.length > 0 { memset(data.mutableBytes, 0, data.length) }
        buffer.data = nil
    }

    /// Removes a copy of the kit the exporter may have staged in the app's temporary folder (a residual not measured: no
    /// simulator boots here, inbox #975), so none outlives the export.
    static func sweep(_ name: String?) {
        guard let name else { return }
        let fm = FileManager.default
        guard let walk = fm.enumerator(at: fm.temporaryDirectory, includingPropertiesForKeys: nil) else { return }
        for case let url as URL in walk where url.lastPathComponent == name {
            try? fm.removeItem(at: url)
        }
    }
}
#endif

// MARK: The Secret Key section and the new password (ADR 0151)

/// A flow's Secret Key section (ADR 0151 decision 4): the sign-in's control — the key typed or a `veyqkit:` token pasted
/// (core's intake swaps it), a kit file opened in place (`.fileImporter`: Files on iOS, the open panel on a Mac), the
/// scan where a camera may be (`KitScannerView`) — and a held kit shown as at the sign-in, its key never on screen, with
/// *Enter it instead*. A kit of another account warns, one made at another server informs (decision 2); neither blocks.
/// The fields carry a `prompt:`, the placeholder a grouped form draws in an empty field. It is not what lets a click focus
/// the field: a replica of these sheets (2026-11-23, in-process clicks) focused an empty field without a prompt at its
/// start, middle and end alike (#1030 is not explained by it).
struct FlowKeySection<Extra: View>: View {
    let intake: KeyIntake
    @Binding var text: String
    /// Whether a kit's key is held: the flow's action may go with the field empty.
    @Binding var holds: Bool
    let label: String
    let hint: String
    let working: Bool
    let model: AppModel
    /// Rows drawn above the key in the same section (the password).
    @ViewBuilder var extra: () -> Extra
    @State private var held: HeldFlow<KeyIntake, KeyIntake.IntakeState>?
    @State private var picking = false
    @State private var scan: QrScan<AnyObject>?
    @State private var noCamera = !QrCamera.available

    private var state: KeyIntake.IntakeState? { held?.value }

    var body: some View {
        Section {
            extra()
            if let kit = state?.kit {
                // the kit's Secret Key is core's, never shown: the row says where it came from; its clear goes back to typing
                LabeledContent(label) {
                    HStack(spacing: 8) {
                        Text(say(.signinKitHeld)).foregroundStyle(Role.muted)
                        Button {
                            intake.clear()
                            held?.state.refresh()
                        } label: { Image(systemName: "xmark.circle.fill") }
                            .buttonStyle(.borderless)
                            .foregroundStyle(Role.muted)
                            .accessibilityLabel(say(.signinKitClear))
                            .help(say(.signinKitClear))
                            .disabled(working)
                    }
                }
                .accessibilityIdentifier("flow-kit-held")
                Text(kit.email).foregroundStyle(Role.muted)
            } else {
                SecureField(label, text: $text, prompt: Text(label))
                    .disabled(working || state?.reading == true)
                // a typo is said as it is typed (core's `KeyIntake.problem`, the web's SecretKeyField); a malformed key says nothing
                if intake.problem(text: text) == .typo {
                    Text(say(.secretKeyTypo)).foregroundStyle(Role.danger)
                        .accessibilityIdentifier("flow-secret-key-typo")
                }
            }
            if state?.reading == true { ProgressView() }
            // the scan first on a phone or tablet, the file first on a Mac, as at the sign-in
            #if os(iOS)
            scanButton
            openButton
            #else
            openButton
            scanButton
            #endif
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(hint)
                if let problem = state?.problem { Text(say(problem)).foregroundStyle(Role.danger) }
                if let kit = state?.kit, let expected = kit.otherAccount {
                    Text(say(.kitEmailMismatch, kit.email, expected)).foregroundStyle(Role.danger)
                }
                if let kit = state?.kit, let here = kit.otherServer {
                    Text(say(.signinKitOtherServer, kit.server ?? "", here))
                }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.pdf, .image]) { result in
            if case .success(let url) = result { model.readFlowKit(intake, url) }
        }
        .sheet(isPresented: Binding(get: { scan != nil }, set: { if !$0 { scan = nil } })) {
            if let scan {
                KitScannerView(scan: scan, onClose: { self.scan = nil }, onNoCamera: { noCamera = true })
            }
        }
        .onAppear {
            if held == nil { held = HeldFlow(intake, current: { $0.current }, flow: { $0.state }) }
        }
        .onDisappear {
            held?.stop()
            held = nil
        }
        // a kit taken empties the typed field: the held key is what the action uses
        .onChange(of: state?.kit != nil, initial: true) { _, now in
            holds = now
            if now { text = "" }
        }
    }

    private var openButton: some View {
        Button { picking = true } label: { Label(say(.signinKitOpen), systemImage: "folder") }
            .disabled(working)
    }

    @ViewBuilder private var scanButton: some View {
        if !noCamera {
            Button { scan = model.scanFlowKit(intake) } label: { Label(say(.signinKitScan), systemImage: "qrcode.viewfinder") }
                .disabled(working)
        }
    }
}

extension FlowKeySection where Extra == EmptyView {
    init(intake: KeyIntake, text: Binding<String>, holds: Binding<Bool>, label: String, hint: String, working: Bool, model: AppModel) {
        self.init(intake: intake, text: text, holds: holds, label: label, hint: hint, working: working, model: model) { EmptyView() }
    }
}

/// The new password and its confirmation with the web's breached-password check (ADR 0151 decision 5): checked when
/// the field is left (`@FocusState`), never while typing; any edit drops the verdict; a hit says so under the field (the
/// count) with *Use it anyway* as a `Toggle`; a check that could not run says that and stops nothing. No range service
/// configured: nothing is drawn.
struct NewPasswordRows: View {
    let breach: BreachCheck
    @Binding var next: String
    @Binding var confirm: String
    let check: (String) async -> Void
    @State private var held: HeldFlow<BreachCheck, BreachCheck.BreachState>?
    @FocusState private var focused: Bool

    private var state: BreachCheck.BreachState? { held?.value }

    var body: some View {
        SecureField(say(.passwordChangeNew), text: $next, prompt: Text(say(.passwordChangeNew)))
            .textContentType(.newPassword)
            .focused($focused)
            .onChange(of: focused) { was, now in
                guard was, !now else { return }
                let n = next
                Task {
                    await check(n)
                    held?.state.refresh()
                }
            }
            .onChange(of: next) {
                breach.edited()
                held?.state.refresh()
            }
        if let state {
            if state.kind == .hit || state.kind == .accepted {
                Text(say(.passwordPwnedWarning, Int(state.count))).foregroundStyle(Role.danger)
                Toggle(say(.passwordPwnedAnyway), isOn: Binding(get: { state.kind == .accepted }, set: { on in
                    breach.useAnyway(on: on)
                    held?.state.refresh()
                }))
            } else if state.kind == .unavailable {
                Text(say(.passwordPwnedUnavailable)).foregroundStyle(Role.muted)
            }
        }
        SecureField(say(.passwordChangeConfirm), text: $confirm, prompt: Text(say(.passwordChangeConfirm)))
            .textContentType(.newPassword)
            .onAppear {
                if held == nil { held = HeldFlow(breach, current: { $0.current }, flow: { $0.state }) }
            }
            .onDisappear {
                held?.stop()
                held = nil
            }
    }
}

// MARK: Change password (D6)

struct PasswordChangeSheet: View {
    let model: AppModel
    @State private var held: HeldFlow<PasswordChange, PasswordChange.State>?
    @State private var current = ""
    @State private var next = ""
    @State private var confirm = ""
    @State private var secretKey = ""
    @State private var kitHeld = false

    private var state: PasswordChange.State? { held?.value }

    var body: some View {
        FlowFrame(
            title: say(.passwordChangeTitle), done: state?.done ?? false, working: state?.working ?? false,
            confirm: (say(.passwordChangeSubmit), !current.isEmpty && !next.isEmpty && !confirm.isEmpty && (!secretKey.isEmpty || kitHeld), run)
        ) {
            if state?.done == true {
                Section {
                    Label(say(.passwordChangeDone), systemImage: "checkmark.circle")
                    Text(say(.passwordChangeDoneNote)).foregroundStyle(Role.muted)
                }
            } else {
                Section {
                    SecureField(say(.passwordChangeCurrent), text: $current, prompt: Text(say(.passwordChangeCurrent))).textContentType(.password)
                } footer: {
                    Text(say(.passwordChangeSub))
                }
                if let f = held?.model {
                    Section {
                        NewPasswordRows(breach: f.breach, next: $next, confirm: $confirm) { n in _ = try? await f.check(newPassword: n) }
                    }
                    FlowKeySection(
                        intake: f.intake, text: $secretKey, holds: $kitHeld, label: say(.passwordChangeSecretKey), hint: say(.passwordChangeSecretKeyHint),
                        working: state?.working ?? false, model: model
                    )
                }
                Section { Refusal(msg: state?.refusal) }
            }
        }
        .secretOnScreen()
        .onAppear {
            if held == nil, let f = model.passwordChangeFlow() { held = HeldFlow(f, current: { $0.current }, flow: { $0.state }) }
        }
        .onDisappear {
            held?.model.close()
            held?.stop()
            held = nil
        }
    }

    private func run() {
        guard let f = held?.model else { return }
        let key = secretKey
        let (c, n, k) = (current, next, confirm)
        secretKey = ""
        Task {
            _ = try? await f.run(currentPassword: c, next: n, confirm: k, secretKey: key)
            held?.state.refresh()
            if f.current.done {
                current = ""; next = ""; confirm = ""
                model.passwordChanged()
            }
        }
    }
}

// MARK: Change the sign-in address (D6)

/// The request (the new address, the password, the Secret Key) → "a link was sent"; then, as on the web, a new kit with
/// the new address (the password and the Secret Key checked first). The confirm is the web's alone (owner 2026-11-18).
struct AddressChangeSheet: View {
    let model: AppModel
    @State private var held: HeldFlow<AddressChange, AddressChange.State>?
    @State private var newEmail = ""
    @State private var password = ""
    @State private var secretKey = ""
    @State private var kitPassword = ""
    @State private var kitSecretKey = ""
    @State private var kitHeld = false
    @State private var kit: KitStep?

    private var state: AddressChange.State? { held?.value }

    var body: some View {
        FlowFrame(
            title: say(.emailChangeAction), done: state?.sentTo != nil, working: state?.working ?? false,
            confirm: (say(.emailChangeSubmit), !newEmail.isEmpty && !password.isEmpty && (!secretKey.isEmpty || kitHeld), request)
        ) {
            if let sent = state?.sentTo {
                Section { Label(say(.emailChangeSent, sent), systemImage: "envelope.badge") }
            } else {
                Section {
                    TextField(say(.emailChangeNewEmail), text: $newEmail)
                        .textContentType(.emailAddress)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.emailAddress)
                        #endif
                } footer: {
                    Text(say(.emailChangeFormSub))
                }
                if let f = held?.model {
                    FlowKeySection(
                        intake: f.intake, text: $secretKey, holds: $kitHeld, label: say(.emailChangeSecretKey), hint: say(.rotationSecretKeyHint),
                        working: state?.working ?? false, model: model
                    ) {
                        SecureField(say(.emailChangePassword), text: $password, prompt: Text(say(.emailChangePassword))).textContentType(.password)
                    }
                }
                Section { Refusal(msg: state?.refusal) }
            }
            // the new kit follows the confirm (inbox #1010): offered once this sign-in took the new address
            if state?.kitOffered == true { kitSection }
        }
        .secretOnScreen()
        .onAppear {
            if held == nil, let f = model.addressChangeFlow() { held = HeldFlow(f, current: { $0.current }, flow: { $0.state }) }
        }
        .onDisappear {
            kit?.close()
            kit = nil
            held?.model.close()
            held?.stop()
            held = nil
        }
    }

    /// "Print a new Emergency Kit": the password and the Secret Key checked, then the kit's step.
    @ViewBuilder
    private var kitSection: some View {
        if let kit {
            KitSaveSection(step: kit)
        } else {
            Section {
                Text(say(.emailChangeKitSub)).foregroundStyle(Role.muted)
            } header: {
                Text(say(.emailChangeKitTitle))
            }
            if let f = held?.model {
                // the flow's one intake: a kit held for the request serves the kit step too (the request sent, its key spent)
                FlowKeySection(
                    intake: f.intake, text: $kitSecretKey, holds: $kitHeld, label: say(.emailChangeSecretKey), hint: say(.rotationSecretKeyHint),
                    working: state?.working ?? false, model: model
                ) {
                    SecureField(say(.emailChangePassword), text: $kitPassword, prompt: Text(say(.emailChangePassword))).textContentType(.password)
                }
            }
            Section {
                Button(say(.emailChangeKitCheck)) { makeKit() }
                    .disabled(kitPassword.isEmpty || (kitSecretKey.isEmpty && !kitHeld) || (state?.working ?? false))
                Refusal(msg: state?.refusal)
            }
        }
    }

    private func request() {
        guard let f = held?.model else { return }
        let key = secretKey
        let (e, p) = (newEmail, password)
        secretKey = ""
        Task {
            _ = try? await f.request(newEmail: e, password: p, secretKey: key)
            password = ""
            held?.state.refresh()
        }
    }

    private func makeKit() {
        guard let f = held?.model else { return }
        let key = kitSecretKey
        let p = kitPassword
        kitSecretKey = ""
        kitPassword = ""
        Task {
            kit = try? await f.kit(password: p, secretKey: key)
            held?.state.refresh()
        }
    }
}

// MARK: A clearer Emergency Kit (ADR 0157 decision 6)

/// *Make a new kit*: the password and the Secret Key (typed, the old kit read or scanned) checked against the floor by the
/// core (`KitRenewal`), then the kit saved as every kit is (`KitSaveSection`); its record written by the core once kept.
struct KitRenewalSheet: View {
    let model: AppModel
    @State private var held: HeldFlow<KitRenewal, KitRenewal.State>?
    @State private var password = ""
    @State private var secretKey = ""
    @State private var kitHeld = false
    @State private var kit: KitStep?

    private var state: KitRenewal.State? { held?.value }

    var body: some View {
        FlowFrame(title: say(.kitRenewTitle), done: false, working: state?.working ?? false) {
            if let kit {
                KitSaveSection(step: kit)
            } else {
                Section { Text(say(.kitRenewSub)).foregroundStyle(Role.muted) }
                if let f = held?.model {
                    FlowKeySection(
                        intake: f.intake, text: $secretKey, holds: $kitHeld, label: say(.emailChangeSecretKey), hint: say(.rotationSecretKeyHint),
                        working: state?.working ?? false, model: model
                    ) {
                        SecureField(say(.emailChangePassword), text: $password, prompt: Text(say(.emailChangePassword))).textContentType(.password)
                    }
                }
                Section {
                    Button(say(.kitRenewCheck)) { makeKit() }
                        .disabled(password.isEmpty || (secretKey.isEmpty && !kitHeld) || (state?.working ?? false))
                        .accessibilityIdentifier("kit-renew-check")
                    Refusal(msg: state?.refusal)
                }
            }
        }
        .secretOnScreen()
        .onAppear {
            if held == nil, let f = model.kitRenewalFlow() { held = HeldFlow(f, current: { $0.current }, flow: { $0.state }) }
        }
        .onDisappear {
            kit?.close()
            kit = nil
            held?.model.close()
            held?.stop()
            held = nil
            // Security says which kit the account has now
            model.refreshSettings()
        }
    }

    private func makeKit() {
        guard let f = held?.model else { return }
        let key = secretKey
        let p = password
        secretKey = ""
        password = ""
        Task {
            kit = try? await f.kit(password: p, secretKey: key)
            held?.state.refresh()
        }
    }
}

// MARK: Replace the keys (D7)

/// The web's order: the facts → the gate (the password, the Secret Key, "also choose a new password") → the new kit
/// saved (never shown) and "I saved it" → the danger confirmation → each space's new lock. A moderator is sent to the
/// web (D9). After it the tenant is opened anew: the account holds the new keys.
struct KeyRotationSheet: View {
    let model: AppModel
    @State private var held: HeldFlow<KeyRotation, KeyRotation.State>?
    @State private var password = ""
    @State private var secretKey = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var kitHeld = false
    @State private var kit: KitStep?
    @State private var kitReady = false
    @State private var asking = false
    @State private var reopened = false
    /// A link's node whose share dialog *Links to renew* opened (inbox #977); its links are read anew once it closes.
    @State private var renewing: NodeAt?

    private var state: KeyRotation.State? { held?.value }

    var body: some View {
        FlowFrame(title: say(.rotationTitle), done: state?.step == .done || state?.step == .onTheWeb, working: state?.step == .working || state?.step == .loading) {
            if let state {
                switch state.step {
                case .onTheWeb:
                    Section {
                        Text(say(.rotationModeratorWeb))
                        Text(say(.accountOnTheWeb, state.server)).foregroundStyle(Role.muted)
                    }
                case .gate: gate(state)
                case .kit: kitStep(state)
                case .done: done(state)
                default: EmptyView()
                }
            }
        }
        .secretOnScreen()
        .dangerConfirm($asking, title: say(.rotationConfirmTitle), message: say(.rotationConfirmBody), action: say(.rotationAction)) { publish() }
        .sheet(isPresented: Binding(get: { renewing != nil }, set: { if !$0 { renewed() } })) {
            if let renewing { NodeShareSheet(model: model, ref: renewing) }
        }
        .onAppear {
            guard held == nil, let f = model.keyRotationFlow() else { return }
            held = HeldFlow(f, current: { $0.current }, flow: { $0.state })
            Task { _ = try? await f.open() }
        }
        .onDisappear {
            held?.model.close()
            held?.stop()
            held = nil
        }
    }

    @ViewBuilder
    private func gate(_ state: KeyRotation.State) -> some View {
        Section(say(.rotationAfterTitle)) {
            ForEach([Msg.rotationFactSharedSpaces, .rotationFactSignsOut, .rotationFactNewKit, .rotationFactPassword,
                     .rotationFactNotRepair, .rotationStoreWiped], id: \.self) { fact in
                Text(say(fact))
            }
        }
        if let f = held?.model {
            FlowKeySection(
                intake: f.intake, text: $secretKey, holds: $kitHeld, label: say(.rotationSecretKey), hint: say(.rotationSecretKeyHint),
                working: false, model: model
            ) {
                SecureField(say(.rotationPassword), text: $password, prompt: Text(say(.rotationPassword))).textContentType(.password)
            }
            Section {
                Toggle(say(.rotationNewPassword), isOn: Binding(get: { state.newPassword }, set: { on in
                    f.setNewPassword(on: on)
                    held?.state.refresh()
                }))
                if state.newPassword {
                    NewPasswordRows(breach: f.breach, next: $newPassword, confirm: $confirmPassword) { n in _ = try? await f.check(newPassword: n) }
                }
            }
        }
        Section {
            Button(say(.rotationNext)) { prove() }
                .disabled(password.isEmpty || (secretKey.isEmpty && !kitHeld) || (state.newPassword && (newPassword.isEmpty || confirmPassword.isEmpty)))
            Refusal(msg: state.refusal)
        }
    }

    @ViewBuilder
    private func kitStep(_ state: KeyRotation.State) -> some View {
        Section {
            Text(say(.rotationKitSub))
            Text(say(.rotationKitKeepOld)).foregroundStyle(Role.muted)
        } header: {
            Text(say(.rotationKitTitle))
        }
        if let kit { KitReadyWatch(step: kit, ready: $kitReady) }
        Section {
            Button(say(.rotationAction), role: .destructive) { asking = true }
                .disabled(!kitReady)
            Refusal(msg: state.refusal)
        }
    }

    @ViewBuilder
    private func done(_ state: KeyRotation.State) -> some View {
        if let refusal = state.refusal {
            Section { Refusal(msg: refusal) }
        } else {
            Section {
                Label(say(.rotationDoneOldKit), systemImage: "checkmark.shield")
                Text(say(.rotationDoneSpaces)).foregroundStyle(Role.muted)
            }
            if !state.scopes.isEmpty {
                Section {
                    ForEach(state.scopes, id: \.scopeId) { s in
                        LabeledContent(scopeName(s)) {
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(say(s.status))
                                if let blocked = s.blocked { Text(say(blocked)).font(.footnote).foregroundStyle(Role.muted) }
                            }
                        }
                    }
                }
            }
            // the links made before, which stay open until renewed (the web's decision 13): the person's own
            if !state.links.isEmpty {
                Section(say(.rotationLinksTitle)) {
                    ForEach(state.links, id: \.shareId) { l in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(l.node?.name ?? say(.rotationLinkNameUnavailable))
                            Text(l.scopeName ?? say(l.scopeWord ?? .rotationScopeNameUnavailable)).font(.footnote).foregroundStyle(Role.muted)
                            if let node = l.node {
                                Button(say(l.upload ? .rotationLinkOpen : .rotationRenew)) { renewing = node }
                                if l.upload { Text(say(.rotationLinkUpload)).font(.footnote).foregroundStyle(Role.muted) }
                            } else if let tenant = l.tenant {
                                Text(say(.rotationLinkElsewhere, tenant)).font(.footnote).foregroundStyle(Role.muted)
                            }
                        }
                    }
                }
            }
        }
    }

    /// The share dialog *Renew* opened closed: the links are read anew (a renewed one drops out).
    private func renewed() {
        renewing = nil
        guard let f = held?.model else { return }
        Task {
            _ = try? await f.refreshLinks()
            held?.state.refresh()
        }
    }

    /// A space of the status list as core names it (inbox #979): its own name, else the personal space or one not opened here.
    private func scopeName(_ s: KeyRotation.ScopeStatus) -> String {
        if let name = s.name { return name }
        return say(s.nameWord ?? .rotationScopeNameUnavailable)
    }

    private func prove() {
        guard let f = held?.model else { return }
        let key = secretKey
        let (p, n, c) = (password, newPassword, confirmPassword)
        secretKey = ""
        Task {
            _ = try? await f.prove(password: p, secretKey: key, newPassword: n, confirm: c)
            held?.state.refresh()
            if f.current.step == .kit {
                password = ""; newPassword = ""; confirmPassword = ""
                kit = f.kit
            }
            finished(f)
        }
    }

    private func publish() {
        guard let f = held?.model else { return }
        Task {
            _ = try? await f.run()
            held?.state.refresh()
            finished(f)
        }
    }

    /// Once the keys are replaced (or an earlier rotation resumed), the tenant is opened anew, once.
    private func finished(_ f: KeyRotation) {
        guard f.current.done, !reopened else { return }
        reopened = true
        model.keysReplaced()
    }
}

/// The kit's step inside a flow, telling the flow when it is ready (a file written and "I saved it" checked).
struct KitReadyWatch: View {
    let step: KitStep
    @Binding var ready: Bool
    @State private var held: HeldFlow<KitStep, KitStep.State>?

    var body: some View {
        KitSaveSection(step: step)
            .onAppear {
                if held == nil { held = HeldFlow(step, current: { $0.current }, flow: { $0.state }) }
            }
            .onChange(of: held?.value.ready ?? false, initial: true) { _, now in ready = now }
            .onDisappear {
                held?.stop()
                held = nil
            }
    }
}

// MARK: Erase the account (D8)

struct ErasureSheet: View {
    let model: AppModel
    @State private var held: HeldFlow<AccountErasure, AccountErasure.State>?
    @State private var password = ""
    @State private var asking = false
    @State private var left = false

    private var state: AccountErasure.State? { held?.value }

    var body: some View {
        FlowFrame(title: say(.erasureTitle), done: state?.serverOwner == true || state?.onTheWeb == true, working: !(state?.loaded ?? false) || (state?.working ?? false)) {
            if let state, state.loaded {
                if state.serverOwner {
                    Section { Text(say(.erasureServerOwner)) }
                } else if state.onTheWeb {
                    Section {
                        Text(say(.erasureModeratorWeb)).fontWeight(.semibold)
                        Text(say(.accountOnTheWeb, state.server)).foregroundStyle(Role.muted)
                    }
                } else {
                    form(state)
                }
            }
        }
        .secretOnScreen()
        .dangerConfirm($asking, title: say(.erasureConfirmTitle), message: say(.erasureConfirmBody), action: say(.erasureAction)) { erase() }
        .onAppear {
            guard held == nil, let f = model.erasureFlow() else { return }
            held = HeldFlow(f, current: { $0.current }, flow: { $0.state })
            Task { _ = try? await f.load() }
        }
        .onDisappear {
            held?.stop()
            held = nil
        }
    }

    @ViewBuilder
    private func form(_ state: AccountErasure.State) -> some View {
        Section {
            ForEach([Msg.erasureFactGoes, .erasureFactSharedStays, .erasureFactKept, .erasureFactBackups, .erasureFactWindow], id: \.self) { fact in
                Text(say(fact))
            }
        }
        let shared = state.tenants.filter { !$0.members.isEmpty }
        let sole = state.tenants.filter { $0.members.isEmpty }
        if !shared.isEmpty {
            Section {
                ForEach(shared, id: \.tenantId) { t in
                    Picker(t.name, selection: Binding(get: { t.chosen ? (t.newOwnerId ?? "") : nil as String? }, set: { choice in
                        guard let choice else { return }
                        held?.model.choose(tenantId: t.tenantId, newOwnerId: choice.isEmpty ? nil : choice)
                        held?.state.refresh()
                    })) {
                        if !t.chosen { Text(say(.erasureChoicePlaceholder)).tag(nil as String?) }
                        Text(say(.erasureDeleteAlong)).tag("" as String?)
                        ForEach(t.members, id: \.userId) { m in Text(say(.erasureMakeOwner, m.name)).tag(m.userId as String?) }
                    }
                }
            } header: {
                Text(say(.erasureChoiceTitle))
            } footer: {
                Text(say(.erasureChoiceSub))
            }
        }
        if !sole.isEmpty {
            Section {
                ForEach(sole, id: \.tenantId) { t in Text(t.name) }
            } header: {
                Text(say(.erasureSoleTitle))
            } footer: {
                Text(say(.erasureSoleSub))
            }
        }
        Section {
            SecureField(say(.erasurePassword), text: $password).textContentType(.password)
            Button(say(.erasureAction), role: .destructive) { asking = true }
                .disabled(password.isEmpty || !state.choicesMade || state.working)
            Refusal(msg: state.refusal)
        }
    }

    private func erase() {
        guard let f = held?.model else { return }
        let p = password
        Task {
            _ = try? await f.erase(password: p)
            password = ""
            held?.state.refresh()
            if f.current.erased, !left {
                left = true
                model.accountErased()
            }
        }
    }
}

// MARK: One's profile (D2)

/// *Your profile* (Settings) and *Edit profile* (one's own person page): the username, the name, the picture framed
/// square and rendered 256 px here (core encodes, pads and seals it), the group-add consent. Without a tenant grant the
/// name and the picture are disabled with the web's hint.
struct ProfileSheet: View {
    let model: AppModel
    @State private var held: HeldFlow<ProfileModel, ProfileModel.State>?
    @State private var item: PhotosPickerItem?
    @State private var cropping: CGImage?
    @State private var pictureNotImage = false
    @State private var choosingFile = false
    @State private var savedOnce = 0
    @Environment(\.dismiss) private var dismiss

    private var state: ProfileModel.State? { held?.value }

    var body: some View {
        FlowFrame(
            title: say(.profileTitle), working: state?.working ?? true,
            confirm: (say(.profileSave), !(state?.username.trimmingCharacters(in: .whitespaces).isEmpty ?? true), save)
        ) {
            if let state, state.loaded {
                if state.refusal == .profileLoadFailed {
                    Section { Refusal(msg: state.refusal) }
                } else {
                    form(state)
                }
            } else {
                Section { Text(say(.profileLoading)).foregroundStyle(Role.muted) }
            }
        }
        .sheet(item: Binding(get: { cropping.map(CroppedImage.init) }, set: { if $0 == nil { cropping = nil } })) { c in
            SquareCropSheet(image: c.image) { rgba in
                cropping = nil
                guard let f = held?.model, let rgba else { return }
                Task {
                    _ = try? await AppleAccount.shared.choosePicture(profile: f, rgba: rgba)
                    held?.state.refresh()
                }
            }
        }
        .onChange(of: item) { _, chosen in
            guard let chosen else { return }
            item = nil
            Task {
                guard let data = try? await chosen.loadTransferable(type: Data.self),
                      let picture = CoverImages.image(data, maxPixels: 2048) else {
                    pictureNotImage = true
                    return
                }
                pictureNotImage = false
                cropping = picture
            }
        }
        #if os(macOS)
        .fileImporter(isPresented: $choosingFile, allowedContentTypes: [.image]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url), let picture = CoverImages.image(data, maxPixels: 2048) else {
                pictureNotImage = true
                return
            }
            pictureNotImage = false
            cropping = picture
        }
        #endif
        .onAppear {
            guard held == nil, let f = model.ownProfile() else { return }
            held = HeldFlow(f, current: { $0.current }, flow: { $0.state })
            Task { _ = try? await f.load() }
        }
        .onDisappear {
            held?.model.close()
            held?.stop()
            held = nil
        }
    }

    @ViewBuilder
    private func form(_ state: ProfileModel.State) -> some View {
        Section {
            HStack(spacing: 16) {
                picture(state)
                VStack(alignment: .leading, spacing: 8) {
                    let choose = say(state.hasPicture ? .profilePictureChange : .profilePictureChoose)
                    #if os(iOS)
                    PhotosPicker(selection: $item, matching: .images) { Text(choose) }
                    #else
                    // a Mac picks a picture from its files, as a Memory's banner does (`MemoryCuration`): the Photos
                    // picker there reaches only the Photos library
                    Button(choose) { choosingFile = true }
                    #endif
                    if state.hasPicture {
                        Button(say(.profilePictureRemove), role: .destructive) { held?.model.removePicture(); held?.state.refresh() }
                            .buttonStyle(.borderless)
                    }
                }
                .disabled(!state.hasKey || state.working)
            }
        } header: {
            Text(say(.profilePictureLabel))
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(say(state.hasKey ? .profilePictureHint : .profilePictureNoKeyYet))
                if state.picture == .set { Text(say(.profilePicturePendingSet)) }
                if state.picture == .clear { Text(say(.profilePicturePendingClear)) }
                if pictureNotImage { Text(say(.profilePictureNotImage)).foregroundStyle(Role.danger) }
                if let r = state.pictureRefusal { Text(say(r)).foregroundStyle(Role.danger) }
            }
        }
        Section {
            TextField(say(.profileUsername), text: Binding(get: { state.username }, set: { held?.model.setUsername(value: $0); held?.state.refresh() }))
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                #endif
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(say(.profileUsernameHint))
                if let r = state.usernameRefusal { Text(say(r)).foregroundStyle(Role.danger) }
            }
        }
        Section {
            TextField(say(.profileFirstName), text: Binding(get: { state.firstName }, set: { held?.model.setFirstName(value: $0); held?.state.refresh() }))
                .textContentType(.givenName)
            TextField(say(.profileLastName), text: Binding(get: { state.lastName }, set: { held?.model.setLastName(value: $0); held?.state.refresh() }))
                .textContentType(.familyName)
        } footer: {
            if !state.hasKey { Text(say(.profileNoNameYet)) }
        }
        .disabled(!state.hasKey)
        Section {
            Toggle(say(.profileConsentGroupAdd), isOn: Binding(get: { state.requireGroupAddConsent }, set: {
                held?.model.setRequireGroupAddConsent(value: $0)
                held?.state.refresh()
            }))
        } footer: {
            Text(say(.profileConsentGroupAddHint))
        }
        if state.saves > 0 && state.refusal == nil && state.pictureRefusal == nil {
            Section { Label(say(.profileSaved), systemImage: "checkmark.circle") }
        }
        if state.refusal != nil { Section { Refusal(msg: state.refusal) } }
    }

    /// The picture the profile draws now (the draft's, else the one held), or the letter.
    private func picture(_ state: ProfileModel.State) -> some View {
        let data = held.flatMap { AppleAccount.shared.picture(profile: $0.model) } as Data?
        return Group {
            #if os(iOS)
            if let data, let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFill() } else { letter(state) }
            #else
            if let data, let image = NSImage(data: data) { Image(nsImage: image).resizable().scaledToFill() } else { letter(state) }
            #endif
        }
        .frame(width: 72, height: 72)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    private func letter(_ state: ProfileModel.State) -> some View {
        Text(String((state.firstName.isEmpty ? state.username : state.firstName).prefix(1)).uppercased())
            .font(.title)
            .foregroundStyle(Role.controlText)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Role.line)
    }

    private func save() {
        guard let f = held?.model else { return }
        Task {
            _ = try? await f.save()
            held?.state.refresh()
            model.profileSaved()
        }
    }
}

/// A picked picture for the cropper's sheet.
private struct CroppedImage: Identifiable {
    let image: CGImage
    var id: ObjectIdentifier { ObjectIdentifier(image) }
}

/// The cover cropper with a square frame (core's `CoverFrame`, zoom 1–3; ADR 0148 decision 2): a drag pans, a pinch or
/// the slider zooms; *Use this picture* renders the framed part to `ProfileModel.EDGE` square RGBA for the core.
struct SquareCropSheet: View {
    let image: CGImage
    let done: (Data?) -> Void
    @State private var pose: CoverFrame.Pose?
    @State private var size: CGSize = .zero
    @State private var dragged: CGSize = .zero
    @State private var pinched: CGFloat = 1

    private var frame: CoverFrame? {
        guard size.width > 0 else { return nil }
        return CoverFrame(imageWidth: Double(image.width), imageHeight: Double(image.height), frameWidth: size.width, frameHeight: size.height)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    cropper
                        .aspectRatio(1, contentMode: .fit)
                        .clipShape(Circle())
                        .listRowInsets(EdgeInsets())
                    if let frame, let pose {
                        Slider(value: Binding(get: { pose.zoom }, set: { self.pose = frame.zoom(pose: pose, factor: $0 / pose.zoom) }),
                               in: CoverFrame.companion.MIN_ZOOM...CoverFrame.companion.MAX_ZOOM) {
                            Text(say(.coverZoom))
                        } minimumValueLabel: {
                            Image(systemName: "minus.magnifyingglass")
                        } maximumValueLabel: {
                            Image(systemName: "plus.magnifyingglass")
                        }
                    }
                } footer: {
                    Text(say(.profilePictureDialogIntro))
                }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.profilePictureLabel))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { done(nil) } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.profilePictureUse)) { done(render()) }.disabled(frame == nil || pose == nil)
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 520)
        #endif
    }

    private var cropper: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                Role.surface
                if let frame, let pose {
                    let scale = frame.baseScale * pose.zoom
                    Image(decorative: image, scale: 1).resizable()
                        .frame(width: Double(image.width) * scale, height: Double(image.height) * scale)
                        .offset(x: pose.x, y: pose.y)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture()
                    .onChanged { g in
                        guard let frame, let pose else { return }
                        self.pose = frame.pan(pose: pose, dx: g.translation.width - dragged.width, dy: g.translation.height - dragged.height)
                        dragged = g.translation
                    }
                    .onEnded { _ in dragged = .zero }
                    .simultaneously(with: MagnifyGesture()
                        .onChanged { g in
                            guard let frame, let pose else { return }
                            self.pose = frame.zoom(pose: pose, factor: g.magnification / pinched)
                            pinched = g.magnification
                        }
                        .onEnded { _ in pinched = 1 })
            )
            .onAppear { size = geo.size }
            .onChange(of: geo.size) { _, new in size = new }
        }
        .onChange(of: size) { _, _ in
            guard let frame, pose == nil else { return }
            pose = frame.centred(zoom: 1)
        }
    }

    /// The framed part drawn into `EDGE` × `EDGE` RGBA, 4 bytes a pixel.
    private func render() -> Data? {
        guard let frame, let pose else { return nil }
        let crop = frame.crop(pose: pose)
        let rect = CGRect(x: crop.x * Double(image.width), y: crop.y * Double(image.height),
                          width: crop.width * Double(image.width), height: crop.height * Double(image.height)).integral
        guard let part = image.cropping(to: rect) else { return nil }
        let edge = Int(ProfileModel.companion.EDGE)
        let data = NSMutableData(length: edge * edge * 4)!
        guard let ctx = CGContext(data: data.mutableBytes, width: edge, height: edge, bitsPerComponent: 8, bytesPerRow: edge * 4,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: edge, height: edge))
        ctx.interpolationQuality = .high
        ctx.draw(part, in: CGRect(x: 0, y: 0, width: edge, height: edge))
        return data as Data
    }
}

// MARK: Safety numbers (D3)

/// The safety number on a person's page (the web's `MySafetyNumber` / `PersonSafetyNumber`): one's own with *Show*,
/// another member's state with *Compare*. A safety number is no secret: no secure window.
struct SafetyNumberSection: View {
    let model: AppModel
    let userId: String
    let name: String
    let mine: Bool
    @State private var shown: PersonTrust.Shown?
    @State private var loaded = false
    @State private var showing = false

    var body: some View {
        Section {
            if mine {
                Button(say(.safetyShow)) { showing = true }
            } else if !loaded {
                ProgressView()
            } else if let shown {
                status(shown)
                if shown.number != nil {
                    Button(say(.safetyOpen)) { showing = true }
                }
            } else {
                Text(say(.safetyLoadError)).foregroundStyle(Role.muted)
            }
        } header: {
            Text(say(mine ? .safetyMineTitle : .safetyTheirsTitle))
        }
        .task(id: userId) { await read() }
        .sheet(isPresented: $showing, onDismiss: { Task { await read() } }) {
            if mine { MySafetySheet(model: model) } else { CompareSheet(model: model, userId: userId, name: name) }
        }
    }

    private func read() async {
        guard !mine, let trust = model.personTrust else { loaded = true; return }
        shown = try? await trust.person(userId: userId)
        loaded = true
    }

    @ViewBuilder
    private func status(_ s: PersonTrust.Shown) -> some View {
        if s.anomaly {
            Label(say(.trustAnomaly), systemImage: "exclamationmark.triangle").foregroundStyle(Role.danger)
            Text(say(.trustAnomalyNote)).font(.footnote).foregroundStyle(Role.muted)
        } else {
            Label(say(s.verified ? .safetyStateVerified : s.isNew ? .safetyStateNew : .safetyStateUnverified),
                  systemImage: s.verified ? "checkmark.seal.fill" : "seal")
            if s.verified {
                let names = (s.verifiedByMe ? [say(.safetyVerifiedByYou)] : []) + s.verifiedBy
                Text(say(.safetyVerifiedBy, names.joined(separator: ", "))).font(.footnote).foregroundStyle(Role.muted)
            }
            if let at = s.rotatedAt {
                Text(say(.trustRotated, shownDayOf(at.int64Value))).font(.footnote).foregroundStyle(Role.muted)
            }
            if s.replaced { Text(say(.safetyReplaced)).font(.footnote).foregroundStyle(Role.muted) }
        }
    }
}

/// The digits in groups, monospaced, as the web shows them.
private struct Digits: View {
    let text: String
    var body: some View {
        Text(verbatim: text).font(.system(.title3, design: .monospaced)).textSelection(.enabled)
    }
}

/// One's own safety number: the digits, the chain code and the code another member scans (drawn from core's matrix).
struct MySafetySheet: View {
    let model: AppModel
    @State private var mine: PersonTrust.Mine?
    @State private var loaded = false

    var body: some View {
        FlowFrame(title: say(.safetyMineTitle), done: true) {
            if let mine {
                Section {
                    Digits(text: mine.number)
                } footer: {
                    Text(say(.safetyMineIntro))
                }
                Section {
                    Digits(text: mine.chainCode)
                } header: {
                    Text(say(.safetyChainCode))
                } footer: {
                    Text(say(.safetyMineChainCodeHint))
                }
                Section {
                    QrMatrixView(text: mine.payload)
                        .frame(maxWidth: 280)
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel(say(.safetyQrAlt))
                }
            } else if loaded {
                Section { Text(say(.safetyMineUnavailable)) }
            } else {
                Section { ProgressView() }
            }
        }
        .task {
            mine = try? await model.personTrust?.mine()
            loaded = true
        }
    }
}

/// Another member's safety number and its compare: their digits and chain code, *Scan their code* (the camera, taking
/// only a `veyqid:` code) or *Compare digits* → "They match" / "They don't match". A match verifies them (core's `verify`).
struct CompareSheet: View {
    let model: AppModel
    let userId: String
    let name: String
    @State private var shown: PersonTrust.Shown?
    @State private var outcome: PersonTrust.Outcome?
    @State private var scanning = false
    @State private var working = false

    var body: some View {
        FlowFrame(title: say(.safetyTheirsDialogTitle, name), done: true, working: working) {
            if let shown, let number = shown.number {
                Section {
                    Digits(text: number)
                } footer: {
                    Text(say(.safetyTheirsIntro, name))
                }
                if let chain = shown.chainCode {
                    Section {
                        Digits(text: chain)
                    } header: {
                        Text(say(.safetyChainCode))
                    } footer: {
                        Text(say(.safetyChainCodeHint, name))
                    }
                }
                if scanning {
                    Section {
                        SafetyScanner { text in
                            scanning = false
                            compare(scanned: text)
                        }
                        .frame(height: 300)
                    } footer: {
                        Text(say(.safetyScanHint, name))
                    }
                }
                if !shown.anomaly && outcome != .verified {
                    Section {
                        if QrCamera.available && !scanning {
                            Button { scanning = true } label: { Label(say(.safetyScan), systemImage: "qrcode.viewfinder") }
                        }
                        Button(say(.safetyTheyMatch)) { compare(match: true) }
                        Button(say(.safetyTheyDiffer), role: .destructive) { compare(match: false) }
                    } header: {
                        Text(say(.safetyCompare))
                    } footer: {
                        Text(say(.safetyCompareHint, name))
                    }
                }
                if let outcome { Section { Text(said(outcome)).foregroundStyle(outcome == .verified ? Role.text : Role.danger) } }
            } else if shown != nil {
                Section { Text(say(.safetyUnreadable)) }
            } else {
                Section { ProgressView() }
            }
        }
        .task { shown = try? await model.personTrust?.person(userId: userId) }
    }

    /// "They match" / "They don't match" ([match]), or a scanned code's text ([scanned]): core compares and verifies.
    private func compare(match: Bool = false, scanned: String? = nil) {
        guard let trust = model.personTrust else { return }
        working = true
        Task {
            if let scanned {
                outcome = try? await trust.compareScan(userId: userId, text: scanned)
            } else {
                outcome = try? await trust.compareDigits(userId: userId, match: match)
            }
            working = false
        }
    }

    private func said(_ o: PersonTrust.Outcome) -> String {
        switch o {
        case .verified: say(.safetyVerified, name)
        case .mismatch: say(.safetyMismatch, name)
        case .foreign: say(.safetyForeign)
        case .outdated: say(.safetyOutdated, name)
        case .moved: say(.safetyMoved, name)
        case .unanchored: say(.safetyUnanchored, name)
        default: say(.safetyFailed)
        }
    }
}

/// The camera over a scan that takes only a `veyqid:` code (core's `QrSchemes`); the code's text goes to the compare.
private struct SafetyScanner: View {
    let found: @MainActor @Sendable (String) -> Void
    @State private var scan: QrScan<AnyObject> = QrCamera.scan { text in
        QrSchemes.shared.of(text: text) == "veyqid" ? text as NSString : nil
    }
    @State private var watch: QrScanWatch?

    var body: some View {
        QrCameraView(scan: scan) { state in
            if state is QrScanStateNoCamera || state is QrScanStateDenied {
                Text(say(.safetyScanError)).foregroundStyle(Role.muted)
            }
        }
        .onAppear {
            let report = found
            watch = scan.watch { @Sendable state in
                guard let text = (state as? QrScanStateFound<AnyObject>)?.value as? String else { return }
                Task { @MainActor in report(text) }
            }
        }
        .onDisappear {
            watch?.stop()
            scan.close()
        }
    }
}
