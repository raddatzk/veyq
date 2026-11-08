import Foundation
import Observation
import VeyqCore

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
        case signIn(working: Bool, error: String?, adding: Bool)
        case signed
    }

    private(set) var screen: ScreenState = .signIn(working: false, error: nil, adding: false)
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

    func signIn(server: String, email: String, password: String, secretKey: String) {
        let adding = isAdding
        screen = .signIn(working: true, error: nil, adding: adding)
        Task {
            do {
                let account = try await AppleSessions.shared.signIn(server: origin(server), email: email, password: password, secretKey: secretKey)
                guard let tenant = account.defaultTenant else {
                    screen = .signIn(working: false, error: "NO_TENANT", adding: adding)
                    return
                }
                let added = connections.add(connection: account, tenant: tenant)
                if let duplicate = added.duplicate { Task { try? await duplicate.signOut() } }
                guard let current = connections.current, let held = current.first, let opened = current.second else { return }
                try await show(held, opened)
            } catch {
                screen = .signIn(working: false, error: Self.reason(of: error), adding: adding)
            }
        }
    }

    /// A Kotlin exception reaches Swift as an NSError carrying it under "KotlinException"; a sign-in failure names its reason.
    private static func reason(of error: Error) -> String {
        let kotlin = (error as NSError).userInfo["KotlinException"]
        return (kotlin as? SignInFailure)?.reason.name ?? "PROTOCOL"
    }

    private var isAdding: Bool { if case .signIn(_, _, let adding) = screen { return adding } else { return false } }

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
        screen = .signIn(working: false, error: nil, adding: true)
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

    /// Signs out of one server; if it was current, another one held becomes current, else the sign-in shows.
    func signOut(_ account: VeyqAccount) {
        let wasCurrent = connections.current?.first == account
        connections.remove(connection: account)
        Task { try? await account.signOut() }
        if let next = connections.current, let held = next.first, let tenant = next.second {
            if wasCurrent { managing = nil; Task { try? await show(held, tenant) } }
            else if managing != nil { managing = managed() }
        } else {
            recent.clear() // the device forgets what was opened once no account is left on it
            hidden.clear()
            managing = nil
            screen = .signIn(working: false, error: nil, adding: false)
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
            })
        }
    }

    // MARK: The current tenant

    private func show(_ account: VeyqAccount, _ tenant: TenantRef) async throws {
        let t = try await AppleSessions.shared.open(account: account, tenant: tenant)
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
