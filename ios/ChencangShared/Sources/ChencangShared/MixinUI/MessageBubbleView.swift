import SwiftUI

/// 气泡长按菜单各项的动作。菜单项由 `BubbleMenu.items(for:)` 决定,只挂给了闭包的项。
public struct BubbleActions {
    public var onShare: (() -> Void)?
    public var onCopyEncrypted: (() -> Void)?
    public var onCopyText: (() -> Void)?
    public var onReport: (() -> Void)?
    public var onDelete: (() -> Void)?

    public init(onShare: (() -> Void)? = nil, onCopyEncrypted: (() -> Void)? = nil,
                onCopyText: (() -> Void)? = nil, onReport: (() -> Void)? = nil, onDelete: (() -> Void)? = nil) {
        self.onShare = onShare
        self.onCopyEncrypted = onCopyEncrypted
        self.onCopyText = onCopyText
        self.onReport = onReport
        self.onDelete = onDelete
    }

    public static let none = BubbleActions()

    func action(for item: BubbleMenuItem) -> (() -> Void)? {
        switch item {
        case .share: return onShare
        case .copyEncrypted: return onCopyEncrypted
        case .copyText: return onCopyText
        case .report: return onReport
        case .delete: return onDelete
        }
    }
}

/// spec §4.2:非对称圆角气泡。self 右对齐 `bubble.self`,peer 左对齐 `bubble.peer` +
/// hairline 边(无头像——1:1 对话头像信息量为零)。状态标仅 self 侧展示。
public struct MessageBubbleView: View {
    let message: ChatMessage
    let actions: BubbleActions
    /// 非 nil 时状态行可点(还没分享的自己的消息:重新出加密卡)。
    let onStatusTap: (() -> Void)?

    public init(message: ChatMessage, actions: BubbleActions = .none, onStatusTap: (() -> Void)? = nil) {
        self.message = message
        self.actions = actions
        self.onStatusTap = onStatusTap
    }

    private var isSelf: Bool { message.direction == .outgoing }

    /// 入站文字命中本地过滤词:先盖住,点一下只在这个视图里展开(不落盘,重进又盖)。
    @State private var revealed = false
    private var covered: Bool {
        !isSelf && message.kind == .text && !revealed && ContentFilter.shared.isFlagged(message.body)
    }

    /// 新类型占位不读 body(老数据存的是写死的中文),显示时按当前语言取。
    private var bodyText: String {
        message.kind == .unsupported ? L10n.threadUnsupportedMessage : message.body
    }

    private var shape: UnevenRoundedRectangle {
        // 非对称角:靠说话者一侧下角 = bubbleTail(现代化的「尾巴」)。
        let r = Moyu.Radius.bubble
        let t = Moyu.Radius.bubbleTail
        return UnevenRoundedRectangle(
            topLeadingRadius: r, bottomLeadingRadius: isSelf ? r : t,
            bottomTrailingRadius: isSelf ? t : r, topTrailingRadius: r,
            style: .continuous
        )
    }

    public var body: some View {
        VStack(alignment: isSelf ? .trailing : .leading, spacing: Moyu.Space.xs) {
            Text(covered ? L10n.filteredCover : bodyText)
                .font(moyuFont(Moyu.FontSize.body))
                .italic(message.kind == .unsupported || covered)
                .foregroundStyle(message.kind == .unsupported || covered ? Moyu.Palette.textSecondary : Moyu.Palette.textPrimary)
                .padding(.horizontal, Moyu.Space.m)
                .padding(.vertical, Moyu.Space.s)
                .background(isSelf ? Moyu.Palette.bubbleSelf : Moyu.Palette.bubblePeer, in: shape)
                .overlay { if !isSelf { shape.strokeBorder(Moyu.Palette.borderHairline, lineWidth: 1) } }
                .onTapGesture { if covered { revealed = true } }
                .modifier(BubbleContextMenu(message: message, actions: actions))
                .frame(maxWidth: Moyu.Size.bubbleMax, alignment: isSelf ? .trailing : .leading)
            statusLabel
        }
        .frame(maxWidth: .infinity, alignment: isSelf ? .trailing : .leading)
    }

    /// 文案见 `MessageStatusLine`(收到的消息返回 nil,不显示)。
    @ViewBuilder private var statusLabel: some View {
        if let text = MessageStatusLine.text(for: message) {
            if let onStatusTap {
                Button(action: onStatusTap) {
                    MessageStatusLabel(status: message.status, text: text)
                }
                .buttonStyle(.plain)
            } else {
                MessageStatusLabel(status: message.status, text: text)
            }
        }
    }
}

/// 状态行的样式:已加密带锁、用强调色;已复制 / 已分享纯文字、次要色。媒体气泡的状态行共用它。
public struct MessageStatusLabel: View {
    let status: ChatMessage.Status
    let text: String

    public init(status: ChatMessage.Status, text: String) {
        self.status = status
        self.text = text
    }

    public var body: some View {
        if status == .sealed {
            Label(text, systemImage: "lock.fill")
                .font(moyuFont(Moyu.FontSize.caption))
                .foregroundStyle(Moyu.Palette.accentPrimary)
        } else {
            Text(text)
                .font(moyuFont(Moyu.FontSize.caption))
                .foregroundStyle(Moyu.Palette.textTertiary)
        }
    }
}

/// 只挂给了动作的菜单项;一项都没有时不挂 contextMenu(长按不出空菜单)。
private struct BubbleContextMenu: ViewModifier {
    let message: ChatMessage
    let actions: BubbleActions

    func body(content: Content) -> some View {
        let entries = BubbleMenu.items(for: message).compactMap { item in
            actions.action(for: item).map { (item, $0) }
        }
        if entries.isEmpty {
            content
        } else {
            content.contextMenu {
                ForEach(entries, id: \.0) { item, action in
                    Button(role: item == .delete ? .destructive : nil, action: action) {
                        Label(BubbleMenu.title(item, for: message), systemImage: BubbleMenu.systemImage(item))
                    }
                }
            }
        }
    }
}
