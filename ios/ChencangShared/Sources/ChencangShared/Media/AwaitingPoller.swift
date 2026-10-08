import Foundation

/// 接收端「等待对方上传」的轮询(先分享、后上传 spec §2),与 Android `AwaitingPoller` 同口径:
/// - 只在这条消息所在会话**可见**(线程屏在屏上且 App 在前台)时轮询;不可见一律停。
/// - `onVisible`:对给定项立即试一次并重开 30 分钟窗口(进会话 / 回前台 / 点「还没收到文件 · 点击重试」)。
/// - `track`:刚请求过、刚进入等待的项(自动下载或点视频之后)从现在起按间隔轮询,不立即再试。
/// - 间隔 10 s、20 s、30 s、60 s,之后恒 60 s;CloudFront 对错误响应缓存约 10 s,首轮不早于 10 s。
/// - 窗口过后停止自动轮询,`windowExpired` 为 true(气泡改「还没收到文件 · 点击重试」)。窗口由每项一个
///   独立的截止计时兜底:在途的那次请求迟迟不回、时钟被拨动,都不能把收口往后推(UAT O4)。
///   在途的请求照常跑完(不打断下载),只是不再开下一轮。
/// - 每项独立(相册各张先到先显示);项不再是 awaiting(拿到了/已过期/失败)即停止这一项。
/// 24 h 过期判定不在这里:`tryFetch`(即 `MediaDownloader.download`)见到超过 24 h 直接判已过期、不发请求。
@MainActor
public final class AwaitingPoller: ObservableObject {
    public static let delays: [TimeInterval] = [10, 20, 30, 60]
    public static let window: TimeInterval = 1800

    public struct Key: Hashable, Sendable {
        public let messageId: String
        public let index: Int
    }

    /// 单调秒数(生产 = `ProcessInfo.systemUptime`):墙钟会被自动校时 / 用户改时间拨动。
    private let clock: () -> TimeInterval
    private let sleeper: (TimeInterval) async -> Void
    private let tryFetch: (String, Int) async -> Void
    private let isAwaiting: (String, Int) -> Bool

    private var visible = false
    /// 每项一个轮询循环 + 它的窗口截止计时;`generation` 防止被替换掉的旧循环收尾时误删新循环。
    private struct Loop {
        let generation: Int
        let task: Task<Void, Never>
        let deadline: Task<Void, Never>
        func cancel() {
            task.cancel()
            deadline.cancel()
        }
    }
    private var loops: [Key: Loop] = [:]
    private var generation = 0
    /// 窗口已过、停止自动轮询的项。
    @Published public private(set) var expired: Set<Key> = []

    /// - Parameter isAwaiting: 这一项当前是否仍在等待;返回 false 就停止这一项的轮询。
    public init(clock: @escaping () -> TimeInterval,
                sleeper: @escaping (TimeInterval) async -> Void,
                tryFetch: @escaping (String, Int) async -> Void,
                isAwaiting: @escaping (String, Int) -> Bool = { _, _ in true }) {
        self.clock = clock
        self.sleeper = sleeper
        self.tryFetch = tryFetch
        self.isAwaiting = isAwaiting
    }

    /// 会话变为可见 / 用户点了重试:这些项立即试一次并重开窗口;其它正在轮询的项不受影响。
    public func onVisible(_ items: [(String, Int)]) {
        visible = true
        for (messageId, index) in items {
            start(Key(messageId: messageId, index: index), fetchNow: true)
        }
    }

    /// 刚请求过、刚进入等待的项:从现在起按间隔轮询(不立即再试)。已在轮询/窗口已过的项不动;不可见时忽略。
    public func track(_ items: [(String, Int)]) {
        guard visible else { return }
        for (messageId, index) in items {
            let key = Key(messageId: messageId, index: index)
            guard loops[key] == nil, !expired.contains(key) else { continue }
            start(key, fetchNow: false)
        }
    }

    /// 会话不可见 / App 离开前台:全部停止。正在进行的那次请求会跑完(不取消下载),之后不再请求。
    public func onHidden() {
        visible = false
        for entry in loops.values { entry.cancel() }
        loops = [:]
        if !expired.isEmpty { expired = [] }
    }

    /// 这一项的 30 分钟窗口已过(气泡显示「还没收到文件 · 点击重试」)。
    public func windowExpired(_ messageId: String, _ index: Int) -> Bool {
        expired.contains(Key(messageId: messageId, index: index))
    }

    /// 可见会话里应当轮询的项:收到的、处于 awaiting 或 downloading 的媒体项(同 `keepsPolling`)。
    /// - 视频只有用户点了才会下载,因此处于 awaiting 的视频一定是点过的(spec §2「视频点了若得 awaiting
    ///   同样进入轮询」)。
    /// - 含 downloading:轮询那次已切到下载中时会话被隐藏又回来,这一项也要接回轮询——那次下载中途失败
    ///   会退回 awaiting,没有循环就再也没人试(re-review N1)。接回时的立即再试被下载器的在途去重吃掉,
    ///   不会重复下载;下完 ready / 普通下载失败成 failed,循环随之结束,所以顺带接上的普通下载无害。
    public static func targets(in messages: [ChatMessage]) -> [(String, Int)] {
        messages.flatMap { message -> [(String, Int)] in
            guard message.direction == .incoming, let items = message.media else { return [] }
            return items.filter { keepsPolling($0.state) }.map { (message.id, $0.index) }
        }
    }

    /// 生产侧 `isAwaiting` 的判定:`awaiting`,或轮询那一次已经开始收 blob 而切到的 `downloading`
    /// (那次下载中途失败会回到 `awaiting`,循环不能在它下载期间被别的调用判停)。
    public static func keepsPolling(_ state: MediaItem.State?) -> Bool {
        state == .awaiting || state == .downloading
    }

    // MARK: - 私有

    private func start(_ key: Key, fetchNow: Bool) {
        loops[key]?.cancel()
        expired.remove(key)
        generation += 1
        let gen = generation
        let windowStart = clock()
        // 截止计时先于循环起跑:同一时刻到期时它先收口,循环不会在 30 分钟整点再发一次请求。
        let sleeper = self.sleeper
        let deadline = Task<Void, Never> { [weak self] in
            await sleeper(Self.window)
            guard !Task.isCancelled else { return }
            self?.expire(key, gen)
        }
        let task = Task<Void, Never> { [weak self] in
            guard let self else { return }
            await self.run(key, generation: gen, windowStart: windowStart, fetchNow: fetchNow)
        }
        loops[key] = Loop(generation: gen, task: task, deadline: deadline)
    }

    private func run(_ key: Key, generation gen: Int, windowStart: TimeInterval, fetchNow: Bool) async {
        if fetchNow {
            await fetch(key)
            guard !Task.isCancelled else { return }
            guard isAwaiting(key.messageId, key.index) else { return finish(key, gen) }
        }
        var attempt = 0
        while true {
            let delay = Self.delays[min(attempt, Self.delays.count - 1)]
            attempt += 1
            let remaining = Self.window - (clock() - windowStart)
            await sleeper(max(0, min(delay, remaining)))
            guard !Task.isCancelled else { return }
            if clock() - windowStart >= Self.window { return expire(key, gen) }
            guard isAwaiting(key.messageId, key.index) else { return finish(key, gen) }
            await fetch(key)
            guard !Task.isCancelled else { return }
            guard isAwaiting(key.messageId, key.index) else { return finish(key, gen) }
        }
    }

    /// 请求放进不继承取消的任务:会话此刻被隐藏也让这次下载正常跑完——取消会把下载打成网络失败
    /// (红 ! + 提示),而用户只是离开了会话。
    private func fetch(_ key: Key) async {
        let tryFetch = self.tryFetch
        await Task { await tryFetch(key.messageId, key.index) }.value
    }

    /// 这一项不再等待(拿到了/已过期/失败):收掉它的循环与截止计时。
    private func finish(_ key: Key, _ gen: Int) {
        guard let loop = loops[key], loop.generation == gen else { return }
        loops[key] = nil
        loop.deadline.cancel()
    }

    /// 窗口到了(截止计时或循环自己先看到):停掉这一项的循环——在途的请求不受影响、跑完为止——
    /// 并标「窗口已过」。只认当前这一轮,被替换/已收尾的旧一轮不动。
    private func expire(_ key: Key, _ gen: Int) {
        guard let loop = loops[key], loop.generation == gen else { return }
        loops[key] = nil
        loop.cancel()
        expired.insert(key)
    }
}
