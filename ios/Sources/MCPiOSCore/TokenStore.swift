import Foundation
import Security

/// Token 256-bit persistito in Keychain (kSecClassGenericPassword).
/// Mai trasmesso in chiaro sulla LAN: solo prove HMAC + AES-256-GCM.
public enum TokenStore {
    private static let service = "com.example.mcpios.token"
    private static let account = "mcp-token"

    public static func load() -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: account,
                                kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let s = String(data: data, encoding: .utf8) else { return nil }
        return LanChannel.isValidToken(s) ? s.lowercased() : nil
    }

    @discardableResult
    public static func rotate() -> String {
        var b = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, 32, &b)
        let token = Data(b).hexString
        save(token)
        return token
    }

    public static func save(_ token: String) {
        let t = token.lowercased()
        guard LanChannel.isValidToken(t), let data = t.data(using: .utf8) else { return }
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: account]
        let attrs: [String: Any] = [kSecValueData as String: data,
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        if SecItemUpdate(q as CFDictionary, attrs as CFDictionary) != errSecSuccess {
            var add = q; add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }
}
