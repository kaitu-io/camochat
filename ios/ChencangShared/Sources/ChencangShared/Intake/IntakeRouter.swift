import Foundation

/// 来件分流结果。
public enum IntakeRoute: Equatable {
    /// 会话消息已解开并落库,打开这条对话并定位到这条消息。
    case openThread(peerId: String, messageId: String)
    /// 配对码:交给「添加联系人」向导处理(这里不握手)。
    case pairing(wire: String)
    case failed(IntakeFailure)
}

/// 主 App 内的来件单一入口(粘贴解密 / 扩展交接):先归类,再决定解密、交给向导,还是报哪种失败。
/// 只在主 App 进程用——会话消息会推进棘轮并写 `ChatStore`,扩展进程不得调用。
@MainActor
public final class IntakeRouter {
    private let chatService: ChatService
    private let contactIds: () -> [String]
    /// 有「待完成邀请」(``awaitingInvites(_:nowMillis:)`` 非空)。
    private let hasAwaitingInvites: () -> Bool
    private let kindOf: (String) -> PairingTransport.WireKind

    public init(
        chatService: ChatService,
        contactIds: @escaping () -> [String],
        hasAwaitingInvites: @escaping () -> Bool,
        kindOf: @escaping (String) -> PairingTransport.WireKind = PairingTransport.classify
    ) {
        self.chatService = chatService
        self.contactIds = contactIds
        self.hasAwaitingInvites = hasAwaitingInvites
        self.kindOf = kindOf
    }

    public func route(_ raw: String) async -> IntakeRoute {
        switch IntakeClassifier.classify(raw, kindOf: kindOf) {
        case .empty, .incomplete, .linkOnly:
            return .failed(.incomplete)
        case .notOurs:
            return .failed(.notOurs)
        case let .pairingInvite(wire), let .pairingResponse(wire):
            return .pairing(wire: wire)
        case let .sessionMessage(wire):
            // 解不开(含还没有联系人)时若手里有待完成邀请,多半是还没加完的人发来的:提示去粘贴对方的回执。
            guard !contactIds().isEmpty else { return .failed(hasAwaitingInvites() ? .pendingInvite : .noContacts) }
            do {
                guard let message = try await chatService.receiveWireText(wire) else {
                    return .failed(hasAwaitingInvites() ? .pendingInvite : .cannotOpen)
                }
                return .openThread(peerId: message.peerId, messageId: message.id)
            } catch {
                return .failed(.saveFailed)
            }
        }
    }
}
