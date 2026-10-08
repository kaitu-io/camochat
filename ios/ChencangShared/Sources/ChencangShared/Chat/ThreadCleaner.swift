import Foundation

/// 删线程 / 删单条消息的唯一入口：消息记录与 `media/<messageId>/` 目录一起删（R5）。
/// 联系人页「清空消息」「删除联系人」、配对向导「不一致」、长按「删除」都走这里。
///
/// 顺序(先分享、后上传 spec §1.1):先删记录(界面立刻消失;迟到的上传回调找不到消息,什么都不写),
/// 再取消该消息的后台上传任务,最后才删文件——不让还在跑的任务去读已删除的 `.cca`。
@MainActor
public struct ThreadCleaner {
    /// 批量取消这些消息的全部上传任务(生产 = `BackgroundUploader.cancel(messageIds:)`,只问一次后台会话)。
    public typealias UploadCanceller = @MainActor ([String]) async -> Void

    private let chatStore: ChatStore
    private let mediaFiles: MediaFiles
    private let cancelUploads: UploadCanceller

    public init(chatStore: ChatStore, mediaFiles: MediaFiles, cancelUploads: @escaping UploadCanceller) {
        self.chatStore = chatStore
        self.mediaFiles = mediaFiles
        self.cancelUploads = cancelUploads
    }

    /// 记录一次删光 → 一次批量取消 → 一口气删掉全部媒体目录(不在逐条 await 之间留下无主明文)。
    public func clear(peerId: String) async throws {
        let ids = chatStore.messages(for: peerId).map(\.id)
        try chatStore.clear(peerId: peerId)
        await cancelUploads(ids)
        ids.forEach(mediaFiles.delete(messageId:))
    }

    public func deleteMessage(id: String, peerId: String) async throws {
        try chatStore.remove(id: id, peerId: peerId)
        await cancelUploads([id])
        mediaFiles.delete(messageId: id)
    }
}
