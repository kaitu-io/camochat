import Foundation

/// 文件式消息存储:App Group 容器 `chat/` 下每 peer 一个 JSON 文件。
/// 单写者纪律:只有主 App 进程写本存储;Action Extension 走 AppGroupInbox
/// 交接(Task 4),绝不直接写这里 —— 避免跨进程并发写。
/// 消息量级(低频高价值密信)下整文件重写的代价可忽略。
@MainActor
public final class ChatStore: ObservableObject {
    @Published public private(set) var threads: [String: [ChatMessage]] = [:]

    public enum StoreError: Error, Equatable {
        /// 这个会话的文件在磁盘上、但还读不了(首次解锁前的数据保护):拒绝写,免得用空列表覆盖历史。
        case threadNotLoaded
    }

    /// 文件在、却读不了的会话(按文件名反推的 peerId)。非空时 `isFullyLoaded == false`。
    /// `@Published`:「加载完成」本身要能驱动界面重算(读回的会话可能是空数组,`threads` 不变)。
    @Published public private(set) var unreadablePeers: Set<String> = []
    public var isFullyLoaded: Bool { unreadablePeers.isEmpty }

    public typealias Reader = (URL) throws -> Data

    private let directory: URL
    private let reader: Reader
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        e.outputFormatting = [.sortedKeys]
        return e
    }()
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return d
    }()

    public init(directory: URL, reader: @escaping Reader = { try Data(contentsOf: $0) }) throws {
        self.directory = directory
        self.reader = reader
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try loadAll()
    }

    /// 生产目录:App Group 容器下的 chat/。容器拿不到(非常规环境)返回 nil,
    /// 调用方负责兜底(MixinAppModel 用 temporaryDirectory 兜底并打 assertionFailure)。
    public static func appGroupDirectory() -> URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: "group.app.chencang.shared")?
            .appendingPathComponent("chat", isDirectory: true)
    }

    public func messages(for peerId: String) -> [ChatMessage] {
        threads[peerId] ?? []
    }

    /// 落盘失败要把内存状态滚回去——`ChatService.ingest` 靠 `message(id:peerId:)`
    /// 判断「这条是不是已经落库过」来做收件箱重试的幂等去重;如果这里在写文件失败后
    /// 仍把消息留在内存里,下一次重试会误判「已经存在」而跳过真正的落盘,消息就会
    /// 悄悄丢掉且不再重试。
    public func append(_ message: ChatMessage) throws {
        try ensureLoaded(message.peerId)
        let previous = threads[message.peerId]
        var list = previous ?? []
        list.removeAll { $0.id == message.id }
        list.append(message)
        list.sort { $0.timestamp < $1.timestamp }
        threads[message.peerId] = list
        do {
            try persist(peerId: message.peerId)
        } catch {
            threads[message.peerId] = previous
            throw error
        }
    }

    /// 局部更新状态。消息已被删 → 什么都不做。落盘失败把内存滚回去(同 `setBody`):
    /// 内存里留着没落成的状态,随后任何一次别的落库都会把它连带写进磁盘。
    /// 「能不能改」的判断(如已分享不降级成已复制)由调用方在同一次主线程同步调用里做完,见 `ChatService`。
    public func setStatus(id: String, peerId: String, _ status: ChatMessage.Status) throws {
        try ensureLoaded(peerId)
        guard let previous = threads[peerId], let i = previous.firstIndex(where: { $0.id == id }) else { return }
        var list = previous
        list[i].status = status
        threads[peerId] = list
        do {
            try persist(peerId: peerId)
        } catch {
            threads[peerId] = previous
            throw error
        }
    }

    public func message(id: String, peerId: String) -> ChatMessage? {
        threads[peerId]?.first { $0.id == id }
    }

    /// 局部更新正文(发送方封缄后写入 R1 两行文本)。消息已被删 → 什么都不做。
    /// 只改这一个字段,不会覆盖并发发生的 setStatus 等其它修改。
    /// 落盘失败把内存滚回去:发送方以「body 非空」判定「已分享」,没落成就不能在内存里留一份
    /// (否则随后任何一次别的落库都会把它连带写进磁盘)。
    public func setBody(messageId: String, peerId: String, body: String) throws {
        try ensureLoaded(peerId)
        guard let previous = threads[peerId], let i = previous.firstIndex(where: { $0.id == messageId }) else { return }
        var list = previous
        list[i].body = body
        threads[peerId] = list
        do {
            try persist(peerId: peerId)
        } catch {
            threads[peerId] = previous
            throw error
        }
    }

    /// 落盘失败把内存滚回去:内存与磁盘不一致会让调用方误判(例如以为某项「已上传」,
    /// 接着删掉它的 `.cca`,磁盘上却还是 uploading)。
    public func updateMediaItem(messageId: String, peerId: String, index: Int,
                                _ change: (inout MediaItem) -> Void) throws {
        try ensureLoaded(peerId)
        guard let previous = threads[peerId],
              let mi = previous.firstIndex(where: { $0.id == messageId }),
              var items = previous[mi].media,
              let ii = items.firstIndex(where: { $0.index == index }) else { return }
        change(&items[ii])
        var list = previous
        list[mi].media = items
        threads[peerId] = list
        do {
            try persist(peerId: peerId)
        } catch {
            threads[peerId] = previous
            throw error
        }
    }

    /// 冷启动调用:上次进程在传输途中被杀,卡住的状态改成可操作的状态。
    /// - 发送方**还没分享**(body 空)的 encrypting/uploading → failed(点 ! 重新封缄,R12)。
    /// - 发送方**已分享**的 uploading 原样保留:系统后台会话可能还在替我们传,由上传引擎对账
    ///   (先分享、后上传 spec §1.1);分享后不会再有 encrypting。
    /// - 接收方 downloading → pending(可再下)。
    /// `peers` 非 nil 时只处理这些会话(数据保护解除后才读回来的会话,见 `reloadUnreadable`)——
    /// 本进程已经在跑的会话不能再复位(会把正在下载/封缄的项打回去)。
    public func resetInterruptedTransfers(peers: Set<String>? = nil) throws {
        for (peerId, list) in threads where peers?.contains(peerId) ?? true {
            var changed = false
            let fixed: [ChatMessage] = list.map { message in
                guard var items = message.media else { return message }
                let shared = message.direction == .outgoing && !message.body.isEmpty
                for i in items.indices {
                    switch items[i].state {
                    case .uploading where shared:
                        break
                    case .encrypting, .uploading:
                        items[i].state = .failed
                        changed = true
                    case .downloading:
                        items[i].state = .pending
                        changed = true
                    default:
                        break
                    }
                }
                var m = message
                m.media = items
                return m
            }
            if changed {
                threads[peerId] = fixed
                try persist(peerId: peerId)
            }
        }
    }

    public func latestPerPeer() -> [String: ChatMessage] {
        threads.compactMapValues { $0.last }
    }

    /// 删除单条消息(仅本机)。媒体文件由 `ThreadCleaner` 一并清理。
    public func remove(id: String, peerId: String) throws {
        try ensureLoaded(peerId)
        guard var list = threads[peerId] else { return }
        list.removeAll { $0.id == id }
        threads[peerId] = list
        try persist(peerId: peerId)
    }

    /// 清空是用户明确要删:读不了的会话也照删文件。
    public func clear(peerId: String) throws {
        unreadablePeers.remove(peerId)
        threads[peerId] = nil
        try? FileManager.default.removeItem(at: fileURL(for: peerId))
    }

    public func clearAll() throws {
        threads = [:]
        unreadablePeers = []
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.pathExtension == "json" {
            try? FileManager.default.removeItem(at: f)
        }
    }

    // MARK: - 私有

    private func fileURL(for peerId: String) -> URL {
        // peerId 是指纹派生 id,理论上是十六进制;仍防御性转义,任何字符都可安全落盘。
        let safe = peerId.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? peerId
        return directory.appendingPathComponent("\(safe).json")
    }

    private func persist(peerId: String) throws {
        let data = try encoder.encode(threads[peerId] ?? [])
        try data.write(to: fileURL(for: peerId), options: .atomic)
    }

    /// 数据保护解除(`protectedDataDidBecomeAvailable` / 回前台)后重读先前读不了的会话。
    /// 返回这次读回来的 peerId(调用方据此只对它们做冷启动复位)。
    @discardableResult
    public func reloadUnreadable() throws -> Set<String> {
        var loaded: Set<String> = []
        for peerId in unreadablePeers {
            let url = fileURL(for: peerId)
            guard let data = try? reader(url) else { continue }
            unreadablePeers.remove(peerId)
            loaded.insert(peerId)
            if let list = try? decoder.decode(LossyArray<ChatMessage>.self, from: data).elements, !list.isEmpty {
                threads[peerId] = list.sorted { $0.timestamp < $1.timestamp }
            }
        }
        return loaded
    }

    private func ensureLoaded(_ peerId: String) throws {
        if unreadablePeers.contains(peerId) { throw StoreError.threadNotLoaded }
    }

    /// 「读不了」(文件在,但数据保护/权限拦着)与「解不开」(内容坏了)分开:前者记进 `unreadablePeers`、
    /// 拒绝写入;后者沿用原来的跳过。peerId 由文件名反推(`fileURL(for:)` 的逆)。
    private func loadAll() throws {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.pathExtension == "json" {
            let data: Data
            do {
                data = try reader(f)
            } catch {
                let name = f.deletingPathExtension().lastPathComponent
                unreadablePeers.insert(name.removingPercentEncoding ?? name)
                continue
            }
            guard let list = try? decoder.decode(LossyArray<ChatMessage>.self, from: data).elements,
                  let peerId = list.first?.peerId else { continue }
            threads[peerId] = list.sorted { $0.timestamp < $1.timestamp }
        }
    }
}
