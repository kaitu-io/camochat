import CryptoKit
import Foundation
@testable import ChencangShared

/// 测试用假签名:SHA-256(payload) 重复两遍(64 字节)。
enum ConfigTestSupport {
    static func fakeSig(_ p: Data) -> Data {
        let h = Data(SHA256.hash(data: p))
        return h + h
    }

    static let fakeVerify: (Data, Data) -> Bool = { p, s in s == fakeSig(p) }

    static func b64(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func envelope(_ payload: String, sig: Data? = nil) -> Data {
        let p = Data(payload.utf8)
        return Data(#"{"p":"\#(b64(p))","s":"\#(b64(sig ?? fakeSig(p)))"}"#.utf8)
    }

    static let sha = "e1bf99ef41e4c63ad1eb453e1e076209e55fc22b609a20be17213158f8d79927"

    static func payload(
        seq: Int64 = 1, schema: Int = 1,
        sources: String = #"["https://a.example/c/"]"#,
        relays: String = #"["https://r.example"]"#,
        shareSite: String = "https://s.example/",
        sha: String = ConfigTestSupport.sha,
        versionCode: Int = 3, minVersionCode: Int = 3,
        mirrors: String = #"["https://m.example/x.apk"]"#,
        extra: String = ""
    ) -> String {
        """
        {"schema":\(schema),"seq":\(seq),"sources":\(sources),"relays":\(relays),"shareSite":"\(shareSite)"\(extra),
        "android":{"minVersionCode":\(minVersionCode),"latest":{"versionCode":\(versionCode),"versionName":"1.0","sha256":"\(sha)","size":10,"mirrors":\(mirrors)}}}
        """
    }

    static func env(_ seq: Int64, sources: String = #"["https://a.example/c/"]"#) -> Data {
        envelope(payload(seq: seq, sources: sources))
    }
}
