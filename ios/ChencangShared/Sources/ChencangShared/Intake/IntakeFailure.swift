import Foundation

/// 来件处理失败后,界面上给用户的下一步。
public enum IntakeNextStep: Equatable {
    /// 去「添加联系人」。
    case addContact
    /// 打开陈仓看看(可能已经解过,在和 TA 的对话里)。
    case openApp
}

/// 来件失败种类,粒度跟 bindings 实际能分的一致:「已经解过」与「不是发给你的」在 DR 解密失败上
/// 不可区分,合并成 `cannotOpen` 一条。
public enum IntakeFailure: Error, Equatable {
    case incomplete
    case notOurs
    case noContacts
    case cannotOpen
    case saveFailed
    /// 解不开,而我手里正有发出去、还在等对方回复的邀请:这条多半来自还没加完的人。
    case pendingInvite
    /// 「粘贴解密」时剪贴板里没有文字(空 / 只有空白)。
    case clipboardEmpty

    /// 「粘贴解密」读到的剪贴板:空或只有空白 → ``clipboardEmpty``(不再当成「不是完整的加密消息」);否则 nil,照常分流。
    public static func forClipboard(_ text: String?) -> IntakeFailure? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .clipboardEmpty }
        return nil
    }

    public var message: String {
        switch self {
        case .incomplete: return L10n.intakeErrorIncomplete
        case .notOurs: return L10n.intakeErrorNotOurs
        case .noContacts: return L10n.intakeErrorNoContacts
        case .cannotOpen: return L10n.intakeErrorCannotDecrypt
        case .saveFailed: return L10n.intakeErrorSaveFailed
        case .pendingInvite: return L10n.intakeErrorPendingInvite
        case .clipboardEmpty: return L10n.intakeErrorClipboardEmpty
        }
    }

    public var nextStep: IntakeNextStep? {
        switch self {
        case .noContacts: return .addContact
        case .cannotOpen: return .openApp
        case .incomplete, .notOurs, .saveFailed, .clipboardEmpty, .pendingInvite: return nil
        }
    }
}
