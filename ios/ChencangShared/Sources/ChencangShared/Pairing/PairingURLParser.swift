import Foundation

/// Parses the `camo://pairing/<code>` deep link into an invite code. No Universal Links.
public enum PairingURLParser {
    public static func parse(url: URL) -> String? {
        // Custom scheme: camo://pairing/<code> or camo://pairing?code=<code>
        if url.scheme == AppURLScheme.name {
            let host = url.host ?? ""
            let parts = url.pathComponents.filter { $0 != "/" }
            if host == "pairing" {
                if let last = parts.last, !last.isEmpty {
                    return last
                }
                if let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
                   let code = comps.queryItems?.first(where: { $0.name == "code" })?.value,
                   !code.isEmpty {
                    return code
                }
            }
        }
        return nil
    }

    /// 链接里带的若是完整配对码(新形态:整段 🔒 配对码放进 `code=` 或路径),返回它;
    /// 老的短码(如 `MCRE-NDQ4-7BPR`)或其它内容返回 nil。
    public static func pairingWire(url: URL) -> String? {
        guard let code = parse(url: url) else { return nil }
        switch IntakeClassifier.classify(code) {
        case let .pairingInvite(wire), let .pairingResponse(wire):
            return wire
        case .empty, .notOurs, .incomplete, .linkOnly, .sessionMessage:
            return nil
        }
    }
}
