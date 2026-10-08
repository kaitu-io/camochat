import Foundation

/// 扩展里认出的配对码是哪一方的:对方发来的第一段(邀请),还是对方发回的那段(回复)。
public enum PairingCodeKind: Equatable {
    case invite
    case response
}

/// 扩展「陈仓解密」处理一段选中文字的结果。
public enum ActionIntakeResult: Equatable {
    case opened(ActionDecryptor.Opened)
    /// 配对码:原样交还给界面,由用户点「打开陈仓添加」时放进 `PendingIntakeStore`。扩展不握手。
    case pairingCode(PairingCodeKind, wire: String)
    case failed(IntakeFailure)
}

/// Action Extension 的解密编排:归类 → 解 wire → 写收件箱 → 返回展示件。
/// 归类映射同 `IntakeRouter`(但扩展进程绝不调用 `IntakeRouter`——它会写 `ChatStore`)。
/// 配对码不碰 crypto、不写收件箱,只交还给界面。
/// 扩展进程解密会推进 DR 棘轮并写回 Keychain(SessionThreadCrypto 内部保证);
/// 主 App 回前台经 invalidateCache 重载 —— 见 M3 plan 全局纪律。
/// 媒体消息只把引用写进收件箱,**从不下载**(spec §4.2),卡片提示打开主 App 查看。
public struct ActionDecryptor {
    public struct Opened: Equatable {
        public let peerId: String
        public let peerName: String
        public let text: String
        public let entryId: String
        public let kind: MessageKind
        public let mediaCount: Int
    }

    private let crypto: ThreadCrypto
    private let readContacts: () -> [AppGroupContact]
    private let inbox: InboxWriting
    private let hasAwaitingInvites: () -> Bool
    private let now: () -> Date
    private let newId: () -> String
    private let kindOf: (String) -> PairingTransport.WireKind

    public init(crypto: ThreadCrypto,
                readContacts: @escaping () -> [AppGroupContact],
                inbox: InboxWriting,
                hasAwaitingInvites: @escaping () -> Bool,
                now: @escaping () -> Date = Date.init,
                newId: @escaping () -> String = { UUID().uuidString },
                kindOf: @escaping (String) -> PairingTransport.WireKind = PairingTransport.classify) {
        self.crypto = crypto; self.readContacts = readContacts
        self.inbox = inbox; self.hasAwaitingInvites = hasAwaitingInvites; self.now = now; self.newId = newId
        self.kindOf = kindOf
    }

    public func open(_ raw: String) async -> ActionIntakeResult {
        switch IntakeClassifier.classify(raw, kindOf: kindOf) {
        case .empty, .incomplete, .linkOnly:
            return .failed(.incomplete)
        case .notOurs:
            return .failed(.notOurs)
        case let .pairingInvite(wire):
            return .pairingCode(.invite, wire: wire)
        case let .pairingResponse(wire):
            return .pairingCode(.response, wire: wire)
        case let .sessionMessage(wire):
            return await openSession(wire)
        }
    }

    private func openSession(_ wire: String) async -> ActionIntakeResult {
        let contacts = readContacts()
        // 解不开(含还没有联系人)时若手里有待完成邀请,多半是还没加完的人发来的(同 `IntakeRouter`)。
        guard !contacts.isEmpty else { return .failed(hasAwaitingInvites() ? .pendingInvite : .noContacts) }
        guard let opened = await crypto.openWire(wire, candidates: contacts.map(\.id)) else {
            return .failed(hasAwaitingInvites() ? .pendingInvite : .cannotOpen)
        }
        let entry = InboxEntry(opened: opened, id: newId(), timestamp: now())
        do {
            try inbox.append(entry)
        } catch {
            // append 内部的 encode 几乎不可能抛——InboxEntry 是纯值类型(String/Date/
            // 定长 Data),JSONEncoder 对它没有失败路径;真出错基本只会是 App Group
            // UserDefaults 配额/容器不可用。棘轮已经推进、这条密文不会有第二次机会:
            // 文字/占位反正当场就给用户看了,不必因为存不进收件箱就藏起来;媒体只有
            // 一条 pending 引用,收件箱是它唯一的落脚点,写不进去就是真丢了,必须让
            // 用户知道去重试而不是误以为「打开陈仓」就能找到。
            if entry.kind.isMedia {
                return .failed(.saveFailed)
            }
        }
        let name = contacts.first { $0.id == entry.peerId }?.displayName ?? L10n.commonContactFallback
        return .opened(Opened(peerId: entry.peerId, peerName: name, text: entry.body, entryId: entry.id,
                              kind: entry.kind, mediaCount: entry.media?.count ?? 0))
    }
}
