import CryptoKit
import Foundation
import Security
import VeyqCore

/// The Keychain for core's `AppleDeviceKeys` on a TV (ADR 0159, ADR 0160): generic-password items of this app, on this
/// device only, never in a backup — the iOS app's `KeychainStore` without LocalAuthentication, which tvOS has not. A TV
/// has no passcode, so a protected or passcode-bound item is refused (core offers neither level there: its
/// `staySignedIn` is empty on tvOS); what it keeps is its refresh tokens and bind keys, readable after the first unlock,
/// and the PIN level's Secure Enclave keys.
///
/// **The PIN's hardware factor** (ADR 0160 decision 2): per account a Secure Enclave P-256 key-agreement key; its ECDH
/// agreement with [Self.point] — a fixed public point whose discrete log nobody knows — is the fixed-input function the
/// enclave computes (it computes no HMAC); the core turns the agreement into `F` through HKDF (`ApplePinFactors`). The
/// enclave's key never leaves it: what is kept here is CryptoKit's `dataRepresentation`, a blob only this device's
/// enclave opens, as a generic-password item `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
final class TvKeychain: NSObject, KeychainBridge {
    private let service = "app.veyq"
    /// The PIN keys' items apart from the core's own (whose aliases are the same strings).
    private let pinService = "app.veyq.pin"

    private func base(_ account: String, service: String? = nil) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service ?? self.service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }

    func store(account: String, data: Data, protected: Bool, passcode: Bool) -> Bool {
        // a TV has no passcode and no presence check: nothing it keeps can be bound to them
        if protected || passcode { return false }
        return add(base(account), data)
    }

    func load(account: String, prompt: String, protected: Bool) -> KeychainRead {
        if protected { return KeychainRead(data: nil, cancelled: false, locked: false) }
        let (data, status) = read(base(account))
        return KeychainRead(data: data, cancelled: false, locked: status == errSecInteractionNotAllowed)
    }

    func remove(account: String) {
        SecItemDelete(base(account) as CFDictionary)
    }

    /// Every item of this app: a first start after an installation removes what an earlier one left (tvOS keeps
    /// Keychain items when an app is deleted), so no refresh token or PIN key outlives the app that kept it.
    func removeAll() {
        for s in [service, pinService] {
            SecItemDelete([
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: s,
                kSecUseDataProtectionKeychain as String: true,
            ] as CFDictionary)
        }
    }

    // MARK: The PIN level's factor (ADR 0160 decision 2)

    /// An enclave this app may make keys in, and the fixed point recomputed as embedded. The tvOS 27.2 Simulator on Apple
    /// silicon reports one: it offered the PIN level, kept it and opened it (measured 2026-11-26, the tvOS pass) — no
    /// check of the simulator either way.
    func pinKeysAvailable() -> Bool { SecureEnclave.isAvailable && Self.point != nil }

    func createPinKey(account: String) -> Bool {
        removePinKey(account: account)
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, &error),
              let key = try? SecureEnclave.P256.KeyAgreement.PrivateKey(accessControl: access) else { return false }
        return add(base(account, service: pinService), key.dataRepresentation)
    }

    func pinAgreement(account: String) -> Data? {
        guard let point = Self.point, let blob = read(base(account, service: pinService)).0,
              let key = try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: blob),
              let shared = try? key.sharedSecretFromKeyAgreement(with: point) else { return nil }
        return shared.withUnsafeBytes { Data($0) }
    }

    func removePinKey(account: String) {
        SecItemDelete(base(account, service: pinService) as CFDictionary)
    }

    /// The fixed point P, compressed (SEC 1): `02 ‖ x`. Derived by try-and-increment hash-to-curve — for i = 0, 1, …:
    /// x = SHA-256(`veyq/v1/pin-bind/point` ‖ byte i) as a big-endian integer; the first x below p for which
    /// x³ − 3x + b is a square on P-256 is P's x (i = 1; i = 0 is no square), and y is its even root. Nobody knows the
    /// discrete log of a point found this way; the generator or any point with a known scalar would make F computable
    /// from the public key alone, so it is never used. Recomputed by [derive] at first use: a mismatch offers no PIN.
    static let embedded = "02f4729c32f5d9f06a853cf14a8160e6076bdc1e22fa0f06cdb5adaeee8a81b896"

    static let point: P256.KeyAgreement.PublicKey? = {
        guard let derived = derive(), hex(derived.compressedRepresentation) == embedded else { return nil }
        return derived
    }()

    /// The derivation above, through CryptoKit: a compressed point decodes only when x is below p and on the curve
    /// (x³ − 3x + b a square), and `02` takes the even root.
    static func derive() -> P256.KeyAgreement.PublicKey? {
        let domain = Data("veyq/v1/pin-bind/point".utf8)
        for i in 0...255 {
            let x = Data(SHA256.hash(data: domain + [UInt8(i)]))
            if let p = try? P256.KeyAgreement.PublicKey(compressedRepresentation: Data([0x02]) + x) { return p }
        }
        return nil
    }

    private static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    // MARK: Items

    private func add(_ query: [String: Any], _ data: Data) -> Bool {
        var q = query
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    private func read(_ query: [String: Any]) -> (Data?, OSStatus) {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        return (status == errSecSuccess ? out as? Data : nil, status)
    }
}
