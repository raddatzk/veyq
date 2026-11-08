import Foundation
import Observation
import VeyqCore

/// What the app shows, over the shared base: the sign-in, or the shell over a signed-in session. The navigation state
/// is core's `Shell` (ADR 0122); this model only holds it and the lists the session read.
@MainActor
@Observable
final class AppModel {
    enum ScreenState {
        case signIn(working: Bool, error: String?)
        case signed
    }

    private(set) var screen: ScreenState = .signIn(working: false, error: nil)
    private(set) var shell: Shell = Shell.companion.start(lastUsed: nil)
    private(set) var applications: [ApplicationId] = []
    private(set) var spaces: [SpaceRef] = []
    private(set) var objects: [ObjectRef] = []
    private(set) var favorites: [ObjectRef] = []
    private(set) var drawer: ArrangedList = ArrangedList(favorites: [], recent: [], all: [])
    var drawerOpen = false

    private var session: VeyqSession?
    private let recent: RecentUses

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
    }

    func signIn(server: String, email: String, password: String, secretKey: String) {
        screen = .signIn(working: true, error: nil)
        Task {
            do {
                let s = try await AppleSessions.shared.signIn(server: origin(server), email: email, password: password, secretKey: secretKey)
                session = s
                objects = try await s.objects()
                favorites = try await s.favorites()
                spaces = try await s.spaces()
                applications = ApplicationsKt.availableApplications(applicationsOff: s.applicationsOff()).map { $0.id }
                shell = Shell.companion.start(lastUsed: recent.lastUsed(objects: objects))
                drawerOpen = shell.application == nil && shell.openObject == nil
                refreshDrawer()
                screen = .signed
            } catch let failure as SignInFailure {
                screen = .signIn(working: false, error: failure.reason.name)
            } catch {
                screen = .signIn(working: false, error: "PROTOCOL")
            }
        }
    }

    func signOut() {
        let s = session
        session = nil
        recent.clear()
        Task { try? await s?.signOut() }
        screen = .signIn(working: false, error: nil)
    }

    func choose(_ app: ApplicationId?) { shell = shell.choose(app: app); drawerOpen = false }
    func open(_ ref: ObjectRef) {
        recent.record(ref: ref, atEpochMs: Int64(Date().timeIntervalSince1970 * 1000))
        shell = shell.open(ref: ref)
        drawerOpen = false
        refreshDrawer()
    }
    func select(_ tab: BarTab) { shell = shell.select(tab: tab) }
    func close() { shell = shell.close() }
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
