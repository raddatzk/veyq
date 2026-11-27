import CoreGraphics
import Foundation
import Observation
import os
import VeyqCore
#if os(iOS)
import UIKit
#endif

/// One tenant row of the switcher (core's `ShellWords.SwitcherEntry`) and the account it switches to.
struct ServerEntry: Identifiable {
    let account: VeyqAccount
    let entry: ShellWords.SwitcherEntry
    var id: String { entry.connection + "\n" + entry.tenant.tenantId }
}

/// A tenant on "Manage servers"; `spaces` is nil while they load.
struct ManagedTenant: Identifiable {
    let tenant: TenantRef
    let hidden: Bool
    let current: Bool
    let spaces: [ManagedSpace]?
    /// Core's answers (`SessionModel.ManagedTenant`): its switch on, whether it may be switched, the line under its name.
    var shown = true
    var switchable = true
    var subtitle = ""
    var id: String { tenant.tenantId }
}

struct ManagedSpace: Identifiable {
    let space: SpaceRef
    let hidden: Bool
    let canHide: Bool
    var id: String { space.scopeId }
}

struct ManagedServer: Identifiable {
    let account: VeyqAccount
    let tenants: [ManagedTenant]
    /// How this device trusts the account, if it does.
    let trusted: TrustedDevice.Method?
    /// The level a staying-signed-in trust is at, and the levels this device offers (core's `ManagedServer`).
    var level: StaySignedIn?
    var levels: [StaySignedIn] = []
    var id: String { account.key }
    var label: String { account.label }
}

/// What the app shows, over the shared base: the sign-in (first, or to add a server), or the shell over the current
/// tenant. The navigation state is core's `Shell` (ADR 0122); this model only holds it and the lists the tenant read,
/// less what this device hides.
@MainActor
@Observable
final class AppModel {
    enum ScreenState {
        /// The remembered accounts resuming at start.
        case starting
        /// The full sign-in, with the Secret Key; server and email filled in where a trusted device's record knew them.
        /// `kit`: an Emergency Kit read, whose Secret Key core holds — the row says so, nothing shows the key.
        /// `notice`: what the way here left to say that is no error; `keepsNothing`: the locked sign-in's way back, which
        /// keeps nothing on this device (uc.account.sessions E3).
        case signIn(working: Bool, error: Msg?, adding: Bool, server: String?, email: String?, kit: SessionModelKitShown?, notice: Msg?, keepsNothing: Bool)
        /// Signed in without unlocking (uc.account.sessions E3, core's `Gate.Locked`): one action and the way back.
        case locked(server: String, email: String, working: Bool, error: Msg?)
        /// *Reset your password* (uc.account.recover-access, core's `Gate.Recover`): the link asked for; the web finishes it.
        case recover(server: String?, email: String?, working: Bool, sent: Bool, error: Msg?)
        /// An account this device trusts: the password alone, or the person's authentication.
        case unlock(record: TrustedDevice, working: Bool, error: Msg?)
        /// In the share extension with no account this device trusts: the app signs in, not the extension (ADR 0138).
        case needsApp
        case signed
    }

    private(set) var screen: ScreenState = .starting
    private(set) var shell: Shell = Shell.companion.start(lastUsed: nil, space: nil)
    private(set) var tenant: TenantRef?
    private(set) var server: String = ""
    private(set) var servers: [ServerEntry] = []
    private(set) var applications: [ApplicationId] = []
    private(set) var filters: [ApplicationId?] = [nil]
    private(set) var spaces: [SpaceRef] = []
    private(set) var nodes: [NodeRef] = []
    private(set) var objects: [ObjectRef] = []
    private(set) var favorites: [ObjectRef] = []
    private(set) var drawer: ArrangedList = ArrangedList(favorites: [], recent: [], all: [])
    /// The drawer's sections with something in them (core's `Lists.drawerSections`): an empty one is not drawn.
    private(set) var drawerSections: [DrawerSection] = []
    private(set) var switching = false
    /// The shell's banner while the server is out of reach (core's `ShellModel.State.banner`); nil online.
    private(set) var offlineBanner: VeyqCore.Notice?
    /// Items made available offline that left this device (core's `State.gone`), said until `goneSeen()`.
    private(set) var goneNotices: [VeyqCore.Notice] = []
    /// The shown level's selection while its Select mode is on (core's `ShellModel.State.selection`, inbox #1049); nil off.
    private(set) var selection: LevelSelection.Picking?
    /// Whether the account reaches its server: offline every write is taken away (ADR 0140 decision 7).
    var online: Bool { offlineBanner == nil }
    /// "Manage servers", shown as a sheet while non-nil.
    var managing: [ManagedServer]?
    var drawerOpen = false

    private let device: ThisDevice
    /// The share extension's model (ADR 0138): it signs in only from what the app left and moves nothing of the app's.
    let inExtension: Bool

    /// The app's one `ThisDevice` in the process: the model's and the background sync's (`BackgroundSync`), so an account
    /// the app holds open is the one a run finds (core's `BackgroundRun`: never opened twice). The share extension makes its own.
    static let appDevice = makeDevice(inExtension: false)

    private static func makeDevice(inExtension: Bool) -> ThisDevice {
        #if os(iOS)
        let label = UIDevice.current.model
        #else
        let label = Host.current().localizedName ?? "Mac"
        #endif
        let keychain = KeychainStore(movesLegacy: !inExtension)
        if !inExtension {
            // What the share extension reads too lives in the app group (ADR 0138); an earlier installation's move there.
            Shared.moveIntoGroup()
            // A first start after an installation: the Keychain may still hold an earlier installation's items.
            if !Shared.defaults.bool(forKey: "veyq.installed") {
                keychain.removeAll()
                Shared.defaults.set(true, forKey: "veyq.installed")
            }
        }
        #if os(iOS)
        let limit = AccountOffline.companion.PHONE_LIMIT
        // ADR 0153 decision 8: the iOS app syncs on the system's schedule (`BackgroundSync`), a Mac never
        let backgroundRuns = true
        #else
        let limit = AccountOffline.companion.DESKTOP_LIMIT
        let backgroundRuns = false
        #endif
        // the share extension never opens the offline store (ADR 0140 decision 3): it names no directory
        return AppleSessions.shared.device(
            keychain: keychain, label: label, prompt: say(.deviceUnlockPrompt), defaults: Shared.defaults,
            offlineRoot: inExtension ? nil : offlineRoot(), offlineLimit: limit, backgroundRuns: backgroundRuns && !inExtension)
    }

    /// The accounts' offline stores' directory (ADR 0140 decision 3): `offline/` in the app's Application Support — on iOS
    /// the app's own container (never the app group), on a Mac `~/Library/Application Support/<bundle id>/`. Made here so
    /// the system's own protection is set on it first: left out of backups (iCloud, Time Machine), and on iOS readable once
    /// the device was first unlocked after a restart. Nil where it cannot be made: then the app keeps nothing.
    private static func offlineRoot() -> String? {
        let fm = FileManager.default
        guard var dir = try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true) else { return nil }
        #if os(macOS)
        dir.appendPathComponent(Bundle.main.bundleIdentifier ?? "app.veyq", isDirectory: true)
        #endif
        dir.appendPathComponent("offline", isDirectory: true)
        do {
            #if os(iOS)
            try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            #else
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            #endif
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try dir.setResourceValues(values)
        } catch {
            return nil
        }
        return dir.path
    }
    private let recent: RecentUses
    private let hidden: Hidden

    /// The current tenant as read, before what this device hides is taken out.
    /// The shell over the current tenant (core's `ShellModel`): its lists, where the person is, the actions on a node.
    @ObservationIgnored private(set) var shellModel: ShellModel?
    @ObservationIgnored private var shellWatch: ShellModel.Watch?
    /// The search over the current tenant (core's `SearchModel`, ADR 0146 decision 7): made as the field is first used and
    /// disposed where the tenant closes (`closeSearch`); `found` is its state on the main actor, which the field draws.
    @ObservationIgnored var searchModel: SearchModel?
    @ObservationIgnored var searchWatch: SearchModel.Watch?
    var found: SearchShown?
    /// The settings section a search result chose (`SettingsSection.id`): the Settings page scrolls there, then clears it.
    var settingsAnchor: String?
    /// Moved when the search was closed from outside the field (the shell left it): the field empties its own text.
    var searchFieldReset = 0
    /// The way in (core's `SessionModel`): the start, the sign-in, the unlock, the switcher, the sign-out.
    @ObservationIgnored private let session: SessionModel<VeyqAccount, TenantSession>
    @ObservationIgnored private var gateWatch: SessionModelWatch?
    @ObservationIgnored private var followWatch: SessionModelWatch?
    /// The writes made offline of the shell open (core's, `QueuedHeld`); nil without a shell.
    var queued: QueuedHeld?
    /// The ask before a loss while writes made offline wait, until it is answered.
    var lossAsk: LossAsk?
    /// A held rename the tray's *Open* asked for: the node's header asks its name again with the person's.
    var renameAsked: QueuedOpen.Rename?
    typealias Opened = SessionModelOpened<VeyqAccount, TenantSession>

    init(inExtension: Bool = false) {
        // each application's part of the core's registry, before any session opens (AppComposition, ADR 0154 decision 3)
        AppComposition.shared.install()
        self.inExtension = inExtension
        device = inExtension ? Self.makeDevice(inExtension: true) : Self.appDevice
        let stored = Shared.defaults.stringArray(forKey: "veyq.recent") ?? []
        recent = RecentUses(
            // called on core's threads (a sign-out of the last account clears it there): @Sendable, never main-actor
            // isolated, or Swift 6 traps (src/mobile/CLAUDE.md § Traps)
            persist: { @Sendable keys in Shared.defaults.set(keys.map { $0.encode() }, forKey: "veyq.recent") },
            initial: stored.compactMap { UseKey.companion.decode(line: $0) },
            keep: 50
        )
        hidden = Hidden(
            persist: { @Sendable keys in Shared.defaults.set(Array(keys), forKey: "veyq.hidden") },
            initial: Set(Shared.defaults.stringArray(forKey: "veyq.hidden") ?? [])
        )
        session = AppleSessions.shared.sessionModel(device: device, hidden: hidden, recent: recent, signInElsewhere: inExtension)
        // a copy sent or a download handed to the save sheet in an earlier run has been read by now (or never will be)
        if !inExtension { Self.sweepTemporary() } else { try? FileManager.default.removeItem(at: Self.pickedDir) }
        gateWatch = session.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.gateChanged() }
        }
        // the shown account ended on its own (`Reach.Ended`: a reconnect found this device's trust gone) and the core
        // opened the next one: the shell follows what the core says (`SessionModel.followed`, ADR 0154 decision 1)
        followWatch = session.watchFollowed { @Sendable _ in
            Task { @MainActor [weak self] in self?.follow() }
        }
    }

    // MARK: What each application holds here (ADR 0154 decision 3, inbox #1066)

    /// Each registered application's `Holding` (the registry's `holding`), built once, in the order they registered: the
    /// core UI asks every one the tenant's moments and the flows an application answers, and names none.
    @ObservationIgnored private var built: [any Holding]?
    private var holdings: [any Holding] {
        if let built { return built }
        let made = Registry.registered().compactMap { $0.holding?(self) }
        built = made
        return made
    }

    /// The holding of type `T` an application registered: how its UI reaches what it holds here.
    func held<T: Holding>(_ type: T.Type) -> T {
        guard let h = holdings.lazy.compactMap({ $0 as? T }).first else { preconditionFailure("no holding \(T.self) registered") }
        return h
    }

    private func eachHolding(_ act: (any Holding) -> Void) { holdings.forEach(act) }

    /// The tray's *Open* of `opens` where an application opens it (a Pot's held entry); false where none does.
    func holdingsOpen(_ opens: QueuedOpen) -> Bool { holdings.contains { $0.openQueued(opens) } }

    /// The shell's uploads into a level (the tray, New's *Upload files* and *Upload folder*): the application's that keeps files.
    var levelUploads: LevelUploads? { holdings.lazy.compactMap { $0.levelUploads }.first }

    /// A picked photo's or video's tiers: the application's that derives them (a link guest's upload and a Mac's import too).
    var deriver: DerivationDeriver? { holdings.lazy.compactMap { $0.deriver }.first }

    /// Whether an application's editor is open over the page (a guest's naming waits while it is).
    var editing: Bool { holdings.contains { $0.editing } }

    /// The row of `nodeId` says `facts` now (an application's write moved what its row says, a Pot's new base currency).
    func setRowDetail(_ nodeId: String, _ facts: [String]) { rowDetails[nodeId] = facts }

    // core's shell rules over its lists; the observed lists are read first, so a view follows them as before
    var space: SpaceRef? { _ = spaces; return shellModel?.current?.lists.space(shell: shell) }

    /// Home's level: the shown folder's children in the shown space, under the shown filter.
    var level: [NodeRef] { _ = nodes; return shellModel?.current?.lists.level(shell: shell) ?? [] }
    /// Home's level in its groups, the folders apart (core's `Home.groups`).
    var levelGroups: [Home.Group] { _ = nodes; return shellModel?.current?.lists.groups(shell: shell) ?? [] }

    /// Where the app opens (ADR 0123 decision 8): every account the person stays signed in to resumes after one
    /// authentication; with none, the most recent password-trusted account asks for its password; with no trusted
    /// account, the sign-in. Once per process.
    func start() {
        Task { if let opened = try? await session.start() { try? await show(opened) } }
    }

    /// What the way in shows, as core's model has it; signed in, the shell shows once it read the tenant opened.
    private func gateChanged() {
        if !(session.current is SessionModelGate.Signed) {
            offer = nil
            terms = nil
            // locked or gone: the open dialogs and the tenant's tree go — a person's index with it (ADR 0144 decision 12)
            eachHolding { $0.locked() }
            closeSearch()
            // a kept link shown in place goes with the tenant; a link open on its own screen is a guest's and stays
            closeBookmark()
            tenantSession?.close()
        }
        switch session.current {
        case let g as SessionModelGate.Signed: trustLog("gate Signed, offer: \(g.offer.map { "\($0.levels)" } ?? "none"), terms: \(g.terms.map { "r\($0.shown.revision)" } ?? "none")")
        case let g as SessionModelGate.Unlock: trustLog("gate Unlock, \(g.record.method), level: \(g.record.unlockLevel.map { "\($0)" } ?? "none")")
        default: trustLog("gate \(type(of: session.current))")
        }
        switch session.current {
        case let g as SessionModelGate.SignIn:
            screen = .signIn(
                working: g.working, error: g.error, adding: g.adding, server: g.server, email: g.email, kit: g.kit,
                notice: g.notice, keepsNothing: g.keepsNothing
            )
        case let g as SessionModelGate.Locked:
            screen = .locked(server: g.server, email: g.email, working: g.working, error: g.error)
        case let g as SessionModelGate.Recover:
            screen = .recover(server: g.server, email: g.email, working: g.working, sent: g.sent, error: g.error)
        case let g as SessionModelGate.Unlock:
            screen = .unlock(record: g.record, working: g.working, error: g.error)
        case is SessionModelGate.NeedsApp:
            screen = .needsApp
        case let g as SessionModelGate.Signed:
            switching = g.switching
            offer = g.offer
            terms = g.terms
            if shellModel != nil, shellModel?.tenant === session.signedTo?.session { applyShell(start: false); screen = .signed }
        default:
            screen = .starting
        }
    }

    /// What the core opened in the shown account's place (`SessionModel.followed`): shown where the shell is over another.
    private func follow() {
        guard let opened = session.followed.value as? Opened else { return }
        trustLog("the shown account ended: the shell follows the next one")
        Task { if shellModel?.tenant !== opened.session { try? await show(opened) } }
    }

    /// The shell's notices of items no longer available offline were shown: core does not say them again.
    func goneSeen() {
        guard let m = shellModel else { return }
        goneNotices = []
        Task { try? await m.goneSeen() }
    }

    /// The debug build's unlock (`devlogin.py --unlock`): the password for the unlock screen, now or once it shows.
    func devUnlock(password: String) {
        devFillPasswordTrust(password)
        Task { if let opened = try? await session.devUnlock(password: password) { try? await show(opened) } }
    }

    /// The full sign-in, which holds the password trust; the core's offer to stay signed in follows it (`offer`). `then`
    /// runs once it opened — the debug build's `VEYQ_DEV_REMEMBER` answers the offer there, as a person would.
    func signIn(server: String, email: String, password: String, secretKey: String, then: (@MainActor () -> Void)? = nil) {
        Task {
            if let opened = try? await session.signIn(server: server, email: email, password: password, secretKey: secretKey) {
                try? await show(opened)
                then?()
            }
        }
    }

    /// The Emergency Kit file the person picked: read in place into memory (`KitBytes`, coordinated, never copied) off the
    /// main thread and handed to core, which finds the kit and holds its Secret Key (`KitDrawer` draws what it asks for);
    /// `typedServer` is the form's server as typed now. What it found shows on the sign-in's gate; the bytes are zeroed
    /// once core's read ended (core zeroes its own copy).
    func readKit(_ url: URL, typedServer: String) {
        Task {
            let bytes = await Task.detached { KitBytes.read(url) }.value
            let drawer = KitDrawer(bytes)
            _ = try? await session.readKit(
                bytes: AppleKit.shared.bytes(data: bytes.view), pictures: AppleKit.shared.pictures(drawing: drawer), typedServer: typedServer
            )
            drawer.close()
            bytes.clear()
        }
    }

    /// The Secret Key row's way back to typing: the kit's held key ends.
    func clearKit() { session.clearKit() }

    /// The sign-in's *I don't have my Secret Key with me* (uc.account.sessions E3): the login alone; the gate shows the locked state.
    func signInLocked(server: String, email: String, password: String) {
        Task { try? await session.signInLocked(server: server, email: email, password: password) }
    }

    /// The locked state's one action, asked first by its view: every device signed out, this one included.
    func signOutEverywhere() { Task { try? await session.signOutEverywhere() } }

    /// The locked state's way back: the sign-in again, keeping nothing on this device.
    func lockedBack() { Task { try? await session.lockedBack() } }

    /// The sign-in's *Forgot your password?* with the form's server and email.
    func forgotPassword(server: String, email: String) { session.forgotPassword(server: server, email: email) }

    /// *Reset your password*'s Send: the link asked for.
    func requestRecovery(server: String, email: String) {
        Task { try? await session.requestRecovery(server: server, email: email) }
    }

    /// *Reset your password*'s Back.
    func backToSignIn() { session.backToSignIn() }

    /// Why the sign-in's Secret Key field, as typed, holds no key (core's `SessionModel.secretKeyProblem`): a typo is said.
    func secretKeyProblem(_ text: String) -> SecretKeyProblem? { session.secretKeyProblem(text: text) }

    /// A flow's Secret Key section took a kit file (ADR 0151 decision 4): read in place into memory as the sign-in's is
    /// and handed to the flow's `intake`, which holds its key until the call or the flow ends.
    func readFlowKit(_ intake: KeyIntake, _ url: URL) {
        Task {
            let bytes = await Task.detached { KitBytes.read(url) }.value
            let drawer = KitDrawer(bytes)
            _ = try? await intake.read(
                bytes: AppleKit.shared.bytes(data: bytes.view), pictures: AppleKit.shared.pictures(drawing: drawer), typedServer: nil
            )
            drawer.close()
            bytes.clear()
        }
    }

    /// A flow's kit scan for `KitScannerView`'s camera: the kit found taken by the flow's `intake`; each decode's time is
    /// logged, never a code's text.
    func scanFlowKit(_ intake: KeyIntake) -> QrScan<AnyObject> {
        AppleKit.shared.scan(intake: intake, timing: QrCamera.timing)
    }

    /// The sign-in's kit scan for `KitScannerView`'s camera: core's scan with the kit's parser as its `accepts`, the found
    /// kit taken by core (the gate fills the form); `typedServer` is the form's server as typed now. Its `Found` carries
    /// only where the kit's server came from; each decode's time is logged, never a code's text.
    func scanKit(typedServer: String) -> QrScan<AnyObject> {
        AppleKit.shared.scan(session: session, typedServer: typedServer, timing: QrCamera.timing)
    }

    // MARK: Device linking (ADR 0059)

    /// The ceremony on screen (`DeviceLinkView`), and its state as core last said it; nil while none is open.
    private(set) var link: DeviceLink?
    private(set) var linkShown: DeviceLink.Shown?
    @ObservationIgnored private var linkWatch: DeviceLink.Watch?
    /// How the link on screen was opened, for "Start Over": the same role, the same server.
    @ObservationIgnored private var linkMake: (@MainActor () -> DeviceLink?)?

    /// Settings' "Show a Code" (*add a new device*, the trusted side): this account's code for the new device to scan.
    func linkShow() {
        guard let account = session.signedTo?.account else { return }
        openLink { AppleLink.shared.showForNewDevice(account: account) }
    }

    /// Settings' "Scan a Code" (*sign in with a QR code*, the trusted side): the new device's code, scanned here.
    func linkScan() {
        guard let account = session.signedTo?.account else { return }
        openLink { AppleLink.shared.scanSignInCode(account: account, timing: QrCamera.timing) }
    }

    /// The sign-in's "Scan a Code from Another Device", over the form's server as typed now (nothing in a scan changes it).
    func scanFromTrusted(server: String) {
        let session = session
        openLink { AppleLink.shared.scanFromTrusted(session: session, server: server, timing: QrCamera.timing) }
    }

    /// The sign-in's "Sign In with a QR Code", over the form's server as typed now.
    func showForSignIn(server: String) {
        let session = session
        openLink { AppleLink.shared.showForSignIn(session: session, server: server) }
    }

    /// "Start Over": a new ceremony of the same role — a new secret, never the old one retried (ADR 0059 §6).
    func linkAgain() {
        guard let make = linkMake else { return }
        openLink(make)
    }

    /// The ceremony's sheet went: the link ends (its polls stop, its scan closes, nothing more is sent).
    func closeLink() {
        linkWatch?.stop()
        linkWatch = nil
        link?.close()
        link = nil
        linkShown = nil
    }

    private func openLink(_ make: @escaping @MainActor () -> DeviceLink?) {
        closeLink()
        guard let made = make() else { return }
        linkMake = make
        link = made
        linkShown = made.current
        // core calls the watch on its own threads: @Sendable, back on the main actor (src/mobile/CLAUDE.md § Traps)
        nonisolated(unsafe) let watched = made
        // the new device signed in: the shell opens over the account the grant made, its offer over it — awaited apart
        // from the watch, which a close ends: a close while the grant is spent ends the sheet, not the sign-in (ADR 0161's Consequences)
        if made.role == .scanFromTrusted || made.role == .showForSignIn {
            let session = session
            Task { @MainActor [weak self] in
                if let opened = try? await session.signedInBy(link: watched) { try? await self?.show(opened) }
            }
        }
        linkWatch = made.watch { @Sendable shown in
            nonisolated(unsafe) let shown = shown
            Task { @MainActor [weak self] in self?.linkChanged(watched, shown) }
        }
    }

    /// Its steps go to the unified log (`veyq-link:`): the step, the refusal and the hint — never a code, a number or a name.
    private func linkChanged(_ made: DeviceLink, _ shown: DeviceLink.Shown) {
        guard made === link else { return }
        linkShown = shown
        NSLog("veyq-link: %@: %@%@%@", "\(made.role)", "\(shown.step)", shown.refusal.map { ", \($0)" } ?? "", shown.hint.map { ", hint \($0)" } ?? "")
        // the new device is signed in: its sheet goes (the shell opens on `signedInBy`, `openLink`)
        if (made.role == .scanFromTrusted || made.role == .showForSignIn) && shown.step == .done { closeLink() }
    }

    #if DEBUG
    /// A pass's sign-out of the current server (`DevLogin`'s `VEYQ_DEV_LINK_DIR/signout`), as Settings' "Sign Out".
    func devSignOut() {
        if let account = session.signedTo?.account { signOut(account) }
    }
    #endif

    // MARK: Staying signed in (ADR 0123 decision 8)

    /// The offer to stay signed in a sign-in left open (core's `Gate.Signed.offer`), a sheet over the shell; nil once answered.
    private(set) var offer: SessionModelOffer?

    /// The tenant's terms gate (core's `Gate.Signed.terms`, uc.account.accept-terms), drawn in place of the shell; nil once
    /// agreed to.
    private(set) var terms: SessionModelTermsGate?

    /// The terms gate's "I Agree" (core's `acceptTerms`): the gate says how it went.
    func acceptTerms() { Task { _ = try? await session.acceptTerms() } }

    /// The terms gate's "Sign Out": the account shown signs out here, as Settings' does.
    func signOutAtTerms() {
        if let account = session.signedTo?.account as? VeyqAccount { signOut(account) }
    }

    /// Settings' Terms: the open tenant's terms in the app's language and, for an owner, the editor's draft (core's
    /// `MemberTerms.editing`); nil where they did not read or no tenant is open.
    func termsEditing() async -> MemberTerms.TermsEditing? {
        guard let t = tenantSession else { return nil }
        return try? await MemberTerms.shared.editing(tenant: t, language: AppleSessions.shared.appLanguage())
    }

    /// The owner's *Save Terms* (core's `MemberTerms.save`): the server's draft and what is said; nil without a tenant.
    func saveTerms(_ draft: MemberTerms.TermsDraft) async -> MemberTerms.Saved? {
        guard let t = tenantSession else { return nil }
        return try? await MemberTerms.shared.save(tenant: t, draft: draft)
    }

    /// Settings' Members (core's `TenantMembers.read`): the open space's roster read anew; nil without a tenant.
    func membersRead() async -> TenantMembers.Roster? { try? await tenantSession?.members.read() }

    /// A row's *Make Co-Owner* ([owner]) or *Remove Co-Owner* (core's `TenantMembers.setRole`).
    func setMemberRole(_ userId: String, owner: Bool) async throws -> TenantMembers.Outcome? {
        try await tenantSession?.members.setRole(userId: userId, owner: owner)
    }

    /// A row's *Remove Member*, after its question with its two options (core's `TenantMembers.remove`): every link off,
    /// and a new lock run after the removal, the removed member left out of it.
    func removeMember(_ userId: String, revokeLinks: Bool, newLock: Bool) async throws -> TenantMembers.Outcome? {
        try await tenantSession?.members.remove(userId: userId, revokeLinks: revokeLinks, newLock: newLock)
    }

    /// The owner's *Give it a new lock*, after its question (core's `TenantMembers.newLock`).
    func membersNewLock() async throws -> TenantMembers.Outcome? {
        try await tenantSession?.members.doNewLock(excluding: nil)
    }

    /// The owner's *Turn off every link* of the space, after its question (core's `TenantMembers.revokeAllLinks`).
    func membersRevokeLinks() async throws -> TenantMembers.Outcome? {
        try await tenantSession?.members.revokeAllLinks()
    }

    // MARK: A scope's icon (core's `ScopeIcons`, uc.spaces.set-space-icon)

    /// The open space's scope id, whose icon Members' header draws.
    var tenantScopeId: String? { tenantSession?.tenant.tenantId }

    /// The core's icons with its own WebP encoder (libwebp's, as the profile's picture; the default argument is not exported).
    private func icons(_ t: TenantSession) -> ScopeIcons { t.icons(encoder: LibwebpEncoderKt.coreWebpEncoder) }

    /// `scopeId`'s icon opened on this device, as encoded bytes; nil where none is set or it does not open (the initial shows).
    func scopeIcon(_ scopeId: String) async -> Data? {
        guard let t = tenantSession, let bytes = try? await icons(t).icon(scopeId: scopeId) else { return nil }
        return Data(kotlin: bytes)
    }

    /// *Add icon* / *Change icon*: the square crop's `ProfileModel.EDGE` RGBA as `scopeId`'s icon (core's `ScopeIcons.set`).
    func setScopeIcon(_ scopeId: String, rgba: Data) async -> ScopeIcons.Outcome? {
        guard let t = tenantSession else { return nil }
        return try? await icons(t).set(scopeId: scopeId, rgba: AppleKit.shared.bytes(data: rgba))
    }

    /// *Remove icon*, after its question (core's `ScopeIcons.clear`).
    func clearScopeIcon(_ scopeId: String) async -> ScopeIcons.Outcome? {
        guard let t = tenantSession else { return nil }
        return try? await icons(t).clear(scopeId: scopeId)
    }

    // MARK: Groups (core's `TenantGroups`, en.spaces.group-scope, uc.spaces.consent-group-add)

    /// Settings' Groups and Group invitations: the open tenant's groups, every act on them core's; nil without a tenant.
    var groups: TenantGroups? { tenantSession?.groups }

    // MARK: Invites (core's `TenantInvites`, uc.spaces.add-member, uc.spaces.manage-member-invites)

    /// *Invite member* by link, with the owners' optional label: the link shown once (`outcome.address`).
    func inviteByLink(label: String?) async throws -> TenantInvites.Outcome? {
        try await tenantSession?.invites.inviteByLink(label: label)
    }

    /// *Invite member* by mail: the code shown once, or none where the owner chose a `password`.
    func inviteByMail(email: String, password: String?) async throws -> TenantInvites.Outcome? {
        try await tenantSession?.invites.inviteByMail(email: email, password: password)
    }

    /// A pending invite's *Send again* / *New link*: its new code or link shown once.
    func reissueInvite(_ invite: TenantInvites.PendingInvite) async throws -> TenantInvites.Outcome? {
        try await tenantSession?.invites.reissue(invite: invite)
    }

    /// A pending invite's *Cancel invite*, after its question.
    func revokeInvite(_ inviteId: String) async throws -> TenantInvites.Outcome? {
        try await tenantSession?.invites.revoke(inviteId: inviteId)
    }

    /// A link invite's label set (empty clears it).
    func setInviteLabel(_ inviteId: String, label: String) async throws -> TenantInvites.Outcome? {
        try await tenantSession?.invites.setLabel(inviteId: inviteId, label: label.isEmpty ? nil : label)
    }

    // MARK: Joining from an invite (core's `TenantJoin`, ADR 0111, ADR 0129)

    /// The account door a join signs in through: this device and the core's no-disk client, as the session's own.
    @ObservationIgnored private lazy var door = AccountDoor(
        primitives: LibsodiumPrimitives.shared, device: device,
        client: { block in AppleKt.appleHttpClient(block: { config in _ = block(config) }) }
    )

    /// The join of a received `invite`, not loaded yet.
    func join(_ invite: ReceivedLink.InviteLink) -> TenantJoin { door.join(invite: invite) }

    /// *Open the space*: the app's own session signs in with what the join holds, its answer shown as a sign-in's is.
    /// False where it did not (the join's state says why).
    func openJoined(_ join: TenantJoin) async -> Bool {
        let any = try? await join.openSpace(session: session as! SessionModel<Connection, AnyObject>)
        guard let opened = any as? Opened else { return false }
        try? await show(opened)
        return true
    }
    /// A change of this device's trust is under way (the offer's answer, Manage's): its controls wait.
    private(set) var trustWorking = false
    /// "Manage servers"' line under a server after a change it refused (a wrong password), by the account's key.
    var manageSaid: [String: String] = [:]

    /// The offer's answer: staying signed in at `level`, or "Not Now" (nil). A cancelled prompt leaves the offer standing
    /// (the core's rule); a level this device could not keep ends it, said in the shell.
    func answerOffer(_ level: StaySignedIn?) {
        guard let level else {
            _ = session.notNow()
            trustLog("offer answered: NotNow")
            return
        }
        Task {
            trustWorking = true
            let change = (try? await session.stay(level: level)) ?? nil
            trustWorking = false
            trustLog("offer answered: \(level) -> \(change.map { "\($0)" } ?? "none")")
            if change == .failed { notice = Notice(text: say(.trustFailed)) }
        }
    }

    /// "Manage servers": `account` staying signed in at `level`, replacing the trust it had.
    func stayAt(_ account: VeyqAccount, _ level: StaySignedIn) {
        Task {
            trustWorking = true
            let change = (try? await session.stay(account: account, level: level)) ?? .failed
            trustWorking = false
            trustLog("manage stay signed in \(level): \(change)")
            manageSaid[account.key] = change == .failed ? say(.trustFailed) : nil
            if managing != nil { managing = managed() }
        }
    }

    /// "Manage servers"' way back to the password trust for `account` with `password`; whether it is done (a wrong
    /// password says so under the server and changes nothing).
    func backToPassword(_ account: VeyqAccount, _ password: String) async -> Bool {
        trustWorking = true
        let change = (try? await session.trustWithPassword(account: account, password: password)) ?? .failed
        trustWorking = false
        trustLog("manage back to the password: \(change)")
        switch change {
        case .done: manageSaid[account.key] = nil
        case .wrongPassword: manageSaid[account.key] = say(.manageWrongPassword)
        default: manageSaid[account.key] = say(.trustFailed)
        }
        if managing != nil { managing = managed() }
        return change == .done
    }

    /// The debug build's `--unlock` password, for "Manage servers"' password field once (never typed by a pass).
    @ObservationIgnored private var devPasswordTrust: String?
    func devFillPasswordTrust(_ password: String) { devPasswordTrust = password }
    func takeDevPasswordTrust() -> String? {
        defer { devPasswordTrust = nil }
        return devPasswordTrust
    }

    /// The device trust's steps in the unified log (`veyq-trust:`; `log show --predicate 'eventMessage CONTAINS
    /// "veyq-trust"'`), for a pass to read before it looks: never a key or a password.
    private func trustLog(_ line: String) {
        NSLog("veyq-trust: %@", line)
    }

    /// The trusted account's unlock: its password, or the person's authentication again.
    func unlock(password: String) {
        Task { if let opened = try? await session.unlock(password: password) { try? await show(opened) } }
    }

    /// From the unlock to the full sign-in, for another account.
    func otherAccount() { session.otherAccount() }

    /// The unlock's *Stop trusting this device* (inbox #1052): core's ask first, always (owner 2026-11-22), naming the
    /// writes made offline that wait in the same question (`QueuedShown.forgetAsk`), then the account forgotten here; the
    /// gate shows the next account or the sign-in.
    func forgetUnlocked() {
        let go = { [weak self] in
            guard let self else { return }
            Task { if (try? await self.session.forgetUnlocked())?.boolValue == true { self.trustLog("unlock stop trusting") } }
        }
        lossAsk = LossAsk(ask: QueuedShown.shared.forgetAsk(waiting: session.waitingOnUnlock()), go: go)
    }

    /// The switcher: another tenant of the same server is the same session at another domain; another server is its own.
    func switchTo(_ account: VeyqAccount, _ tenant: TenantRef) {
        drawerOpen = false
        managing = nil
        Task { if let opened = try? await session.switchTo(account: account, tenant: tenant) { try? await show(opened) } }
    }

    func addServer() {
        drawerOpen = false
        managing = nil
        session.addServer()
    }

    func cancelAdd() { session.cancelAdd() }

    // MARK: Manage servers

    func openManage() {
        drawerOpen = false
        Task {
            try? await session.loadSpaces { @Sendable in
                Task { @MainActor [weak self] in
                    if self?.managing != nil { self?.managing = self?.managed() }
                }
            }
        }
        managing = managed()
    }

    func setTenantHidden(_ account: VeyqAccount, _ tenant: TenantRef, _ value: Bool) {
        hidden.setTenantHidden(connectionKey: account.key, tenantId: tenant.tenantId, hidden: value)
        if managing != nil { managing = managed() }
        applyShell(start: false)
    }

    func setSpaceHidden(_ scopeId: String, _ value: Bool) {
        hidden.setSpaceHidden(scopeId: scopeId, hidden: value)
        if managing != nil { managing = managed() }
        shellModel?.rebuild()
        applyShell(start: false)
    }

    /// Signs out of one server; if it was current, another one held becomes current, else the unlock of an account
    /// this device still trusts with the password, or the sign-in. Staying signed in ends with the sign-out; the
    /// password trust stays (as on the web).
    func signOut(_ account: VeyqAccount) {
        // asked first while writes made offline wait (they go with the sign-out)
        askingLoss(.signOut, of: account) { [weak self] in self?.signOutNow(account) }
    }

    private func signOutNow(_ account: VeyqAccount) {
        if session.signedTo?.account == account { uploads.clear() }
        // a share's files handed over and not queued yet go unsent (ADR 0138 decision 6)
        ShareHandoff.clear()
        Task {
            guard let out = try? await session.signOut(account: account) else { return }
            if let next = out.next {
                managing = nil
                try? await show(next)
            } else if out.wasCurrent {
                managing = nil
                closeTenant()
            } else if managing != nil {
                managing = managed()
            }
        }
    }

    /// Ends this device's trust in `account`: the next sign-in here asks for the Secret Key again.
    func forgetDevice(_ account: VeyqAccount) {
        askingLoss(.stopTrusting, of: account) { [weak self] in self?.forgetDeviceNow(account) }
    }

    private func forgetDeviceNow(_ account: VeyqAccount) {
        Task {
            try? await session.forgetDevice(account: account)
            trustLog("manage stop trusting")
            manageSaid[account.key] = nil
            if managing != nil { managing = managed() }
        }
    }

    // MARK: The account's Settings (uc.account.sessions)

    /// The open Settings page's state (core's `AccountSettings`); nil while it is closed — on iOS a sheet while non-nil.
    var settings: AccountSettingsState?
    @ObservationIgnored private var settingsModel: AccountSettings<VeyqAccount, TenantSession>?
    @ObservationIgnored private var settingsWatch: AccountSettingsWatch?
    /// "Storage on this device" read anew at each change of the pins while the page is open (core's).
    @ObservationIgnored private var storageWatch: AccountSettingsWatch?

    // MARK: A place reached from outside (inbox #1415)

    /// A `veyq://open` place held in memory until a shell opens (never stored, never logged): one for no tenant open here
    /// then moves nothing.
    @ObservationIgnored private var arriving: PlaceLink?

    /// A `veyq://open` place: the shell's now where one is on screen, else once one opens.
    func arrive(_ place: PlaceLink) {
        guard let model = arrivable() else { arriving = place; return }
        arrived(model.arrive(link: place))
    }

    /// The shell on screen (signed in and unlocked); a locked one holds a link until its unlock opens a shell anew.
    private func arrivable() -> ShellModel? {
        guard let model = shellModel, model.current != nil, session.current is SessionModelGate.Signed else { return nil }
        return model
    }

    /// The open shell's answer to a member's link pasted or shared (core's `OpenLink.openPlace`); on the unlock it waits for
    /// the shell the unlock opens (`arriving`); nil on the sign-in, where no account is on the device to open it.
    func arriveHere(_ place: PlaceLink) -> Arrived? {
        if let model = arrivable() { return model.arrive(link: place) }
        // the unlock on screen, or the start still deciding whether one comes (a share that started the app)
        guard session.current is SessionModelGate.Unlock || session.current is SessionModelGate.Starting else { return nil }
        arriving = place
        return Arrived.Waiting.shared
    }

    /// What an arrival answered: a Settings link opens the page at its section (its top: the first); the rest is the shell's.
    func arrived(_ answer: Arrived) {
        guard let settings = answer as? Arrived.Settings else { return }
        settingsAnchor = settings.section ?? SettingsSection.appearance.id
        #if os(iOS)
        openSettings()
        #endif
    }

    /// The drawer's Settings (and a Mac's Settings window): the current account's page, read anew as it opens.
    func openSettings() {
        drawerOpen = false
        guard settingsModel == nil, let account = session.signedTo?.account else { return }
        let m = AccountSettingsCompanion.shared.of(session: session, account: account, separator: decimalSeparator)
        settingsModel = m
        settings = m.current
        settingsWatch = m.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.syncSettings() }
        }
        storageWatch = m.startFollowStorage()
        Task { try? await m.refresh() }
    }

    func closeSettings() {
        settingsWatch?.stop()
        settingsWatch = nil
        storageWatch?.stop()
        storageWatch = nil
        settingsModel = nil
        settings = nil
    }

    private func syncSettings() {
        if let m = settingsModel { settings = m.current }
    }

    /// Runs `block` on the open page's model; a sign-out it answers closes the page and is followed as Manage's is.
    private func onSettings(_ block: @escaping @MainActor (AccountSettings<VeyqAccount, TenantSession>) async throws -> SessionModelSignedOut<VeyqAccount, TenantSession>?) {
        guard let m = settingsModel else { return }
        Task {
            guard let out = try? await block(m) else { return }
            trustLog("settings: signed out here")
            closeSettings()
            if out.wasCurrent { uploads.clear() }
            if let next = out.next {
                managing = nil
                try? await show(next)
            } else if out.wasCurrent {
                managing = nil
                closeTenant()
            }
        }
    }

    /// The account shown signed out and no other opened: the shell and everything of the tenant close — each application's
    /// models (`Holding.signedOut`), the writes made offline, the music, the maps, the headers.
    private func closeTenant() {
        shellWatch?.stop()
        shellModel = nil
        closeSearch()
        tenantSession?.close()
        closeImport()
        eachHolding { $0.signedOut() }
        closeQueued()
        closeMusic()
        closeMaps()
        dropHeaders()
    }

    /// "Manage servers" as core lists it, for SwiftUI's lists.
    private func managed() -> [ManagedServer] {
        let servers = session.managed().map { c in
            ManagedServer(account: c.account, tenants: c.tenants.map { t in
                ManagedTenant(tenant: t.tenant, hidden: t.hidden, current: t.current, spaces: t.spaces?.map {
                    ManagedSpace(space: $0.space, hidden: $0.hidden, canHide: $0.canHide)
                }, shown: t.shown, switchable: t.switchable, subtitle: say(piece: t.subtitle))
            }, trusted: c.trusted, level: c.level, levels: c.levels)
        }
        trustLog("manage servers: " + servers.map { "\($0.trusted.map { "\($0)" } ?? "none")/\($0.level.map { "\($0)" } ?? "none")" }.joined(separator: ", "))
        return servers
    }

    // MARK: The current tenant

    private func show(_ opened: Opened) async throws {
        let account = opened.account
        let tenant = opened.tenant
        let t = opened.session
        // the re-key's rules and its background resume are the core's (`AccountDoor.open`/`opened`)
        let switched = tenantSession.map { was in was.account !== account || was.tenant.tenantId != tenant.tenantId } ?? true
        if switched { uploads.clear() }
        // the tenant shown before closes: its tree and what was derived from it (a person's index) dropped now (ADR 0144 decision 12)
        if let was = tenantSession, was !== t { was.close() }
        tenantSession = t
        // what was open of the tenant before closes (each application's pages), then the new shell's models are built
        eachHolding { $0.leave() }
        let model = ShellModel(tenant: t, hidden: hidden, recent: recent, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        closeSearch()
        shellWatch?.stop()
        shellModel = model
        closeImport()
        eachHolding { $0.shown(model, t, switched: switched) }
        openQueued(model)
        openMaps(model)
        pickUpHandoffs()
        openMusic(model, switched: switched)
        dropHeaders()
        try await model.load(openLastUsed: incoming == nil)
        // a place a `veyq:` link named before this shell opened (the sign-in, the unlock): opened now, over this tenant only
        if let place = arriving {
            arriving = nil
            arrived(model.arrive(link: place))
        }
        shellWatch = model.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.applyShell(start: false) }
        }
        await readDetails(t, reset: true)
        // The members' pictures as core reads them (when is core's rule); the letters show meanwhile.
        avatars = [:]
        avatarsWatch?.stop()
        avatarsWatch = t.pictures.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.syncAvatars() }
        }
        Task { try? await t.pictures.load() }
        self.tenant = tenant
        server = account.label
        applyShell(start: true)
        screen = .signed
    }

    /// The shell as core's model holds it now, with this device's servers; on `start`, the drawer opens on Home.
    func applyShell(start: Bool) {
        guard let signed = session.signedTo, shellModel?.tenant === signed.session, let state = shellModel?.current else { return }
        let account = signed.account
        let tenant = signed.tenant
        let l = state.lists
        spaces = l.spaces
        // what the share extension handed over for one of these spaces (ADR 0138 decision 6)
        pickUpHandoffs()
        nodes = l.nodes
        objects = l.objects
        favorites = l.favorites
        applications = l.applications
        filters = l.filters.map { $0 as? ApplicationId }
        drawer = l.drawer
        servers = ShellWords.shared.switcher(accounts: session.accounts, hidden: hidden, current: account, tenant: tenant).compactMap { e in
            session.accounts.first { $0.key == e.connection }.map { ServerEntry(account: $0, entry: e) }
        }
        drawerSections = shellModel?.drawerSections(lists: l) ?? []
        let wasSearching = shell.searching
        shell = state.shell
        // the search left for anything but what it opened (whose back returns to it): the field empties (core's `close`)
        if wasSearching, !shell.searching, shell.back?.searching != true { searchModel?.close(); searchFieldReset += 1 }
        let wasOffline = offlineBanner != nil
        offlineBanner = state.banner
        goneNotices = state.gone
        selection = state.selection
        if wasOffline, offlineBanner == nil, maps?.state.value is MapStateOffline, let m = shellModel { openMaps(m) }
        if start { drawerOpen = shell.drawerOpensAtStart }
    }

    /// Whether "New" is offered where the shell is (core's `ShellModel.canCreateHere`).
    var canCreateHere: Bool { _ = shell; return shellModel?.canCreateHere ?? false }

    /// The shown level's plan (core's `ShellModel.levelPlan`, ADR 0154 decision 2): its New and its tools, drawn whole;
    /// nil where the shell shows no level (an object, a person, a kept link, Search).
    var levelPlan: LevelPlan? {
        _ = shell
        _ = offlineBanner // observed: what is offered moves with the reach
        return shellModel?.levelPlan(shell: shell)
    }

    /// What the drawer's New offers (core's `ShellModel.drawerCreates`): made at the shown space's top, from anywhere.
    var drawerCreates: [AppKinds.Create] {
        _ = shell
        _ = offlineBanner
        return shellModel?.drawerCreates() ?? []
    }

    /// Whether the tenant's map services were built: the Settings page offers their consent (core's `ShellModel.mapsOffered`).
    var mapsOffered: Bool { _ = shell; return shellModel?.mapsOffered ?? false }

    /// A level row's menu (core's `ShellModel.rowPlan`); nil before a session.
    func rowPlan(_ row: NodeAt) async -> LevelRow.Plan? {
        guard let m = shellModel else { return nil }
        return try? await m.rowPlan(row: row)
    }

    /// The shell moved by `change` (its transitions are `Shell`'s), at once.
    private func moveShell(_ change: @escaping (Shell) -> Shell) {
        shellModel?.update(change: change)
        applyShell(start: false)
    }

    // MARK: What another app shared (ADR 0138)

    /// Files shared to veyq, waiting for the place they go, and how many did not read (core's `ShareInbox`).
    typealias Incoming = ShareInbox.Incoming

    /// The share's way from arrival to where its files went (core's `ShareInbox`): a file let go is a copy removed.
    let inbox = ShareInbox(release: { @Sendable source in
        if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
    })
    /// The files waiting (the pick mode is on); nil when none wait.
    private(set) var incoming: Incoming?
    /// How the share ends: true once its files were saved or handed to where they went, false when it was cancelled.
    var onShareDone: ((Bool) -> Void)?

    func syncInbox() { incoming = inbox.waiting.value as? Incoming }

    /// Files arrive: the pick mode starts at the space's Home, never in the object last open, so the person chooses where
    /// they go (ADR 0138 decision 1) — the start does the same while the session is still opening.
    func receiveShare(_ incoming: Incoming) {
        moveShell { Shell.companion.start(lastUsed: nil, space: $0.space) }
        inbox.receive(incoming: incoming)
        syncInbox()
    }

    /// Files dropped onto the app (ADR 0138 decision 9): an open object that takes them at once — its application says so
    /// (an open Pot opens its new expense with them) — gets them; anywhere else they wait in the pick mode, as a share's do
    /// (a Memory's gallery takes its own drop, `drop`).
    func receiveDrop(_ incoming: Incoming) {
        if let ref = shell.openObject, holdings.contains(where: { $0.takesDrop(ref, incoming) }) {
            inbox.receive(incoming: incoming)
            syncInbox()
        } else {
            receiveShare(incoming)
        }
    }

    func cancelShare() {
        inbox.cancel()
        syncInbox()
        onShareDone?(false)
    }

    /// Whether the pick mode lets `ref` be chosen for what waits: its kind's answer, and this member's right there.
    func takesShare(_ ref: ObjectRef) -> Bool {
        _ = incoming // observed: asked again as the waiting files change
        return shellModel?.takesShare(inbox: inbox, ref: ref.at) ?? false
    }

    /// Where the pick mode's banner offers *Add here* (ADR 0138 decision 3; core's `ShellModel.addHere`): the Memory's or
    /// chapter's page on screen, or the level shown — a folder or a space's top — where this member may put files; nil on
    /// any other page or where it takes nothing of what waits.
    var addHereTarget: AddHere? {
        guard incoming != nil, let m = shellModel else { return nil }
        _ = spaces // observed: a level's rights move with the lists
        // a page an application holds on screen answers first (a Memory's gallery), else the level shown
        return holdings.lazy.compactMap { $0.addHereTarget(m, inbox: self.inbox, at: self.shell) }.first
            ?? m.addHere(inbox: inbox, shell: shell, page: nil)
    }

    /// *Add here*: what `target` takes of the waiting files goes there — a gallery's photos and videos, a level's files into
    /// Depot's queue — and the share ends; the place's application answers it (`Holding.addHere`). In the app they are
    /// queued at once; the share extension hands them to the app through the app group and closes (ADR 0138 decision 6,
    /// owner 2026-11-19), the app queueing them the next time it has the space open (`pickUpHandoffs`).
    func addHere(_ target: AddHere) {
        let label = levelLabel
        _ = holdings.contains { $0.addHere(target, label: label) }
        syncInbox()
    }

    /// What an application's *Add here* took (`taken`; nil: nothing) and what it left out (`notice`): the share ends.
    func shareTaken(_ taken: ShareInbox.Taken?, notice: VeyqCore.Notice?) {
        syncInbox()
        guard taken != nil else { return }
        if let notice { show(notice) }
        onShareDone?(true)
    }

    /// Files and folders dropped onto `target` — the level shown, a folder's row, a Memory's or chapter's gallery — through
    /// core's one drop (`ShellModel.drop`, ADR 0154 decision 1; ADR 0138 decision 9): the core judges the target, sorts,
    /// uploads (or offers a download's import, a Mac's) and says; this draws its outcome. A level that takes no drop hands
    /// its files to the pick mode, as a share's (an open Pot takes its own, `receiveDrop`); a row's or a gallery's refusal
    /// is said.
    func drop(_ providers: [NSItemProvider], onto target: DropTarget) async {
        Self.dropLog("drop onto \(type(of: target)): \(providers.count) items")
        let items = await IncomingFiles.dropped(providers)
        Self.dropLog("drop: \(items.map { "\(type(of: $0))" })")
        let files = items.compactMap { ($0 as? Dropped.File)?.source }
        // the target's application answers (a gallery's Memories, a level's or a folder row's Depot); none, core's drop alone
        var answered: DropOutcome?
        if let m = shellModel {
            for h in holdings where answered == nil { answered = await h.drop(m, target, items) }
            if answered == nil {
                do {
                    answered = try await m.drop(target: target, items: items, depot: nil, left: { @Sendable source in
                        if let url = ApplePlatform.shared.urlOf(source: source) { AppModel.removePicked(url) }
                    }, deletedName: say(.potDeletedPerson))
                } catch {
                    Self.dropLog("core's drop threw \(type(of: error))")
                }
            }
        }
        // no outcome is a drop that says nothing (inbox #1039): the trace names it
        guard let outcome = answered else {
            Self.dropLog("drop: no outcome\(shellModel == nil ? " (no shell)" : "")")
            for s in files { if let url = ApplePlatform.shared.urlOf(source: s) { Self.removePicked(url) } }
            return
        }
        Self.dropLog("drop outcome \(type(of: outcome))")
        if let refused = outcome as? DropOutcome.Refused {
            Self.dropLog("drop refused")
            if target is DropTarget.Level, !files.isEmpty {
                receiveDrop(Incoming(files: files, refused: Int32(items.filter { $0 is Dropped.Unread }.count)))
                return
            }
            for s in files { if let url = ApplePlatform.shared.urlOf(source: s) { Self.removePicked(url) } }
            show(refused.notice)
            return
        }
        if let offered = outcome as? DropOutcome.Offered {
            #if os(macOS)
            offerImport(offered.offer)
            #else
            offered.offer.close()
            #endif
        }
        tell(outcome.notices)
    }

    /// The core's notices of one outcome, said in order (each on its own line), as they live (`show`).
    func tell(_ notices: [VeyqCore.Notice]) { show(notices) }

    /// The drop target a folder's row of the level shown is, where the core offers one (`ShellModel.dropTarget`: a folder
    /// this member may put files in — never a row by its kind, never a guest's identity folder); nil elsewhere.
    func dropTarget(_ node: NodeRef) -> DropTarget.Row? {
        _ = spaces // observed: a level's rights move with the lists
        guard let m = shellModel else { return nil }
        let at = NodeAt(scopeId: node.scopeId, nodeId: node.nodeId, kind: node.kind, name: node.name)
        return m.dropTarget(row: at) ? DropTarget.Row(folder: at) : nil
    }

    /// A drop's path in a debug build's unified log (`log stream --level debug --predicate 'subsystem == "app.veyq" AND
    /// category == "drop"'`): the types each item registered and the branch it took — no name, no content.
    nonisolated static func dropLog(_ line: String) {
        #if DEBUG
        Logger(subsystem: "app.veyq", category: "drop").debug("\(line, privacy: .public)")
        #endif
    }

    /// What the share extension handed over for a space this tenant has open: queued where its application keeps it — a
    /// Memory or chapter (Memories' queue), a folder or a top (Depot's) —, each copy removed once the queue lets it go (ADR
    /// 0138 decision 6); one no application takes is let go. Asked once the tenant's models were built.
    func pickUpHandoffs() {
        guard !inExtension, shellModel != nil, !spaces.isEmpty else { return }
        for waiting in ShareHandoff.pending(in: Set(spaces.map(\.scopeId))) {
            let sources = waiting.sources
            if !holdings.contains(where: { $0.pickUp(waiting.target, sources) }) {
                for s in sources { if let url = ApplePlatform.shared.urlOf(source: s) { AppModel.removePicked(url) } }
            }
        }
    }

    /// The new expense's editor closed unsaved: the files wait again, for this Pot or another (from Home).
    func returnShare() {
        if inbox.giveBack() != nil { moveShell { Shell.companion.start(lastUsed: nil, space: $0.space) } }
        syncInbox()
    }

    /// The new expense the shared files went into was saved: the share is done.
    func shareSaved() {
        inbox.saved()
        onShareDone?(true)
    }

    // MARK: Core's actions on a node

    /// The headers handed out, one per node and name (core's `NodeHeaderModel`), each watched: a view's body asks again on
    /// each update.
    @ObservationIgnored private var headers: [String: (model: NodeHeaderModel, watch: NodeHeaderModel.Watch)] = [:]

    /// Moves on with every change of a header's state: what a header's view reads to be drawn anew.
    private(set) var headerRevision = 0

    private func dropHeaders() {
        headers.values.forEach { $0.watch.stop() }
        headers = [:]
    }

    /// The header of `node` (core's `NodeHeaderModel` over the shell), its kind's page knowing `facts`: an open Pot of the
    /// node closes once it went to the trash, and each write reads the lists and the rows' details anew.
    func header(_ node: NodeAt, facts: AppKinds.Facts?) -> NodeHeaderModel? {
        guard let m = shellModel else { return nil }
        let key = "\(node.scopeId)/\(node.nodeId)/\(node.name)"
        if let held = headers[key] { return held.model }
        let made = m.header(node: node, facts: facts, closeOpen: { @Sendable ref in
            let (scopeId, id, kind, name) = (ref.scopeId, ref.nodeId, ref.kind, ref.name)
            Task { @MainActor [weak self] in
                guard let self else { return }
                let trashed = NodeAt(scopeId: scopeId, nodeId: id, kind: kind, name: name)
                self.eachHolding { $0.trashed(trashed) }
            }
        }, changed: { @Sendable in
            Task { @MainActor [weak self] in
                guard let self, let t = self.tenantSession else { return }
                self.applyShell(start: false)
                await self.readDetails(t)
            }
        })
        let watch = made.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.headerRevision += 1 }
        }
        headers[key] = (made, watch)
        return made
    }

    /// A pull to refresh on a list or a page (#953): the lists read anew with each row's facts, and the members (a picture
    /// replaced since among them, core's).
    func refreshPulled() async {
        guard let t = tenantSession else { return }
        await refreshLists(t, people: true)
    }

    func refreshLists(_ t: TenantSession, people: Bool = false) async {
        try? await shellModel?.refresh(people: people)
        applyShell(start: false)
        await readDetails(t)
    }

    /// What the shell says at its bottom (ADR 0154 decision 4): a success, closing itself after the core's `lifeMs`
    /// (`NoticeKind.SAID`), or a trash's or a move's Undo while core offers it (`NoticeKind.UNDO`). A refusal or a failure
    /// (`NoticeKind.HELD`) is never here: it is the shell's alert (`notice`), which stays until it is closed.
    struct TrashNotice: Identifiable {
        let id = UUID()
        let message: String
        /// How long it shows before it closes itself (the core's `NoticeKind.lifeMs`); nil while core's offer holds it.
        var lifeMs: Int64? = VeyqCore.Notice.companion.SAID_MS
        /// The header whose trash this is, while core offers its undo (core ends the offer after its window).
        var header: NodeHeaderModel? = nil
        /// The move sheet whose undo this is, while core offers it (core ends the offer after its window, as a trash's).
        var move: MoveSheet? = nil
        /// A level selection's delete whose undo this is, while core offers it.
        var selection: SelectionTrash? = nil
        /// Whether it offers an Undo.
        var undoable: Bool { header != nil || move != nil || selection != nil }
    }

    // MARK: A level's selection (core's LevelSelection over the shell, inbox #1049)

    /// Select on (the level's tool): its Select mode, nothing selected yet.
    func selectStart() { Task { try? await shellModel?.selectStart() } }
    /// Select off (*Cancel*, or the tool again): the selection cleared.
    func selectCancel() { shellModel?.selectCancel() }
    func selectToggle(_ nodeId: String) { shellModel?.selectToggle(id: nodeId) }
    func selectAll() { shellModel?.selectAll() }
    func selectNone() { shellModel?.selectNone() }

    /// The selection's *Move to…* or *Copy to…*: one sheet over every selected row it reaches; nil where it reaches none.
    func selectionMoveSheet(copying: Bool) -> MoveSheet? {
        guard let m = shellModel else { return nil }
        return m.selectionMoveSheet(copying: copying, deletedName: say(.potDeletedPerson)) { [weak self] in
            Task { @MainActor in
                guard let self, let t = self.tenantSession else { return }
                await self.readDetails(t)
            }
        }
    }

    /// The first selected row `action` reaches (what a sheet over the selection is anchored at); nil where it reaches none.
    func selectedFirst(_ action: SelectionAction) -> NodeAt? { shellModel?.selected(action: action).first }

    /// The selection's *Report*: one report of every selected row; nil where it reaches none.
    func selectionReportSheet() -> VeyqCore.ReportSheet? { shellModel?.selectionReportSheet() }

    /// The selection's *Delete*: its one question over every selected row it reaches; nil where it reaches none.
    func selectionTrash() -> SelectionTrash? { shellModel?.selectionTrash {} }

    /// Runs a selection's delete; the bottom of the shell says what came of it, with its one Undo while core offers it.
    func trash(_ delete: SelectionTrash) async {
        guard let said = try? await delete.trash() else { return }
        if let t = tenantSession { await readDetails(t) }
        undoWatch?()
        guard delete.current.undo != nil else {
            show(said)
            return
        }
        let notice = TrashNotice(message: say(notice: said), lifeMs: nil, selection: delete)
        trashNotice = notice
        let id = notice.id
        let watch = delete.watch { @Sendable state in
            guard state.undo == nil else { return }
            Task { @MainActor [weak self] in self?.dismissTrashNotice(id) }
        }
        undoWatch = { watch.stop() }
    }

    private(set) var trashNotice: TrashNotice?
    /// Stops the watch on the undo the notice offers (a header's trash, a move sheet's move).
    @ObservationIgnored private var undoWatch: (() -> Void)?

    /// Moves `header`'s node with everything under it to the space's trash (core's: a page whose node went leaves for its
    /// parent, an open Pot of it closes); the bottom of the shell says so, with its Undo for as long as core offers it.
    func trash(_ header: NodeHeaderModel) async {
        guard let said = try? await header.trash() else { return }
        undoWatch?()
        guard header.current.undo != nil else {
            show(said)
            return
        }
        let notice = TrashNotice(message: say(notice: said), lifeMs: nil, header: header)
        trashNotice = notice
        let id = notice.id
        let watch = header.watch { @Sendable state in
            guard state.undo == nil else { return }
            Task { @MainActor [weak self] in self?.dismissTrashNotice(id) }
        }
        undoWatch = { watch.stop() }
    }

    /// The Undo of the notice at the bottom: a move moved back, or the last delete's node back from the trash under its name.
    func undoTrash() async {
        if let delete = trashNotice?.selection {
            trashNotice = nil
            undoWatch?()
            if let failed = try? await delete.undo() { show(failed) }
            return
        }
        if let move = trashNotice?.move {
            trashNotice = nil
            undoWatch?()
            if let failed = try? await move.undo() { show(failed) }
            return
        }
        guard let header = trashNotice?.header else { return }
        trashNotice = nil
        undoWatch?()
        if let failed = try? await header.undoTrash() { show(failed) }
    }

    /// What a move or copy said once `sheet` is done, at the bottom of the shell: a move's Undo for as long as core offers it
    /// (the header CR's D4: a trash's window).
    func moved(_ sheet: MoveSheet) {
        guard let done = sheet.current.done else { return }
        undoWatch?()
        guard sheet.current.undo != nil else {
            show(done)
            return
        }
        let notice = TrashNotice(message: say(notice: done), lifeMs: nil, move: sheet)
        trashNotice = notice
        let id = notice.id
        let watch = sheet.watch { @Sendable state in
            guard state.undo == nil else { return }
            Task { @MainActor [weak self] in self?.dismissTrashNotice(id) }
        }
        undoWatch = { watch.stop() }
    }

    func dismissTrashNotice(_ id: UUID) { if trashNotice?.id == id { trashNotice = nil } }

    /// A notice the core raised, shown as its kind lives (ADR 0154 decision 4, the web's `useToast`): a refusal or a
    /// failure as the shell's alert until it is closed, anything else at the bottom, closing itself after the core's
    /// `lifeMs` — an Undo raised without an offer to watch lives as a success does.
    func show(_ said: VeyqCore.Notice) { show([said]) }

    /// Several notices of one outcome, said together (each on its own line): held where any is.
    func show(_ said: [VeyqCore.Notice]) {
        guard !said.isEmpty else { return }
        let text = said.map { say(notice: $0) }.joined(separator: "\n")
        if said.contains(where: { $0.kind == .held }) {
            notice = Notice(text: text)
        } else {
            undoWatch?()
            trashNotice = TrashNotice(message: text, lifeMs: said.compactMap { $0.kind.lifeMs?.int64Value }.max() ?? VeyqCore.Notice.companion.SAID_MS)
        }
    }

    // MARK: A report (core's ReportSheet)

    /// The report of `node` to the tenant's moderators (core's `ReportSheet`: the form's rules, the send, the privacy URL).
    func reportSheet(_ node: NodeAt) -> VeyqCore.ReportSheet? { shellModel?.reportSheet(node: node) }

    // MARK: Guest links (core's ShareLinks)

    /// The tenant's link rules (its capability set): what the share dialog offers and requires.
    /// Whether the shell stands where the space's top level offers its share, "Add files only" (core's `offersTopLevelShare`).
    func offersTopLevelShare() -> Bool { shellModel?.offersTopLevelShare(shell: shell) ?? false }

    func linkRules() -> ShareLinks.Rules { tenantSession?.linkRules ?? ShareLinks.RulesCompanion.shared.UNKNOWN }

    /// The share sheet of `node` (core's `ShareSheet` over the shell: its links, the form, mint, edit, renew, switch off and
    /// on, delete, a folder's "Add files only"; a link's close tells the tenant, whose open Pot reads again); the lists and
    /// the rows' details are read anew after a drop box was made.
    func shareSheet(_ node: NodeAt) -> VeyqCore.ShareSheet? {
        guard let m = shellModel else { return nil }
        return m.shareSheet(node: node) { @Sendable in
            Task { @MainActor [weak self] in
                guard let self, let t = self.tenantSession else { return }
                self.applyShell(start: false)
                await self.readDetails(t)
            }
        }
    }

    /// The share of space `scopeId`'s top level (core's `ShareSheet.topLevel`: "Add files only" alone, a drop box at the top).
    func topLevelShareSheet(_ scopeId: String) -> VeyqCore.ShareSheet? {
        guard let m = shellModel else { return nil }
        return m.topLevelShareSheet(scopeId: scopeId) { @Sendable in
            Task { @MainActor [weak self] in
                guard let self, let t = self.tenantSession else { return }
                self.applyShell(start: false)
                await self.readDetails(t)
            }
        }
    }

    /// The Memory's music interrupted while a banner's video plays (ADR 0142 decision 13); nil where no player is open.
    func interruptMusic() -> MusicPlayer.Interruption? { musicPlayer?.interrupt() }

    // MARK: A person's profile (ADR 0144 decisions 11–13)

    /// Person `userId`'s profile opened over what is on screen (core's `Shell.openPerson`; back returns there).
    func openPerson(_ userId: String) {
        shellModel?.openPerson(userId: userId)
        applyShell(start: false)
    }

    /// Kept link [node]'s page opened over what is on screen (core's `ShellModel.openBookmark`, #1032).
    func openBookmark(_ node: NodeAt) {
        shellModel?.openBookmark(node: node)
        applyShell(start: false)
    }

    /// Person `userId`'s frame as the tenant's directory names them; nil while no tenant is open.
    func profile(_ userId: String) -> PersonProfile? { shellModel?.profile(userId: userId) }

    // MARK: The gallery's filter, the contributions and the view mode (ADR 0149 decisions 5, 6 and 8)

    /// Moves with each view choice, so every level and a person's Memories draw the account's one choice anew.
    private(set) var viewRevision = 0

    /// How a level is drawn: the account's choice on this device (core's `ViewModes`), Tiles before any (`ViewMode.FIRST`).
    var levelView: ViewMode {
        _ = viewRevision
        return shellModel?.levelView ?? ViewMode.companion.FIRST
    }

    /// `mode` chosen for every level and a person's Memories (one choice per account on this device).
    func chooseView(_ mode: ViewMode) {
        shellModel?.chooseView(mode: mode)
        viewRevision += 1
    }

    /// A person's Memories chose their view through their own model (the same store): the levels follow.
    func viewChosen() { viewRevision += 1 }

    // MARK: Map services (ADR 0143)

    /// The open tenant's map services on this device (core's `MapServices`, its map's recipient Apple Maps: MapKit draws
    /// it, the operator's geocoder names places), built beside the uploads and closed with them — a sign-out, a switch.
    @ObservationIgnored private(set) var maps: MapServices?
    /// What a map's place shows now (core's `MapState`): MapKit is mounted only on `Shown` — off means off, on Apple too.
    private(set) var mapState: MapState = MapStateNotOffered.shared
    /// Moves on every consent change: a view that shows the hosts the consent was given for reads them anew.
    private(set) var mapsRevision = 0

    private func openMaps(_ m: ShellModel) {
        maps?.close()
        // the geocoder's `accept-language` is the app's language, read here: the closure runs on core's threads
        let lang = Bundle.main.preferredLocalizations.first ?? "en"
        let made = m.mapServices(recipient: .appleMaps, lang: { @Sendable in lang })
        maps = made
        syncMaps()
    }

    private func closeMaps() {
        maps?.close()
        maps = nil
        syncMaps()
    }

    /// The consent changes only through this app's calls below, so the state is read after each (and at an open).
    private func syncMaps() {
        mapState = (maps?.state.value as? MapState) ?? MapStateNotOffered.shared
        mapsRevision += 1
    }

    /// What a map's place shows: offline no map, whatever the services were built under (ADR 0143 D9).
    var mapShown: MapState { online ? mapState : MapStateOffline.shared }

    /// *Turn map services on* (the prompt in context, the settings' switch): this device's consent for the hosts served now.
    func mapsOn() {
        maps?.turnOn()
        syncMaps()
    }

    /// *Turn map services off* (on every map, the settings' switch): withdrawn at once, every map unmounted.
    func mapsOff() {
        maps?.turnOff()
        syncMaps()
    }

    // MARK: Music (ADR 0142)

    /// The account's one music player (core's `MusicPlayer` over two `AVDeck`s and the system's controls), built when a
    /// tenant opens and stopped where its models close: a sign-out, a switch to another tenant. Its queue plays on while the
    /// person moves between pages and, on iOS, in the background.
    @ObservationIgnored private var musicPlayer: MusicPlayer?
    @ObservationIgnored private var musicPlayerWatch: MusicPlayer.Watch?
    /// What plays now, for the bar and the lightbox's compact controls; nil while no queue is loaded.
    private(set) var nowPlaying: NowPlaying?
    /// The tracks that did not play on this device this session (the Music view marks them).
    private(set) var musicSkipped: Set<String> = []
    /// "None of these tracks can be played on this device", for 5 s after a queue ended so.
    private(set) var musicNothingPlays = false
    @ObservationIgnored private var musicNotice: PlayerNotice?

    /// The player's commands (the bar's previous, play, pause, next, seek), nil while no tenant is open.
    var musicCommands: (any PlayerCommands)? { musicPlayer }

    /// What a gallery plays its music with: the account's player, an untitled track named by its number.
    var galleryMusic: PageMusic? {
        musicPlayer.map { PageMusic(player: $0, untitled: { @Sendable n in say(.musicUntitled, n) }) }
    }

    private func openMusic(_ m: ShellModel, switched: Bool) {
        if musicPlayer != nil, !switched { return }
        closeMusic()
        holdMusic(m.musicPlayer(newDeck: { AVDeck.make($0) }, controls: SystemMusicControls()))
    }

    /// What a guest's Memory plays its music with (#1034): the account's player, or — signed out — one made for the links,
    /// which the music bar draws as it draws the account's.
    func guestMusic() -> PageMusic? {
        if musicPlayer == nil { holdMusic(AppleSharing.shared.musicPlayer(newDeck: { AVDeck.make($0) }, controls: SystemMusicControls())) }
        return galleryMusic
    }

    private func holdMusic(_ player: MusicPlayer) {
        musicPlayer = player
        let id = ObjectIdentifier(player)
        musicPlayerWatch = player.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in self?.musicChanged(state, of: id) }
        }
    }

    private func musicChanged(_ state: MusicPlayer.State, of player: ObjectIdentifier) {
        guard let current = musicPlayer, ObjectIdentifier(current) == player else { return }
        nowPlaying = state.nowPlaying
        musicSkipped = state.skipped
        if state.notice != musicNotice {
            musicNotice = state.notice
            if state.notice == .unplayable {
                musicNothingPlays = true
                Task { @MainActor [weak self] in
                    try? await waitCore(VeyqCore.Notice.companion.NOTHING_PLAYS_MS)
                    self?.musicNothingPlays = false
                }
            }
        }
    }

    /// The bar's close: the queue stops.
    func stopMusic() { musicPlayer?.stop() }

    private func closeMusic() {
        musicPlayerWatch?.stop()
        musicPlayerWatch = nil
        musicPlayer?.stop()
        musicPlayer = nil
        nowPlaying = nil
        musicSkipped = []
        musicNothingPlays = false
        musicNotice = nil
    }

    /// The bar's node name: the Memory or chapter whose music plays opens.
    func openMusicNode() {
        if let route = nowPlaying?.route { open(route) }
    }

    // MARK: The move and copy sheet (core's MoveSheet)

    /// The move or copy sheet of `node` (core's `MoveSheet` over the shell: its browser, the guest folder's confirm, the
    /// kind's copy — a Pot by the open Pot —, the storage check, the move's undo); each write reads the lists anew.
    func moveSheet(_ node: NodeAt, copying: Bool) -> MoveSheet? {
        guard let m = shellModel else { return nil }
        let changed: @Sendable () -> Void = {
            Task { @MainActor [weak self] in
                guard let self, let t = self.tenantSession else { return }
                self.applyShell(start: false)
                await self.readDetails(t)
            }
        }
        // the kind whose copy an application makes answers (a Pot's, by the open Pot); every other the composition's
        return holdings.lazy.compactMap { $0.moveSheet(m, node, copying: copying, changed: changed) }.first
            ?? m.moveSheet(node: node, copying: copying, open: nil, deletedName: say(.potDeletedPerson)) { changed() }
    }

    // MARK: The space's trash (core's NodeTrash)

    /// The trash sheet of `scopeId` (core's `TrashSheet`); the lists are read anew after each write that went through.
    func trashSheet(_ scopeId: String) -> TrashSheet? {
        guard let m = shellModel else { return nil }
        return m.trashSheet(scopeId: scopeId) { @Sendable in
            Task { @MainActor [weak self] in
                guard let self, let t = self.tenantSession else { return }
                self.applyShell(start: false)
                await self.readDetails(t)
            }
        }
    }

    // MARK: The account's flows (ADR 0148 decision 10)

    /// The signed-in member's user id on the tenant shown: whose person page is one's own.
    var myUserId: String? { session.signedTo?.account.userId }

    /// One's own profile on the tenant shown (core's `ProfileModel`), made anew for each sheet; the sheet closes it.
    func ownProfile() -> ProfileModel? { tenantSession.map { AppleAccount.shared.profile(tenant: $0) } }

    /// The tenant's safety numbers and their compare (core's `PersonTrust`).
    var personTrust: PersonTrust? { tenantSession?.personTrust }

    /// Settings' Who's who (core's `PersonTrust.whosWho`, inbox #967): every other member of the open space, read anew; nil
    /// while no tenant is open.
    func whosWho() async -> WhosWho? { try? await personTrust?.whosWho() }

    /// A Who's who row: Settings closes and the person's profile opens over the shell, where the compare lives.
    func openPersonFromWhosWho(_ userId: String) {
        closeSettings()
        openPerson(userId)
    }

    func passwordChangeFlow() -> PasswordChange? { session.signedTo?.account.passwordChange() }
    func addressChangeFlow() -> AddressChange? {
        guard let s = session.signedTo else { return nil }
        return AppleAccount.shared.addressChange(account: s.account, tenant: s.tenant)
    }
    func keyRotationFlow() -> KeyRotation? {
        guard let account = session.signedTo?.account else { return nil }
        return account.keyRotation(open: tenantSession.flatMap { $0.account === account ? $0 : nil })
    }
    func erasureFlow() -> AccountErasure? { session.signedTo?.account.erasure() }
    func kitRenewalFlow() -> KitRenewal? { session.signedTo?.account.kitRenewal() }

    /// The open Settings page read anew (a flow that changed what it shows ended).
    func refreshSettings() {
        guard let m = settingsModel else { return }
        Task { try? await m.refresh() }
    }

    /// After a save of one's profile: the shell's people read again, so every name follows (core read them, and the pictures).
    func profileSaved() {
        guard let t = tenantSession else { return }
        Task { await refreshLists(t, people: true) }
    }

    /// After a password change: this device's saved key is gone (core forgot it), so the open Settings page reads anew —
    /// its trust row offers the password trust again.
    func passwordChanged() {
        guard let m = settingsModel else { return }
        Task { try? await m.refresh() }
    }

    /// After a rotation: the account holds the new keys, so the tenant shown is opened anew (its keys were the old identity's).
    func keysReplaced() {
        guard let s = session.signedTo else { return }
        Task { if let opened = try? await session.switchTo(account: s.account, tenant: s.tenant) { try? await show(opened) } }
    }

    /// After the erasure: core forgot the account on this device; it leaves the session as a sign-out does.
    func accountErased() {
        guard let account = session.signedTo?.account else { return }
        closeSettings()
        signOut(account)
    }

    // MARK: An open Pot (Expenses)

    /// The tenant the shell shows, opened: where an object's own view reads from.
    private(set) var tenantSession: TenantSession?
    /// What each object's row says beside its name, by node id (an application's answer: Expenses' `PotRows`).
    private(set) var rowDetails: [String: [String]] = [:]
    /// Counts each read of the lists: a page of what lies below its node reads again on every one (as Android's does),
    /// not only when the number of rows moved.
    private(set) var listsRevision = 0

    /// What the rows read last (core's `RowDetails`): a part that does not read next time keeps these.
    @ObservationIgnored private var rows = RowDetails.ReadCompanion.shared.EMPTY

    /// The rows' facts and the lists' covers, read anew (core's `RowDetails`: Pots, files, drop boxes, cover keys).
    func readDetails(_ t: TenantSession, reset: Bool = false) async {
        if reset { rows = RowDetails.ReadCompanion.shared.EMPTY }
        guard let read = try? await RowDetails.shared.read(tenant: t, previous: rows), tenantSession === t else { return }
        rows = read
        // a page on screen reads itself anew (a replaced file's blob is shown)
        eachHolding { $0.listsRead() }
        var details = read.details.mapValues { $0.map { say(piece: $0) } }
        // the last line an application's rows say (a Memory's date, ADR 0143 D5), where the core's details say none
        if let m = shellModel {
            var lines: [String: String] = [:]
            for h in holdings { lines.merge(await h.rowLines(m)) { a, _ in a } }
            if tenantSession === t {
                for (nodeId, line) in lines where details[nodeId] == nil { details[nodeId] = [line] }
            }
        }
        rowDetails = details
        listsRevision += 1
        await readCovers(t, named: read.covers)
    }

    /// Something the shell asks or says once (an upload's refusal, its "Replace anyway"), with its action where it offers one.
    struct Notice: Identifiable {
        let id = UUID()
        let text: String
        var action: String?
        var run: (() async -> Void)?
    }

    var notice: Notice?

    /// Where copies sent to another app wait to be read: cleared before the next one, when the sheet is done and at start.
    static var sharedDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("shared", isDirectory: true) }

    /// Where the Photos picker's copies wait for their upload (`PickedMedia`, one directory each): the person's own originals,
    /// never a decrypted veyq copy; each removed once the queue is done with it, all of them when the tenant closes and at
    /// start.
    static var pickedDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("picked", isDirectory: true) }

    /// Removes the copy at `url` with its own directory, where it is one of `pickedDir`'s; any other file is left alone.
    nonisolated static func removePicked(_ url: URL) {
        ShareHandoff.remove(url) // a copy the share extension handed over (ADR 0138 decision 6)
        let dir = url.deletingLastPathComponent().standardizedFileURL.resolvingSymlinksInPath()
        let picked = FileManager.default.temporaryDirectory.appendingPathComponent("picked", isDirectory: true).standardizedFileURL.resolvingSymlinksInPath()
        guard dir.deletingLastPathComponent().path == picked.path else { return }
        try? FileManager.default.removeItem(at: dir)
    }

    /// Where a download waits for the system's save sheet (iOS's `fileMover`): removed once the sheet is done and at start.
    static var downloadsDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("downloads", isDirectory: true) }

    /// A new directory of its own under `downloadsDir` for one download; nil where it could not be made.
    static func downloadDir() -> URL? {
        let dir = downloadsDir.appendingPathComponent(UUID().uuidString, isDirectory: true)
        return (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil ? dir : nil
    }

    /// Every decrypted copy the app wrote for the person — a copy sent, a download for the save sheet — and the Photos
    /// picker's copies, swept at start: what a crash or a sheet left behind is not kept (ADR 0047: no plaintext at rest but
    /// where the person saves it).
    private static func sweepTemporary() {
        try? FileManager.default.removeItem(at: sharedDir)
        try? FileManager.default.removeItem(at: downloadsDir)
        // the Photos picker's copies a crash left: not decrypted, but no upload reads them any more
        try? FileManager.default.removeItem(at: pickedDir)
        // a share's handoff the extension never finished, or one queued in a process that ended (ADR 0138 decision 6)
        ShareHandoff.sweep()
        // what builds before this rule left there: a viewed file's decrypted copy (`view-…`), a download's own directory
        let tmp = FileManager.default.temporaryDirectory
        for name in (try? FileManager.default.contentsOfDirectory(atPath: tmp.path)) ?? []
        where name.hasPrefix("view-") || UUID(uuidString: name) != nil {
            try? FileManager.default.removeItem(at: tmp.appendingPathComponent(name))
        }
    }

    func clearSentCopies() {
        try? FileManager.default.removeItem(at: Self.sharedDir)
    }

    /// The app's one upload queue (core's `UploadQueue`, the web's `core/blob/queue.ts`): every application's uploads, one
    /// after another, retried once; the shell's uploads button shows it (`UploadsButton`). It outlives a screen, not the
    /// account: a sign-out or a switch of tenant empties it.
    let uploads = UploadQueue()

    /// The tray over `uploads` alone (core's `UploadsTray`): the sign-in's and the unlock's, where a link guest's uploads
    /// left running signed out show (inbox #1250). The process's, as the queue is.
    @ObservationIgnored private(set) lazy var signedOutTray = SignedOutTray(queue: uploads)

    /// A picked file as an upload lists it: its name, size, type, modified time (read inside its security scope).
    struct Picked {
        let name: String
        let size: Int64
        let type: String
        let modifiedAt: Int64?

        init?(_ url: URL) {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .contentTypeKey, .nameKey]) else { return nil }
            name = values.name ?? url.lastPathComponent
            size = Int64(values.fileSize ?? 0)
            type = values.contentType?.preferredMIMEType ?? "application/octet-stream"
            modifiedAt = values.contentModificationDate.map { Int64($0.timeIntervalSince1970 * 1000) }
        }
    }

    /// The level shown, by name: where an upload lands.
    var levelLabel: String {
        shell.folder?.name ?? Names.spaceName(spaces.first { $0.scopeId == shell.space })
    }

    /// The level shown as a drop's target (core's `DropTarget.Level`), named as its title draws it.
    var levelDropTarget: DropTarget { DropTarget.Level(label: levelLabel) }

    #if os(macOS)
    /// A download's import offered by an upload's screen (core's `ImportDialog`), its sheet shown while it is open
    /// (`ImportSheet`); a second offer while one is open is closed at once (one import at a time, as the web's dialog).
    struct ImportOffer: Identifiable {
        let dialog: ImportDialog
        var id: ObjectIdentifier { ObjectIdentifier(dialog) }
    }

    var importing: ImportOffer?

    /// What a finished import says once its sheet is gone (the imported line, the web's toast).
    @ObservationIgnored var importedLine: [VeyqCore.Notice] = []

    func offerImport(_ dialog: ImportDialog?) {
        guard let dialog else { return }
        if importing != nil {
            dialog.close()
            return
        }
        importing = ImportOffer(dialog: dialog)
    }
    #endif

    /// An import offered and not answered goes with the tenant it was offered over (a switch, a sign-out).
    private func closeImport() {
        #if os(macOS)
        importing?.dialog.close()
        importing = nil
        #endif
    }

    /// The entries below the directory at `url`, each by its path below it and what it is (hidden ones included, as the
    /// web's directory input holds them; a link reported, never followed; one the enumerator could not read reported); a
    /// directory enumerator is not iterated in an async context, so this is its own synchronous walk.
    nonisolated static func folderFiles(_ url: URL) -> [ApplePlatform.Entry] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .isReadableKey, .fileSizeKey, .contentModificationDateKey, .contentTypeKey]
        var files: [ApplePlatform.Entry] = []
        let base = url.standardizedFileURL.path
        func relative(_ file: URL) -> String {
            let full = file.standardizedFileURL.path
            return full.hasPrefix(base + "/") ? String(full.dropFirst(base.count + 1)) : file.lastPathComponent
        }
        func entry(_ file: URL, _ kind: ApplePlatform.EntryKind, _ v: URLResourceValues? = nil) -> ApplePlatform.Entry {
            ApplePlatform.Entry(
                relativePath: relative(file), path: file.standardizedFileURL.path, kind: kind, size: Int64(v?.fileSize ?? 0),
                type: v?.contentType?.preferredMIMEType ?? "application/octet-stream",
                modifiedAt: v?.contentModificationDate.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) }
            )
        }
        var unreadable: [ApplePlatform.Entry] = []
        let walk = FileManager.default.enumerator(at: url, includingPropertiesForKeys: keys) { failed, _ in
            unreadable.append(entry(failed, .unreadable))
            return true
        }
        while let file = walk?.nextObject() as? URL {
            guard let v = try? file.resourceValues(forKeys: Set(keys)) else { files.append(entry(file, .unreadable)); continue }
            if v.isSymbolicLink == true { files.append(entry(file, .link)) }
            else if v.isDirectory == true { files.append(entry(file, .directory)) }
            else if v.isRegularFile == true { files.append(entry(file, v.isReadable == false ? .unreadable : .file, v)) }
            else { files.append(entry(file, .other)) }
        }
        return files + unreadable
    }

    /// Why `item` did not go up, in its application's words (the uploads list's line under it); nil while it may still.
    func uploadProblem(_ item: UploadQueue.Item) -> String? {
        Notices.shared.uploadProblem(item: item).map { say(notice: $0) }
    }

    /// Each listed node's cover as a small picture of its framed part, by node id, with the key it was opened under — its
    /// blob and crop (core's covers, uc.platform.node-cover): opened once per blob and crop, dropped when the node names
    /// another or none.
    private(set) var covers: [String: (blobId: String, image: CGImage)] = [:]

    private func readCovers(_ t: TenantSession, named: [String: String]) async {
        let changes = NodeCover.shared.changes(named: named, held: covers.mapValues { $0.blobId })
        var next = covers.filter { changes.keep.contains($0.key) }
        for (nodeId, key) in changes.open {
            if let opened = (try? await AppleSessions.shared.openFramedCover(tenant: t, nodeId: nodeId)) ?? nil,
               let whole = CoverImages.image(opened.data as Data, maxPixels: Int(NodeCover.shared.THUMBNAIL_EDGE)),
               let image = Self.framed(whole, opened.crop) {
                next[nodeId] = (key, image)
            }
        }
        if tenantSession === t { covers = next }
    }

    /// The part of `image` a cover's `crop` shows (core's `CoverFrame.pixels`, the web's `coverImageStyle`).
    private static func framed(_ image: CGImage, _ crop: NodeCover.Crop) -> CGImage? {
        let r = CoverFrame.companion.pixels(crop: crop, width: Int32(image.width), height: Int32(image.height))
        return image.cropping(to: CGRect(x: Int(r.get(index: 0)), y: Int(r.get(index: 1)), width: Int(r.get(index: 2)), height: Int(r.get(index: 3))))
    }

    /// Sets the picture `data` as the cover of `ref`, framed by `crop`: made the web's cover JPEG on the device (core's
    /// `NodeCover.LONG_EDGE` and `QUALITY`, the web's `COVER_SPEC`) — the whole picture, the crop stored beside it as the web stores it —, uploaded and
    /// attached (core's `NodeCover`); false when it did not land.
    func setCover(_ ref: NodeAt, data: Data, crop: NodeCover.Crop = NodeCover.CropCompanion.shared.FULL) async -> Bool {
        guard let t = tenantSession, let jpeg = CoverImages.jpeg(data, maxPixels: Int(NodeCover.shared.LONG_EDGE), quality: NodeCover.shared.QUALITY) else { return false }
        let outcome = try? await AppleSessions.shared.setCover(tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, jpeg: jpeg, crop: crop)
        await refreshLists(t)
        return outcome == .done
    }

    /// `ref`'s cover as it is stored — the whole picture and its crop — for the cover sheet to frame anew; nil: none.
    func coverSource(_ ref: NodeAt) async -> (image: CGImage, crop: NodeCover.Crop)? {
        guard let t = tenantSession, let opened = (try? await AppleSessions.shared.openFramedCover(tenant: t, nodeId: ref.nodeId)) ?? nil,
              let image = CoverImages.image(opened.data as Data, maxPixels: Int(NodeCover.shared.LONG_EDGE)) else { return nil }
        return (image, opened.crop)
    }

    /// `ref`'s cover as its page's head shows it: the framed part of the stored picture, at up to 2048 px; nil: none.
    func coverHero(_ ref: NodeAt) async -> CGImage? {
        guard let source = await coverSource(ref) else { return nil }
        return Self.framed(source.image, source.crop)
    }

    /// Moves the crop of `ref`'s cover, its picture kept (the web's `keep`); false when it did not land.
    func recropCover(_ ref: NodeAt, crop: NodeCover.Crop) async -> Bool {
        guard let t = tenantSession else { return false }
        let outcome = try? await NodeCover.shared.recrop(tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, crop: crop)
        await refreshLists(t)
        return outcome == .done
    }

    /// Creates a folder named `name` where the shell is (core's `createFolderHere`, the web's `FolderNameDialog`); the lists
    /// are read anew. Core's typed answer (`NameAnswer`) drawn by `asked`: its problem keeps the dialog open, its notice is
    /// said once it closed, queued offline it closes (the folder shows where it was made).
    func createFolder(name: String) async -> String? {
        guard let m = shellModel else { return say(.folderFailed) }
        let answer = try? await m.createFolderHere(name: name)
        applyShell(start: false)
        await readDetails(m.tenant)
        return asked(answer)
    }

    /// A name dialog's save as core answered it (`NameAnswer`, ADR 0154 decision 1): the problem's words while it stays open
    /// (nil: it closes), its notice said here once it closed. A save that answered nothing (its task cancelled) is never
    /// taken as made: the dialog stays open saying it was not saved.
    func asked(_ answer: NameAnswer?) -> String? {
        nameAnswered(answer) { show($0) }
    }

    /// What the drawer's "New" asked Home to create, until Home opens its dialog.
    var newRequest: AppKinds.Create?
    /// The space whose trash is open (a level's tool, `LevelTools`), while non-nil.
    var trashShown: TrashOf?

    /// The drawer's "New": Home, where its dialog for `create` opens.
    func requestNew(_ create: AppKinds.Create) {
        choose(nil)
        newRequest = create
    }

    /// Removes the cover of `ref`; false when it did not land.
    func removeCover(_ ref: NodeAt) async -> Bool {
        guard let t = tenantSession else { return false }
        let outcome = try? await NodeCover.shared.remove(tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId)
        await refreshLists(t)
        return outcome == .done
    }

    /// The members' pictures this device opened, by user id (core's `AvatarsModel`): the letter for everyone else.
    private(set) var avatars: [String: Data] = [:]
    @ObservationIgnored private var avatarsWatch: AvatarsModel.Watch?

    /// The open tenant's pictures as core's model holds them now.
    private func syncAvatars() {
        guard let t = tenantSession else { return }
        avatars = AppleSessions.shared.pictures(tenant: t)
    }

    /// Writes node `ref`'s download to `path` (core's `ShellModel.download` into its `FileSink`: a file the file itself, any
    /// other node with everything under it as the web's ZIP); what it left out, nil when it did not finish (nothing left).
    func downloadNode(_ ref: NodeAt, to path: String) async -> [NodeExport.Skipped]? {
        guard let m = shellModel else { return nil }
        return try? await FileDownloads.shared.subtree(
            shell: m, ref: ref, deletedName: say(.potDeletedPerson), nowMs: Int64(Date().timeIntervalSince1970 * 1000), path: path
        )
    }

    func choose(_ app: ApplicationId?) { moveShell { $0.choose(app: app) }; drawerOpen = false }
    func switchSpace(_ scopeId: String) { moveShell { $0.switchSpace(scopeId: scopeId) } }
    func enter(_ folder: FolderRef) { moveShell { $0.enter(folder: folder) } }
    func setFilter(_ app: ApplicationId?) { moveShell { $0.filter(app: app) } }
    func goBack() { moveShell { $0.goBack() } }

    // MARK: Received links and kept ones (ADR 0150)

    /// *Open a link* and the link open as its guest (ADR 0150 decisions 3–5): a screen in the place of the sign-in or the
    /// shell, its guest's session in the Keychain, its uploads in the app's one queue.
    let guests = GuestLinks()

    /// The tenant a signed-in person keeps a link in (*Keep*); nil signed out.
    var guestTenant: TenantSession? { shellModel?.tenant }

    /// A link's page `page` on screen: its kind's member page reads its model through its application's holding (the
    /// gallery, the file, the Pot), so the guest's model is held in the shell's place while it shows (ADR 0150 decision 5:
    /// share view = member view).
    func adoptGuestPage(_ page: GuestPage) { _ = holdings.contains { $0.adopt(page) } }

    /// A link's page `page` left: its model goes, the member's place is the shell's again.
    func releaseGuestPage(_ page: GuestPage) { _ = holdings.contains { $0.release(page) } }

    /// The bookmark page on screen (core's `BookmarkPage`): one at a time; an in-place link let go with it.
    @ObservationIgnored private var openBookmark: (page: BookmarkPage, held: BookmarkHeld)?

    /// The page of bookmark `node`, loading (its meta opened, the share shown in place where the tenant embeds). Called while
    /// a view is drawn, so it changes nothing observed.
    func bookmarkPage(_ node: NodeAt) -> BookmarkHeld? {
        if let held = openBookmark, held.page.node == node { return held.held }
        guard let t = shellModel?.tenant else { return nil }
        closeBookmark()
        let page = AppleSharing.shared.bookmarkPage(tenant: t, node: node, opener: guests.opener(self))
        let held = BookmarkHeld(page, model: self)
        openBookmark = (page, held)
        Task { _ = try? await AppleSharing.shared.load(page: page) }
        return held
    }

    /// The bookmark's page left: its in-place link let go.
    func closeBookmark() {
        openBookmark?.held.stop()
        openBookmark?.page.close()
        openBookmark = nil
    }

    /// *Open on its own page*: the kept link's screen, its password handed in memory, *Keep* offered.
    func bookmarkOwnPage(_ page: BookmarkPage) {
        if let screen = page.openOwnPage(tenant: shellModel?.tenant) { guests.show(screen, self) }
    }

    /// A kept link's row's *Report shared content* (core's `BookmarkPage.reportSharedFromRow`): its meta read, the link's
    /// screen with its report open.
    func rowReportShared(_ node: NodeAt) {
        guard let t = shellModel?.tenant else { return }
        let page = AppleSharing.shared.bookmarkPage(tenant: t, node: node, opener: guests.opener(self))
        Task {
            if let screen = try? await AppleSharing.shared.reportSharedFromRow(page: page) { guests.show(screen, self) }
        }
    }

    /// *Report shared content*: the kept link's screen with its report to the share's own operator open.
    func bookmarkReportShared(_ page: BookmarkPage) {
        if let screen = page.reportShared() { guests.show(screen, self) }
    }

    /// New → Bookmark (core's `BookmarkCreate`) at `parentId` of space `scopeId` (nil: its top); nil before the shell is read.
    func bookmarkCreate(scopeId: String, parentId: String?) -> BookmarkCreate? {
        guard let t = shellModel?.tenant else { return nil }
        let kind = spaces.first { $0.scopeId == scopeId }?.kind
        return BookmarkCreate(door: BookmarkCreate.companion.into(tenant: t, scopeId: scopeId, parentId: parentId), groupSpace: kind != "PERSONAL", link: nil, password: nil)
    }

    /// A bookmark was made (New or a link's *Keep*): the lists read anew, so its row shows.
    func bookmarkMade() {
        guard let t = tenantSession else { return }
        Task { await refreshLists(t) }
    }

    /// A picked file as a guest's upload source (a drop box's, a replace's), read in place where it lies.
    func guestSource(_ url: URL) -> UploadSource? {
        guard let p = Picked(url) else { return nil }
        return ApplePlatform.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) })
    }

    func open(_ ref: ObjectRef) {
        shellModel?.open(ref: ref)
        applyShell(start: false)
        drawerOpen = false
    }

    /// [ref] chosen from the split view's sidebar: a top-level destination, no back (core's `Shell.openAtTop`).
    func openAtTop(_ ref: ObjectRef) {
        shellModel?.openAtTop(ref: ref)
        applyShell(start: false)
    }

    func select(_ tab: BarTab) { moveShell { $0.select(tab: tab) } }
    func search(_ on: Bool) { moveShell { $0.search(on: on) } }

    func applicationList(_ app: ApplicationId) -> ArrangedList {
        _ = objects; _ = favorites
        return shellModel?.current?.lists.applicationList(app: app) ?? ArrangedList(favorites: [], recent: [], all: [])
    }
}

/// A picture held in an `NSCache` (which takes objects).
final class CGImageBox {
    let image: CGImage
    init(_ image: CGImage) { self.image = image }
}

/// The Settings page's actions, each a call of core's `AccountSettings` (the page decides nothing).
extension AppModel: SettingsActions {
    func renameDevice(_ label: String) { onSettings { try await $0.rename(label: label); return nil } }
    func renameFamily(_ familyId: String, _ label: String) { onSettings { try await $0.renameFamily(familyId: familyId, label: label); return nil } }
    func signOutFamily(_ familyId: String) {
        // this device's own family is a sign-out here: asked first while writes made offline wait (ADR 0153 decision 2)
        if settings?.families?.first(where: { $0.id == familyId })?.current == true {
            askingLoss(.signOut) { [weak self] in self?.onSettings { try await $0.signOut(familyId: familyId) } }
        } else {
            onSettings { try await $0.signOut(familyId: familyId) }
        }
    }
    func signOutOthers() { onSettings { try await $0.signOutOthers(); return nil } }
    func signOutAndRemoveKey(_ familyId: String) { onSettings { try await $0.signOutAndRemoveKey(familyId: familyId); return nil } }
    func removeSavedKey(_ deviceId: String) { onSettings { try await $0.removeSavedKey(deviceId: deviceId); return nil } }
    func removeEveryOtherSavedKey() { onSettings { try await $0.removeEveryOtherSavedKey(); return nil } }
    func stopTrusting() {
        // asked first while writes made offline wait (ADR 0153 decision 2): they go with the trust
        askingLoss(.stopTrusting) { [weak self] in
            self?.onSettings { [weak self] m in
                try await m.stopTrusting()
                self?.trustLog("settings stop trusting")
                return nil
            }
        }
    }
    func signOutHere() { askingLoss(.signOut) { [weak self] in self?.onSettings { try await $0.signOutHere() } } }
    func setOfflineLimit(_ amount: String, _ unit: LimitUnit) { onSettings { try await $0.setOfflineLimit(amount: amount, unit: unit); return nil } }
    func turnOfflineLimitOff() { onSettings { try await $0.turnOfflineLimitOff(); return nil } }
    func setBackgroundSync(_ on: Bool) { onSettings { try await $0.setBackgroundSync(on: on); return nil } }
    func clearOfflineCache() { onSettings { try await $0.clearOfflineCache(); return nil } }
    func unpinOffline(_ node: String) { onSettings { try await $0.unpinOffline(node: node); return nil } }
    func askRemoveAllOffline() { settingsModel?.askRemoveAllOffline(); syncSettings() }
    func cancelRemoveAllOffline() { settingsModel?.cancelRemoveAllOffline(); syncSettings() }
    func removeAllOffline() { askingLoss(.removeAll) { [weak self] in self?.onSettings { try await $0.removeAllOffline(); return nil } } }

    func settingsStay(_ level: StaySignedIn) async -> TrustChange {
        guard let m = settingsModel else { return .failed }
        let change = (try? await m.stay(level: level)) ?? .failed
        trustLog("settings stay signed in \(level): \(change)")
        return change
    }

    func settingsBackToPassword(_ password: String) async -> TrustChange {
        guard let m = settingsModel else { return .failed }
        let change = (try? await m.trustWithPassword(password: password)) ?? .failed
        trustLog("settings back to the password: \(change)")
        return change
    }
}

