import Foundation
@testable import ChencangShared

/// In-memory drop-in for `AppGroupDefaults` so we don't need the App Group
/// entitlement (or the simulator's special handling) during unit tests.
public final class FakeAppGroupDefaults: AppGroupDefaults, @unchecked Sendable {
    private var storage: [String: Data] = [:]

    public init() {}

    public func data(forKey: String) -> Data? {
        storage[forKey]
    }

    public func set(_ value: Data?, forKey: String) {
        if let v = value {
            storage[forKey] = v
        } else {
            storage.removeValue(forKey: forKey)
        }
    }
}
