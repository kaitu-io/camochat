import XCTest

/// 终审 F2 的模拟器/Debug 复现用例:App 带 `CC_UAT_THREAD_STRESS` 启动,DEBUG 钩子
/// (`ChencangCompanion/Views/Mixin/UATThreadStress.swift`)往专用线程写 44 条历史并直接进线程,
/// 再背靠背「发」10 条语音(真实落库状态序列 + 后台密集进度回调,不加密不联网),最后追加
/// 「压力自测完成」。
///
/// 判据:
/// - 60 秒内看到「压力自测完成」——线程没卡住,而且非动画滚到底生效了(LazyVStack 只把屏幕上的行放进无障碍树);
/// - 期间每次查询 App 界面树都在 5 秒内返回——F2 时 XCUITest 连续 60 秒拿不到快照;
/// - 结束后「切换到语音」仍可点。
///
/// 只能跑 Debug 构建(钩子是 `#if DEBUG`),不需要配对、不需要网络:
/// `xcodebuild test -project ChencangiOS.xcodeproj -scheme ChencangCompanion -destination 'platform=iOS Simulator,name=iPhone 15' -only-testing:ChencangCompanionUITests/UATThreadStressTests`
final class UATThreadStressTests: UATCase {
    func testVoiceBurstInLongThreadStaysResponsive() {
        app.launchEnvironment["CC_UAT_THREAD_STRESS"] = "1"
        launchInChinese()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))

        let marker = app.staticTexts["压力自测完成"]
        let deadline = Date().addingTimeInterval(60)
        var worstQuery: TimeInterval = 0
        var queries = 0
        var done = false
        while Date() < deadline {
            let t = Date()
            done = marker.exists           // 每次都要一份 App 界面快照:主线程卡住就会卡在这里
            worstQuery = max(worstQuery, Date().timeIntervalSince(t))
            queries += 1
            if done { break }
            Thread.sleep(forTimeInterval: 0.25)
        }
        shot("stress-final")
        record("stress-result", "done=\(done) queries=\(queries) worstQuery=\(String(format: "%.2f", worstQuery))s")
        XCTAssertTrue(done, "60 秒内没看到「压力自测完成」:线程卡住或没滚到底")
        XCTAssertLessThan(worstQuery, 5, "界面快照最慢 \(worstQuery) 秒:主线程被占住了")

        let voiceBubbles = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "语音 ")).count
        record("stress-visible-voice-bubbles", "\(voiceBubbles)")
        XCTAssertGreaterThanOrEqual(voiceBubbles, 5, "底部应能看到刚发的语音气泡")
        XCTAssertFalse(app.descendants(matching: .any)["传输中"].exists, "全部发完后不应还有进度环")
        let toggle = app.buttons["切换到语音"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5) && toggle.isHittable, "发完后输入栏应可操作")
    }
}
