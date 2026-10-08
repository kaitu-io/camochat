import Chencang
import Foundation

/// 信封 `{"p": base64url(payload), "s": base64url(sig)}` → 已验签、已校验的 `AppConfig`。
/// 校验规则与 Android `SignedConfigCodec` 一一对应(含 `android` 段:iOS 不用,但同样要合法才收)。
public enum SignedConfigCodec {
    public static let productionVerify: (Data, Data) -> Bool = { verifySignedConfig(payload: $0, sig: $1) }

    private struct Envelope: Decodable { let p: String; let s: String }

    private struct Payload: Decodable {
        let schema: Int
        let seq: Int64
        let sources: [String]
        let relays: [String]
        let shareSite: String
        let android: Android?

        struct Android: Decodable {
            let minVersionCode: Int
            let latest: Latest
        }

        struct Latest: Decodable {
            let versionCode: Int
            let versionName: String
            let sha256: String
            let size: Int64
            let mirrors: [String]
        }
    }

    /// 任何失败(信封坏、签名坏、schema 不对、校验不过)一律返回 nil。
    public static func decode(_ envelope: Data,
                              verify: (Data, Data) -> Bool = SignedConfigCodec.productionVerify) -> AppConfig? {
        guard let env = try? JSONDecoder().decode(Envelope.self, from: envelope),
              let payload = base64URL(env.p), let sig = base64URL(env.s),
              verify(payload, sig),
              let p = try? JSONDecoder().decode(Payload.self, from: payload),
              valid(p) else { return nil }
        return AppConfig(schema: p.schema, seq: p.seq, sources: p.sources, relays: p.relays, shareSite: p.shareSite)
    }

    private static func base64URL(_ s: String) -> Data? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b)
    }

    private static func https(_ u: String) -> Bool { u.hasPrefix("https://") }

    private static func isLowerHex64(_ s: String) -> Bool {
        s.utf8.count == 64 && s.utf8.allSatisfy { ($0 >= 48 && $0 <= 57) || ($0 >= 97 && $0 <= 102) }
    }

    private static func valid(_ c: Payload) -> Bool {
        if c.schema != 1 { return false }
        if c.sources.isEmpty || c.relays.isEmpty { return false }
        if !c.sources.allSatisfy({ https($0) && $0.hasSuffix("/") }) { return false }
        if !c.relays.allSatisfy({ https($0) && !$0.hasSuffix("/") }) { return false }
        if !https(c.shareSite) || !c.shareSite.hasSuffix("/") { return false }
        guard let a = c.android else { return true }
        let l = a.latest
        return isLowerHex64(l.sha256) && l.versionCode >= a.minVersionCode
            && !l.mirrors.isEmpty && l.mirrors.allSatisfy(https)
    }
}
