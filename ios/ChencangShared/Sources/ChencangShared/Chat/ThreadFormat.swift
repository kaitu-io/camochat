import Foundation

/// 线程列表里的一行:一条消息 + 它上方要不要带时间胶囊。
///
/// 终审 F2a:以前 `ForEach` 的每个元素产出「可选的时间胶囊 + 消息行」两个视图,消息行上又挂了
/// 一个与 ForEach id 相同的 `.id(message.id)`——LazyVStack 里同一个 id 对应不定个数的子视图,
/// `scrollTo` 目标的身份不稳定,正是 spindump 里 `LazySubviewPlacements` 反复重排的诱因。
/// 现在胶囊在 ForEach 外预先算好,每个元素恰好渲染成一个视图,id 只有 ForEach 这一份。
public struct ThreadRowModel: Identifiable, Equatable {
    public let message: ChatMessage
    public let showsTimeChip: Bool
    public var id: String { message.id }
}

public enum ThreadFormat {
    public static let gapSeconds: TimeInterval = 10 * 60

    public static func rows(_ messages: [ChatMessage]) -> [ThreadRowModel] {
        messages.indices.map { i in
            ThreadRowModel(message: messages[i],
                           showsTimeChip: showTimeChip(prev: i > 0 ? messages[i - 1] : nil, cur: messages[i]))
        }
    }

    public static func showTimeChip(prev: ChatMessage?, cur: ChatMessage) -> Bool {
        guard let prev else { return true }
        return cur.timestamp.timeIntervalSince(prev.timestamp) > gapSeconds
    }

    /// 时间胶囊:同日只显示时分,跨日带月日;格式跟随系统语言,12/24 小时制跟随用户设置(模板 `j`)。
    public static func chipText(_ date: Date, now: Date, locale: Locale = .current) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let fmt = DateFormatter()
        fmt.locale = locale
        fmt.setLocalizedDateFormatFromTemplate(cal.isDate(date, inSameDayAs: now) ? "jm" : "MMMd jm")
        return fmt.string(from: date)
    }
}
