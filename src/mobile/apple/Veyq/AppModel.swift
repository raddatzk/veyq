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
        /// In the share extension with no account this device trusts: the app signs in, not the extension (ADR 0129).
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
    /// The share extension's model (ADR 0129): it signs in only from what the app left and moves nothing of the app's.
    let inExtension: Bool

    private static func makeDevice(inExtension: Bool) -> ThisDevice {
        #if os(iOS)
        let label = UIDevice.current.model
        #else
        let label = Host.current().localizedName ?? "Mac"
        #endif
        let keychain = KeychainStore(movesLegacy: !inExtension)
        if !inExtension {
            // What the share extension reads too lives in the app group (ADR 0129); an earlier installation's move there.
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
            persist: { keys in Shared.defaults.set(keys.map { $0.encode() }, forKey: "veyq.recent") },
            initial: stored.compactMap { UseKey.companion.decode(line: $0) },
            keep: 50
        )
        hidden = Hidden(
            persist: { keys in Shared.defaults.set(Array(keys), forKey: "veyq.hidden") },
            initial: Set(Shared.defaults.stringArray(forKey: "veyq.hidden") ?? [])
        )
        session = AppleSessions.shared.sessionModel(device: device, hidden: hidden, recent: recent, signInElsewhere: inExtension)
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
        switch session.current {
        case let g as SessionModelGate.SignIn:
            screen = .signIn(working: g.working, error: g.error, adding: g.adding, server: g.server, email: g.email)
        case let g as SessionModelGate.Unlock:
            screen = .unlock(record: g.record, working: g.working, error: g.error)
        case is SessionModelGate.NeedsApp:
            screen = .needsApp
        case let g as SessionModelGate.Signed:
            switching = g.switching
            if shellModel != nil, shellModel?.tenant === session.signedTo?.session { applyShell(start: false); screen = .signed }
        default:
            screen = .starting
        }
    }

    /// The debug build's unlock (`devlogin.py --unlock`): the password for the unlock screen, now or once it shows.
    func devUnlock(password: String) {
        Task { if let opened = try? await session.devUnlock(password: password) { try? await show(opened) } }
    }

    /// The full sign-in, then this device trusted with the account: staying signed in when `remember` (which asks the
    /// person to authenticate; declined, the password trust instead), else the password trust.
    func signIn(server: String, email: String, password: String, secretKey: String, remember: Bool = false) {
        Task {
            if let opened = try? await session.signIn(server: server, email: email, password: password, secretKey: secretKey, remember: remember) {
                try? await show(opened)
            }
        }
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
            } else if managing != nil {
                managing = managed()
            }
        }
    }

    /// Ends this device's trust in `account`: the next sign-in here asks for the Secret Key again.
    func forgetDevice(_ account: VeyqAccount) {
        Task {
            try? await session.forgetDevice(account: account)
            if managing != nil { managing = managed() }
        }
    }

    /// "Manage servers" as core lists it, for SwiftUI's lists.
    private func managed() -> [ManagedServer] {
        session.managed().map { c in
            ManagedServer(account: c.account, tenants: c.tenants.map { t in
                ManagedTenant(tenant: t.tenant, hidden: t.hidden, current: t.current, spaces: t.spaces?.map {
                    ManagedSpace(space: $0.space, hidden: $0.hidden, canHide: $0.canHide)
                }, shown: t.shown, switchable: t.switchable, subtitle: say(piece: t.subtitle))
            }, trusted: c.trusted)
        }
    }

    // MARK: The current tenant

    private func show(_ opened: Opened) async throws {
        let account = opened.account
        let tenant = opened.tenant
        let t = opened.session
        // a member's write pulls its path forward, and the scopes served as re-keying are finished in the background
        t.rekeyRules = AppKinds.shared.rules
        if let was = tenantSession, was.account !== account || was.tenant.tenantId != tenant.tenantId { uploads.clear() }
        watchUploads()
        tenantSession = t
        resumeRekeys(t)
        let pots = PotModel(tenant: t, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        potWatch?.stop()
        potModel = pots
        potWatch = pots.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.syncPot() }
        }
        pot = nil
        reportOffered = false
        Task { await readReportOffered() }
        let model = ShellModel(tenant: t, hidden: hidden, recent: recent, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        shellWatch?.stop()
        shellModel = model
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

    // MARK: What another app shared (ADR 0129)

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
    /// they go (ADR 0129 decision 1) — the start does the same while the session is still opening.
    func receiveShare(_ incoming: Incoming) {
        moveShell { Shell.companion.start(lastUsed: nil, space: $0.space) }
        inbox.receive(incoming: incoming)
        syncInbox()
    }

    /// Files dropped onto the app (ADR 0129 decision 9): an open Pot that takes them opens its new expense with them at once;
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

    /// Whether `ref` is pinned; nil while its space's pin set is not loaded (no heart is offered then).
    /// Whether `ref` is a guest's identity folder or lies in one (core's answer over the tree last read).
    func inIdentityFolder(_ ref: NodeAt) -> Bool { tenantSession?.inIdentityFolder(scopeId: ref.scopeId, nodeId: ref.nodeId) ?? false }

    func isFavorite(_ ref: NodeAt) -> Bool? { tenantSession?.isFavorite(scopeId: ref.scopeId, nodeId: ref.nodeId)?.boolValue }

    /// Pins or unpins `ref`, then the lists are read anew; false when it was not stored (the heart goes back).
    func toggleFavorite(_ ref: NodeAt) async -> Bool {
        guard let m = shellModel else { return false }
        let stored = (try? await m.toggleFavorite(ref: ref))?.boolValue ?? false
        await readDetails(m.tenant)
        applyShell(start: false)
        return stored
    }

    private func refreshLists(_ t: TenantSession) async {
        try? await shellModel?.refresh()
        applyShell(start: false)
        await readDetails(t)
    }

    /// What a delete says at the bottom of the shell: moved to the trash, with the node as it was for its Undo; or why not.
    struct TrashNotice: Identifiable {
        let id = UUID()
        let message: String
        let node: OpenNode?
        /// A move's undo: the node moved back the same way, to where it was (the web's `moveRun` Undo).
        var moveBack: MoveBack? = nil
    }

    private(set) var trashNotice: TrashNotice?

    /// Moves `ref` with everything under it to the space's trash; a page whose node went leaves for its parent.
    func trash(_ ref: NodeAt) async {
        guard let m = shellModel, let r = try? await m.trash(ref: ref) else { return }
        if r.gone, let open = pot?.ref, open.nodeId == ref.nodeId { potModel?.close(ref: open); syncPot() }
        applyShell(start: false)
        await readDetails(m.tenant)
        trashNotice = TrashNotice(message: say(notice: r.notice), node: r.undoable ? r.before : nil)
    }

    /// The Undo of the last delete: the node back from the trash under its name, as it was.
    func undoTrash() async {
        if let back = trashNotice?.moveBack {
            trashNotice = nil
            if await move(back.ref, to: back.to, toName: nil, from: nil) != .moved {
                trashNotice = TrashNotice(message: say(.moveUndoFailed, back.ref.name), node: nil)
            }
            return
        }
        guard let notice = trashNotice, let node = notice.node, let m = shellModel else { return }
        trashNotice = nil
        var failed: VeyqCore.Notice?
        do { failed = try await m.undoTrash(node: node) } catch { failed = Notices.shared.trashUndoFailed(name: node.name) }
        applyShell(start: false)
        await readDetails(m.tenant)
        if let failed { trashNotice = TrashNotice(message: say(notice: failed), node: nil) }
    }

    func dismissTrashNotice(_ id: UUID) { if trashNotice?.id == id { trashNotice = nil } }

    // MARK: A report (core's NodeReport)

    /// Whether the tenant's server takes reports (it publishes a moderation key): Report is offered only then.
    private(set) var reportOffered = false

    /// Asks the server once per tenant whether it takes reports.
    func readReportOffered() async {
        guard let t = tenantSession else { return }
        reportOffered = ((try? await NodeReport.shared.published(tenant: t)) ?? nil) != nil
    }

    /// Reports `ref` to the moderators: whole or by its name, in `category` for `reason`, `contact` optional.
    func report(_ ref: NodeAt, scope: NodeReport.Scope, category: String, reason: String, contact: String) async -> NodeReport.Sent {
        let failed = NodeReport.Sent(outcome: .failed, reference: nil, receivedAt: nil)
        guard let t = tenantSession else { return failed }
        return (try? await NodeReport.shared.report(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, scope: scope, category: category, reason: reason, contact: contact
        )) ?? failed
    }

    /// The server's privacy notice at `anchor` (`privacyLink`): the page every place that takes personal data points to.
    func privacyLink(_ anchor: String) -> String { "\(tenantSession?.origin ?? "")/privacy#\(anchor)" }

    // MARK: Guest links (core's ShareLinks)

    /// The tenant's link rules (its capability set): what the share dialog offers and requires.
    func linkRules() -> ShareLinks.Rules { tenantSession?.linkRules ?? ShareLinks.RulesCompanion.shared.UNKNOWN }

    /// The READ and READ_WRITE links on `ref`, newest first; nil when they do not list.
    func shareLinks(_ ref: NodeAt) async -> [ShareLinks.Link]? {
        guard let t = tenantSession else { return nil }
        return try? await ShareLinks.shared.list(tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, writeOnly: ref.kind == DepotFiles.shared.DROPBOX)
    }

    /// Mints a `role` link on `ref`; on success its address, to hand out once.
    func mintLink(_ ref: NodeAt, role: String, label: String, password: String, expiresAt: String?) async -> ShareLinks.Minted {
        let failed = ShareLinks.Minted(outcome: .failed, url: nil, refusal: nil)
        guard let t = tenantSession else { return failed }
        return (try? await ShareLinks.shared.mint(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, role: role, label: label, password: password, expiresAt: expiresAt,
            waitsMs: NodeEpochWrite.shared.REKEY_WAITS_MS
        )) ?? failed
    }

    /// Switches link `shareId` on `ref` off and re-keys the subtree below it (core's `ShareLinks.switchOff`, each kind's
    /// content through `AppKinds`); an open Pot is read anew, since its key may have changed.
    func switchOffLink(_ ref: NodeAt, shareId: String) async -> ShareLinks.Closed {
        guard let t = tenantSession else { return .failed }
        let r = (try? await ShareLinks.shared.switchOff(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, shareId: shareId, kinds: AppKinds.shared.rekeys,
            tokenInput: { @Sendable kind, name in AppModel.tokenInputFor(kind: kind, name: name) },
            uuid: { @Sendable in UUID().uuidString.lowercased() }
        )) ?? .failed
        await potRekeyed()
        return r
    }

    /// Finishes, in the background, the re-key of every scope `t` was served as re-keying (ADR 0114 decision 9: a link's close
    /// or a rotation another device began, or this one left when it closed): what this device can, top-down; the lists are
    /// read anew after it. A run that cannot finish leaves the marks for the next start or another device.
    private func resumeRekeys(_ t: TenantSession) {
        guard !t.rekeyingScopes.isEmpty else { return }
        Task {
            let ran = (try? await NodeRekey.shared.resume(tenant: t)) ?? [:]
            if !ran.isEmpty, tenantSession === t {
                await potRekeyed()
                await refreshLists(t)
            }
        }
    }

    /// Edits one of this member's open links on `ref` in place: label, password (nil untouched, blank removed), end, role.
    func editLink(_ ref: NodeAt, shareId: String, edit: ShareLinkChanges.Edit) async -> ShareLinkChanges.Changed {
        let failed = ShareLinkChanges.Changed(outcome: .failed, url: nil)
        guard let t = tenantSession else { return failed }
        return (try? await ShareLinkChanges.shared.edit(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, shareId: shareId, edit: edit, nowMs: Int64(Date().timeIntervalSince1970 * 1000)
        )) ?? failed
    }

    /// Renews link `shareId` on `ref`: a new address carrying its settings, the old one closed and the node re-keyed.
    /// Switches link `shareId` on `ref` on again, its address kept (`ShareLinkChanges.reactivate`).
    func reactivateLink(_ ref: NodeAt, shareId: String) async -> ShareLinkChanges.Changed {
        let failed = ShareLinkChanges.Changed(outcome: .failed, url: nil)
        guard let t = tenantSession else { return failed }
        return (try? await ShareLinkChanges.shared.reactivate(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, shareId: shareId, waitsMs: NodeEpochWrite.shared.REKEY_WAITS_MS
        )) ?? failed
    }

    func renewLink(_ ref: NodeAt, shareId: String) async -> ShareLinkChanges.Changed {
        let failed = ShareLinkChanges.Changed(outcome: .failed, url: nil)
        guard let t = tenantSession else { return failed }
        let r = (try? await ShareLinkChanges.shared.renew(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, shareId: shareId, kinds: AppKinds.shared.rekeys,
            tokenInput: { @Sendable kind, name in AppModel.tokenInputFor(kind: kind, name: name) },
            uuid: { @Sendable in UUID().uuidString.lowercased() }, nowMs: Int64(Date().timeIntervalSince1970 * 1000),
            waitsMs: NodeEpochWrite.shared.REKEY_WAITS_MS
        )) ?? failed
        await potRekeyed()
        return r
    }

    /// Deletes link `shareId` on `ref` for good.
    func deleteLink(_ ref: NodeAt, shareId: String) async -> ShareLinkChanges.Changed {
        let failed = ShareLinkChanges.Changed(outcome: .failed, url: nil)
        guard let t = tenantSession else { return failed }
        let r = (try? await ShareLinkChanges.shared.delete(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, shareId: shareId, kinds: AppKinds.shared.rekeys,
            tokenInput: { @Sendable kind, name in AppModel.tokenInputFor(kind: kind, name: name) },
            uuid: { @Sendable in UUID().uuidString.lowercased() }, nowMs: Int64(Date().timeIntervalSince1970 * 1000)
        )) ?? failed
        await potRekeyed()
        return r
    }

    /// The open Pot read anew under its key, after a re-key that may have reached it: a link on it or on a folder above it
    /// closed, or a scope's re-key finished in the background. A Pot the re-key did not reach reads the same.
    private func potRekeyed() async {
        try? await potModel?.rekeyed()
        syncPot()
    }

    // MARK: A move (core's NodeMove)

    /// Where a move's undo puts the node back.
    struct MoveBack {
        let ref: NodeAt
        let to: String?
    }

    /// Each kind's placement, as the move browser and the move ask it.
    let placement = KindPlacement()

    /// Every node of `scopeId` this device opens: what the move browser walks.
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
        guard let t = tenantSession else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else { return nil }
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

    func scopeNodes(_ scopeId: String) async -> [NodeRef] {
        (try? await shellModel?.scopeNodes(scopeId: scopeId)) ?? []
    }

    /// Moves `ref` under `to` (nil: the space's root), its name kept; the lists are read anew. With `toName`, the bottom of
    /// the shell says where it went and offers its undo back to `from`.
    func move(_ ref: NodeAt, to: String?, toName: String?, from: String?) async -> NodeMove.Outcome {
        guard let m = shellModel else { return .failed }
        let r = try? await m.move(ref: ref, to: to)
        applyShell(start: false)
        await readDetails(m.tenant)
        let outcome = r?.result.outcome ?? .failed
        if outcome == .moved, let toName {
            trashNotice = TrashNotice(
                message: say(.moveMoved, ref.name, toName), node: nil, moveBack: MoveBack(ref: ref, to: from)
            )
        }
        return outcome
    }

    /// Copies the open Pot `ref` to `to` (nil: the space's root) in its space (uc.platform.copy-node): a new Pot under fresh
    /// keys with its people, live entries and receipts; the lists are read anew and the shell says where it went.
    func copyPot(_ ref: NodeAt, to: String?, toName: String) async -> PotCopy.Outcome {
        guard let pots = potModel, let shellModel, let open = pot?.ref, open.nodeId == ref.nodeId else { return .failed }
        let r = try? await pots.duplicate(shell: shellModel, ref: open, to: to, deletedName: say(.potDeletedPerson), uuid: { @Sendable in UUID().uuidString.lowercased() })
        applyShell(start: false)
        await readDetails(shellModel.tenant)
        let outcome = r?.outcome ?? .failed
        if outcome == .copied {
            trashNotice = TrashNotice(message: say(.theCopyDone, ref.name, toName), node: nil, moveBack: nil)
        }
        return outcome
    }

    /// Renames `ref` to `name` (core's `NodeRename`: the placement, then its open links' names); the lists are read anew
    /// and an open page follows the node under its new name. Why it did not land, in core's words, or nil once renamed;
    /// `linksFailed` says a link kept the old name (the rename stands).
    func rename(_ ref: NodeAt, to name: String) async -> (problem: String?, linksFailed: Bool) {
        guard let m = shellModel, let r = try? await m.rename(ref: ref, name: name) else { return (say(.nodeSaveFailed), false) }
        applyShell(start: false)
        await readDetails(m.tenant)
        return (r.notice.map { say(notice: $0) }, r.linksFailed)
    }

    /// How a kind folds a name to its slug token's input: the composition root's answer (`AppKinds`, the web's
    /// `registry.tokenInputFor`). Nil for a kind the app does not know: nothing is placed under it.
    /// Free of the main actor: core calls it from its own threads, after a request answered (a re-key, a placement).
    nonisolated static func tokenInputFor(kind: String, name: String) -> String? {
        AppKinds.shared.tokenInput(kind: kind, name: name)
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

    /// "Add files only" on folder `folder`: a drop box named `name` in it with its first link (`DropBoxes.create`); the lists
    /// are read anew.
    func createDropBox(_ folder: NodeAt, name: String, password: String, expiresAt: String?) async -> DropBoxes.Created {
        await createDropBox(scopeId: folder.scopeId, parentId: folder.nodeId, name: name, password: password, expiresAt: expiresAt)
    }

    /// "Add files only" at `parentId` of `scopeId` (nil: the space's top level, the web's level share).
    func createDropBox(scopeId: String, parentId: String?, name: String, password: String, expiresAt: String?) async -> DropBoxes.Created {
        guard let t = tenantSession else { return DropBoxes.Created(outcome: .failed, nodeId: nil, url: nil, minted: nil) }
        let r = (try? await DropBoxes.shared.create(tenant: t, scopeId: scopeId, parentId: parentId, name: name, password: password, expiresAt: expiresAt))
            ?? DropBoxes.Created(outcome: .failed, nodeId: nil, url: nil, minted: nil)
        await refreshLists(t)
        return r
    }

    /// Drop box `nodeId`'s guests' folders with their files (`DropBoxes.folders`); nil when they could not be read.
    func dropBoxFolders(scopeId: String, nodeId: String) async -> [DropBoxes.GuestFolder]? {
        guard let t = tenantSession else { return nil }
        return try? await DropBoxes.shared.folders(tenant: t, scopeId: scopeId, dropBoxId: nodeId)
    }

    /// Every file of the tenant with its content's reference and opened meta, by node id (Depot's `DepotFiles.list`).
    private(set) var files: [String: DepotFiles.File] = [:]

    /// What the rows read last (core's `RowDetails`): a part that does not read next time keeps these.
    @ObservationIgnored private var rows = RowDetails.ReadCompanion.shared.EMPTY

    /// The rows' facts and the lists' covers, read anew (core's `RowDetails`: Pots, files, drop boxes, cover keys).
    private func readDetails(_ t: TenantSession, reset: Bool = false) async {
        if reset { rows = RowDetails.ReadCompanion.shared.EMPTY }
        guard let read = try? await RowDetails.shared.read(tenant: t, previous: rows), tenantSession === t else { return }
        rows = read
        files = read.files
        rowDetails = read.details.mapValues { $0.map { say(piece: $0) } }
        listsRevision += 1
        await readCovers(t, named: read.covers)
    }

    /// A file page's facts: size, the sealed type (a claim, shown as such), modified (core's `DepotFiles.facts`).
    func fileFacts(_ f: DepotFiles.File) -> String {
        DepotFiles.shared.facts(f: f).map { say(piece: $0) }.joined(separator: " · ")
    }

    /// What a file's page shows (core's `FileView`): its first frame read and decided by its bytes, then what that needs.
    /// Who uploaded `f`'s content (Depot's `uploaderOf`), as its page names it; nil where nobody is to be named.
    func uploaderText(_ f: DepotFiles.File) async -> String? {
        guard let t = tenantSession, let u = try? await Uploaders.shared.of(tenant: t, file: f) else { return nil }
        return say(notice: Uploaders.shared.words(u: u))
    }

    func fileView(_ f: DepotFiles.File, nodeName: String) async -> FileShown {
        guard let t = tenantSession, let v = try? await AppleDepot.shared.view(tenant: t, file: f, nodeName: nodeName) else { return .failed }
        let d = v.decision
        switch d.kind {
        case .image:
            guard let data = v.data, let image = CoverImages.image(data as Data, maxPixels: 4096) else {
                return .message(say(FileView.shared.message(kind: .unsupported)))
            }
            return .picture(image)
        case .text:
            return .text(v.text ?? "", truncated: d.truncated)
        case .pdf, .media:
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("view-\(UUID().uuidString).\(d.fileExtension ?? "bin")")
            guard (try? await AppleDepot.shared.download(tenant: t, file: f, path: url.path))?.boolValue == true else { return .failed }
            return d.kind == .pdf ? .pdf(url) : .media(url)
        default: return .message(say(FileView.shared.message(kind: d.kind)))
        }
    }

    /// File `ref`'s content written to a temporary file under its download name, for the system's save sheet.
    func downloadFile(_ ref: NodeAt) async -> Downloaded? {
        guard let t = tenantSession, let f = files[ref.nodeId], f.hasContent else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else { return nil }
        let url = dir.appendingPathComponent(DepotFiles.shared.downloadName(file: f, nodeName: ref.name))
        guard (try? await AppleDepot.shared.download(tenant: t, file: f, path: url.path))?.boolValue == true else { return nil }
        return Downloaded(url: url)
    }

    /// Something the shell asks or says once (an upload's refusal, its "Replace anyway"), with its action where it offers one.
    struct Notice: Identifiable {
        let id = UUID()
        let text: String
        var action: String?
        var run: (() async -> Void)?
    }

    var notice: Notice?

    /// The file whose content the person is picking a replacement for (the header's "Replace…"), while they pick.
    var replacing: String?

    /// The file a person is about to send a copy of to another app ("Send a Copy…"), while the shell says first that the
    /// copy leaves veyq decrypted. The data are theirs; the app says what happens and never forbids it.
    var sendingCopy: NodeAt?

    /// A decrypted copy on its way to the system's share sheet, until the sheet is done with it.
    struct SentCopy: Identifiable {
        let url: URL
        var id: URL { url }
    }

    var sentCopy: SentCopy?

    /// Where copies sent to another app wait to be read: cleared before the next one and when the sheet is done.
    private static var sharedDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("shared", isDirectory: true) }

    func clearSentCopies() {
        try? FileManager.default.removeItem(at: Self.sharedDir)
    }

    /// A decrypted copy of file `ref` under its own name (core's `downloadName`), for the system's share sheet.
    func copyForSending(_ ref: NodeAt) async -> SentCopy? {
        guard let t = tenantSession, let f = files[ref.nodeId], f.hasContent else { return nil }
        clearSentCopies()
        let dir = Self.sharedDir.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else { return nil }
        let url = dir.appendingPathComponent(DepotFiles.shared.downloadName(file: f, nodeName: ref.name))
        guard (try? await AppleDepot.shared.download(tenant: t, file: f, path: url.path))?.boolValue == true else { return nil }
        return SentCopy(url: url)
    }

    /// The app's one upload queue (core's `UploadQueue`, the web's `core/blob/queue.ts`): every application's uploads, one
    /// after another, retried once; the shell's uploads button shows it (`UploadsButton`). It outlives a screen, not the
    /// account: a sign-out or a switch of tenant empties it.
    let uploads = UploadQueue()

    /// The queue as it stands, on the main actor (its watch).
    private(set) var uploadState: UploadQueue.State?
    @ObservationIgnored private var uploadWatch: UploadQueue.Watch?

    /// The name of a picked directory whose folders are being made before its files queue up.
    private(set) var preparing: String?

    private func watchUploads() {
        guard uploadWatch == nil else { return }
        uploadWatch = uploads.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in self?.uploadState = state }
        }
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

    /// Queues the picked `urls` as new files under the level shown (`DepotFiles.UploadJob`): a name a file already holds
    /// offers its replace (Depot's `replaceConfirm`).
    func uploadFiles(_ urls: [URL]) {
        guard let t = tenantSession, let scopeId = shell.space else { return }
        let parentId = shell.folder?.nodeId
        let jobs: [UploadQueueJob] = urls.compactMap { url in
            guard let p = Picked(url) else { return nil }
            return AppleDepot.shared.uploadJob(
                tenant: t, scopeId: scopeId, parentId: parentId, url: url, name: p.name, size: p.size, type: p.type,
                modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) }
            )
        }
        enqueueDepot(jobs, label: levelLabel, key: parentId ?? scopeId)
    }

    private func enqueueDepot(_ jobs: [UploadQueueJob], label: String, key: String?) {
        uploads.enqueue(jobs: jobs, label: label, key: key, onSettled: { @Sendable item in
            nonisolated(unsafe) let item = item
            Task { @MainActor [weak self] in await self?.depotSettled(item) }
        }, onFinished: nil)
    }

    /// A Depot item settled: the lists read anew; a name a file holds offers that file's replace, content someone replaced
    /// meanwhile "Replace anyway" — each said once, the item taken out of the tray (its notice says it).
    private func depotSettled(_ item: UploadQueue.Item) async {
        guard let t = tenantSession else { return }
        await refreshLists(t)
        guard let offer = DepotFiles.shared.offerFor(item: item, blobIdOf: { [files] id in files[id]?.blobId }) else { return }
        uploads.remove(id: item.id)
        let nodeId = offer.nodeId
        let source = offer.source
        let expected = offer.expectedBlobId
        notice = Notice(text: say(notice: offer.notice), action: say(offer.action)) { [weak self] in
            self?.replace(nodeId, source: source, expected: expected)
        }
    }

    /// Queues file `nodeId`'s content replaced with `source` over `expected`.
    private func replace(_ nodeId: String, source: UploadSource, expected: String?) {
        guard let t = tenantSession, let f = files[nodeId] else { return }
        enqueueDepot([DepotFiles.ReplaceJob(tenant: t, file: f, source: source, expectedBlobId: expected, name: source.name)], label: source.name, key: nodeId)
    }

    /// Depot's "Upload folder…": the directory at `url` (the system's folder picker) listed here — only its files, hidden ones
    /// included, as the web's directory input holds them —, its folders made under the level shown (`FolderUpload`) and its
    /// files queued as one batch under its name; what could not be made is said, in the web's words.
    func uploadFolder(_ url: URL) async {
        guard let t = tenantSession, let scopeId = shell.space else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        let files = Self.folderFiles(url)
        if scoped { url.stopAccessingSecurityScopedResource() }
        preparing = url.lastPathComponent
        let report = try? await AppleDepot.shared.prepareFolder(
            tenant: t, scopeId: scopeId, parentId: shell.folder?.nodeId, root: url, rootName: url.lastPathComponent, files: files
        )
        preparing = nil
        await refreshLists(t)
        guard let report else { notice = Notice(text: say(.shareFailed)); return }
        let said = Notices.shared.folderUpload(r: report)
        if !said.isEmpty { notice = Notice(text: said.map { say(notice: $0) }.joined(separator: "\n")) }
        if report.outcome == .ready { enqueueDepot(report.jobs, label: report.name, key: report.rootId) }
    }

    /// The files below the directory at `url` (hidden ones included, as the web's directory input holds them), each by its
    /// path below it; a directory enumerator is not iterated in an async context, so this is its own synchronous walk.
    nonisolated private static func folderFiles(_ url: URL) -> [AppleDepot.FolderFile] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey, .contentTypeKey]
        var files: [AppleDepot.FolderFile] = []
        let base = url.standardizedFileURL.path
        if let walk = FileManager.default.enumerator(at: url, includingPropertiesForKeys: keys) {
            for case let file as URL in walk {
                guard let v = try? file.resourceValues(forKeys: Set(keys)), v.isRegularFile == true else { continue }
                let full = file.standardizedFileURL.path
                let relative = full.hasPrefix(base + "/") ? String(full.dropFirst(base.count + 1)) : file.lastPathComponent
                files.append(AppleDepot.FolderFile(
                    relativePath: relative, path: full, size: Int64(v.fileSize ?? 0),
                    type: v.contentType?.preferredMIMEType ?? "application/octet-stream",
                    modifiedAt: v.contentModificationDate.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) }
                ))
            }
        }
        return files
    }

    /// The header's "Replace…": file `nodeId`'s content replaced with the picked file at `url` over `expected`, queued.
    func replaceFile(_ nodeId: String, url: URL, expected: String?) {
        guard let t = tenantSession, let f = files[nodeId], let p = Picked(url) else { return }
        let job = AppleDepot.shared.replaceJob(
            tenant: t, file: f, url: url, name: p.name, size: p.size, type: p.type,
            modifiedAt: p.modifiedAt.map { KotlinLong(value: $0) }, expectedBlobId: expected
        )
        enqueueDepot([job], label: p.name, key: nodeId)
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
               let whole = CoverImages.image(opened.data as Data, maxPixels: 384),
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

    /// Sets the picture `data` as the cover of `ref`, framed by `crop`: made the web's cover JPEG on the device (long edge
    /// 2048, quality 0.85, `COVER_SPEC`) — the whole picture, the crop stored beside it as the web stores it —, uploaded and
    /// attached (core's `NodeCover`); false when it did not land.
    func setCover(_ ref: NodeAt, data: Data, crop: NodeCover.Crop = NodeCover.CropCompanion.shared.FULL) async -> Bool {
        guard let t = tenantSession, let jpeg = CoverImages.jpeg(data, maxPixels: 2048, quality: 0.85) else { return false }
        let outcome = try? await AppleSessions.shared.setCover(tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, jpeg: jpeg, crop: crop)
        await refreshLists(t)
        return outcome == .done
    }

    /// `ref`'s cover as it is stored — the whole picture and its crop — for the cover sheet to frame anew; nil: none.
    func coverSource(_ ref: NodeAt) async -> (image: CGImage, crop: NodeCover.Crop)? {
        guard let t = tenantSession, let opened = (try? await AppleSessions.shared.openFramedCover(tenant: t, nodeId: ref.nodeId)) ?? nil,
              let image = CoverImages.image(opened.data as Data, maxPixels: 2048) else { return nil }
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

    /// Creates a folder named `name` in the space and folder on screen, core's folder (the web's `FolderNameDialog`); the
    /// lists are read anew. Why not, in the web's words, or nil once created.
    func createFolder(name: String) async -> String? {
        guard let m = shellModel, let scopeId = shell.space else { return say(.folderFailed) }
        let refused = try? await m.createFolder(scopeId: scopeId, parentId: shell.folder?.nodeId, name: name)
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

    /// One write to the open Pot (`op` on its session): saving, then the Pot as read after it and a refusal as its message;
    /// true once it was stored.
    func potWrite(_ op: @escaping @MainActor (PotSession) async throws -> PotSession.WriteOutcome) async -> Bool {
        guard let pots = potModel, let ref = pot?.ref, let session = pots.beginWrite() else { return false }
        syncPot()
        let outcome = (try? await op(session)) ?? .refused
        let stored = pots.endWrite(outcome: outcome)
        syncPot()
        // A settings write may have set the base currency: the Pot's row says it at once.
        if let facts = pots.rowDetails() { rowDetails[ref.nodeId] = facts }
        return stored
    }

    /// The "New pot" sheet: saving while it creates, and its refusal (a `PotCreate.Outcome` name).
    struct CreatingPot {
        var saving = false
        var message: String?
    }

    private(set) var creatingPot = CreatingPot()

    /// The current members of `scopeId`, for the "New pot" sheet's people; empty when they do not read.
    func membersOf(_ scopeId: String) async -> [Person] {
        guard let t = tenantSession else { return [] }
        return ((try? await t.scopeMembers(scopeId: scopeId)) ?? nil) ?? []
    }

    /// Creates a Pot named `name` at the root of `scopeId` with `currency` and `people`, reads the tenant's lists again and
    /// opens it; true once it exists (its people may still have failed: then it opens and says so on Settings).
    func createPot(scopeId: String, name: String, currency: String, minorUnits: Int32, people: [PotCreate.NewPerson]) async -> Bool {
        guard let pots = potModel, let shellModel else { return false }
        creatingPot = CreatingPot(saving: true)
        // in the folder on screen, where the sheet creates in the space shown (the web's "New" at a level)
        let parent = PotCreate.shared.parentIn(shell: shell, scopeId: scopeId)
        let created = (try? await pots.create(
            shell: shellModel, scopeId: scopeId, parentId: parent, name: name,
            base: Ledger.BaseCurrency(currency: currency, minorUnits: minorUnits), people: people
        ))?.boolValue ?? false
        let c = pots.creating.value as? PotModel.Creating
        creatingPot = CreatingPot(saving: false, message: c?.message ?? (created ? nil : "REFUSED"))
        applyShell(start: false)
        await readDetails(shellModel.tenant)
        return created
    }

    func clearCreatingPot() { potModel?.clearCreating(); creatingPot = CreatingPot() }

    /// One unit of `currency` in the open Pot's base from today's table, or nil: the rate is typed then.
    func rateFor(_ currency: String) async -> ExchangeRates.Filled? {
        (try? await potModel?.rateFor(currency: currency, day: FormState.dayString(Date()))) ?? nil
    }

    /// A receipt of the open Pot opened to its bytes, or nil (blocked, or it did not read).
    /// Downloads the open Pot `ref` as the web's ZIP (uc.platform.export-import, ADR 0082) into a file of its own in the
    /// temporary directory, named as the web names it, for the system's save sheet; nil when it did not complete.
    func downloadPot(_ ref: ObjectRef) async -> URL? {
        guard let session = potSession, pot?.ref == ref else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else { return nil }
        let url = dir.appendingPathComponent(NodeExport.shared.fileName(name: ref.name))
        let written = try? await FileDownloads.shared.pot(
            session: session, deletedName: say(.potDeletedPerson), nowMs: Int64(Date().timeIntervalSince1970 * 1000), path: url.path
        )
        return written == nil ? nil : url
    }

    /// Why a node was left out of a download or a copy, in the web's words (`export.reason.*`, `node.copy.reason.*`).
    private static func leftOutLine(_ s: NodeExport.Skipped) -> String { say(notice: Notices.shared.leftOut(s: s)) }

    /// Folder `ref` with everything under it as the web's ZIP in a temporary file (core's `NodeExport`, the kinds the
    /// composition root exports), and what it left out as lines to say; nil when it did not finish.
    func downloadFolder(_ ref: NodeAt) async -> Downloaded? {
        guard let t = tenantSession else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else { return nil }
        let url = dir.appendingPathComponent(NodeExport.shared.fileName(name: ref.name))
        let skipped = try? await FileDownloads.shared.subtree(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, kinds: AppKinds.shared.exports(deletedName: say(.potDeletedPerson)),
            appVersion: String(VeyqAccount.companion.MAJOR), nowMs: Int64(Date().timeIntervalSince1970 * 1000), path: url.path
        )
        guard let skipped else { return nil }
        return Downloaded(url: url, leftOut: skipped.map(Self.leftOutLine))
    }

    /// What a copy of folder `ref` would make (its kinds) and leave out (as lines to say), before anything is written.
    func copyPreview(_ ref: NodeAt) async -> (kinds: Set<String>, leftOut: [String]) {
        guard let t = tenantSession,
              let p = try? await SubtreeCopy.shared.preview(tenant: t, scopeId: ref.scopeId, rootId: ref.nodeId,
                                                           kinds: AppKinds.shared.copies(deletedName: "", uuid: { @Sendable in "" })) else { return ([], []) }
        return (p.kinds, p.leftOut.map(Self.leftOutLine))
    }

    /// Copies folder `ref` with everything under it to `to` (nil: the space's top) in its space (core's `SubtreeCopy`): a new
    /// folder tree under fresh keys, each Pot by Expenses' copy; the lists are read anew and the shell says where it went.
    /// Why not, or nil once copied.
    /// Why a copy of `ref` cannot go: the tenant has no room for its bytes (`StorageFit.noRoomForCopy`); nil where it fits.
    func copyNoRoom(_ ref: NodeAt) async -> String? {
        guard let t = tenantSession,
              let bytes = try? await StorageFit.shared.noRoomForCopy(
                tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, kinds: AppKinds.shared.exports(deletedName: say(.potDeletedPerson))
              ) else { return nil }
        let size = ByteCountFormatter.string(fromByteCount: bytes.int64Value, countStyle: .file)
        return say(.theCopyNoRoom, size)
    }

    func copyFolder(_ ref: NodeAt, to: String?, toName: String) async -> String? {
        guard let m = shellModel else { return say(.theCopyFailed) }
        let refused = try? await m.duplicate(ref: ref, to: to, deletedName: say(.potDeletedPerson), uuid: { @Sendable in UUID().uuidString.lowercased() })
        applyShell(start: false)
        await readDetails(m.tenant)
        if let refused { return say(notice: refused) }
        trashNotice = TrashNotice(message: say(.theCopyDone, ref.name, toName), node: nil, moveBack: nil)
        return nil
    }

    func openReceipt(_ receiptId: String) async -> Data? {
        guard let session = potSession else { return nil }
        return (try? await AppleSessions.shared.openReceipt(pot: session, receiptId: receiptId)) ?? nil
    }

    /// The receipts the last save left out for want of room (the entry was saved).
    var skippedReceipts: [String] { potModel?.skippedReceipts ?? [] }

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
