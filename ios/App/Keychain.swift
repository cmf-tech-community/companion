import Foundation
import Security

/// Generic passwords of this app: the FMC token and the watch's pairing secret (both personal data).
enum Keychain {
    static func get(_ account: String) -> String? {
        var out: AnyObject?
        let q = query(account).merging([kSecReturnData as String: true]) { $1 }
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func set(_ account: String, _ value: String?) {
        SecItemDelete(query(account) as CFDictionary)
        guard let value else { return }
        let q = query(account).merging([
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]) { $1 }
        SecItemAdd(q as CFDictionary, nil)
    }

    /// Every account this app keeps (the per-watch keys are `k-<id>` and `stock-<id>`).
    static func accounts() -> [String] {
        var out: AnyObject?
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "tools.fmc.companion",
                                kSecMatchLimit as String: kSecMatchLimitAll, kSecReturnAttributes as String: true]
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let items = out as? [[String: Any]] else { return [] }
        return items.compactMap { $0[kSecAttrAccount as String] as? String }
    }

    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "tools.fmc.companion",
         kSecAttrAccount as String: account]
    }
}
