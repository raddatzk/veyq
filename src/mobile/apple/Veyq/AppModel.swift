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
    private let device: ThisDevice = {
        #if os(iOS)
        let label = UIDevice.current.model
        #else
        let label = Host.current().localizedName ?? "Mac"
        #endif
        let keychain = KeychainStore()
        // A first start after an installation: the Keychain may still hold an earlier installation's items.
        if !UserDefaults.standard.bool(forKey: "veyq.installed") {
            keychain.removeAll()
            UserDefaults.standard.set(true, forKey: "veyq.installed")
        }
        return AppleSessions.shared.device(keychain: keychain, label: label, prompt: String(localized: "Open your spaces"))
    }()
    private var started = false
    private let recent: RecentUses
    private let hidden: Hidden

    /// The current tenant as read, before what this device hides is taken out.
    private var rawSpaces: [SpaceRef] = []
    private var rawNodes: [NodeRef] = []
    private var rawFavorites: [ObjectRef] = []
    private var rawOff: Set<String> = []
    private var managedSpaces: [String: [SpaceRef]] = [:]

    init() {
        let stored = UserDefaults.standard.stringArray(forKey: "veyq.recent") ?? []
        recent = RecentUses(
            persist: { keys in
                UserDefaults.standard.set(keys.map { "\($0.scopeId)\t\($0.nodeId)\t\($0.lastUsedEpochMs)" }, forKey: "veyq.recent")
            },
            initial: stored.compactMap { line in
                let p = line.split(separator: "\t").map(String.init)
                guard p.count == 3, let at = Int64(p[2]) else { return nil }
                return UseKey(scopeId: p[0], nodeId: p[1], lastUsedEpochMs: at)
            },
            keep: 50
        )
        hidden = Hidden(
            persist: { keys in UserDefaults.standard.set(Array(keys), forKey: "veyq.hidden") },
            initial: Set(UserDefaults.standard.stringArray(forKey: "veyq.hidden") ?? [])
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
            screen = .signIn(working: false, error: nil, adding: false, server: nil, email: nil)
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
        screen = .signIn(working: false, error: nil, adding: false, server: nil, email: nil)
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
        tenantSession = t
        pot = nil
        rawSpaces = try await t.spaces()
        rawNodes = try await t.nodes()
        rawFavorites = try await t.favorites()
        rawOff = t.applicationsOff()
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
            shell = Shell.companion.start(lastUsed: recent.lastUsed(objects: objects), space: defaultSpace)
            drawerOpen = shell.isHome && shell.space == nil
        } else if let s = shell.space, !spaces.contains(where: { $0.scopeId == s }) {
            shell = shell.switchSpace(scopeId: defaultSpace ?? "")
        }
        refreshDrawer()
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

    /// Opens and reads the Pot `ref` unless it is the one shown.
    func loadPot(_ ref: ObjectRef) {
        if let p = pot, p.ref == ref, !p.failed { return }
        pot = PotState(ref: ref)
        guard let tenant = tenantSession else { pot?.failed = true; return }
        Task {
            let session = (try? await AppleSessions.shared.openPot(tenant: tenant, scopeId: ref.scopeId, nodeId: ref.nodeId)) ?? nil
            potSession = session
            if pot?.ref == ref { pot = PotState(ref: ref, view: session.map { PotView(session: $0) }, failed: session == nil) }
        }
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
        let r = try? await PotCreate.shared.create(
            tenant: t, scopeId: scopeId, parentId: nil, name: name,
            base: Ledger.BaseCurrency(currency: currency, minorUnits: minorUnits), people: people
        )
        guard let r, let nodeId = r.nodeId else {
            creatingPot = CreatingPot(message: r?.outcome.name ?? "REFUSED")
            return false
        }
        t.forgetTree()
        rawNodes = (try? await t.nodes()) ?? rawNodes
        rawFavorites = (try? await t.favorites()) ?? rawFavorites
        rebuild(start: false)
        creatingPot = CreatingPot(message: r.outcome == .created ? nil : r.outcome.name)
        if let ref = ((try? await t.objects()) ?? []).first(where: { $0.nodeId == nodeId }) { open(ref) }
        return true
    }

    func clearCreatingPot() { creatingPot = CreatingPot() }

    /// Forgets the open Pot's last refusal (a sheet closed).
    func clearPotMessage() { pot?.message = nil }

    /// Adds an expense to the open Pot, paid by `payer`, split equally among `beneficiaries`, dated today; true once stored.
    func addExpense(title: String, amount: String, payer: String, beneficiaries: [String], category: String) async -> Bool {
        guard var state = pot, let session = potSession else { return false }
        state.saving = true
        state.message = nil
        pot = state
        let day = DateFormatter()
        day.calendar = Calendar(identifier: .gregorian)
        day.locale = Locale(identifier: "en_US_POSIX")
        day.dateFormat = "yyyy-MM-dd"
        let outcome = (try? await AppleSessions.shared.addExpense(
            pot: session, title: title, amountText: amount, payer: payer, beneficiaries: beneficiaries,
            date: day.string(from: Date()), category: category, minorUnits: Int32(state.view?.minorUnits ?? 2)
        )) ?? .refused
        state.view = PotView(session: session)
        state.saving = false
        state.message = outcome == .written ? nil : outcome.name
        pot = state
        return outcome == .written
    }

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
