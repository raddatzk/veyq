import Foundation

/// What the app and its share extension hold together (ADR 0126): on iOS the app group's defaults — the device's records
/// and trust marks, its recent and hidden lists — and, through the entitlements' first keychain access group, its
/// Keychain items (`KeychainStore`). macOS has no extension yet and keeps the app's own.
enum Shared {
    static let appGroup = "group.me.raddatz.veyq"

    /// The defaults both read: the app group's suite on iOS, the app's own on macOS. (`UserDefaults` is documented
    /// thread-safe; Swift does not know it as `Sendable`.)
    nonisolated(unsafe) static let defaults: UserDefaults = {
        #if os(iOS)
        UserDefaults(suiteName: appGroup) ?? .standard
        #else
        .standard
        #endif
    }()

    /// Moves what an installation from before the app group kept in its own defaults into the group's, once: every
    /// key of veyq's (`veyq.` and core's `veyq:`), so a trusted account stays trusted. The app's alone; the extension
    /// only reads.
    static func moveIntoGroup() {
        #if os(iOS)
        guard defaults !== UserDefaults.standard, !defaults.bool(forKey: "veyq.group-moved") else { return }
        for (key, value) in UserDefaults.standard.dictionaryRepresentation() where key.hasPrefix("veyq") {
            if defaults.object(forKey: key) == nil { defaults.set(value, forKey: key) }
            UserDefaults.standard.removeObject(forKey: key)
        }
        defaults.set(true, forKey: "veyq.group-moved")
        #endif
    }
}
