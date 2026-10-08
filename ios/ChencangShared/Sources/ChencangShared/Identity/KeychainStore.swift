import Foundation
import Security

/// Abstraction over the Keychain so tests can mock without entitlement plumbing.
public protocol KeychainStoreProtocol: Sendable {
    func read(key: String) throws -> Data?
    func write(key: String, data: Data) throws
    func delete(key: String) throws
}

public enum KeychainError: Error, Equatable, LocalizedError {
    case unhandled(OSStatus)

    public var errorDescription: String? {
        switch self {
        case .unhandled(let status):
            let msg = (SecCopyErrorMessageString(status, nil) as String?) ?? "unknown"
            return "Keychain error (OSStatus \(status)): \(msg)"
        }
    }
}

/// Generic-password backed Keychain wrapper. Uses
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` so the Action Extension
/// can read after the first unlock but secrets never leave the device. The
/// access group must match the entitlement:
/// `$(AppIdentifierPrefix)app.chencang.shared`.
public final class KeychainStore: KeychainStoreProtocol, @unchecked Sendable {
    private let service: String
    private let accessGroup: String?

    public init(
        service: String = "app.chencang.identity",
        accessGroup: String? = nil
    ) {
        self.service = service
        self.accessGroup = accessGroup
    }

    public func read(key: String) throws -> Data? {
        var query = baseQuery(key: key)
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnData as String] = true
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        if status != errSecSuccess { throw KeychainError.unhandled(status) }
        return item as? Data
    }

    public func write(key: String, data: Data) throws {
        let query = baseQuery(key: key)
        let attrs: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if updateStatus == errSecSuccess { return }
        if updateStatus != errSecItemNotFound {
            throw KeychainError.unhandled(updateStatus)
        }
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        if addStatus != errSecSuccess {
            throw KeychainError.unhandled(addStatus)
        }
    }

    public func delete(key: String) throws {
        let query = baseQuery(key: key)
        let status = SecItemDelete(query as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            throw KeychainError.unhandled(status)
        }
    }

    private func baseQuery(key: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        if let g = accessGroup {
            q[kSecAttrAccessGroup as String] = g
        }
        return q
    }
}
