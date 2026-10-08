import Foundation

/// 配对码的分享/复制文本:只有一段说明,说明末行是带配对码的链接(`PairingLink.make`),
/// 首行以 🔒 开头。卡片图片是主分享物,这段文字用于「复制」。
public enum PairingShareText {
    public static func compose(wire: String, site: String, isResponse: Bool) -> String {
        let link = PairingLink.make(site: site, wire: wire)
        return isResponse ? L10n.pairingShareHeaderResponse(link) : L10n.pairingShareHeaderInvite(link)
    }
}

/// 一份要发给对方的配对码(邀请或回执):界面用它画卡片图(`link`)或复制文字版(`text`)。
public struct PairingShare: Equatable {
    public let wire: String
    public let isResponse: Bool

    public init(wire: String, isResponse: Bool) {
        self.wire = wire
        self.isResponse = isResponse
    }

    /// 配对链接,卡片二维码和文字版末行都是它。
    public var link: String { PairingLink.make(site: ConfigRepository.shared.current().shareSite, wire: wire) }

    public var text: String {
        PairingShareText.compose(wire: wire, site: ConfigRepository.shared.current().shareSite, isResponse: isResponse)
    }
}
