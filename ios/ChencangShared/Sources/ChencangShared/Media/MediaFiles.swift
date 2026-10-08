import Foundation

/// 媒体文件落盘（R5）：`<root>/<messageId>/<index>.cca`（密文，重试/下载中转用）与 `<index>.bin`（明文）。
/// 生产 root = App 容器 `Application Support/media`——不放 App Group，扩展从不写媒体文件；
/// root 标记 `isExcludedFromBackup`，不进 iCloud/电脑备份。
public struct MediaFiles: Sendable {
    /// 真正把字节写到磁盘的那一步(可注入:单测据此断言传给系统的文件保护级别)。
    public typealias Writer = @Sendable (Data, URL, Data.WritingOptions) throws -> Void

    public let root: URL
    private let writer: Writer

    public init(root: URL, writer: @escaping Writer = { data, url, options in try data.write(to: url, options: options) }) {
        self.root = root
        self.writer = writer
    }

    public static func production() -> MediaFiles {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return MediaFiles(root: base.appendingPathComponent("media", isDirectory: true))
    }

    public func directory(messageId: String) -> URL {
        root.appendingPathComponent(messageId, isDirectory: true)
    }

    public func ccaURL(messageId: String, index: Int) -> URL {
        directory(messageId: messageId).appendingPathComponent("\(index).cca")
    }

    public func binURL(messageId: String, index: Int) -> URL {
        directory(messageId: messageId).appendingPathComponent("\(index).bin")
    }

    public func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    public func write(_ data: Data, to url: URL) throws {
        try ensureRoot()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try writer(data, url, Self.writingOptions(for: url))
    }

    /// 明文 `.bin` 用 `completeFileProtectionUnlessOpen`(终审 minor 12):锁屏后新开不了,
    /// 已经打开着的(正在播的视频)不受影响。视频的可播放硬链接与 `.bin` 同一个 inode,保护等级随之一致。
    /// `.cca` 是密文,用 `completeFileProtectionUntilFirstUserAuthentication`:锁屏后后台上传
    /// 会话仍要能读它(先分享、后上传 spec §1.1)。
    static func writingOptions(for url: URL) -> Data.WritingOptions {
        switch url.pathExtension {
        case "bin": return [.atomic, .completeFileProtectionUnlessOpen]
        case "cca": return [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        default: return .atomic
        }
    }

    /// 播放器与截帧按扩展名识别容器：在同一消息目录里建一个指向 `.bin` 的硬链接，
    /// 随消息目录一起删除，不在 tmp 留明文副本。
    public func playableURL(messageId: String, index: Int, ext: String) throws -> URL {
        let link = directory(messageId: messageId).appendingPathComponent("\(index).\(ext)")
        if !exists(link) {
            try FileManager.default.linkItem(at: binURL(messageId: messageId, index: index), to: link)
        }
        return link
    }

    public func remove(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    public func delete(messageId: String) {
        remove(directory(messageId: messageId))
    }

    public func deleteAll() throws {
        guard exists(root) else { return }
        try FileManager.default.removeItem(at: root)
    }

    private func ensureRoot() throws {
        guard !exists(root) else { return }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = root
        try url.setResourceValues(values)
    }
}
