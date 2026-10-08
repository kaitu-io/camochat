import Foundation

public enum PairingError: Error, Equatable {
    case noIdentity
    case notImplemented(String)
    case malformed(String)
    case state(String)
}
