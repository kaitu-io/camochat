import XCTest

/// F1 复现用例:线程内容超过一屏时,
/// 往输入框键入一段会折成多行的草稿,量「键入开始 → 能读回输入框内容」要多久。
///
/// XCTest 的 `typeText` 与随后的界面查询都要等 App 空闲,所以这个时长就是「App 多久才空闲」。
/// 键入的是无害文本(重复的「测」),不点加密、不发送;`ThreadViewModel.draft` 只在内存里,读完即清空。
///
/// 环境变量(经 `scripts/uat-run.sh` 传入):
/// - `UAT_F1_THREAD`:线程显示名;
/// - `UAT_F1_CHARS`:键入字数;
/// - `UAT_F1_HOLD`(可选):进线程后、键入前先等这么多秒——给宿主机留出把 Instruments 挂到进程上的时间
///   (`launchApp()` 会重启 App,只能在它之后 attach)。等待前写一条 `f1-ready`。
///
/// 记录项:`f1-idle-seconds`(验收看这个)、`f1-type-seconds`(`typeText` 返回用时)、`f1-typed-chars`
/// (读回的字数,不记内容)。
final class UATF1Tests: UATCase {
    /// 收尾:把陈仓退到后台并回主屏(不截图)。
    func testGoHome() {
        app.activate()
        XCUIDevice.shared.press(.home)
    }

    /// 只读:列出会话列表里的线程名(陈仓自己的界面),并对 `UAT_F1_SURVEY` 里逗号分隔的每个线程记录
    /// 进线程后可见的气泡/状态条数(粗略,LazyVStack 只给屏上的行)。不键入、不发送。
    func testSurvey() {
        launchApp()
        backToList()
        Thread.sleep(forTimeInterval: 2)
        let names = app.staticTexts.allElementsBoundByIndex.map { $0.label }.filter { !$0.isEmpty }
        record("f1-list", names.joined(separator: " | "))
        shot("f1-list")
        for t in (env("UAT_F1_SURVEY") ?? "").split(separator: ",").map(String.init) {
            openThread(t)
            Thread.sleep(forTimeInterval: 2)
            let vis = app.staticTexts.allElementsBoundByIndex.map { $0.label }.filter { !$0.isEmpty }
            record("f1-survey-\(t)", "visibleTexts=\(vis.count) buttons=\(app.buttons.count)")
            shot("f1-survey-\(t)")
            backToList()
        }
    }

    func testComposeIdle() {
        let thread = env("UAT_F1_THREAD") ?? "UAT-15"
        let chars = Int(env("UAT_F1_CHARS") ?? "") ?? 58
        let hold = TimeInterval(env("UAT_F1_HOLD") ?? "") ?? 0

        launchApp()
        openThread(thread)
        let field = composerField()
        XCTAssertTrue(field.waitForExistence(timeout: 10), "找不到输入框")
        clearComposer(field)
        Thread.sleep(forTimeInterval: 2)
        shot("f1-before")

        record("f1-ready", "thread-opened hold=\(hold)")
        if hold > 0 { Thread.sleep(forTimeInterval: hold) }

        let t0 = Date()
        record("f1-type-start", "\(t0.timeIntervalSince1970)")
        typeInto(field, String(repeating: "测", count: chars))
        let typed = Date().timeIntervalSince(t0)
        record("f1-type-seconds", String(format: "%.1f", typed))

        let text = composerText(field)
        let idle = Date().timeIntervalSince(t0)
        record("f1-typed-chars", "\(text.count)")
        record("f1-idle-seconds", String(format: "%.1f", idle))
        shot("f1-after")

        clearComposer(field)
    }
}
