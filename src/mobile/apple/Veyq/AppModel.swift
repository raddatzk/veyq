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
        case signIn(working: Bool, error: Msg?, adding: Bool, server: String?, email: String?)
        /// An account this device trusts: the password alone, or the person's authentication.
        case unlock(record: TrustedDevice, working: Bool, error: Msg?)
        /// In the share extension with no account this device trusts: the app signs in, not the extension (ADR 0131).
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
    /// "Manage servers", shown as a sheet while non-nil.
    var managing: [ManagedServer]?
    var drawerOpen = false

    private let device: ThisDevice
    /// The share extension's model (ADR 0131): it signs in only from what the app left and moves nothing of the app's.
    let inExtension: Bool

    private static func makeDevice(inExtension: Bool) -> ThisDevice {
        #if os(iOS)
        let label = UIDevice.current.model
        #else
        let label = Host.current().localizedName ?? "Mac"
        #endif
        let keychain = KeychainStore(movesLegacy: !inExtension)
        if !inExtension {
            // What the share extension reads too lives in the app group (ADR 0131); an earlier installation's move there.
            Shared.moveIntoGroup()
            // A first start after an installation: the Keychain may still hold an earlier installation's items.
            if !Shared.defaults.bool(forKey: "veyq.installed") {
                keychain.removeAll()
                Shared.defaults.set(true, forKey: "veyq.installed")
            }
        }
        return AppleSessions.shared.device(keychain: keychain, label: label, prompt: say(.deviceUnlockPrompt), defaults: Shared.defaults)
    }
    private let recent: RecentUses
    private let hidden: Hidden

    /// The current tenant as read, before what this device hides is taken out.
    /// The shell over the current tenant (core's `ShellModel`): its lists, where the person is, the actions on a node.
    @ObservationIgnored private var shellModel: ShellModel?
    @ObservationIgnored private var shellWatch: ShellModel.Watch?
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
        if !(session.current is SessionModelGate.Signed) { offer = nil }
        switch session.current {
        case let g as SessionModelGate.Signed: trustLog("gate Signed, offer: \(g.offer.map { "\($0.levels)" } ?? "none")")
        case let g as SessionModelGate.Unlock: trustLog("gate Unlock, \(g.record.method), level: \(g.record.unlockLevel.map { "\($0)" } ?? "none")")
        default: trustLog("gate \(type(of: session.current))")
        }
        switch session.current {
        case let g as SessionModelGate.SignIn:
            screen = .signIn(working: g.working, error: g.error, adding: g.adding, server: g.server, email: g.email)
        case let g as SessionModelGate.Unlock:
            screen = .unlock(record: g.record, working: g.working, error: g.error)
        case is SessionModelGate.NeedsApp:
            screen = .needsApp
        case let g as SessionModelGate.Signed:
            switching = g.switching
            offer = g.offer
            if shellModel != nil, shellModel?.tenant === session.signedTo?.session { applyShell(start: false); screen = .signed }
        default:
            screen = .starting
        }
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
                closeDepot()
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
        if let was = tenantSession, was.account !== account || was.tenant.tenantId != tenant.tenantId { uploads.clear() }
        tenantSession = t
        let pots = PotModel(tenant: t, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        potWatch?.stop()
        potModel = pots
        potWatch = pots.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.syncPot() }
        }
        pot = nil
        let model = ShellModel(tenant: t, hidden: hidden, recent: recent, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        shellWatch?.stop()
        shellModel = model
        openDepot(model, t)
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
        shell = state.shell
        if start { drawerOpen = shell.drawerOpensAtStart }
    }

    /// Whether "New" is offered where the shell is (core's `ShellModel.canCreateHere`).
    var canCreateHere: Bool { _ = shell; return shellModel?.canCreateHere ?? false }

    /// The shell moved by `change` (its transitions are `Shell`'s), at once.
    private func moveShell(_ change: @escaping (Shell) -> Shell) {
        shellModel?.update(change: change)
        applyShell(start: false)
    }

    // MARK: What another app shared (ADR 0131)

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
    /// they go (ADR 0131 decision 1) — the start does the same while the session is still opening.
    func receiveShare(_ incoming: Incoming) {
        moveShell { Shell.companion.start(lastUsed: nil, space: $0.space) }
        inbox.receive(incoming: incoming)
        syncInbox()
    }

    /// Files dropped onto the app (ADR 0131 decision 9): an open Pot that takes them opens its new expense with them at once;
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

    private func refreshLists(_ t: TenantSession) async {
        try? await shellModel?.refresh()
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

    /// Memory or chapter `nodeId`'s gallery (`MemoryMedia.list`); nil when it could not be read.
    func memoryGallery(scopeId: String, nodeId: String) async -> MemoryMedia.Gallery? {
        guard let t = tenantSession else { return nil }
        return try? await MemoryMedia.shared.list(tenant: t, scopeId: scopeId, nodeId: nodeId)
    }

    /// Decoded tiers by blob id (a gallery asks for every tile as it scrolls past; the cache lets the system evict).
    private let mediaPictures = NSCache<NSString, CGImageBox>()

    /// `blob` of `item` read and decoded as a picture (a tile, a preview); nil where it does not read or is no picture.
    func mediaPicture(_ item: MemoryMedia.Item, blob: MemoryMedia.Blob) async -> CGImage? {
        if let hit = mediaPictures.object(forKey: blob.blobId as NSString) { return hit.image }
        guard let t = tenantSession,
              let data = try? await AppleMemories.shared.read(tenant: t, item: item, blob: blob),
              let image = CoverImages.image(data as Data, maxPixels: 4096) else { return nil }
        mediaPictures.setObject(CGImageBox(image), forKey: blob.blobId as NSString)
        return image
    }

    /// `item`'s heavy EXIF tier (`MediaDetails.exif`); nil where none was sealed or it could not be read.
    func mediaExif(_ item: MemoryMedia.Item) async -> MediaDetails.Exif? {
        guard let t = tenantSession else { return nil }
        return try? await MediaDetails.shared.exif(tenant: t, item: item)
    }

    /// Who added each of `items`, by owner key (`MediaDetails.uploaders`); empty where it could not be read.
    func mediaUploaders(scopeId: String, items: [MemoryMedia.Item]) async -> [String: MediaDetails.Uploader] {
        guard let t = tenantSession else { return [:] }
        return (try? await MediaDetails.shared.uploaders(tenant: t, scopeId: scopeId, items: items)) ?? [:]
    }

    /// `item`'s original written to a temporary file under `name`, for the system's save sheet; nil where it did not read.
    func downloadMedium(_ item: MemoryMedia.Item, name: String) async -> URL? {
        guard let t = tenantSession, let dir = Self.downloadDir() else { return nil }
        let url = dir.appendingPathComponent(name)
        guard (try? await AppleMemories.shared.download(tenant: t, item: item, path: url.path))?.boolValue == true else {
            try? FileManager.default.removeItem(at: dir)
            return nil
        }
        return url
    }

    /// Moves each time a medium this device uploaded is in (a gallery on screen reads itself again).
    private(set) var galleryRevision = 0

    /// Queues the picked photos and videos (`urls`; `fromPhotos`: copies the Photos picker handed over, whose own dates are
    /// no capture time, removed once each is in or cancelled) for Memory or chapter `nodeId` (`label` its name;
    /// `MediaUpload.Job`): each one's tiers derived here (`MediaTiers`) while the one before it is on the wire; each one in
    /// moves the gallery on screen, the batch's end the lists (the Memory's date range).
    func uploadMedia(scopeId: String, nodeId: String, label: String, urls: [URL], fromPhotos: Bool) {
        guard let t = tenantSession else { return }
        let jobs: [UploadQueueJob] = urls.compactMap { url in
            guard let p = Picked(url) else { return nil }
            return AppleMemories.shared.uploadJob(
                tenant: t, scopeId: scopeId, nodeId: nodeId, url: url, name: p.name, size: p.size, type: p.type,
                modifiedAt: fromPhotos ? nil : p.modifiedAt.map { KotlinLong(value: $0) }, deriver: MediaDeriver.shared
            )
        }
        uploads.enqueue(jobs: jobs, label: label, key: nodeId, onSettled: { @Sendable item in
            let done = item.status == .done
            // a copy is kept while its upload may still be retried
            if fromPhotos, done || item.status == .cancelled, let job = item.job as? MediaUpload.Job,
               let url = AppleMemories.shared.urlOf(source: job.picked) {
                try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
            }
            Task { @MainActor [weak self] in if done { self?.galleryRevision += 1 } }
        }, onFinished: { @Sendable _ in
            Task { @MainActor [weak self] in
                guard let self, let t = self.tenantSession else { return }
                await self.refreshLists(t)
            }
        })
    }

    /// `item`'s original read at any offset, for a player (`MemoryMedia.reader`); nil where it does not open here.
    func mediaReader(_ item: MemoryMedia.Item) -> RangeReader? {
        guard let t = tenantSession else { return nil }
        return MemoryMedia.shared.reader(tenant: t, item: item, blob: item.original)
    }

    /// Every node of `scopeId` this device opens (a Memory's chapters).
    func scopeNodes(_ scopeId: String) async -> [NodeRef] {
        (try? await shellModel?.scopeNodes(scopeId: scopeId)) ?? []
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
        rowDetails = read.details.mapValues { $0.map { say(piece: $0) } }
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

    /// Where a download waits for the system's save sheet (iOS's `fileMover`): removed once the sheet is done and at start.
    static var downloadsDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("downloads", isDirectory: true) }

    /// A new directory of its own under `downloadsDir` for one download; nil where it could not be made.
    static func downloadDir() -> URL? {
        let dir = downloadsDir.appendingPathComponent(UUID().uuidString, isDirectory: true)
        return (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil ? dir : nil
    }

    /// Every decrypted copy the app wrote for the person — a copy sent, a download for the save sheet — swept at start: what
    /// a crash or a sheet left behind is not kept (ADR 0047: no plaintext at rest but where the person saves it).
    private static func sweepTemporary() {
        try? FileManager.default.removeItem(at: sharedDir)
        try? FileManager.default.removeItem(at: downloadsDir)
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

    /// What the drawer's "New" asked Home to create, until Home opens its dialog.
    enum NewKind { case folder, pot }
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
