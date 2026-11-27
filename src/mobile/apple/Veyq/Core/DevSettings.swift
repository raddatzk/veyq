import SwiftUI
import VeyqCore

/// Debug builds only — the account's Settings page (`uc.account.sessions`) drawn over a played account,
/// reached without a server or a sign-in: started with `VEYQ_DEV_SETTINGS=1` in its environment, the app shows
/// `SettingsView` over a fixed state — this device staying signed in, a second device with a saved key, a session the
/// register does not name, a signed-out device with a saved key, "Add a Device" offered. Each action plays its effect
/// on that state the way core's `AccountSettings` answers it (`AccountSettingsTest` holds the real rules) and goes to
/// the unified log (`veyq-settings:`), so a pass reads what was done before it looks. `VEYQ_DEV_SETTINGS=refused`
/// starts with the list refused and the register unreadable. A release build compiles none of it.
enum DevSettings {
    static var requested: String? {
        #if DEBUG
        let v = ProcessInfo.processInfo.environment["VEYQ_DEV_SETTINGS"]
        return v == "1" || v == "refused" ? v : nil
        #else
        nil
        #endif
    }
}

#if DEBUG
@MainActor
@Observable
final class PlayedSettings: SettingsActions {
    private struct Row { var id: String; var created: Int64; var used: Int64; var current: Bool; var label: String?; var removesKey: Bool }

    private static let day: Int64 = 86_400_000
    private static let now: Int64 = 1_794_000_000_000 // 2026-11-06, 21:20 UTC
    private let refused: Bool
    private var label = "iPhone 17"
    private var method: TrustedDevice.Method? = .deviceKey
    private var level: StaySignedIn? = .presence
    private var rows: [Row] = [
        Row(id: "f-here", created: now - 30 * day, used: now, current: true, label: "iPhone 17", removesKey: false),
        Row(id: "f-laptop", created: now - 12 * day, used: now - 2 * day, current: false, label: "Work laptop", removesKey: true),
        Row(id: "f-unknown", created: now - 3 * day, used: now - day, current: false, label: nil, removesKey: false),
    ]
    private var saved: [(String, String)] = [("dev-old", "Old phone")]
    private var otherKeys = true

    /// *Sync in the Background* as the core plays it (`ThisDevice.backgroundSync`): a switch at the login level on an
    /// iPhone, the reason it is missing at any other trust there, nothing on a Mac.
    private var backgroundOn = true
    private var background: (on: KotlinBoolean?, missing: Msg?) {
        #if os(iOS)
        if method == .deviceKey && level == .login { return (KotlinBoolean(value: backgroundOn), nil) }
        return (nil, .accountSettingsBackgroundSyncMissing)
        #else
        return (nil, nil)
        #endif
    }

    init(refused: Bool) { self.refused = refused }

    private func log(_ line: String) { NSLog("veyq-settings: %@", line) }

    var state: AccountSettingsState {
        let trust = AccountSettingsTrust(method: method, level: level, levels: [.presence, .login])
        if refused {
            return AccountSettingsState(
                email: "ada@example.org", emailConfirmed: nil, device: AccountSettingsDevice(label: label, familyId: nil, trust: trust),
                families: nil, savedKeyDevices: [], otherSavedKeys: false, addDevice: false, working: false,
                refusal: .accountSettingsLoadFailed, registerRefusal: .accountSettingsRegisterUnreadable, storage: nil,
                storageUnavailable: nil, askingRemoveAll: false, backgroundSync: nil, backgroundSyncMissing: nil, kit: nil)
        }
        return AccountSettingsState(
            email: "ada@example.org", emailConfirmed: KotlinBoolean(value: false),
            device: AccountSettingsDevice(label: label, familyId: "f-here", trust: trust),
            families: rows.map {
                AccountSettingsFamily(
                    id: $0.id, createdAt: KotlinLong(value: $0.created), lastUsedAt: KotlinLong(value: $0.used),
                    current: $0.current, label: $0.label, removesKey: $0.removesKey)
            },
            savedKeyDevices: saved.map { AccountSettingsSavedKeyDevice(deviceId: $0.0, label: $0.1) },
            otherSavedKeys: otherKeys, addDevice: true, working: false, refusal: nil, registerRefusal: nil, storage: nil,
            storageUnavailable: nil, askingRemoveAll: false, backgroundSync: background.on, backgroundSyncMissing: background.missing, kit: nil)
    }

    func closeSettings() { log("close") }
    func setBackgroundSync(_ on: Bool) {
        log("background sync \(on)")
        backgroundOn = on
    }
    func renameDevice(_ name: String) {
        log("rename")
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        label = trimmed
        rows = rows.map { var r = $0; if r.current { r.label = trimmed }; return r }
    }
    func renameFamily(_ familyId: String, _ name: String) {
        log("renameFamily \(familyId)")
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        rows = rows.map { var r = $0; if r.id == familyId, r.label != nil { r.label = trimmed }; return r }
    }
    func signOutFamily(_ familyId: String) {
        log("signOut \(familyId)")
        rows.removeAll { $0.id == familyId && !$0.current }
    }
    func signOutOthers() {
        log("signOutOthers")
        rows.removeAll { !$0.current }
    }
    func signOutAndRemoveKey(_ familyId: String) {
        log("signOutAndRemoveKey \(familyId)")
        rows.removeAll { $0.id == familyId }
    }
    func removeSavedKey(_ deviceId: String) {
        log("removeSavedKey \(deviceId)")
        saved.removeAll { $0.0 == deviceId }
    }
    func removeEveryOtherSavedKey() {
        log("removeEveryOtherSavedKey")
        saved = []
        otherKeys = false
        rows = rows.map { var r = $0; r.removesKey = false; return r }
    }
    func settingsStay(_ to: StaySignedIn) async -> TrustChange {
        log("stay \(to)")
        method = .deviceKey
        level = to
        return .done
    }
    func settingsBackToPassword(_ password: String) async -> TrustChange {
        log("backToPassword") // never the password
        method = .device
        level = nil
        return .done
    }
    func stopTrusting() {
        log("stopTrusting")
        method = nil
        level = nil
    }
    func signOutHere() { log("signOutHere") }
}

struct DevSettingsView: View {
    @State private var played: PlayedSettings

    init(variant: String) { _played = State(initialValue: PlayedSettings(refused: variant == "refused")) }

    var body: some View {
        NavigationStack {
            SettingsView(state: played.state, actions: played)
                .navigationTitle(say(.drawerSettings))
        }
        .tint(Role.accent)
    }
}
#endif
