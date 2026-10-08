import Foundation
@testable import ChencangShared

/// In-memory KeychainStore double for unit tests. Tracks `delete` and
/// `write` calls so tests can assert on side effects.
public final class FakeKeychain: KeychainStoreProtocol, @unchecked Sendable {
    public private(set) var items: [String: Data] = [:]
    public private(set) var writeCount: Int = 0
    public private(set) var deleteCount: Int = 0

    /// When set, `read(key:)` throws this instead of looking `items` up —
    /// simulates a real Keychain error that is NOT "item not found" (e.g.
    /// `errSecInteractionNotAllowed` while the device is locked). Real
    /// `KeychainStore.read` only returns `nil` for the not-found case; every
    /// other OSStatus throws `KeychainError.unhandled`, so this double needs
    /// a way to model that distinct path.
    public var readError: Error?

    /// When set, `delete(key:)` throws this instead of removing the item —
    /// simulates a Keychain delete failure (e.g. mid-wipe). Mirrors
    /// `readError`'s shape.
    public var deleteError: Error?

    public init(seed: [String: Data] = [:]) {
        self.items = seed
    }

    public func read(key: String) throws -> Data? {
        if let readError { throw readError }
        return items[key]
    }

    public func write(key: String, data: Data) throws {
        items[key] = data
        writeCount += 1
    }

    public func delete(key: String) throws {
        if let deleteError { throw deleteError }
        items.removeValue(forKey: key)
        deleteCount += 1
    }
}
