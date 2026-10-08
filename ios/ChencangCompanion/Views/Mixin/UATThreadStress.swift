#if DEBUG
import Foundation
import ChencangShared

/// 仅 DEBUG:线程布局压力钩子,供模拟器上的 XCUITest `UATThreadStressTests` 复现终审 F2
/// (iPhone 12 松手发语音后,主线程在线程页 LazyVStack 布局里空转约 3 分钟)。
///
/// 启动环境变量带 `CC_UAT_THREAD_STRESS` 时:往一个专用线程(`uat-stress`,不对应任何联系人、
/// 也不出现在会话列表)写 44 条文字/图片历史,直接进这个线程,再背靠背「发」10 条语音——走和
/// 真实发送同样的落库状态序列(encrypting → uploading → sealed + 写 body)和同样的进度通路
/// (后台线程密集回调 → `ProgressThrottle` → `MediaActivity`),只是不加密、不联网。
/// 全部完成后追加一条「压力自测完成」,测试据此判定线程没卡住、也滚到了底。
@MainActor
enum UATThreadStress {
    static let environmentKey = "CC_UAT_THREAD_STRESS"
    static let peerId = "uat-stress"
    static let doneMarker = "压力自测完成"

    static func runIfRequested(model: MixinAppModel) {
        guard ProcessInfo.processInfo.environment[environmentKey] != nil else { return }
        Task { await run(model: model) }
    }

    private static func run(model: MixinAppModel) async {
        let store = model.chatStore
        try? store.clear(peerId: peerId)
        let base = Date().addingTimeInterval(-3_600)
        for i in 0..<44 {
            let isImage = i % 4 == 0
            try? store.append(ChatMessage(
                id: "stress-h\(i)", peerId: peerId, direction: i % 2 == 0 ? .incoming : .outgoing,
                body: isImage ? "" : "历史消息 \(i)", timestamp: base.addingTimeInterval(TimeInterval(i * 60)),
                status: i % 2 == 0 ? .received : .shared, kind: isImage ? .image : .text,
                media: isImage ? [MediaItem(index: 0, kind: .image, durMs: 0, width: 1200, height: 900, byteLen: 1,
                                            blobSecret: Data(), blobId: "", state: .ready)] : nil))
        }
        model.showOnboarding = false
        model.apply(ShellNav.openThread(peerId))
        try? await Task.sleep(nanoseconds: 1_500_000_000)

        // 10 条语音,每 150 ms 起一条,彼此重叠(和连续松手发送时一样,前一条还在传,后一条已落库)
        await withTaskGroup(of: Void.self) { group in
            for n in 0..<10 {
                group.addTask { await sendFakeVoice(n, model: model) }
                try? await Task.sleep(nanoseconds: 150_000_000)
            }
        }
        try? store.append(ChatMessage(id: "stress-done", peerId: peerId, direction: .incoming, body: doneMarker,
                                      timestamp: Date(), status: .received))
    }

    private static func sendFakeVoice(_ n: Int, model: MixinAppModel) async {
        let store = model.chatStore
        let activity = model.mediaActivity
        let id = "stress-v\(n)-\(UUID().uuidString)"
        try? store.append(ChatMessage(
            id: id, peerId: peerId, direction: .outgoing, body: "", timestamp: Date(), status: .sealed, kind: .voice,
            media: [MediaItem(index: 0, kind: .voice, durMs: 5_000 + n * 1_000, width: 0, height: 0, byteLen: 0,
                              blobSecret: Data(), blobId: "", state: .encrypting)]))
        try? await Task.sleep(nanoseconds: 50_000_000)
        try? store.updateMediaItem(messageId: id, peerId: peerId, index: 0) { $0.state = .uploading }

        // 模拟网络层:后台线程 300 次进度回调(约 1.5 秒),节流后才跳主 actor——与 MediaSender 同一通路
        let throttle = ProgressThrottle()
        await Task.detached(priority: .userInitiated) {
            for i in 1...300 {
                let value = Double(i) / 300
                if throttle.shouldEmit(value) {
                    Task { @MainActor in activity.setProgress(value, messageId: id, index: 0) }
                }
                usleep(5_000)
            }
        }.value
        activity.clearProgress(messageId: id, index: 0)
        try? store.updateMediaItem(messageId: id, peerId: peerId, index: 0) { $0.state = .sealed }
        try? store.setBody(messageId: id, peerId: peerId, body: "🔒 陈仓加密语音 · 压力自测")
    }
}
#endif
