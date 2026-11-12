import Foundation
import LocalAuthentication
import Security
import VeyqCore

/// The Keychain for core's `AppleDeviceKeys` (ADR 0123 decision 8): generic-password items of this app, on this device
/// only, never in a backup or iCloud. A protected item (the presence level's unlock key of the accounts the person stays
/// signed in to) carries `.userPresence` — Face ID, Touch ID or the passcode — and exists only while a passcode is set.
/// A passcode item (the login level's unlock key, ADR 0123 decision 8) asks nothing but exists
/// only while a passcode is set, readable while the device is unlocked.
///
/// Reading a protected item first asks the person through LocalAuthentication, once per start (`reuse`), and hands the
/// evaluated context to the Keychain, so the device's own enforcement of the item's access control asks nothing twice.
/// The explicit ask is what the iOS Simulator has: it stores the access control but does not enforce it (measured
/// 2026-11-08: a `.userPresence` item read with no prompt). Both ask the person; the Keychain's is the one a device's
/// Secure Enclave holds to. A read blocks the calling thread: core calls it off the main thread.
///
/// On iOS the items are the app's and its share extension's (ADR 0131): the entitlements' first keychain access group is
/// a shared one, where every new item goes. An item an installation from before it kept in the app's own group is moved
/// there when the app reads it ([movesLegacy]; the extension cannot see it until then).
final class KeychainStore: NSObject, KeychainBridge {
    private let service = "me.raddatz.veyq"
    /// The shared group's name without the team prefix the entitlements put before it.
    static let sharedGroupSuffix = "me.raddatz.veyq.shared"
    private let movesLegacy: Bool

    /// [movesLegacy]: the app moves an item it finds outside the shared group; the extension only reads.
    init(movesLegacy: Bool) {
        self.movesLegacy = movesLegacy
    }
    private let reuse: TimeInterval = 15
    private var context = LAContext()
    private var authenticatedAt: Date?
    private let lock = NSLock()

    private func base(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }

    func store(account: String, data: Data, protected: Bool, passcode: Bool) -> Bool {
        var query = base(account)
        query[kSecValueData as String] = data
        if protected {
            var error: Unmanaged<CFError>?
            guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .userPresence, &error) else {
                return false
            }
            query[kSecAttrAccessControl as String] = access
        } else if passcode {
            query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
        } else {
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        }
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    func load(account: String, prompt: String, protected: Bool) -> KeychainRead {
        var query = base(account)
        query[kSecReturnData as String] = true
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        if protected {
            guard let evaluated = authenticate(prompt) else { return KeychainRead(data: nil, cancelled: true) }
            query[kSecUseAuthenticationContext as String] = evaluated
        }
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        if status == errSecSuccess, let item = out as? [String: Any], let data = item[kSecValueData as String] as? Data {
            let passcode = !protected && (item[kSecAttrAccessible as String] as? String) == (kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly as String)
            moveIntoSharedGroup(account, data: data, protected: protected, passcode: passcode, group: item[kSecAttrAccessGroup as String] as? String)
            return KeychainRead(data: data, cancelled: false)
        }
        return KeychainRead(data: nil, cancelled: status == errSecUserCanceled || status == errSecAuthFailed)
    }

    /// The person's Face ID, Touch ID or passcode, asked once within `reuse` and blocking the calling thread; the
    /// evaluated context, or nil when they did not authenticate.
    private func authenticate(_ prompt: String) -> LAContext? {
        lock.lock()
        defer { lock.unlock() }
        if let at = authenticatedAt, Date().timeIntervalSince(at) < reuse { return context }
        let fresh = LAContext()
        fresh.touchIDAuthenticationAllowableReuseDuration = reuse
        let done = DispatchSemaphore(value: 0)
        var ok = false
        fresh.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: prompt) { success, _ in
            ok = success
            done.signal()
        }
        done.wait()
        guard ok else { return nil }
        context = fresh
        authenticatedAt = Date()
        return fresh
    }

    /// An item read from outside the shared group stored again into it (the default group) and removed where it was.
    private func moveIntoSharedGroup(_ account: String, data: Data, protected: Bool, passcode: Bool, group: String?) {
        #if os(iOS)
        guard movesLegacy, let group, !group.hasSuffix(Self.sharedGroupSuffix) else { return }
        guard store(account: account, data: data, protected: protected, passcode: passcode) else { return }
        var legacy = base(account)
        legacy[kSecAttrAccessGroup as String] = group
        SecItemDelete(legacy as CFDictionary)
        #endif
    }

    func remove(account: String) {
        SecItemDelete(base(account) as CFDictionary)
    }

    /// Every item of this app. iOS keeps Keychain items when an app is deleted; a new installation, whose records are
    /// gone with its defaults, removes them before it starts, so no refresh token outlives the app that kept it.
    func removeAll() {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecUseDataProtectionKeychain as String: true,
        ] as CFDictionary)
    }
}
