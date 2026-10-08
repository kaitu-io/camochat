import Foundation
import CryptoKit
import Chencang

/// In-band pairing transport + 🔒 wire disambiguation.
///
/// Both pairing blobs (A→B ``ClassicalPreKeyBundle``, B→A
/// ``ClassicalInbandHeader``) and DR session ciphertext (text and media alike)
/// travel over the SAME 🔒 (U+1F512) wire — `encodeWire` prepends 🔒 +
/// Base32768(bytes). A received/pasted 🔒 text must be disambiguated into
/// session-ciphertext vs pairing-bundle vs pairing-header BEFORE it is fed to
/// the right consumer.
///
/// To make that robust we wrap the pairing CBOR in an explicit 2-byte ENVELOPE
/// prefix rather than sniffing CBOR map-header bytes (which shift when optional
/// fields like `inviterUsername` / `bobDisplayName` are omitted):
///
/// ```
/// pairing wire bytes = [0xCB, type] ++ CBOR
///   type 0x01 = bundle (A→B)
///   type 0x02 = header (B→A)
/// ```
///
/// `0xCB` sits one below the session wire MAGIC's first byte `0xCC`
/// (`core/src/wire/header.rs` `MAGIC = [0xCC, 0xC8]`), so a pairing envelope can
/// NEVER collide with session ciphertext on the very first byte.
///
/// CROSS-PLATFORM CONTRACT: this 0xCB / 0x01 / 0x02 envelope is byte-identical
/// to chencang-android's `PairingTransport`. Do not change these constants
/// without updating both platforms in lockstep.
public enum PairingTransport {

    /// Envelope discriminator byte — distinct from session MAGIC[0] (0xCC).
    public static let pairingMagic0: UInt8 = 0xCB

    /// Envelope type: A→B classical prekey bundle.
    public static let typeBundle: UInt8 = 0x01

    /// Envelope type: B→A classical in-band response header.
    public static let typeHeader: UInt8 = 0x02

    /// Session wire MAGIC — mirror of `core/src/wire/header.rs` `MAGIC = [0xCC, 0xC8]`.
    private static let sessionMagic0: UInt8 = 0xCC
    private static let sessionMagic1: UInt8 = 0xC8

    /// What a 🔒 wire text decoded to, after inspecting its leading bytes.
    public enum WireKind: Equatable {
        /// L3 session ciphertext (DR wire, text and media alike).
        case session
        /// A→B pairing prekey bundle (0xCB 0x01 envelope).
        case pairingBundle
        /// B→A pairing response header (0xCB 0x02 envelope).
        case pairingHeader
        /// Not a 🔒 wire, or an unrecognised / too-short payload.
        case unknown
    }

    /// Wrap an A→B bundle CBOR as a 🔒 pairing wire.
    public static func bundleToWire(_ cbor: Data) -> String {
        encodeWire(ciphertext: Data([pairingMagic0, typeBundle]) + cbor)
    }

    /// Wrap a B→A header CBOR as a 🔒 pairing wire.
    public static func headerToWire(_ cbor: Data) -> String {
        encodeWire(ciphertext: Data([pairingMagic0, typeHeader]) + cbor)
    }

    /// Classify a received/pasted text. Non-🔒 text, undecodable bodies, and
    /// unrecognised / too-short payloads all map to ``WireKind/unknown`` — never
    /// a throw.
    public static func classify(_ wireText: String) -> WireKind {
        guard let bytes = try? decodeWire(s: wireText), bytes.count >= 2 else {
            return .unknown
        }
        let b0 = bytes[bytes.startIndex]
        let b1 = bytes[bytes.startIndex + 1]
        switch (b0, b1) {
        case (sessionMagic0, sessionMagic1): return .session
        case (pairingMagic0, typeBundle): return .pairingBundle
        case (pairingMagic0, typeHeader): return .pairingHeader
        default: return .unknown
        }
    }

    /// Strip the 🔒 wrapper AND the 2-byte pairing envelope, returning the raw
    /// CBOR ready to feed into the engine's `decodeClassicalBundle` /
    /// `decodeClassicalHeader`.
    ///
    /// - Throws: ``PairingError/malformed(_:)`` if `wireText` is not a pairing
    ///   wire (non-🔒, session ciphertext, or unrecognised envelope).
    public static func pairingPayload(_ wireText: String) throws -> Data {
        let kind = classify(wireText)
        guard kind == .pairingBundle || kind == .pairingHeader else {
            throw PairingError.malformed("not a pairing wire (kind=\(kind))")
        }
        // classify already proved this decodes and carries the 2-byte envelope.
        let bytes = try decodeWire(s: wireText)
        return bytes.subdata(in: bytes.index(bytes.startIndex, offsetBy: 2)..<bytes.endIndex)
    }

    /// 一份邀请的摘要:邀请负载字节(去掉 🔒 包装与 2 字节信封)的 SHA-256,小写十六进制。只含公开内容。
    ///
    /// - Throws: ``PairingError/malformed(_:)`` if `wireText` is not a pairing bundle.
    public static func inviteDigest(_ wireText: String) throws -> String {
        guard classify(wireText) == .pairingBundle else {
            throw PairingError.malformed("not a pairing invite")
        }
        return SHA256.hash(data: try pairingPayload(wireText)).map { String(format: "%02x", $0) }.joined()
    }

    /// 在粘贴文本里找出配对码:候选(每行首个 🔒 起的子串)从后往前,取第一个能识别的;
    /// 都不认再找配对链接;仍没有则保持旧行为(首个 🔒 起裁空白;没有 🔒 就只裁空白,之后归为 ``WireKind/unknown``)。
    /// 容忍首行说明与微信「他发来的：」类前缀。
    public static func locate(_ raw: String) -> String {
        for candidate in IntakeClassifier.lockCandidates(raw).reversed() where classify(candidate) != .unknown {
            return candidate
        }
        // 没有可认的 🔒 段:取文本里最后一个带配对码的链接(`PairingLink`),转回标准 wire。
        if let wire = PairingLink.wires(in: raw).last { return wire }
        if let r = raw.range(of: "\u{1F512}") {
            return String(raw[r.lowerBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
