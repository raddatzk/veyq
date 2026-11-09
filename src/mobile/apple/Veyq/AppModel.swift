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
        case signIn(working: Bool, error: String?, adding: Bool, server: String?, email: String?)
        /// An account this device trusts: the password alone, or the person's authentication.
        case unlock(record: TrustedDevice, working: Bool, error: String?)
        /// In the share extension with no account this device trusts: the app signs in, not the extension (ADR 0124).
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

    private let connections = AppleSessions.shared.connections()
    private let device: ThisDevice
    /// The share extension's model (ADR 0124): it signs in only from what the app left and moves nothing of the app's.
    let inExtension: Bool

    private static func makeDevice(inExtension: Bool) -> ThisDevice {
        #if os(iOS)
        let label = UIDevice.current.model
        #else
        let label = Host.current().localizedName ?? "Mac"
        #endif
        let keychain = KeychainStore(movesLegacy: !inExtension)
        if !inExtension {
            // What the share extension reads too lives in the app group (ADR 0124); an earlier installation's move there.
            Shared.moveIntoGroup()
            // A first start after an installation: the Keychain may still hold an earlier installation's items.
            if !Shared.defaults.bool(forKey: "veyq.installed") {
                keychain.removeAll()
                Shared.defaults.set(true, forKey: "veyq.installed")
            }
        }
        return AppleSessions.shared.device(keychain: keychain, label: label, prompt: String(localized: "Open your spaces"), defaults: Shared.defaults)
    }
    private var started = false
    private let recent: RecentUses
    private let hidden: Hidden

    /// The current tenant as read, before what this device hides is taken out.
    private var rawSpaces: [SpaceRef] = []
    private var rawNodes: [NodeRef] = []
    private var rawFavorites: [ObjectRef] = []
    private var rawOff: Set<String> = []
    private var managedSpaces: [String: [SpaceRef]] = [:]

    init(inExtension: Bool = false) {
        self.inExtension = inExtension
        device = Self.makeDevice(inExtension: inExtension)
        let stored = Shared.defaults.stringArray(forKey: "veyq.recent") ?? []
        recent = RecentUses(
            persist: { keys in
                Shared.defaults.set(keys.map { "\($0.scopeId)\t\($0.nodeId)\t\($0.lastUsedEpochMs)" }, forKey: "veyq.recent")
            },
            initial: stored.compactMap { line in
                let p = line.split(separator: "\t").map(String.init)
                guard p.count == 3, let at = Int64(p[2]) else { return nil }
                return UseKey(scopeId: p[0], nodeId: p[1], lastUsedEpochMs: at)
            },
            keep: 50
        )
        hidden = Hidden(
            persist: { keys in Shared.defaults.set(Array(keys), forKey: "veyq.hidden") },
            initial: Set(Shared.defaults.stringArray(forKey: "veyq.hidden") ?? [])
        )
    }

    var space: SpaceRef? { spaces.first { $0.scopeId == shell.space } }

    /// Home's level: the shown folder's children in the shown space, under the shown filter.
    var level: [NodeRef] {
        guard let s = shell.space else { return [] }
        return Home.shared.level(nodes: nodes, scopeId: s, parentId: shell.folder?.nodeId, filter: shell.filter)
    }

    /// Where the app opens (ADR 0123 decision 8): every account the person stays signed in to resumes after one
    /// authentication; with none, the most recent password-trusted account asks for its password; with no trusted
    /// account, the sign-in. Once per process.
    func start() {
        guard !started else { return }
        started = true
        let records = device.trusted()
        guard !records.isEmpty else {
            screen = inExtension ? .needsApp : .signIn(working: false, error: nil, adding: false, server: nil, email: nil)
            return
        }
        Task {
            var stopped: TrustedDevice?
            for record in records where record.method == .deviceKey {
                do {
                    try await add(AppleSessions.shared.resume(record: record, device: device))
                } catch {
                    // The person did not confirm: nothing more is asked of them this start.
                    if Self.reason(of: error) == "DEVICE_AUTH_CANCELLED" { stopped = record; break }
                }
            }
            if let current = connections.current, let held = current.first, let tenant = current.second {
                try? await show(held, tenant)
            } else {
                showLocked(stopped)
            }
        }
    }

    /// The unlock for `preferred`, else the most recent account this device trusts, else the sign-in.
    private func showLocked(_ preferred: TrustedDevice? = nil, error: String? = nil) {
        if let record = preferred ?? device.trusted().first {
            screen = .unlock(record: record, working: false, error: error)
            if let password = pendingUnlock { pendingUnlock = nil; unlock(password: password) }
        } else {
            screen = .signIn(working: false, error: error, adding: false, server: nil, email: nil)
        }
    }

    /// The debug build's unlock (`devlogin.py --unlock`): the password for the unlock screen, now or once it shows.
    private var pendingUnlock: String?

    func devUnlock(password: String) {
        if case .unlock = screen { unlock(password: password) } else { pendingUnlock = password }
    }

    private func add(_ account: VeyqAccount) throws {
        guard let tenant = account.defaultTenant else { throw NoTenant() }
        let added = connections.add(connection: account, tenant: tenant)
        if let duplicate = added.duplicate { Task { try? await duplicate.signOut() } }
    }

    private struct NoTenant: Error {}

    /// The full sign-in, then this device trusted with the account: staying signed in when `remember` (which asks the
    /// person to authenticate; declined, the password trust instead), else the password trust.
    func signIn(server: String, email: String, password: String, secretKey: String, remember: Bool = false) {
        started = true
        let adding = isAdding
        screen = .signIn(working: true, error: nil, adding: adding, server: server, email: email)
        Task {
            do {
                let account = try await AppleSessions.shared.signIn(server: origin(server), email: email, password: password, secretKey: secretKey, device: device)
                _ = try? await AppleSessions.shared.trust(account: account, password: password, remember: remember)
                try add(account)
                guard let current = connections.current, let held = current.first, let opened = current.second else { return }
                try await show(held, opened)
            } catch is NoTenant {
                screen = .signIn(working: false, error: "NO_TENANT", adding: adding, server: server, email: email)
            } catch {
                screen = .signIn(working: false, error: Self.reason(of: error), adding: adding, server: server, email: email)
            }
        }
    }

    /// The trusted account's unlock: its password, or the person's authentication again.
    func unlock(password: String) {
        guard case .unlock(let record, _, _) = screen else { return }
        screen = .unlock(record: record, working: true, error: nil)
        Task {
            do {
                let account = record.method == .device
                    ? try await AppleSessions.shared.unlockOnDevice(record: record, password: password, device: device)
                    : try await AppleSessions.shared.resume(record: record, device: device)
                try add(account)
                guard let current = connections.current, let held = current.first, let opened = current.second else { return }
                try await show(held, opened)
            } catch {
                let reason = Self.reason(of: error)
                switch reason {
                case "DEVICE_NOT_TRUSTED", "SESSION_ENDED":
                    screen = .signIn(working: false, error: reason, adding: false, server: record.server, email: record.email)
                case "DEVICE_AUTH_CANCELLED":
                    screen = .unlock(record: record, working: false, error: nil)
                default:
                    screen = .unlock(record: record, working: false, error: reason)
                }
            }
        }
    }

    /// From the unlock to the full sign-in, for another account.
    func otherAccount() {
        screen = inExtension ? .needsApp : .signIn(working: false, error: nil, adding: false, server: nil, email: nil)
    }

    /// A Kotlin exception reaches Swift as an NSError carrying it under "KotlinException"; a sign-in failure names its reason.
    private static func reason(of error: Error) -> String {
        let kotlin = (error as NSError).userInfo["KotlinException"]
        return (kotlin as? SignInFailure)?.reason.name ?? "PROTOCOL"
    }

    private var isAdding: Bool { if case .signIn(_, _, let adding, _, _) = screen { return adding } else { return false } }

    /// The switcher: another tenant of the same server is the same session at another domain; another server is its own.
    func switchTo(_ account: VeyqAccount, _ tenant: TenantRef) {
        connections.switchTo(connection: account, tenant: tenant)
        switching = true
        drawerOpen = false
        managing = nil
        Task {
            try? await show(account, tenant)
            switching = false
        }
    }

    func addServer() {
        drawerOpen = false
        managing = nil
        screen = .signIn(working: false, error: nil, adding: true, server: nil, email: nil)
    }

    func cancelAdd() {
        guard let current = connections.current, let account = current.first, let tenant = current.second else { return }
        Task { try? await show(account, tenant) }
    }

    // MARK: Manage servers

    func openManage() {
        drawerOpen = false
        managedSpaces = [:]
        managing = managed()
        Task {
            for c in connections.all {
                for t in c.tenants {
                    if let s = try? await AppleSessions.shared.open(account: c, tenant: t).spaces() {
                        managedSpaces[c.key + "#" + t.tenantId] = s
                        if managing != nil { managing = managed() }
                    }
                }
            }
        }
    }

    func setTenantHidden(_ account: VeyqAccount, _ tenant: TenantRef, _ value: Bool) {
        hidden.setTenantHidden(connectionKey: account.key, tenantId: tenant.tenantId, hidden: value)
        if managing != nil { managing = managed() }
        rebuild(start: false)
    }

    func setSpaceHidden(_ scopeId: String, _ value: Bool) {
        hidden.setSpaceHidden(scopeId: scopeId, hidden: value)
        if managing != nil { managing = managed() }
        rebuild(start: false)
    }

    /// Signs out of one server; if it was current, another one held becomes current, else the unlock of an account
    /// this device still trusts with the password, or the sign-in. Staying signed in ends with the sign-out; the
    /// password trust stays (as on the web).
    func signOut(_ account: VeyqAccount) {
        let wasCurrent = connections.current?.first == account
        connections.remove(connection: account)
        Task {
            try? await account.signOut()
            if let next = connections.current, let held = next.first, let tenant = next.second {
                if wasCurrent { managing = nil; try? await show(held, tenant) }
                else if managing != nil { managing = managed() }
            } else {
                recent.clear() // the device forgets what was opened once no account is left on it
                hidden.clear()
                managing = nil
                showLocked()
            }
        }
    }

    /// Ends this device's trust in `account`: the next sign-in here asks for the Secret Key again.
    func forgetDevice(_ account: VeyqAccount) {
        Task {
            try? await account.forgetThisDevice()
            if managing != nil { managing = managed() }
        }
    }

    private func managed() -> [ManagedServer] {
        let current = connections.current
        return connections.all.map { c in
            ManagedServer(account: c, tenants: c.tenants.map { t in
                let spaces = managedSpaces[c.key + "#" + t.tenantId]
                return ManagedTenant(
                    tenant: t,
                    hidden: hidden.isTenantHidden(connectionKey: c.key, tenantId: t.tenantId),
                    current: current?.first == c && current?.second == t,
                    spaces: spaces.map { list in
                        list.map { ManagedSpace(space: $0, hidden: hidden.isSpaceHidden(scopeId: $0.scopeId), canHide: hidden.canHideSpace(scopeId: $0.scopeId, spaces: list)) }
                    }
                )
            }, trusted: c.trusted?.method)
        }
    }

    // MARK: The current tenant

    private func show(_ account: VeyqAccount, _ tenant: TenantRef) async throws {
        let t = try await AppleSessions.shared.open(account: account, tenant: tenant)
        // a member's write pulls its path forward, and the scopes served as re-keying are finished in the background
        t.rekeyRules = AppKinds.shared.rules
        tenantSession = t
        resumeRekeys(t)
        pot = nil
        reportOffered = false
        Task { await readReportOffered() }
        rawSpaces = try await t.spaces()
        rawNodes = try await t.nodes()
        rawFavorites = try await t.favorites()
        rawOff = t.applicationsOff()
        rowDetails = (try? await PotRows.shared.details(tenant: t)) ?? [:]
        await readCovers(t)
        // The pictures the directory names a revision for, in the background; the letters show meanwhile.
        avatars = [:]
        Task {
            let pictures = (try? await AppleSessions.shared.syncAvatars(tenant: t)) ?? [:]
            if tenantSession === t { avatars = pictures }
        }
        self.tenant = tenant
        server = account.label
        rebuild(start: true)
        screen = .signed
    }

    /// The shell's lists from what was read, less what this device hides; on `start`, the shell where the app opens.
    private func rebuild(start: Bool) {
        guard let current = connections.current, let account = current.first, let tenant = current.second else { return }
        spaces = hidden.visibleSpaces(spaces: rawSpaces)
        nodes = hidden.visibleNodes(nodes: rawNodes, spaces: rawSpaces)
        objects = nodes.compactMap { $0.asObject() }
        favorites = hidden.visibleObjects(objects: rawFavorites, spaces: rawSpaces)
        applications = ApplicationsKt.drawerApplications(applicationsOff: rawOff).map { $0.id }
        filters = [nil] + ApplicationsKt.availableApplications(applicationsOff: rawOff).map { $0.id }
        servers = connections.all.map { c in
            ServerEntry(account: c, tenants: hidden.visibleTenants(connectionKey: c.key, tenants: c.tenants, current: c == account ? tenant : nil))
        }
        let defaultSpace = Home.shared.defaultSpace(spaces: spaces)?.scopeId
        if start {
            shell = Shell.companion.start(lastUsed: incoming == nil ? recent.lastUsed(objects: objects) : nil, space: defaultSpace)
            drawerOpen = shell.isHome && shell.space == nil
        } else if let s = shell.space, !spaces.contains(where: { $0.scopeId == s }) {
            shell = shell.switchSpace(scopeId: defaultSpace ?? "")
        }
        refreshDrawer()
    }

    // MARK: What another app shared (ADR 0124)

    /// Files shared to veyq, waiting for the Pot they go to (the pick mode), and how many were left out.
    struct Incoming {
        let files: [PotSession.PendingReceipt]
        let refused: Int
    }

    private(set) var incoming: Incoming?
    /// What a Pot took, until its new expense is saved: its editor closed unsaved gives it back to the pick mode.
    private var taken: Incoming?
    /// How the share ends: true once its files were saved where they went, false when it was cancelled.
    var onShareDone: ((Bool) -> Void)?

    /// Files arrive: the pick mode starts at the space's Home, never in the object last open, so the person chooses where
    /// they go (ADR 0124 decision 1) — the start does the same while the session is still opening.
    func receiveShare(_ incoming: Incoming) {
        shell = Shell.companion.start(lastUsed: nil, space: shell.space)
        self.incoming = incoming
    }

    /// Files dropped onto the app (ADR 0124 decision 9): an open Pot that takes them opens its new expense with them at once;
    /// anywhere else they wait in the pick mode, as a share's do.
    func receiveDrop(_ incoming: Incoming) {
        if let ref = shell.openObject, ref.kind == PotSession.companion.KIND, pot?.ref == ref, let view = pot?.view,
           view.canWrite, !view.people.isEmpty, !incoming.files.isEmpty {
            self.incoming = incoming
        } else {
            receiveShare(incoming)
        }
    }

    func cancelShare() {
        incoming = nil
        onShareDone?(false)
    }

    /// The waiting files, taken by the Pot opened as their place: its new expense's receipts.
    func takeShare() -> [PotSession.PendingReceipt]? {
        guard let waiting = incoming, !waiting.files.isEmpty else { return nil }
        taken = waiting
        incoming = nil
        return waiting.files
    }

    /// The new expense's editor closed unsaved: the files wait again, for this Pot or another.
    func returnShare() {
        guard let t = taken else { return }
        taken = nil
        receiveShare(t)
    }

    /// The new expense the shared files went into was saved: the share is done.
    func shareSaved() {
        taken = nil
        onShareDone?(true)
    }

    // MARK: Core's actions on a node

    /// Whether `ref` is pinned; nil while its space's pin set is not loaded (no heart is offered then).
    func isFavorite(_ ref: NodeAt) -> Bool? { tenantSession?.isFavorite(scopeId: ref.scopeId, nodeId: ref.nodeId)?.boolValue }

    /// Pins or unpins `ref`, then the lists are read anew; false when it was not stored (the heart goes back).
    func toggleFavorite(_ ref: NodeAt) async -> Bool {
        guard let t = tenantSession, let now = t.isFavorite(scopeId: ref.scopeId, nodeId: ref.nodeId)?.boolValue else { return false }
        let stored = (try? await t.setFavorite(scopeId: ref.scopeId, nodeId: ref.nodeId, on: !now))?.boolValue ?? false
        await refreshLists(t)
        return stored
    }

    private func refreshLists(_ t: TenantSession) async {
        rawNodes = (try? await t.nodes()) ?? rawNodes
        rawFavorites = (try? await t.favorites()) ?? rawFavorites
        rowDetails = (try? await PotRows.shared.details(tenant: t)) ?? rowDetails
        await readCovers(t)
        rebuild(start: false)
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
        guard let t = tenantSession else { return }
        let before = try? await t.node(scopeId: ref.scopeId, nodeId: ref.nodeId)
        let deleted = (try? await NodeTrash.shared.delete(tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId)) ?? .failed
        if deleted == .trashed || deleted == .gone {
            if shell.openObject?.nodeId == ref.nodeId { goBack() }
            if pot?.ref.nodeId == ref.nodeId { pot = nil; potSession = nil }
        }
        await refreshLists(t)
        // a folder on Home's path went: Home shows the folder above it
        if deleted == .trashed || deleted == .gone, let i = shell.path.firstIndex(where: { $0.nodeId == ref.nodeId }) {
            shell = shell.at(tree: await scopeNodes(ref.scopeId), folderId: i > 0 ? shell.path[i - 1].nodeId : nil)
        }
        switch deleted {
        case .trashed: trashNotice = TrashNotice(message: String(localized: "“\(ref.name)” moved to the trash."), node: before ?? nil)
        case .gone: trashNotice = TrashNotice(message: String(localized: "It is no longer there."), node: nil)
        case .scopeDeleting: trashNotice = TrashNotice(message: String(localized: "This space is being deleted."), node: nil)
        default: trashNotice = TrashNotice(message: String(localized: "Deleting didn’t work. Please try again."), node: nil)
        }
    }

    /// The Undo of the last delete: the node back from the trash under its name, as it was.
    func undoTrash() async {
        if let back = trashNotice?.moveBack {
            trashNotice = nil
            if await move(back.ref, to: back.to, toName: nil, from: nil) != .moved {
                trashNotice = TrashNotice(message: String(localized: "“\(back.ref.name)” could not be moved back. Please move it yourself."), node: nil)
            }
            return
        }
        guard let notice = trashNotice, let node = notice.node, let t = tenantSession else { return }
        trashNotice = nil
        var restored = false
        if let tokenInput = Self.tokenInputFor(kind: node.kind, name: node.name) {
            let r = try? await NodeTrash.shared.restore(tenant: t, node: node, tokenInput: tokenInput, waitsMs: NodeEpochWrite.shared.REKEY_WAITS_MS)
            restored = r == .restored
        }
        await refreshLists(t)
        if !restored {
            trashNotice = TrashNotice(message: String(localized: "“\(node.name)” could not be restored. You can restore it from the trash."), node: nil)
        }
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
        return try? await ShareLinks.shared.list(tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId)
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
        if let session = potSession {
            _ = try? await session.rekeyed()
            if potSession === session { pot?.view = PotView(session: session) }
        }
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
    func scopeNodes(_ scopeId: String) async -> [NodeRef] {
        guard let t = tenantSession else { return [] }
        return ((try? await t.nodes()) ?? []).filter { $0.scopeId == scopeId }
    }

    /// Moves `ref` under `to` (nil: the space's root), its name kept; the lists are read anew. With `toName`, the bottom of
    /// the shell says where it went and offers its undo back to `from`.
    func move(_ ref: NodeAt, to: String?, toName: String?, from: String?) async -> NodeMove.Outcome {
        guard let t = tenantSession else { return .failed }
        let r = try? await NodeMove.shared.move(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, to: to,
            tokenInput: { @Sendable kind, name in AppModel.tokenInputFor(kind: kind, name: name) },
            allows: placement, waitsMs: NodeEpochWrite.shared.REKEY_WAITS_MS
        )
        await refreshLists(t)
        if r?.outcome == .moved { await relocate(ref) }
        let outcome = r?.outcome ?? .failed
        if outcome == .moved, let toName {
            trashNotice = TrashNotice(
                message: String(localized: "“\(ref.name)” moved to “\(toName)”."), node: nil, moveBack: MoveBack(ref: ref, to: from)
            )
        }
        return outcome
    }

    /// Copies the open Pot `ref` to `to` (nil: the space's root) in its space (uc.platform.copy-node): a new Pot under fresh
    /// keys with its people, live entries and receipts; the lists are read anew and the shell says where it went.
    func copyPot(_ ref: NodeAt, to: String?, toName: String) async -> PotCopy.Outcome {
        guard let t = tenantSession, let session = potSession, pot?.ref.nodeId == ref.nodeId else { return .failed }
        let r = try? await PotCopy.shared.duplicate(
            source: session, tenant: t, scopeId: ref.scopeId, parentId: to, deletedName: String(localized: "deleted person"),
            uuid: { @Sendable in UUID().uuidString.lowercased() }
        )
        await refreshLists(t)
        let outcome = r?.outcome ?? .failed
        if outcome == .copied {
            trashNotice = TrashNotice(message: String(localized: "“\(ref.name)” is copied to “\(toName)”."), node: nil, moveBack: nil)
        }
        return outcome
    }

    /// Home's path read anew where `ref` is a folder on it (renamed or moved): the folder shown is where it is now.
    private func relocate(_ ref: NodeAt) async {
        guard shell.path.contains(where: { $0.nodeId == ref.nodeId }) else { return }
        shell = shell.at(tree: await scopeNodes(ref.scopeId), folderId: shell.folder?.nodeId)
    }

    /// Renames `ref` to `name` (core's `NodeRename`: the placement, then its open links' names); the lists are read anew
    /// and an open page follows the node under its new name. Why it did not land, in core's words, or nil once renamed;
    /// `linksFailed` says a link kept the old name (the rename stands).
    func rename(_ ref: NodeAt, to name: String) async -> (problem: String?, linksFailed: Bool) {
        guard let t = tenantSession else { return (String(localized: "That didn’t work. Please try again."), false) }
        let r = try? await NodeRename.shared.rename(
            tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, name: name,
            tokenInput: { @Sendable kind, name in AppModel.tokenInputFor(kind: kind, name: name) },
            nowMs: Int64(Date().timeIntervalSince1970 * 1000), waitsMs: NodeEpochWrite.shared.REKEY_WAITS_MS
        )
        guard let r else { return (String(localized: "That didn’t work. Please try again."), false) }
        switch r.outcome {
        case .renamed:
            await refreshLists(t)
            await relocate(ref)
            if shell.openObject?.nodeId == ref.nodeId,
               let renamed = ((try? await t.objects()) ?? []).first(where: { $0.scopeId == ref.scopeId && $0.nodeId == ref.nodeId }) {
                shell = shell.goBack().open(ref: renamed)
            }
            return (nil, !(r.links?.failed.isEmpty ?? true))
        case .nameTaken:
            if let sibling = r.taken {
                // the colliding sibling's noun with its article: its kind's `nounWithArticle`, else "an item"
                let kind = sibling.kind == "EXPENSES_POT" ? String(localized: "a Pot") : String(localized: "an item")
                return (String(localized: "There is already \(kind) named “\(sibling.name)” here. Choose another name."), false)
            }
            return (String(localized: "Something with a similar name already exists here."), false)
        case .notAllowed: return (String(localized: "That isn’t allowed here."), false)
        case .busy: return (String(localized: "The pot’s key is being renewed. Try again in a moment."), false)
        default: return (String(localized: "That didn’t work. Please try again."), false)
        }
    }

    /// How a kind folds a name to its slug token's input: the composition root's answer (`AppKinds`, the web's
    /// `registry.tokenInputFor`). Nil for a kind the app does not know: nothing is placed under it.
    /// Free of the main actor: core calls it from its own threads, after a request answered (a re-key, a placement).
    nonisolated static func tokenInputFor(kind: String, name: String) -> String? {
        AppKinds.shared.tokenInput(kind: kind, name: name)
    }

    // MARK: The space's trash (core's NodeTrash)

    /// The trash of `scopeId` (the root of each deleted subtree); nil when it could not be read.
    func trashOf(_ scopeId: String) async -> [NodeTrash.Trashed]? {
        guard let t = tenantSession else { return nil }
        return try? await NodeTrash.shared.list(tenant: t, scopeId: scopeId)
    }

    /// Whether the app can bring `row` back: its kind's fold of a name is known here.
    func canRestore(_ row: NodeTrash.Trashed) -> Bool { Self.tokenInputFor(kind: row.kind, name: row.name) != nil }

    /// Brings `row` back from the trash, under `newName` after its own was taken; the lists are read anew.
    func restoreFromTrash(_ row: NodeTrash.Trashed, newName: String?) async -> NodeTrash.Restored {
        guard let t = tenantSession,
              let token = Self.tokenInputFor(kind: row.kind, name: newName?.trimmingCharacters(in: .whitespaces) ?? row.name) else { return .refused }
        let r = (try? await NodeTrash.shared.restoreFromTrash(
            tenant: t, node: row, tokenInput: token, newName: newName, waitsMs: NodeEpochWrite.shared.REKEY_WAITS_MS
        )) ?? .refused
        await refreshLists(t)
        return r
    }

    /// Erases `row` and everything under it now: irreversible.
    func purge(_ row: NodeTrash.Trashed) async -> NodeTrash.Purged {
        guard let t = tenantSession else { return .failed }
        return (try? await NodeTrash.shared.purge(tenant: t, scopeId: row.scopeId, nodeId: row.id)) ?? .failed
    }

    // MARK: An open Pot (Expenses)

    /// The tenant the shell shows, opened: where an object's own view reads from.
    private var tenantSession: TenantSession?
    private var potSession: PotSession?

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

    /// Each listed node's cover as a small picture, by node id, with the blob it was opened from (core's covers,
    /// uc.platform.node-cover): opened once per blob, dropped when the node names another or none.
    private(set) var covers: [String: (blobId: String, image: CGImage)] = [:]

    private func readCovers(_ t: TenantSession) async {
        let named = ((try? await NodeCover.shared.named(tenant: t)) ?? [:])
        var next = covers.filter { named[$0.key] == $0.value.blobId }
        for (nodeId, blobId) in named where next[nodeId] == nil {
            if let data = (try? await AppleSessions.shared.openCover(tenant: t, nodeId: nodeId)) ?? nil,
               let image = CoverImages.image(data, maxPixels: 192) {
                next[nodeId] = (blobId, image)
            }
        }
        if tenantSession === t { covers = next }
    }

    /// Sets the picture `data` as the cover of `ref`: made the web's cover JPEG on the device (long edge 2048, quality 0.85,
    /// `COVER_SPEC`), uploaded and attached (core's `NodeCover`); false when it did not land.
    func setCover(_ ref: NodeAt, data: Data) async -> Bool {
        guard let t = tenantSession, let jpeg = CoverImages.jpeg(data, maxPixels: 2048, quality: 0.85) else { return false }
        let outcome = try? await AppleSessions.shared.setCover(tenant: t, scopeId: ref.scopeId, nodeId: ref.nodeId, jpeg: jpeg)
        await refreshLists(t)
        return outcome == .done
    }

    /// Creates a folder named `name` in the space and folder on screen, core's folder (the web's `FolderNameDialog`); the
    /// lists are read anew. Why not, in the web's words, or nil once created.
    func createFolder(name: String) async -> String? {
        guard let t = tenantSession, let scopeId = shell.space else { return String(localized: "The folder could not be created. Please try again.") }
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if n.isEmpty { return String(localized: "Give it a name.") }
        let r = try? await FolderCreate.shared.create(tenant: t, scopeId: scopeId, parentId: shell.folder?.nodeId, name: n)
        await refreshLists(t)
        if r is NodeCreateResultDone { return nil }
        if let refused = r as? NodeCreateResultRefused, refused.reason == .nameTaken {
            return String(localized: "Something with this name is already here.")
        }
        return String(localized: "The folder could not be created. Please try again.")
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

    /// The members' pictures this device opened, by user id (core's `MemberAvatars`): the letter for everyone else.
    private(set) var avatars: [String: Data] = [:]

    /// Opens and reads the Pot `ref` unless it is the one shown.
    func loadPot(_ ref: ObjectRef) {
        if let p = pot, p.ref == ref, !p.failed { return }
        pot = PotState(ref: ref)
        guard let tenant = tenantSession else { pot?.failed = true; return }
        Task {
            let session = (try? await AppleSessions.shared.openPot(tenant: tenant, scopeId: ref.scopeId, nodeId: ref.nodeId)) ?? nil
            potSession = session
            potPoll = LivePoll(cadence: LivePoll.companion.ITEMS, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) }, random: { @Sendable in KotlinDouble(value: Double.random(in: 0..<1)) })
            if pot?.ref == ref { pot = PotState(ref: ref, view: session.map { PotView(session: $0) }, failed: session == nil) }
        }
    }

    private var potPoll: LivePoll?

    /// The open Pot's live poll (core's `LivePoll`) for as long as the calling task runs — the Pot's screen while the app
    /// is active, so nothing is asked in the background. Swift drives the loop: a Swift task's cancellation does not reach a
    /// Kotlin coroutine, so it sleeps here and stops at once when cancelled. A tick is skipped while a write shows saving;
    /// the view is read anew when one moved what is held.
    func livePot() async {
        guard let session = potSession, let cadence = potPoll else { return }
        var wait = cadence.firstDelay()
        while !Task.isCancelled {
            if wait > 0 { try? await Task.sleep(for: .milliseconds(wait)) }
            guard !Task.isCancelled, potSession === session else { return }
            cadence.started()
            var outcome = LivePoll.Tick.skip
            if pot?.saving == false {
                do {
                    if let moved = try await session.poll() {
                        outcome = .done
                        if moved.boolValue, potSession === session { pot?.view = PotView(session: session) }
                    }
                } catch {
                    outcome = .failed
                }
            }
            wait = cadence.next(outcome: outcome)
        }
    }

    /// Reads the open Pot again now (pull to refresh), moved or not.
    func refreshPot() async {
        guard let session = potSession else { return }
        _ = try? await session.refresh()
        _ = try? await session.readReceipts()
        if potSession === session { pot?.view = PotView(session: session) }
    }

    /// One write to the open Pot (`op` on its session): it shows saving, then the Pot as read after it, and a refusal as its
    /// message (a `WriteOutcome` name); true once stored.
    @discardableResult
    func potWrite(_ op: @escaping @MainActor (PotSession) async throws -> PotSession.WriteOutcome) async -> Bool {
        guard var state = pot, let session = potSession else { return false }
        state.saving = true
        state.message = nil
        pot = state
        let outcome = (try? await op(session)) ?? .refused
        state.view = PotView(session: session)
        state.saving = false
        state.message = outcome == .written ? nil : outcome.name
        pot = state
        // A settings write may have set the base currency: the Pot's row says it at once.
        rowDetails[state.ref.nodeId] = PotRows.shared.detailsOf(meta: session.meta)
        return outcome == .written
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
        guard let t = tenantSession else { return false }
        creatingPot = CreatingPot(saving: true)
        // in the folder on screen, where the sheet creates in the space shown (the web's "New" at a level)
        let parent = scopeId == shell.space ? shell.folder?.nodeId : nil
        let r = try? await PotCreate.shared.create(
            tenant: t, scopeId: scopeId, parentId: parent, name: name,
            base: Ledger.BaseCurrency(currency: currency, minorUnits: minorUnits), people: people, categories: []
        )
        guard let r, let nodeId = r.nodeId else {
            creatingPot = CreatingPot(message: r?.outcome.name ?? "REFUSED")
            return false
        }
        t.forgetTree()
        rawNodes = (try? await t.nodes()) ?? rawNodes
        rawFavorites = (try? await t.favorites()) ?? rawFavorites
        rowDetails = (try? await PotRows.shared.details(tenant: t)) ?? rowDetails
        await readCovers(t)
        rebuild(start: false)
        creatingPot = CreatingPot(message: r.outcome == .created ? nil : r.outcome.name)
        if let ref = ((try? await t.objects()) ?? []).first(where: { $0.nodeId == nodeId }) { open(ref) }
        return true
    }

    func clearCreatingPot() { creatingPot = CreatingPot() }

    /// One unit of `currency` in the open Pot's base from today's table, or nil: the rate is typed then.
    func rateFor(_ currency: String) async -> ExchangeRates.Filled? {
        guard let session = potSession else { return nil }
        return (try? await session.rateFor(currency: currency, day: FormState.dayString(Date()))) ?? nil
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
            session: session, deletedName: String(localized: "deleted person"), nowMs: Int64(Date().timeIntervalSince1970 * 1000), path: url.path
        )
        return written == nil ? nil : url
    }

    func openReceipt(_ receiptId: String) async -> Data? {
        guard let session = potSession else { return nil }
        return (try? await AppleSessions.shared.openReceipt(pot: session, receiptId: receiptId)) ?? nil
    }

    /// The receipts the last save left out for want of room (the entry was saved).
    var skippedReceipts: [String] { potSession?.skippedReceipts ?? [] }

    /// Forgets the open Pot's last refusal (a sheet closed).
    func clearPotMessage() { pot?.message = nil }

    func choose(_ app: ApplicationId?) { shell = shell.choose(app: app); drawerOpen = false }
    func switchSpace(_ scopeId: String) { shell = shell.switchSpace(scopeId: scopeId) }
    func enter(_ folder: FolderRef) { shell = shell.enter(folder: folder) }
    func setFilter(_ app: ApplicationId?) { shell = shell.filter(app: app) }
    func goBack() { shell = shell.goBack() }

    func open(_ ref: ObjectRef) {
        recent.record(ref: ref, atEpochMs: Int64(Date().timeIntervalSince1970 * 1000))
        shell = shell.open(ref: ref)
        drawerOpen = false
        refreshDrawer()
    }

    func select(_ tab: BarTab) { shell = shell.select(tab: tab) }
    func search(_ on: Bool) { shell = shell.search(on: on) }

    func applicationList(_ app: ApplicationId) -> ArrangedList {
        ListsKt.arrange(
            favorites: favorites.filter { $0.application == app },
            uses: [],
            all: objects.filter { $0.application == app },
            favoritesLimit: nil,
            recentLimit: nil
        )
    }

    private func refreshDrawer() {
        drawer = ListsKt.arrange(favorites: favorites, uses: recent.resolve(objects: objects), all: [], favoritesLimit: 6, recentLimit: 8)
    }

    /// A bare host gets https, except a dev `*.localhost` host, which the dev backend serves on http.
    private func origin(_ typed: String) -> String {
        let t = typed.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if t.contains("://") { return t }
        let host = String(t.split(separator: ":").first ?? "")
        return (host == "localhost" || host.hasSuffix(".localhost")) ? "http://\(t)" : "https://\(t)"
    }
}
