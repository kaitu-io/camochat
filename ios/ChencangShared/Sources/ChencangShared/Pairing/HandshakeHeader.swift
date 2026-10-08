import Foundation

/// CBOR handshake header relayed via the server at redeem (B → A). Carries B's
/// public identity + ephemeral pub keys + KEM ciphertexts + the 5-byte
/// session_id + B's chosen display name. NEVER includes the session root key.
///
/// **Wire compatibility is load-bearing**: this must encode/decode byte-for-byte
/// the same as chencang-android's `kotlinx.serialization.cbor` `HandshakeHeader`
/// (kotlinx-serialization-cbor 1.6.3), because A and B may be on different
/// platforms. The kotlinx specifics reproduced here:
///   - a class is an **indefinite-length map** (`0xBF … 0xFF`);
///   - keys are CBOR text strings = the property names, in declaration order;
///   - a `ByteArray` (no `@ByteString`) is a CBOR **array of integers** (major 4),
///     and each Kotlin `Byte` is *signed*: bytes 0x80–0xFF encode as CBOR
///     **negative** integers (major 1), bytes 0x00–0x7F as major 0;
///   - `encodeDefaults = false`, so a nil `bobDisplayName` is **omitted**.
/// The decoder additionally tolerates definite-length maps/arrays for safety.
public struct HandshakeHeader: Equatable, Sendable {
    public let bobIkX25519: Data
    public let bobIkEd25519: Data
    public let bobIkMlkem768: Data
    public let bobIkMldsa65: Data
    public let ekX25519Pub: Data
    public let ekMlkemPub: Data
    public let kemCtToSpk: Data
    public let kemCtToIk: Data
    public let sessionId: Data
    public let bobDisplayName: String?

    public init(
        bobIkX25519: Data, bobIkEd25519: Data, bobIkMlkem768: Data, bobIkMldsa65: Data,
        ekX25519Pub: Data, ekMlkemPub: Data, kemCtToSpk: Data, kemCtToIk: Data,
        sessionId: Data, bobDisplayName: String?
    ) {
        self.bobIkX25519 = bobIkX25519
        self.bobIkEd25519 = bobIkEd25519
        self.bobIkMlkem768 = bobIkMlkem768
        self.bobIkMldsa65 = bobIkMldsa65
        self.ekX25519Pub = ekX25519Pub
        self.ekMlkemPub = ekMlkemPub
        self.kemCtToSpk = kemCtToSpk
        self.kemCtToIk = kemCtToIk
        self.sessionId = sessionId
        self.bobDisplayName = bobDisplayName
    }

    // MARK: Encode

    public func encode() -> Data {
        var out = Data()
        out.append(0xBF) // indefinite-length map
        appendByteArrayEntry(&out, "bobIkX25519", bobIkX25519)
        appendByteArrayEntry(&out, "bobIkEd25519", bobIkEd25519)
        appendByteArrayEntry(&out, "bobIkMlkem768", bobIkMlkem768)
        appendByteArrayEntry(&out, "bobIkMldsa65", bobIkMldsa65)
        appendByteArrayEntry(&out, "ekX25519Pub", ekX25519Pub)
        appendByteArrayEntry(&out, "ekMlkemPub", ekMlkemPub)
        appendByteArrayEntry(&out, "kemCtToSpk", kemCtToSpk)
        appendByteArrayEntry(&out, "kemCtToIk", kemCtToIk)
        appendByteArrayEntry(&out, "sessionId", sessionId)
        if let name = bobDisplayName { // encodeDefaults=false: nil omitted
            appendTextKey(&out, "bobDisplayName")
            appendText(&out, name)
        }
        out.append(0xFF) // break
        return out
    }

    private func appendByteArrayEntry(_ out: inout Data, _ key: String, _ bytes: Data) {
        appendTextKey(&out, key)
        out.append(0x9F) // indefinite-length array
        for b in bytes { appendByteAsInt(&out, b) }
        out.append(0xFF) // break
    }

    private func appendTextKey(_ out: inout Data, _ key: String) { appendText(&out, key) }

    private func appendText(_ out: inout Data, _ s: String) {
        let u = Array(s.utf8)
        appendHead(&out, major: 3, value: UInt64(u.count))
        out.append(contentsOf: u)
    }

    /// Kotlin `Byte` is signed: high-bit-set bytes → CBOR negative integers.
    private func appendByteAsInt(_ out: inout Data, _ b: UInt8) {
        let signed = Int8(bitPattern: b)
        if signed >= 0 {
            appendHead(&out, major: 0, value: UInt64(signed))
        } else {
            appendHead(&out, major: 1, value: UInt64(-1 - Int(signed)))
        }
    }

    private func appendHead(_ out: inout Data, major: UInt8, value: UInt64) {
        let m = major << 5
        switch value {
        case ..<24:
            out.append(m | UInt8(value))
        case ..<0x100:
            out.append(m | 24); out.append(UInt8(value))
        case ..<0x1_0000:
            out.append(m | 25)
            out.append(UInt8(value >> 8)); out.append(UInt8(value & 0xFF))
        case ..<0x1_0000_0000:
            out.append(m | 26)
            for shift in stride(from: 24, through: 0, by: -8) { out.append(UInt8((value >> UInt64(shift)) & 0xFF)) }
        default:
            out.append(m | 27)
            for shift in stride(from: 56, through: 0, by: -8) { out.append(UInt8((value >> UInt64(shift)) & 0xFF)) }
        }
    }

    // MARK: Decode

    public static func decode(_ data: Data) throws -> HandshakeHeader {
        var c = Cursor(data)
        let entries = try c.readMapHeader()
        var fields: [String: Data] = [:]
        var name: String?
        var i = 0
        while true {
            if let n = entries { if i >= n { break } } else if c.peekBreak() { c.advance(); break }
            let key = try c.readTextString()
            if key == "bobDisplayName" {
                name = try c.readTextString()
            } else {
                fields[key] = try c.readByteArray()
            }
            i += 1
        }
        func req(_ k: String) throws -> Data {
            guard let v = fields[k] else { throw PairingError.malformed("handshake header missing \(k)") }
            return v
        }
        return HandshakeHeader(
            bobIkX25519: try req("bobIkX25519"),
            bobIkEd25519: try req("bobIkEd25519"),
            bobIkMlkem768: try req("bobIkMlkem768"),
            bobIkMldsa65: try req("bobIkMldsa65"),
            ekX25519Pub: try req("ekX25519Pub"),
            ekMlkemPub: try req("ekMlkemPub"),
            kemCtToSpk: try req("kemCtToSpk"),
            kemCtToIk: try req("kemCtToIk"),
            sessionId: try req("sessionId"),
            bobDisplayName: name
        )
    }

    /// Minimal CBOR reader covering the subset kotlinx emits for this struct:
    /// (in)definite maps, text strings, and (in)definite integer arrays whose
    /// elements are major-0/1 ints standing in for signed `Byte`s.
    private struct Cursor {
        let d: [UInt8]
        var i = 0
        init(_ data: Data) { d = [UInt8](data) }

        mutating func advance() { i += 1 }
        func peekBreak() -> Bool { i < d.count && d[i] == 0xFF }

        mutating func readByte() throws -> UInt8 {
            guard i < d.count else { throw PairingError.malformed("cbor truncated") }
            defer { i += 1 }
            return d[i]
        }

        /// Reads a head and returns (majorType, argumentValue). Caller handles
        /// indefinite (additional info 31) separately via the initial byte.
        mutating func readHead() throws -> (UInt8, UInt64) {
            let b = try readByte()
            let major = b >> 5
            let info = b & 0x1F
            switch info {
            case ..<24: return (major, UInt64(info))
            case 24: return (major, UInt64(try readByte()))
            case 25:
                let hi = UInt64(try readByte()); let lo = UInt64(try readByte())
                return (major, hi << 8 | lo)
            case 26:
                var v: UInt64 = 0
                for _ in 0..<4 { v = v << 8 | UInt64(try readByte()) }
                return (major, v)
            case 27:
                var v: UInt64 = 0
                for _ in 0..<8 { v = v << 8 | UInt64(try readByte()) }
                return (major, v)
            default:
                throw PairingError.malformed("cbor unsupported additional-info \(info)")
            }
        }

        /// Returns the entry count for a definite map, or nil for an indefinite map.
        mutating func readMapHeader() throws -> Int? {
            guard i < d.count else { throw PairingError.malformed("cbor truncated") }
            let b = d[i]
            guard b >> 5 == 5 else { throw PairingError.malformed("cbor expected map") }
            if b & 0x1F == 31 { i += 1; return nil }
            let (_, n) = try readHead()
            return Int(n)
        }

        mutating func readTextString() throws -> String {
            let (major, n) = try readHead()
            guard major == 3 else { throw PairingError.malformed("cbor expected text string") }
            guard i + Int(n) <= d.count else { throw PairingError.malformed("cbor text overrun") }
            let slice = d[i..<i + Int(n)]
            i += Int(n)
            guard let s = String(bytes: slice, encoding: .utf8) else {
                throw PairingError.malformed("cbor invalid utf8")
            }
            return s
        }

        /// Reads a CBOR array (major 4, definite or indefinite) of integers and
        /// rebuilds the byte string, mapping major-1 negatives back to bytes.
        mutating func readByteArray() throws -> Data {
            guard i < d.count else { throw PairingError.malformed("cbor truncated") }
            let b = d[i]
            guard b >> 5 == 4 else { throw PairingError.malformed("cbor expected array") }
            var out = Data()
            if b & 0x1F == 31 {
                i += 1 // consume indefinite array head
                while !peekBreak() { out.append(try readIntAsByte()) }
                i += 1 // consume break
            } else {
                let (_, n) = try readHead()
                for _ in 0..<Int(n) { out.append(try readIntAsByte()) }
            }
            return out
        }

        mutating func readIntAsByte() throws -> UInt8 {
            let (major, v) = try readHead()
            switch major {
            case 0: return UInt8(truncatingIfNeeded: Int(v))
            case 1: return UInt8(truncatingIfNeeded: -1 - Int(v))
            default: throw PairingError.malformed("cbor expected integer in byte array")
            }
        }
    }
}

// MARK: - Data hex helpers (used by HandshakeHeader CBOR tests + pairing transport)

extension Data {
    /// Lowercase hex, matching the server's `hexLower` + Android's `toHex()`.
    var ccHexLower: String {
        map { String(format: "%02x", $0) }.joined()
    }

    /// Parse a lowercase/uppercase hex string into bytes. Returns nil on odd
    /// length or any non-hex digit.
    init?(ccHex: String) {
        guard ccHex.count % 2 == 0 else { return nil }
        var out = Data(capacity: ccHex.count / 2)
        var idx = ccHex.startIndex
        while idx < ccHex.endIndex {
            let next = ccHex.index(idx, offsetBy: 2)
            guard let byte = UInt8(ccHex[idx..<next], radix: 16) else { return nil }
            out.append(byte)
            idx = next
        }
        self = out
    }
}
