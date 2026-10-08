import AVFoundation
import ChencangShared

/// AVAudioSession 的激活/停用会阻塞调用线程(Apple 文档明说),不能放主线程(终审 minor 5)。
/// 录音与放音共用这一条**串行**后台队列:串行保证先后顺序——比如按下录音前先停掉放音,
/// 放音的停用一定排在录音的激活之前,不会反过来把刚激活的录音会话关掉。放音的激活带作废令牌
/// (终审复审 1),见 `GatedSerialQueue`。
enum AudioSessionQueue {
    private static let queue = GatedSerialQueue(label: "cc.audiosession")

    /// 在队列上执行并等结果(激活:后续要紧接着启动引擎,必须等它完成)。
    static func run(_ work: @escaping @Sendable () throws -> Void) async throws {
        try await queue.run(work)
    }

    /// 令牌仍有效才执行(放音激活用);返回是否真的激活了。
    static func run(unlessCancelled flag: CancellationFlag, _ work: @escaping @Sendable () throws -> Void) async -> Bool {
        await queue.run(unlessCancelled: flag, work)
    }

    /// 停用不必等:排进队列就返回。
    static func deactivate() {
        queue.async {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }
}
