import Foundation
import Chencang

/// Swift facade over the Rust binding's `deriveSafetyEmoji`. The 512-entry
/// emoji dictionary and BLAKE2b derivation live on the Rust side so iOS and
/// Android stay byte-for-byte identical.
public struct EmojiFingerprint: Equatable, Sendable {
    public let emojis: [String]

    public init(sessionSecret: Data) throws {
        guard sessionSecret.count == 32 else {
            throw EmojiFingerprintError.badLength(sessionSecret.count)
        }
        self.emojis = try deriveSafetyEmoji(sessionSecret: sessionSecret)
    }

    public var display: String {
        emojis.joined(separator: " ")
    }
}

public enum EmojiFingerprintError: Error, Equatable {
    case badLength(Int)
}
