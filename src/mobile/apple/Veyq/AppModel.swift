import CoreGraphics
import Foundation
import Observation
import VeyqCore
#if os(iOS)
import UIKit
#endif

/// One server in the switcher: its account and the tenants the switcher lists there.
struct ServerEntry: Identifiable {
    let account: VeyqAccount
    let tenants: [TenantRef]
    var id: String { account.key }
    var label: String { account.label }
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
        case signIn(working: Bool, error: Msg?, adding: Bool, server: String?, email: String?, kit: SessionModelKitShown?)
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
    private(set) var switching = false
    /// The shell's banner while the server is out of reach (core's `ShellModel.State.banner`); nil online.
    private(set) var offlineBanner: VeyqCore.Notice?
    /// Items made available offline that left this device (core's `State.gone`), said until `goneSeen()`.
    private(set) var goneNotices: [VeyqCore.Notice] = []
    /// Whether the account reaches its server: offline every write is taken away (ADR 0140 decision 7).
    var online: Bool { offlineBanner == nil }
    /// "Manage servers", shown as a sheet while non-nil.
    var managing: [ManagedServer]?
    var drawerOpen = false

    private let device: ThisDevice
    /// The share extension's model (ADR 0138): it signs in only from what the app left and moves nothing of the app's.
    let inExtension: Bool

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
        #else
        let limit = AccountOffline.companion.DESKTOP_LIMIT
        #endif
        // the share extension never opens the offline store (ADR 0140 decision 3): it names no directory
        return AppleSessions.shared.device(
            keychain: keychain, label: label, prompt: say(.deviceUnlockPrompt), defaults: Shared.defaults,
            offlineRoot: inExtension ? nil : offlineRoot(), offlineLimit: limit)
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
    typealias Opened = SessionModelOpened<VeyqAccount, TenantSession>

    init(inExtension: Bool = false) {
        self.inExtension = inExtension
        device = Self.makeDevice(inExtension: inExtension)
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
        if !inExtension { Self.sweepTemporary() }
        gateWatch = session.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.gateChanged() }
        }
    }

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
            // locked or gone: the open dialogs and the tenant's tree go — a person's index with it (ADR 0144 decision 12)
            closeCuration()
            closeSearch()
            tenantSession?.close()
        }
        switch session.current {
        case let g as SessionModelGate.Signed: trustLog("gate Signed, offer: \(g.offer.map { "\($0.levels)" } ?? "none")")
        case let g as SessionModelGate.Unlock: trustLog("gate Unlock, \(g.record.method), level: \(g.record.unlockLevel.map { "\($0)" } ?? "none")")
        default: trustLog("gate \(type(of: session.current))")
        }
        switch session.current {
        case let g as SessionModelGate.SignIn:
            screen = .signIn(working: g.working, error: g.error, adding: g.adding, server: g.server, email: g.email, kit: g.kit)
        case let g as SessionModelGate.Unlock:
            screen = .unlock(record: g.record, working: g.working, error: g.error)
        case is SessionModelGate.NeedsApp:
            screen = .needsApp
        case let g as SessionModelGate.Signed:
            switching = g.switching
            offer = g.offer
            if shellModel != nil, shellModel?.tenant === session.signedTo?.session { applyShell(start: false); screen = .signed }
            followEnded(g)
        default:
            screen = .starting
        }
    }

    /// The shown account ended on its own (`Reach.Ended`: a reconnect found this device's trust gone): core took it out of
    /// the switcher and opened the next one, which no call of this model asked for — the shell follows it here. A switch
    /// or a sign-out this model made shows its own opened account.
    private func followEnded(_ g: SessionModelGate.Signed) {
        guard !g.switching, let opened = session.signedTo, let shown = shellModel?.tenant, shown !== opened.session,
              !session.accounts.contains(where: { $0 === shown.account }) else { return }
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
        // from the watch, which a close ends: a close while the grant is spent ends the sheet, not the sign-in (ADR 0136's Consequences)
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
        if session.signedTo?.account == account { uploads.clear() }
        Task {
            guard let out = try? await session.signOut(account: account) else { return }
            if let next = out.next {
                managing = nil
                try? await show(next)
            } else if out.wasCurrent {
                managing = nil
                shellWatch?.stop()
                shellModel = nil
                closeSearch()
                tenantSession?.close()
                closeDepot()
                closeMedia()
                dropHeaders()
                closeFile()
            } else if managing != nil {
                managing = managed()
            }
        }
    }

    /// Ends this device's trust in `account`: the next sign-in here asks for the Secret Key again.
    func forgetDevice(_ account: VeyqAccount) {
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
                shellWatch?.stop()
                shellModel = nil
                closeSearch()
                tenantSession?.close()
                closeDepot()
                closeMedia()
                dropHeaders()
                closeFile()
            }
        }
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
        let pots = PotModel(tenant: t, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        potWatch?.stop()
        potModel = pots
        potWatch = pots.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.syncPot() }
        }
        pot = nil
        let model = ShellModel(tenant: t, hidden: hidden, recent: recent, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        closeSearch()
        shellWatch?.stop()
        shellModel = model
        openDepot(model, t)
        closeGallery(windows: true)
        galleryShown = nil
        openMedia(model, switched: switched)
        openMusic(model, switched: switched)
        dropHeaders()
        closeFile()
        try await model.load(openLastUsed: incoming == nil)
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
    private func applyShell(start: Bool) {
        guard let signed = session.signedTo, shellModel?.tenant === signed.session, let state = shellModel?.current else { return }
        let account = signed.account
        let tenant = signed.tenant
        let l = state.lists
        spaces = l.spaces
        nodes = l.nodes
        objects = l.objects
        favorites = l.favorites
        applications = l.applications
        filters = l.filters.map { $0 as? ApplicationId }
        drawer = l.drawer
        servers = session.accounts.map { c in
            ServerEntry(account: c, tenants: hidden.visibleTenants(connectionKey: c.key, tenants: c.tenants, current: c == account ? tenant : nil))
        }
        let wasSearching = shell.searching
        shell = state.shell
        // the search left for anything but what it opened (whose back returns to it): the field empties (core's `close`)
        if wasSearching, !shell.searching, shell.back?.searching != true { searchModel?.close(); searchFieldReset += 1 }
        let wasOffline = offlineBanner != nil
        offlineBanner = state.banner
        goneNotices = state.gone
        if wasOffline, offlineBanner == nil, maps?.state.value is MapStateOffline, let m = shellModel { openMaps(m) }
        if start { drawerOpen = shell.drawerOpensAtStart }
    }

    /// Whether "New" is offered where the shell is (core's `ShellModel.canCreateHere`).
    var canCreateHere: Bool { _ = shell; return shellModel?.canCreateHere ?? false }

    /// The shell moved by `change` (its transitions are `Shell`'s), at once.
    private func moveShell(_ change: @escaping (Shell) -> Shell) {
        shellModel?.update(change: change)
        applyShell(start: false)
    }

    // MARK: What another app shared (ADR 0138)

    /// Files shared to veyq, waiting for the Pot they go to, and how many were left out (core's `ShareInbox`).
    typealias Incoming = ShareInbox.Incoming

    /// The share's way from arrival to where its files went (core's `ShareInbox`).
    let inbox = ShareInbox()
    /// The files waiting (the pick mode is on); nil when none wait.
    private(set) var incoming: Incoming?
    /// How the share ends: true once its files were saved where they went, false when it was cancelled.
    var onShareDone: ((Bool) -> Void)?

    private func syncInbox() { incoming = inbox.waiting.value as? Incoming }

    /// Files arrive: the pick mode starts at the space's Home, never in the object last open, so the person chooses where
    /// they go (ADR 0138 decision 1) — the start does the same while the session is still opening.
    func receiveShare(_ incoming: Incoming) {
        moveShell { Shell.companion.start(lastUsed: nil, space: $0.space) }
        inbox.receive(incoming: incoming)
        syncInbox()
    }

    /// Files dropped onto the app (ADR 0138 decision 9): an open Pot that takes them opens its new expense with them at once;
    /// anywhere else they wait in the pick mode, as a share's do.
    func receiveDrop(_ incoming: Incoming) {
        if let ref = shell.openObject, ref.kind == PotSession.companion.KIND, pot?.ref == ref, inbox.takesDropOn(view: pot?.view, incoming: incoming) {
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

    /// The waiting files, taken by the Pot opened as their place: its new expense's receipts.
    func takeShare() -> [PotSession.PendingReceipt]? {
        let files = inbox.take()
        syncInbox()
        return files
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
            let id = ref.nodeId
            Task { @MainActor [weak self] in
                guard let self, let open = self.pot?.ref, open.nodeId == id else { return }
                self.potModel?.close(ref: open)
                self.syncPot()
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

    private func refreshLists(_ t: TenantSession, people: Bool = false) async {
        try? await shellModel?.refresh(people: people)
        applyShell(start: false)
        await readDetails(t)
    }

    /// What a delete says at the bottom of the shell: moved to the trash, with its Undo while core offers it; or why not.
    struct TrashNotice: Identifiable {
        let id = UUID()
        let message: String
        /// The header whose trash this is, while core offers its undo (core ends the offer after its window).
        var header: NodeHeaderModel? = nil
        /// The move sheet whose undo this is, while core offers it (core ends the offer after its window, as a trash's).
        var move: MoveSheet? = nil
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
            trashNotice = TrashNotice(message: say(notice: said))
            return
        }
        let notice = TrashNotice(message: say(notice: said), header: header)
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
        if let move = trashNotice?.move {
            trashNotice = nil
            undoWatch?()
            if let failed = try? await move.undo() { trashNotice = TrashNotice(message: say(notice: failed)) }
            return
        }
        guard let header = trashNotice?.header else { return }
        trashNotice = nil
        undoWatch?()
        if let failed = try? await header.undoTrash() { trashNotice = TrashNotice(message: say(notice: failed)) }
    }

    /// What a move or copy said once `sheet` is done, at the bottom of the shell: a move's Undo for as long as core offers it
    /// (the header CR's D4: a trash's window).
    func moved(_ sheet: MoveSheet) {
        guard let done = sheet.current.done else { return }
        undoWatch?()
        guard sheet.current.undo != nil else {
            trashNotice = TrashNotice(message: say(notice: done))
            return
        }
        let notice = TrashNotice(message: say(notice: done), move: sheet)
        trashNotice = notice
        let id = notice.id
        let watch = sheet.watch { @Sendable state in
            guard state.undo == nil else { return }
            Task { @MainActor [weak self] in self?.dismissTrashNotice(id) }
        }
        undoWatch = { watch.stop() }
    }

    func dismissTrashNotice(_ id: UUID) { if trashNotice?.id == id { trashNotice = nil } }

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

    // MARK: Memories (core's GalleryModel, Lightbox and MediaUploads)

    /// The Memory or chapter page on screen (core's `GalleryModel`, one at a time) and its watch; read anew as the core says.
    @ObservationIgnored private var openGallery: (model: GalleryModel, watch: GalleryModel.Watch)?

    /// What the Memory or chapter page on screen shows (`GalleryModel.State`) and of which node, on the main actor.
    private var galleryShown: (node: NodeAt, state: GalleryModel.State)?

    /// What `node`'s gallery shows, once its model delivered it; nil before, and for any other node.
    func galleryState(_ node: NodeAt) -> GalleryModel.State? {
        guard let shown = galleryShown, shown.node == node else { return nil }
        return shown.state
    }

    /// The page of Memory or chapter `node` (core's `GalleryModel` over the shell, its uploads through `media`). Called while a
    /// view is drawn, so it changes nothing observed: the state arrives through the watch.
    func galleryModel(_ node: NodeAt) -> GalleryModel? {
        if let held = openGallery, held.model.node == node { return held.model }
        guard let m = shellModel, let uploads = media else { return nil }
        closeGallery()
        let made = m.galleryModel(ref: node, uploads: uploads, music: galleryMusic)
        let id = ObjectIdentifier(made)
        let watch = made.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in
                // a state of a page closed meanwhile is dropped
                guard let self, let open = self.openGallery?.model, ObjectIdentifier(open) == id else { return }
                self.galleryShown = (open.node, state)
                // the search opened one of this page's photos (ADR 0146 decision 8): the page shows again, its lightbox on it
                if state.searchOpened != nil, self.shell.searching { self.search(false) }
            }
        }
        openGallery = (made, watch)
        return made
    }

    /// `node`'s page's live poll (core's `GalleryModel.live`, run by core) for as long as the calling task runs — the page
    /// while the app is active, so nothing is asked in the background; its cancellation stops the poll.
    func liveGallery(_ node: NodeAt) async {
        guard let live = galleryModel(node)?.startLive() else { return }
        while !Task.isCancelled { try? await Task.sleep(for: .seconds(3600)) }
        live.stop()
    }

    /// The gallery goes (another opened, the tenant closed): its lightbox and a video's reader close with it. The tenant's
    /// close passes `windows`: every lightbox window's gallery goes too.
    private func closeGallery(windows: Bool = false) {
        closeCuration()
        closeMusicView()
        closeTrackList()
        openGallery?.watch.stop()
        openGallery?.model.close()
        openGallery = nil
        if windows {
            windowGalleries.values.forEach { $0.close() }
            windowGalleries = [:]
        }
    }

    /// A lightbox in a window of its own (a Mac's "Open in New Window"): its own gallery and the lightbox on it.
    struct WindowLightbox {
        let gallery: GalleryModel
        let box: VeyqCore.Lightbox
    }

    /// The galleries the lightbox windows opened, by identity: closed with their window, and all with the tenant.
    private var windowGalleries: [ObjectIdentifier: GalleryModel] = [:]

    /// A lightbox window's models for `value` (its ids): a gallery of its own over the shell — not the main window's page,
    /// so each goes without the other — and core's lightbox on the medium named (`lightboxOn`: before the gallery's first
    /// read it waits for it). Nil where the node is no longer a listed Memory or chapter, or no tenant is open.
    func windowLightbox(_ value: LightboxWindowValue) -> WindowLightbox? {
        guard let m = shellModel, let uploads = media, let node = m.galleryAt(scopeId: value.scopeId, nodeId: value.nodeId) else { return nil }
        let gallery = m.galleryModel(ref: node, uploads: uploads, music: galleryMusic)
        windowGalleries[ObjectIdentifier(gallery)] = gallery
        return WindowLightbox(gallery: gallery, box: gallery.lightboxOn(mediaId: value.mediaId))
    }

    /// Whether lightbox window gallery `gallery` is still open (the tenant's close closed it otherwise).
    func holdsWindowGallery(_ gallery: GalleryModel) -> Bool { windowGalleries[ObjectIdentifier(gallery)] != nil }

    /// A lightbox window went: its gallery closes, its lightbox and a video's reader with it.
    func closeWindowGallery(_ gallery: GalleryModel) {
        gallery.close()
        windowGalleries[ObjectIdentifier(gallery)] = nil
    }

    /// Decoded tiers by blob id (a gallery asks for every tile as it scrolls past; the cache lets the system evict).
    private let mediaPictures = NSCache<NSString, CGImageBox>()

    /// Blob `blobId`'s bytes as `read` hands them, decoded as a picture and kept; nil where it does not read or is no picture.
    func mediaPicture(_ blobId: String, read: () async -> Data?) async -> CGImage? {
        if let hit = mediaPictures.object(forKey: blobId as NSString) { return hit.image }
        guard let data = await read(), let image = CoverImages.image(data, maxPixels: 4096) else { return nil }
        mediaPictures.setObject(CGImageBox(image), forKey: blobId as NSString)
        return image
    }

    /// Memories' uploads of the open tenant (core's `MediaUploads` over `uploads`): a Photos pick's copy is removed once the
    /// queue is done with it, and all of them when the tenant closes.
    @ObservationIgnored private var media: MediaUploads?

    /// Memories' uploads for shell `m`: a new tenant's hand the old one's picks back (its queue was emptied), the same
    /// tenant opened again carries them on.
    private func openMedia(_ m: ShellModel, switched: Bool) {
        let made = m.mediaUploads(queue: uploads, deriver: MediaDeriver.shared, left: { @Sendable source in
            if let url = AppleMemories.shared.urlOf(source: source) { AppModel.removePicked(url) }
        }) { @Sendable in
            Task { @MainActor [weak self] in
                guard let self, let t = self.tenantSession else { return }
                await self.refreshLists(t)
            }
        }
        if let old = media {
            if switched { old.close() } else { old.handOver(next: made) }
        }
        media = made
        openMaps(m)
    }

    private func closeMedia() {
        closeGallery(windows: true)
        galleryShown = nil
        media?.close()
        media = nil
        closeMusic()
        closeMaps()
    }

    // MARK: A Memory's curation (ADR 0144)

    /// The open gallery's participants picker while its sheet shows (core's `ParticipantsPicker`).
    var participantsEdit: ParticipantsEdit?
    /// The open gallery's banner dialog while its sheet shows (core's `BannerEditor`).
    var bannerEdit: BannerEdit?

    /// `gallery`'s participants picker opened over the space's current members, where its rights offer it.
    func openParticipants(_ gallery: GalleryModel) {
        guard let picker = gallery.participantsPicker() else { return }
        participantsEdit?.close()
        participantsEdit = ParticipantsEdit(picker: picker, gallery: gallery)
    }

    func closeParticipants() {
        participantsEdit?.close()
        participantsEdit = nil
    }

    /// `gallery`'s banner dialog opened, where whoever looks may set its banner, online.
    func openBanner(_ gallery: GalleryModel) {
        guard openGallery?.model === gallery, let editor = gallery.bannerEditor(pictures: BannerImages.pictures()) else { return }
        bannerEdit?.close()
        bannerEdit = BannerEdit(editor: editor, gallery: gallery)
    }

    /// The banner's dialog closed: a running save stopped (the old banner stays), a Photos copy removed.
    func closeBanner() {
        bannerEdit?.close()
        bannerEdit = nil
    }

    /// The open gallery's dialogs closed with it (another gallery, a switch, a lock, a sign-out).
    private func closeCuration() {
        closeParticipants()
        closeBanner()
    }

    /// The picked file at `url` as an upload's source (`fromPhotos`: a copy the Photos picker made, whose date is no capture
    /// time); nil where it does not read.
    func pickedSource(_ url: URL, fromPhotos: Bool) -> UploadSource? {
        guard let p = Picked(url) else { return nil }
        return AppleMemories.shared.source(url: url, name: p.name, size: p.size, type: p.type,
                                           modifiedAt: fromPhotos ? nil : p.modifiedAt.map { KotlinLong(value: $0) })
    }

    /// The Memory's music interrupted while a banner's video plays (ADR 0142 decision 13); nil where no player is open.
    func interruptMusic() -> MusicPlayer.Interruption? { musicPlayer?.interrupt() }

    // MARK: A person's profile (ADR 0144 decisions 11–13)

    /// Person `userId`'s profile opened over what is on screen (core's `Shell.openPerson`; back returns there).
    func openPerson(_ userId: String) {
        shellModel?.openPerson(userId: userId)
        applyShell(start: false)
    }

    /// Person `userId`'s frame as the tenant's directory names them; nil while no tenant is open.
    func profile(_ userId: String) -> PersonProfile? { shellModel?.profile(userId: userId) }

    /// Memories' section of person `userId`'s profile (core's `PersonMemories`), read on its open.
    func personMemories(_ userId: String) -> PersonSection? {
        shellModel.map { PersonSection($0.personMemories(userId: userId)) }
    }

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

    /// The Memories bar's Map tab over the application's list (its All tab's set, core's `MemoriesMap`).
    func memoriesMap() async -> MemoriesMap? {
        guard let m = shellModel else { return nil }
        let refs = memoriesListRefs()
        return try? await m.memoriesMap(memories: refs)
    }

    /// The Memories of the application's list as the tree holds them (the Map tab's set is the All tab's, ADR 0143 D6).
    private func memoriesListRefs() -> [NodeRef] {
        let ids = Set(applicationList(.memories).all.map(\.nodeId))
        return nodes.filter { ids.contains($0.nodeId) }
    }

    /// The pin's Memory opened, as its row opens it.
    func openPin(_ pin: MemoriesMap.Pin) {
        if let ref = objects.first(where: { $0.nodeId == pin.nodeId && $0.scopeId == pin.scopeId }) { open(ref) }
    }

    /// Memory or chapter `gallery`'s date editor (core's), nil where whoever looks may not edit its meta or offline.
    func dateEditor(_ gallery: GalleryModel) -> DateEditor? { shellModel?.dateEditor(gallery: gallery) }

    /// Memory or chapter `gallery`'s place editor (core's) over this tenant's map services.
    func placeEditor(_ gallery: GalleryModel) -> PlaceEditor? { shellModel?.placeEditor(gallery: gallery) }

    /// The open page's Map view tracks (core's `TrackList` over its gallery), while the view shows; its state arrives in
    /// `tracksShown`.
    @ObservationIgnored private var openTracks: (model: TrackList, watch: TrackList.Watch)?
    private(set) var tracksShown: TrackList.State?

    /// `gallery`'s tracks, made on the Map view's first ask.
    func trackList(_ gallery: GalleryModel) -> TrackList? {
        if let held = openTracks, held.model.gallery === gallery { return held.model }
        guard let m = shellModel, let uploads = media else { return nil }
        closeTrackList()
        let made = m.trackList(gallery: gallery, uploads: uploads, left: { @Sendable source in
            if let url = AppleMemories.shared.urlOf(source: source) { AppModel.removePicked(url) }
        })
        let id = ObjectIdentifier(made)
        let watch = made.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in
                guard let self, let open = self.openTracks?.model, ObjectIdentifier(open) == id else { return }
                self.tracksShown = state
            }
        }
        openTracks = (made, watch)
        return made
    }

    private func closeTrackList() {
        openTracks?.watch.stop()
        openTracks?.model.close()
        openTracks = nil
        tracksShown = nil
    }

    /// Picked GPX files (`urls`) offered to `tracks`' node: each checked on the device; what is refused is said by the view.
    func addTracks(_ tracks: TrackList, urls: [URL]) async {
        let sources: [UploadSource] = urls.compactMap { url in
            guard let p = Picked(url) else { return nil }
            return AppleMemories.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) })
        }
        guard !sources.isEmpty else { return }
        _ = try? await tracks.add(sources: sources)
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
    private var galleryMusic: GalleryModel.Music? {
        musicPlayer.map { GalleryModel.Music(player: $0, untitled: { @Sendable n in say(.musicUntitled, n) }) }
    }

    private func openMusic(_ m: ShellModel, switched: Bool) {
        if musicPlayer != nil, !switched { return }
        closeMusic()
        let player = m.musicPlayer(newDeck: { AVDeck.make($0) }, controls: SystemMusicControls())
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
                    try? await Task.sleep(for: .seconds(5))
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

    /// The open page's Music view (core's `MusicModel` over its gallery), while the view shows; its state arrives in
    /// `musicShown`.
    @ObservationIgnored private var openMusicView: (model: MusicModel, watch: MusicModel.Watch)?
    private(set) var musicShown: MusicModel.State?

    /// `gallery`'s Music view model, made on its first ask.
    func musicModel(_ gallery: GalleryModel) -> MusicModel? {
        if let held = openMusicView, held.model.gallery === gallery { return held.model }
        guard let m = shellModel, let uploads = media else { return nil }
        closeMusicView()
        let made = m.musicModel(gallery: gallery, uploads: uploads, measure: AssetMeasure(), left: { @Sendable source in
            if let url = AppleMemories.shared.urlOf(source: source) { AppModel.removePicked(url) }
        })
        let id = ObjectIdentifier(made)
        let watch = made.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in
                guard let self, let open = self.openMusicView?.model, ObjectIdentifier(open) == id else { return }
                self.musicShown = state
            }
        }
        openMusicView = (made, watch)
        return made
    }

    private func closeMusicView() {
        openMusicView?.watch.stop()
        openMusicView?.model.close()
        openMusicView = nil
        musicShown = nil
    }

    /// Picked audio files (`urls`) added to `music`'s node as tracks: each checked on the device, the refused said.
    @discardableResult func addMusic(_ music: MusicModel, urls: [URL]) async -> [String] {
        let sources: [UploadSource] = urls.compactMap { url in
            guard let p = Picked(url) else { return nil }
            return AppleMemories.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) })
        }
        guard !sources.isEmpty, let said = try? await music.add(sources: sources) else { return [] }
        return said.map { say(notice: $0) }
    }

    /// Queues the picked photos and videos (`urls`; `fromPhotos`: copies the Photos picker handed over, whose own dates are
    /// no capture time) into `gallery`'s Memory or chapter (core's `MediaUploads`): each one's tiers derived here
    /// (`MediaTiers`) while the one before it is on the wire; the core reads the gallery anew as each is in, the lists at
    /// the batch's end, and hands each copy back once the queue is done with it.
    func uploadMedia(_ gallery: GalleryModel, urls: [URL], fromPhotos: Bool) {
        let sources: [UploadSource] = urls.compactMap { url in
            guard let p = Picked(url) else {
                if fromPhotos { Self.removePicked(url) }
                return nil
            }
            return AppleMemories.shared.source(
                url: url, name: p.name, size: p.size, type: p.type,
                modifiedAt: fromPhotos ? nil : p.modifiedAt.map { KotlinLong(value: $0) }
            )
        }
        _ = gallery.upload(sources: sources)
    }

    // MARK: The move and copy sheet (core's MoveSheet)

    /// The move or copy sheet of `node` (core's `MoveSheet` over the shell: its browser, the guest folder's confirm, the
    /// kind's copy — a Pot by the open Pot —, the storage check, the move's undo); each write reads the lists anew.
    func moveSheet(_ node: NodeAt, copying: Bool) -> MoveSheet? {
        guard let m = shellModel else { return nil }
        return m.moveSheet(node: node, copying: copying, pot: potModel, deletedName: say(.potDeletedPerson)) { @Sendable in
            Task { @MainActor [weak self] in
                guard let self, let t = self.tenantSession else { return }
                self.applyShell(start: false)
                await self.readDetails(t)
            }
        }
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

    // MARK: An open Pot (Expenses)

    /// The tenant the shell shows, opened: where an object's own view reads from.
    private var tenantSession: TenantSession?
    /// The Pot open in the app (core's `PotModel`): its views, its live poll, its writes.
    @ObservationIgnored private var potModel: PotModel?
    @ObservationIgnored private var potWatch: PotModel.Watch?
    private var potSession: PotSession? { potModel?.session }

    /// The open Pot as core's model holds it now.
    private func syncPot() {
        pot = potModel?.current.map { PotState(ref: $0.ref, view: $0.view, failed: $0.failed, saving: $0.saving, message: $0.message) }
    }

    /// An open Pot's views: `view` nil while it loads, `failed` when it did not open; `message` the last add's refusal.
    struct PotState {
        let ref: ObjectRef
        var view: PotView?
        var failed = false
        var saving = false
        var message: String?
    }

    private(set) var pot: PotState?

    /// What each object's row says beside its name, by node id (an application's answer: Expenses' `PotRows`).
    private(set) var rowDetails: [String: [String]] = [:]
    /// Counts each read of the lists: a page of what lies below its node reads again on every one (as Android's does),
    /// not only when the number of rows moved.
    private(set) var listsRevision = 0

    /// Drop box `ref`'s page (core's `DropBoxPage`: its guests' folders read anew with the lists, a delete asked first).
    func dropBoxPage(_ ref: NodeAt) -> DropBoxPage? { shellModel?.dropBoxPage(ref: ref) }

    /// What the rows read last (core's `RowDetails`): a part that does not read next time keeps these.
    @ObservationIgnored private var rows = RowDetails.ReadCompanion.shared.EMPTY

    /// The rows' facts and the lists' covers, read anew (core's `RowDetails`: Pots, files, drop boxes, cover keys).
    private func readDetails(_ t: TenantSession, reset: Bool = false) async {
        if reset { rows = RowDetails.ReadCompanion.shared.EMPTY }
        guard let read = try? await RowDetails.shared.read(tenant: t, previous: rows), tenantSession === t else { return }
        rows = read
        // the file on screen reads itself anew: a replaced blob is shown
        openFile?.model.reload()
        var details = read.details.mapValues { $0.map { say(piece: $0) } }
        // a Memory's row shows its date as its second line (ADR 0143 D5): the facts its sealed meta holds
        if let dates = try? await shellModel?.memoryDates(), tenantSession === t {
            for (nodeId, date) in dates where details[nodeId] == nil {
                if let text = MemoryDateText.row(date) { details[nodeId] = [text] }
            }
        }
        rowDetails = details
        listsRevision += 1
        await readCovers(t, named: read.covers)
    }

    /// The file page on screen (core's `FileModel`, one at a time) and its watch; read anew whenever the lists are.
    @ObservationIgnored private var openFile: (model: FileModel, watch: FileModel.Watch)?

    /// What the file page on screen shows (`FileModel.State`) and of which node, on the main actor.
    private var fileShown: (node: NodeAt, state: FileModel.State)?

    /// What file `node`'s page shows, once its model delivered it; nil before, and for any other node.
    func fileState(_ node: NodeAt) -> FileModel.State? {
        guard let shown = fileShown, shown.node == node else { return nil }
        return shown.state
    }

    /// The page of file `node` (core's `FileModel` over the shell): iOS hands a copy on through its share sheet, a Mac does
    /// not. Called while a view is drawn, so it changes nothing observed: the state arrives through the watch.
    func fileModel(_ node: NodeAt) -> FileModel? {
        if let held = openFile, held.model.node == node { return held.model }
        guard let m = shellModel else { return nil }
        closeFile()
        #if os(iOS)
        let shares = true
        #else
        let shares = false
        #endif
        let made = m.fileModel(ref: node, shares: shares)
        let id = ObjectIdentifier(made)
        let watch = made.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in
                // a state of a page closed meanwhile is dropped
                guard let self, let open = self.openFile?.model, ObjectIdentifier(open) == id else { return }
                self.fileShown = (open.node, state)
            }
        }
        openFile = (made, watch)
        return made
    }

    private func closeFile() {
        openFile?.watch.stop()
        openFile?.model.close()
        openFile = nil
    }

    /// Something the shell asks or says once (an upload's refusal, its "Replace anyway"), with its action where it offers one.
    struct Notice: Identifiable {
        let id = UUID()
        let text: String
        var action: String?
        var run: (() async -> Void)?
    }

    var notice: Notice?

    /// The file page whose content the person is picking a replacement for (the header's "Replace…"), while they pick.
    var replacing: FileModel? {
        didSet { if let replacing { replaceChosen = replacing } }
    }
    /// The file page Replace was chosen on, kept past the importer's dismissal: its binding clears `replacing` before the
    /// completion runs, which then found nothing to replace (the picked file was dropped, measured 2026-11-12).
    @ObservationIgnored var replaceChosen: FileModel?

    /// The file a person is about to send a copy of to another app ("Send a Copy…"), while the shell says first that the
    /// copy leaves veyq decrypted. The data are theirs; the app says what happens and never forbids it.
    var sendingCopy: NodeAt?

    /// A decrypted copy on its way to the system's share sheet, until the sheet is done with it.
    struct SentCopy: Identifiable {
        let url: URL
        var id: URL { url }
    }

    var sentCopy: SentCopy?

    /// Where copies sent to another app wait to be read: cleared before the next one, when the sheet is done and at start.
    private static var sharedDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("shared", isDirectory: true) }

    /// Where the Photos picker's copies wait for their upload (`PickedMedia`, one directory each): the person's own originals,
    /// never a decrypted veyq copy; each removed once the queue is done with it, all of them when the tenant closes and at
    /// start.
    static var pickedDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("picked", isDirectory: true) }

    /// Removes the copy at `url` with its own directory, where it is one of `pickedDir`'s; any other file is left alone.
    nonisolated static func removePicked(_ url: URL) {
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

    /// A decrypted copy of file `ref` under its own name (core's `downloadName`), for the system's share sheet.
    func copyForSending(_ ref: NodeAt) async -> SentCopy? {
        guard let header = fileModel(ref)?.current.header, header.facts.hasContent else { return nil }
        clearSentCopies()
        let dir = Self.sharedDir.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else { return nil }
        let url = dir.appendingPathComponent(header.downloadName)
        // the file itself (core's download of a file): the one decrypted copy the person asked for, swept at the next start
        guard await downloadNode(ref, to: url.path) != nil else { return nil }
        return SentCopy(url: url)
    }

    /// The app's one upload queue (core's `UploadQueue`, the web's `core/blob/queue.ts`): every application's uploads, one
    /// after another, retried once; the shell's uploads button shows it (`UploadsButton`). It outlives a screen, not the
    /// account: a sign-out or a switch of tenant empties it.
    let uploads = UploadQueue()

    /// The uploads as the shell shows them (core's `DepotUploads` over `uploads`, one per open tenant): the uploads button
    /// draws `depotState`, Depot's uploads go through it, and the shell shows what it says (`said`).
    private(set) var depot: DepotUploads?
    private(set) var depotState: DepotUploads.State?
    @ObservationIgnored private var depotWatch: DepotUploads.Watch?

    private func openDepot(_ m: ShellModel, _ t: TenantSession) {
        closeDepot()
        let d = m.depotUploads(queue: uploads) { @Sendable in
            Task { @MainActor [weak self] in
                guard let self, let t = self.tenantSession else { return }
                await self.refreshLists(t)
            }
        }
        depot = d
        depotState = d.current
        depotWatch = d.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.syncDepot() }
        }
    }

    private func closeDepot() {
        depotWatch?.stop()
        depotWatch = nil
        depot?.close()
        depot = nil
        depotState = nil
    }

    /// The uploads' state on the main actor; what they say (`said`) the shell shows as an alert of its own, one at a time.
    private func syncDepot() {
        guard let d = depot else { return }
        depotState = d.current
    }

    /// A picked file as an upload lists it: its name, size, type, modified time (read inside its security scope).
    private struct Picked {
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
    private var levelLabel: String {
        shell.folder?.name ?? Names.spaceName(spaces.first { $0.scopeId == shell.space })
    }

    /// Queues the picked `urls` as new files under the level shown (core's `DepotUploads.upload`): a name a file already
    /// holds offers its replace.
    func uploadFiles(_ urls: [URL]) {
        guard let d = depot, let scopeId = shell.space else { return }
        let sources: [UploadSource] = urls.compactMap { url in
            guard let p = Picked(url) else { return nil }
            return AppleDepot.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) })
        }
        _ = d.upload(scopeId: scopeId, parentId: shell.folder?.nodeId, label: levelLabel, sources: sources)
    }

    /// Depot's "Upload folder…": the directory at `url` (the system's folder picker) walked here — every entry by its path
    /// below it, hidden ones included, a link said as one and never followed — and handed to core's
    /// `DepotUploads.uploadFolder`, which keeps, prepares, queues and says.
    func uploadFolder(_ url: URL) async {
        guard let d = depot, let scopeId = shell.space else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        let files = Self.folderFiles(url)
        if scoped { url.stopAccessingSecurityScopedResource() }
        let found = AppleDepot.shared.found(root: url, files: files)
        try? await d.uploadFolder(scopeId: scopeId, parentId: shell.folder?.nodeId, shownName: nil, path: url.path, found: found)
    }

    /// The entries below the directory at `url`, each by its path below it and what it is (hidden ones included, as the
    /// web's directory input holds them; a link reported, never followed; one the enumerator could not read reported); a
    /// directory enumerator is not iterated in an async context, so this is its own synchronous walk.
    nonisolated private static func folderFiles(_ url: URL) -> [AppleDepot.FolderFile] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .isReadableKey, .fileSizeKey, .contentModificationDateKey, .contentTypeKey]
        var files: [AppleDepot.FolderFile] = []
        let base = url.standardizedFileURL.path
        func relative(_ file: URL) -> String {
            let full = file.standardizedFileURL.path
            return full.hasPrefix(base + "/") ? String(full.dropFirst(base.count + 1)) : file.lastPathComponent
        }
        func entry(_ file: URL, _ kind: FolderUpload.Kind, _ v: URLResourceValues? = nil) -> AppleDepot.FolderFile {
            AppleDepot.FolderFile(
                relativePath: relative(file), path: file.standardizedFileURL.path, kind: kind, size: Int64(v?.fileSize ?? 0),
                type: v?.contentType?.preferredMIMEType ?? "application/octet-stream",
                modifiedAt: v?.contentModificationDate.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) }
            )
        }
        var unreadable: [AppleDepot.FolderFile] = []
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

    /// The header's "Replace…": the content `file` showed when Replace was chosen replaced with the picked file at `url`.
    func replaceFile(_ file: FileModel, url: URL) {
        guard let d = depot, let p = Picked(url) else { return }
        let source = AppleDepot.shared.source(url: url, name: p.name, size: p.size, type: p.type, modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) })
        _ = d.replace(file: file, source: source)
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

    /// Creates a folder named `name` where the shell is (core's `createFolderHere`) (the web's `FolderNameDialog`); the
    /// lists are read anew. Why not, in the web's words, or nil once created.
    func createFolder(name: String) async -> String? {
        guard let m = shellModel else { return say(.folderFailed) }
        let refused = try? await m.createFolderHere(name: name)
        applyShell(start: false)
        await readDetails(m.tenant)
        return refused.map { say(notice: $0) }
    }

    /// A Memory `name` made in space `scopeId` under `parentId` (nil: the space's top) — New Memory, core's
    /// `ShellModel.createMemory` (the web's `MemoryNodeDialog`: checked against the loaded tree, then sent) —, opened once
    /// made (the web's), its maker listed among its participants where `wasThere` (ADR 0144 decision 2), its place armed where
    /// `autoPlace` (uc.memory.place A1). Why not, in the
    /// dialog's words, or nil.
    func createMemory(scopeId: String, parentId: String?, name: String, wasThere: Bool = false, autoPlace: Bool = false) async -> String? {
        guard let m = shellModel else { return say(notice: MemoryCreate.shared.refused(reason: nil)) }
        let made = try? await m.createMemory(scopeId: scopeId, parentId: parentId, name: name, wasThere: wasThere, autoPlace: autoPlace)
        applyShell(start: false)
        await readDetails(m.tenant)
        return opened(made)
    }

    /// Whether New Memory in space `scopeId` offers *I was there* (a tenant or group space, ADR 0144 decision 2).
    func offersWasThere(_ scopeId: String) -> Bool { shellModel?.offersWasThere(scopeId: scopeId) ?? false }

    /// Whether New Memory offers *Determine location automatically* (uc.memory.place A1: this tenant's map services shown on this device).
    func offersAutoPlace() -> Bool { shellModel?.offersAutoPlace() ?? false }

    /// A chapter `name` made in `gallery`'s Memory (core's `GalleryModel.createChapter`, which reads the lists anew), opened
    /// once made; why not, or nil.
    func createChapter(_ gallery: GalleryModel, name: String) async -> String? {
        let made = try? await gallery.createChapter(name: name)
        applyShell(start: false)
        if let m = shellModel { await readDetails(m.tenant) }
        return opened(made)
    }

    /// What a Memory's or a chapter's create came to: the node made opened (both are Memories'), or why not in words.
    private func opened(_ made: MemoryCreate.Made?) -> String? {
        if let notice = made?.notice { return say(notice: notice) }
        guard let n = made?.node else { return say(notice: MemoryCreate.shared.refused(reason: nil)) }
        if let page = GalleryModel.companion.page(node: n) { open(page) }
        return nil
    }

    /// What the drawer's "New" asked Home to create, until Home opens its dialog.
    enum NewKind { case folder, pot, memory }
    var newRequest: NewKind?

    /// The drawer's "New": Home, where its dialog for `kind` opens.
    func requestNew(_ kind: NewKind) {
        choose(nil)
        newRequest = kind
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

    /// Opens and reads the Pot `ref` unless it is the one shown.
    func loadPot(_ ref: ObjectRef) {
        guard let pots = potModel else { return }
        Task {
            try? await pots.open(ref: ref)
            syncPot()
        }
    }

    /// The open Pot's live poll (core's `LivePoll`, run by core) for as long as the calling task runs — the Pot's screen
    /// while the app is active, so nothing is asked in the background; its cancellation stops the poll.
    func livePot() async {
        guard let live = potModel?.startLive() else { return }
        while !Task.isCancelled { try? await Task.sleep(for: .seconds(3600)) }
        live.stop()
    }

    /// Reads the open Pot again now (pull to refresh).
    func refreshPot() async {
        try? await potModel?.refresh()
        syncPot()
    }

    /// Records settle-up `rows` as paid, dated the core's today (core's `PotModel.record`); a refusal is the Pot's message.
    func recordSettleUp(_ rows: [PotView.SettleRow]) async {
        guard let pots = potModel else { return }
        _ = try? await pots.record(rows: rows)
        syncPot()
    }

    /// Whether deleted entry `entryId` may be restored now, by the core's clock.
    func restorable(_ entryId: String) -> Bool { potModel?.restorable(entryId: entryId) ?? false }

    /// Restores deleted entry `entryId` (core's `PotModel.restore`, its window the core's); true once it was.
    func restoreEntry(_ entryId: String) async -> Bool {
        guard let pots = potModel else { return false }
        let done = (try? await pots.restore(entryId: entryId))?.boolValue ?? false
        syncPot()
        return done
    }

    /// The open Pot's settings and people (core's `PotSettings`), one per open Pot, held here; its tab draws `settingsState`,
    /// set after each of its actions (only they change it).
    @ObservationIgnored private var potSettings: PotSettings?
    @ObservationIgnored private var potSettingsOf: String?
    private(set) var settingsState: PotSettings.State?

    /// The open Pot's settings model, made anew when another Pot is open.
    @discardableResult func openSettings() -> PotSettings? {
        guard let ref = pot?.ref, pot?.view != nil else { return nil }
        if potSettingsOf != ref.nodeId {
            potSettings = potModel?.settings()
            potSettingsOf = ref.nodeId
            settingsState = potSettings?.current
        }
        return potSettings
    }

    /// One change of the open Pot's settings or people (`op`, a `PotSettings` action); true once it was stored. A new
    /// base currency is said on the Pot's row at once.
    func settingsWrite(_ op: @escaping @MainActor (PotSettings) async throws -> KotlinBoolean) async -> Bool {
        guard let s = openSettings(), let pots = potModel, let ref = pot?.ref else { return false }
        let stored = (try? await op(s))?.boolValue ?? false
        settingsState = s.current
        syncPot()
        if let facts = pots.rowDetails() { rowDetails[ref.nodeId] = facts }
        return stored
    }

    /// A person's erase asked (core's state: done only by `settingsWrite { try await $0.erase() }`).
    func askErase(_ personId: String) { openSettings()?.askErase(personId: personId); settingsState = potSettings?.current }

    func cancelErase() { potSettings?.cancelAsk(); settingsState = potSettings?.current }

    /// "New pot" (core's `PotCreateSheet`) open in the app, watched here (its members arrive on their own); its sheet draws
    /// `createState`.
    @ObservationIgnored private var createSheet: PotCreateSheet?
    @ObservationIgnored private var createWatch: PotCreateSheet.Watch?
    private(set) var createState: PotCreateSheet.State?

    /// Opens "New pot" at the shell's place now, in the person's language's currency.
    func openCreateSheet() {
        closeCreateSheet()
        guard let shellModel, let s = potModel?.createSheet(shell: shellModel, language: Locale.current.language.languageCode?.identifier ?? "") else { return }
        createSheet = s
        createState = s.current
        createWatch = s.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.createState = self?.createSheet?.current }
        }
    }

    func createSpace(_ scopeId: String) { createSheet?.space(scopeId: scopeId); createState = createSheet?.current }
    func createCurrency(_ code: String) { createSheet?.currency(code: code); createState = createSheet?.current }
    func createAddMember(_ userId: String) { createSheet?.addMember(userId: userId); createState = createSheet?.current }
    func createRemove(_ index: Int) { createSheet?.remove(index: Int32(index)); createState = createSheet?.current }

    /// A name typed added; true when there was one (the field is emptied).
    func createAddTyped(_ text: String) -> Bool {
        let added = createSheet?.addTyped(text: text) ?? false
        createState = createSheet?.current
        return added
    }

    func canCreatePot(_ name: String) -> Bool { createSheet?.canCreate(name: name) ?? false }

    /// Creates the Pot named `name` (a person still `typed` joins first) and opens it; true once it exists.
    func createPot(name: String, typed: String) async -> Bool {
        guard let s = createSheet, let shellModel else { return false }
        let created = (try? await s.create(name: name, typed: typed))?.boolValue ?? false
        createState = s.current
        if created {
            applyShell(start: false)
            await readDetails(shellModel.tenant)
        }
        return created
    }

    /// Today by the core's clock (`yyyy-MM-dd`): the editor's date picker falls back to it.
    var today: String { potModel?.today() ?? "" }

    func closeCreateSheet() {
        createWatch?.stop()
        createWatch = nil
        createSheet?.close()
        createSheet = nil
        createState = nil
    }

    /// The entry editor open in the app (core's `EntryEditor` over the Pot's model), watched by this model, not by its
    /// sheet: what the sheet draws is `editorState`, set at once after each edit (a field never lags what was typed) and
    /// from the watch for what the core changes itself (the rate's fill).
    @ObservationIgnored private var editor: EntryEditor?
    @ObservationIgnored private var editorWatch: EntryEditor.Watch?
    private(set) var editorState: EntryEditor.State?

    /// Opens the editor of `entryId` in the open Pot (nil: a new entry) with `pending` as its receipts; false when the
    /// Pot cannot take one.
    func openEditor(_ entryId: String?, pending: [PotSession.PendingReceipt] = []) -> Bool {
        closeEditor()
        guard let e = potModel?.editor(entryId: entryId, separator: decimalSeparator, pending: pending) else { return false }
        editor = e
        editorState = e.current
        editorWatch = e.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.editorState = self?.editor?.current }
        }
        return true
    }

    func edit(_ change: EditorChange) {
        editor?.change(change: change)
        editorState = editor?.current
    }

    func addReceipts(_ picked: [EntryEditor.Picked]) {
        editor?.addReceipts(picked: picked)
        editorState = editor?.current
    }

    func dropReceipt(_ id: String) {
        editor?.dropReceipt(id: id)
        editorState = editor?.current
    }

    /// Saves the entry: done (close the editor, saying what the answer says), or not (the editor says why).
    func saveEntry() async -> EntryEditor.Saved? {
        guard let e = editor else { return nil }
        let saved = try? await e.save()
        editorState = e.current
        return saved
    }

    func askDelete() { editor?.askDelete(); editorState = editor?.current }
    func cancelAsk() { editor?.cancelAsk(); editorState = editor?.current }

    /// Deletes the entry once asked; true when it was.
    func deleteEntry() async -> Bool {
        guard let e = editor else { return false }
        let done = (try? await e.delete())?.boolValue ?? false
        editorState = e.current
        return done
    }

    func closeEditor() {
        editorWatch?.stop()
        editorWatch = nil
        editor?.close()
        editor = nil
        editorState = nil
    }


    /// A receipt of the open Pot opened to its bytes, or nil (blocked, or it did not read).
    /// Writes the open Pot `ref` as the web's ZIP (uc.platform.export-import, ADR 0082) to `path` (core's `FileSink`, which
    /// removes a download that did not finish); nil when it did not complete.
    func downloadPot(_ ref: ObjectRef, to path: String) async -> [NodeExport.Skipped]? {
        guard let session = potSession, pot?.ref == ref else { return nil }
        let written = try? await FileDownloads.shared.pot(
            session: session, deletedName: say(.potDeletedPerson), nowMs: Int64(Date().timeIntervalSince1970 * 1000), path: path
        )
        return written == nil ? nil : []
    }

    /// Writes node `ref`'s download to `path` (core's `ShellModel.download` into its `FileSink`: a file the file itself, any
    /// other node with everything under it as the web's ZIP); what it left out, nil when it did not finish (nothing left).
    func downloadNode(_ ref: NodeAt, to path: String) async -> [NodeExport.Skipped]? {
        guard let m = shellModel else { return nil }
        return try? await FileDownloads.shared.subtree(
            shell: m, ref: ref, deletedName: say(.potDeletedPerson), nowMs: Int64(Date().timeIntervalSince1970 * 1000), path: path
        )
    }

    func openReceipt(_ receiptId: String) async -> Data? {
        guard let session = potSession else { return nil }
        return (try? await AppleSessions.shared.openReceipt(pot: session, receiptId: receiptId)) ?? nil
    }


    /// Forgets the open Pot's last refusal (a sheet closed).
    func clearPotMessage() { potModel?.clearMessage(); syncPot() }

    func choose(_ app: ApplicationId?) { moveShell { $0.choose(app: app) }; drawerOpen = false }
    func switchSpace(_ scopeId: String) { moveShell { $0.switchSpace(scopeId: scopeId) } }
    func enter(_ folder: FolderRef) { moveShell { $0.enter(folder: folder) } }
    func setFilter(_ app: ApplicationId?) { moveShell { $0.filter(app: app) } }
    func goBack() { moveShell { $0.goBack() } }

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
    func signOutFamily(_ familyId: String) { onSettings { try await $0.signOut(familyId: familyId) } }
    func signOutOthers() { onSettings { try await $0.signOutOthers(); return nil } }
    func signOutAndRemoveKey(_ familyId: String) { onSettings { try await $0.signOutAndRemoveKey(familyId: familyId); return nil } }
    func removeSavedKey(_ deviceId: String) { onSettings { try await $0.removeSavedKey(deviceId: deviceId); return nil } }
    func removeEveryOtherSavedKey() { onSettings { try await $0.removeEveryOtherSavedKey(); return nil } }
    func stopTrusting() {
        onSettings { [weak self] m in
            try await m.stopTrusting()
            self?.trustLog("settings stop trusting")
            return nil
        }
    }
    func signOutHere() { onSettings { try await $0.signOutHere() } }
    func setOfflineLimit(_ amount: String, _ unit: LimitUnit) { onSettings { try await $0.setOfflineLimit(amount: amount, unit: unit); return nil } }
    func turnOfflineLimitOff() { onSettings { try await $0.turnOfflineLimitOff(); return nil } }
    func clearOfflineCache() { onSettings { try await $0.clearOfflineCache(); return nil } }
    func unpinOffline(_ node: String) { onSettings { try await $0.unpinOffline(node: node); return nil } }
    func askRemoveAllOffline() { settingsModel?.askRemoveAllOffline(); syncSettings() }
    func cancelRemoveAllOffline() { settingsModel?.cancelRemoveAllOffline(); syncSettings() }
    func removeAllOffline() { onSettings { try await $0.removeAllOffline(); return nil } }

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
