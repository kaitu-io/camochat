import Foundation

/// 来件(粘贴 / 分享进来的文本)归类结果。
public enum IntakeKind: Equatable {
    case empty
    case notOurs
    /// 没有 🔒,整段只是一条路径恰为 `/m/` 或 `/p/` 的 https 链接:长按加密消息时只选中了首行链接,
    /// 🔒 那段没复制上。
    case linkOnly
    /// 含 🔒,但没有任何一段能解出已知种类(被截断或损坏)。
    case incomplete
    case sessionMessage(wire: String)
    case pairingInvite(wire: String)
    case pairingResponse(wire: String)
}

/// 单一入口:识别任何 🔒 内容,容忍首行说明与聊天软件的昵称前缀。
public enum IntakeClassifier {
    private static let trailingJunk = CharacterSet.whitespacesAndNewlines
        .union(CharacterSet(charactersIn: "\"'”’」』》)）"))
    /// 只判「整段是一条链接」时用:开头的引号也一并去掉。
    private static let quoteJunk = trailingJunk.union(CharacterSet(charactersIn: "“‘「『《(（"))

    public static func classify(
        _ raw: String,
        kindOf: (String) -> PairingTransport.WireKind = PairingTransport.classify
    ) -> IntakeKind {
        if raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .empty }
        let candidates = lockCandidates(raw)
        for candidate in candidates.reversed() {
            switch kindOf(candidate) {
            case .session: return .sessionMessage(wire: candidate)
            case .pairingBundle: return .pairingInvite(wire: candidate)
            case .pairingHeader: return .pairingResponse(wire: candidate)
            case .unknown: continue
            }
        }
        // 没有任何 🔒 段被认出:再找带配对码的链接(取最后一个合法的)。
        for wire in PairingLink.wires(in: raw).reversed() {
            switch kindOf(wire) {
            case .pairingBundle: return .pairingInvite(wire: wire)
            case .pairingHeader: return .pairingResponse(wire: wire)
            default: continue
            }
        }
        if candidates.isEmpty { return isShareLinkOnly(raw) ? .linkOnly : .notOurs }
        return .incomplete
    }

    /// 去首尾空白与引号后,整段是否就是一条 `https://<任意主机>/m/` 或 `/p/` 链接(不带查询与片段)。
    static func isShareLinkOnly(_ raw: String) -> Bool {
        let text = raw.trimmingCharacters(in: quoteJunk)
        guard !text.contains(where: { $0.isWhitespace }),
              let parts = URLComponents(string: text),
              parts.scheme?.lowercased() == "https",
              let host = parts.host, !host.isEmpty,
              parts.query == nil, parts.fragment == nil
        else { return false }
        return parts.path == "/m/" || parts.path == "/p/"
    }

    /// 按行拆;每行从第一个 🔒 起截,去尾部空白与收尾引号;顺序同原文(调用方从后往前试)。
    public static func lockCandidates(_ text: String) -> [String] {
        text.components(separatedBy: .newlines).compactMap { line in
            guard let lock = line.range(of: "🔒") else { return nil }
            return String(line[lock.lowerBound...]).trimmingCharacters(in: trailingJunk)
        }
    }
}
