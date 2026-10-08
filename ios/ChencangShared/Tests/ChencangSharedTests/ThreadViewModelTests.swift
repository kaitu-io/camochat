import XCTest
@testable import ChencangShared

/// `seal()` 里抛 `SessionStoreError.noSession` 的假 crypto,复现「会话丢失」路径
/// ——镜像 Android `ConversationViewModelTest` 的 `NoSessionCrypto`。
private struct ThrowingThreadCrypto: ThreadCrypto {
    func sealText(peerId: String, text: String) async throws -> String {
        throw SessionStoreError.noSession(peerId)
    }
    func sealMedia(peerId: String, items: [MediaItem]) async throws -> String {
        throw SessionStoreError.noSession(peerId)
    }
    func openWire(_ wire: String, candidates: [String]) async -> OpenedWire? { nil }
}

/// `ThreadViewModel.seal()` 是 fire-and-forget(内部起 `Task`,不等调用方 await),
/// 镜像 Android `viewModelScope.launch`。Mac 上跑 `swift test` 没有 Compose 那种
/// 可推进的测试调度器,只能真等 Task 落地——本文件统一用短轮询(`Task.yield()`
/// 循环,上限 2s)同步到 `@Published` 状态变化,而不是掐着睡眠时长赌时序。
@MainActor
final class ThreadViewModelTests: XCTestCase {
    private var site: String { ConfigRepository.shared.current().shareSite }
    private var dir: URL!
    private var store: ChatStore!
    /// 每个用例一份独立偏好:交出偏好会被写入,不能漏到别的用例(也不碰测试进程的 `.standard`)。
    private var defaults: UserDefaults!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-threadvm-tests-\(UUID().uuidString)", isDirectory: true)
        store = try ChatStore(directory: dir)
        defaults = UserDefaults(suiteName: "cc-threadvm-\(UUID().uuidString)")
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func makeService(crypto: ThreadCrypto) -> ChatService {
        ChatService(store: store, crypto: crypto, contactIds: { ["alice"] })
    }

    /// `preferring` 预置交出偏好(`.copy` = 加密后不自动弹分享面板)。
    private func makeVM(_ crypto: ThreadCrypto, preferring: SealCardAction? = nil,
                        kindOf: @escaping (String) -> PairingTransport.WireKind = PairingTransport.classify) -> ThreadViewModel {
        if let preferring { defaults.set(preferring.rawValue, forKey: ThreadViewModel.sealActionKey) }
        return ThreadViewModel(peerId: "alice", service: makeService(crypto: crypto), preferences: defaults,
                               kindOf: kindOf)
    }

    private func poll(timeout: TimeInterval = 2, until condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            await Task.yield()
        }
    }

    private func sealText(_ vm: ThreadViewModel, _ text: String) async throws -> ThreadViewModel.SealCard {
        let before = vm.card?.messageId
        vm.draft = text
        vm.seal()
        await poll { vm.card != nil && vm.card?.messageId != before }
        return try XCTUnwrap(vm.card)
    }

    private func status(_ id: String, peer: String = "alice") -> ChatMessage.Status? {
        store.message(id: id, peerId: peer)?.status
    }

    private func seedImages(id: String, peer: String, count: Int) throws {
        let items = (0..<count).map {
            MediaItem(index: $0, kind: .image, durMs: 0, width: 10, height: 10, byteLen: 1,
                      blobSecret: Data(repeating: 1, count: 32), blobId: "B\($0)", state: .sealed)
        }
        try store.append(ChatMessage(id: id, peerId: peer, direction: .outgoing, body: "🔒 x\n🔒W",
                                     timestamp: Date(), status: .sealed, kind: .image, media: items))
    }

    // ① seal 成功产出封缄卡(摘要=明文首行,分享文本=固定首行+wire)、清 draft、落一条 OUT/sealed。
    func testSealProducesCardAndClearsDraft() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hello\nsecond line")

        // 假 crypto 的 wire 会原样带上明文(含换行),所以只拆首行。
        let lines = card.shareText.split(separator: "\n", maxSplits: 1).map(String.init)
        XCTAssertEqual(lines.count, 2)
        XCTAssertEqual(lines[0], L10n.cardShareHeaderText("\(site)m/"))
        XCTAssertTrue(lines[1].hasPrefix("🔒"))
        XCTAssertEqual(card.summary, "hello")
        XCTAssertEqual(card.caption(recipientName: "小明"), L10n.cardSendTo("小明"))
        XCTAssertEqual(card.phase, .sharing, "默认偏好是分享:加密完直接弹分享面板")
        XCTAssertEqual(card.peerId, "alice")
        XCTAssertEqual(vm.draft, "")
        XCTAssertEqual(store.messages(for: "alice").first?.status, .sealed)
    }

    // ② 会话丢失(ThrowingThreadCrypto)→ sendError 置位、draft 保留、不落库。
    func testSealOnMissingSessionSetsSendErrorKeepsDraftDoesNotPersist() async throws {
        let vm = makeVM(ThrowingThreadCrypto())
        vm.draft = "hello"

        vm.seal()
        await poll { vm.sendError != nil }

        XCTAssertNil(vm.card)
        XCTAssertEqual(vm.draft, "hello")
        XCTAssertEqual(vm.sendError, L10n.mediaFailureSessionLost)
        XCTAssertTrue(store.messages(for: "alice").isEmpty)
    }

    // ③ 空/纯空白 draft → seal 无操作。
    func testSealOnEmptyDraftIsNoOp() {
        let vm = makeVM(EchoThreadCrypto())
        vm.draft = "   "

        vm.seal()

        XCTAssertNil(vm.card)
        XCTAssertEqual(vm.draft, "   ")
    }

    // ④ 点「分享」只弹面板,不标记;面板报告完成才标已分享、收卡、给「已分享」提示。
    func testShareMarksOnlyOnCompletion() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")

        vm.shareCard()
        let request = try XCTUnwrap(vm.presentedShare)
        XCTAssertEqual(request.messageId, card.messageId)
        XCTAssertEqual(request.peerId, "alice")
        XCTAssertEqual(request.text, card.shareText)
        XCTAssertEqual(vm.card?.phase, .sharing)
        XCTAssertEqual(status(card.messageId), .sealed, "点分享不应标记")

        vm.shareReported(request, completed: true)
        vm.presentedShare = nil
        vm.shareSheetDismissed()

        XCTAssertEqual(status(card.messageId), .shared)
        XCTAssertNil(vm.card)
        XCTAssertEqual(vm.sharedNotice, 1)
    }

    // ⑤ 取消 → 不标记,卡片留着并显示「未发出，可再次分享」;可再次分享。
    func testShareCancelKeepsCardAsNotSent() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")

        vm.shareCard()
        let request = try XCTUnwrap(vm.presentedShare)
        vm.shareReported(request, completed: false)
        vm.presentedShare = nil
        vm.shareSheetDismissed()

        XCTAssertEqual(status(card.messageId), .sealed)
        XCTAssertEqual(vm.card?.phase, .notSent)
        XCTAssertEqual(vm.card?.caption(recipientName: "小明"), L10n.statusNotSentTapAgain)
        XCTAssertEqual(vm.card?.viewState, .notSent)
        XCTAssertEqual(vm.sharedNotice, 0)

        vm.shareCard()
        XCTAssertEqual(vm.presentedShare?.messageId, card.messageId)
        XCTAssertEqual(vm.card?.phase, .sharing)
    }

    // ⑥ iOS 怪癖:取消某个分享扩展后在同一面板再选别的 App → 先报 false 再报 true。
    // 任一 true 即标,且只标一次(重复 true 不重复提示)。
    func testCancelThenPickAnotherMarksOnce() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")

        vm.shareCard()
        let request = try XCTUnwrap(vm.presentedShare)
        vm.shareReported(request, completed: false)
        XCTAssertEqual(status(card.messageId), .sealed)
        vm.shareReported(request, completed: true)
        vm.shareReported(request, completed: true)
        vm.presentedShare = nil
        vm.shareSheetDismissed()

        XCTAssertEqual(status(card.messageId), .shared)
        XCTAssertNil(vm.card)
        XCTAssertEqual(vm.sharedNotice, 1)
    }

    // ⑦ 面板收起先于完成回调到达(true 晚到)也要标记。
    func testLateCompletionAfterDismissStillMarks() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")

        vm.shareCard()
        let request = try XCTUnwrap(vm.presentedShare)
        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertEqual(vm.card?.phase, .notSent)

        vm.shareReported(request, completed: true)
        XCTAssertEqual(status(card.messageId), .shared)
        XCTAssertNil(vm.card)
    }

    // ⑧ 复制:返回同一份分享文本、卡片进「已复制」态;按捕获的 id 收尾即标已复制(不是已分享)。
    func testCopyReturnsShareTextAndMarksById() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")

        let copied = try XCTUnwrap(vm.copyCard())
        XCTAssertEqual(copied.text, card.shareText)
        XCTAssertEqual(vm.card?.phase, .copied)
        XCTAssertEqual(vm.card?.viewState, .copied)

        vm.markCopied(messageId: copied.messageId, peerId: copied.peerId)
        XCTAssertEqual(status(card.messageId), .copied)
        XCTAssertNil(vm.card)
        XCTAssertEqual(vm.sharedNotice, 0)
        XCTAssertEqual(vm.copiedNotice, 1)
    }

    // ⑨ dismissSealed 只收卡,不动落库状态(仍是 sealed)。
    func testDismissSealedClearsCardWithoutMarkingSent() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")

        vm.dismissSealed()

        XCTAssertNil(vm.card)
        XCTAssertEqual(status(card.messageId), .sealed)
    }

    // ⑩ consumeSendError 清位,供 UI 展示一次 alert 后复位。
    func testConsumeSendErrorClears() async throws {
        let vm = makeVM(ThrowingThreadCrypto())
        vm.draft = "hello"
        vm.seal()
        await poll { vm.sendError != nil }

        vm.consumeSendError()

        XCTAssertNil(vm.sendError)
    }

    // ⑪ 回归:延迟收尾(如「复制」600ms 窗口)必须按调度前捕获的 messageId 收尾,
    // 窗口期内封缄的下一条不能被误标、误收卡。
    func testMarkCopiedByIdDoesNotClobberNewerSealedMessage() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let cardA = try await sealText(vm, "A")
        let cardB = try await sealText(vm, "B")
        XCTAssertNotEqual(cardA.messageId, cardB.messageId)

        vm.markCopied(messageId: cardA.messageId, peerId: "alice")

        XCTAssertEqual(status(cardA.messageId), .copied)
        XCTAssertEqual(status(cardB.messageId), .sealed)
        XCTAssertEqual(vm.card?.messageId, cardB.messageId)
        XCTAssertEqual(vm.copiedNotice, 1)
        XCTAssertEqual(vm.sharedNotice, 0)
    }

    // ⑫ 媒体封缄:生成与文字一致的封缄卡(摘要=类型与数量),并自动弹分享面板。
    func testMediaSealedBuildsCardAndAutoPresentsShare() throws {
        let vm = makeVM(EchoThreadCrypto())
        try seedImages(id: "m1", peer: "alice", count: 3)

        vm.mediaSealed(messageId: "m1", peerId: "alice", shareText: "🔒 x\n🔒W")

        let card = try XCTUnwrap(vm.card)
        XCTAssertEqual(card.summary, L10n.mediaPhotoCount(3))
        XCTAssertEqual(card.caption(recipientName: "小明"), L10n.cardSendTo("小明"))
        XCTAssertEqual(card.shareText, "🔒 x\n🔒W")
        XCTAssertEqual(card.phase, .sharing)
        XCTAssertEqual(vm.presentedShare?.messageId, "m1")
        XCTAssertEqual(status("m1"), .sealed)

        let request = try XCTUnwrap(vm.presentedShare)
        vm.shareReported(request, completed: false)
        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertEqual(vm.card?.phase, .notSent)
        XCTAssertEqual(status("m1"), .sealed)
    }

    // ⑬ 转发到别的会话:只弹面板、完成时标目标会话那条;本线程不出卡。
    func testMediaSealedForOtherPeerMarksThatPeerWithoutCard() throws {
        let vm = makeVM(EchoThreadCrypto())
        try seedImages(id: "f1", peer: "bob", count: 1)

        vm.mediaSealed(messageId: "f1", peerId: "bob", shareText: "🔒 y\n🔒W")
        XCTAssertNil(vm.card, "转发到别的会话不应在本线程出卡")
        let request = try XCTUnwrap(vm.presentedShare)
        XCTAssertEqual(request.peerId, "bob")
        vm.shareReported(request, completed: true)

        XCTAssertEqual(status("f1", peer: "bob"), .shared)
        XCTAssertEqual(vm.sharedNotice, 0, "转发到别的会话完成,源线程不提示「已分享」(对齐 Android)")
        XCTAssertEqual(vm.copiedNotice, 0)
    }

    // ⑬b 本线程还没交出的文字卡,不会被一次转发顶掉。
    func testForwardDoesNotReplacePendingTextCard() async throws {
        let vm = makeVM(EchoThreadCrypto(), preferring: .copy)
        let text = try await sealText(vm, "hi")
        try seedImages(id: "f1", peer: "bob", count: 1)

        vm.mediaSealed(messageId: "f1", peerId: "bob", shareText: "🔒 y\n🔒W")
        let request = try XCTUnwrap(vm.presentedShare)
        vm.shareReported(request, completed: false)
        vm.presentedShare = nil
        vm.shareSheetDismissed()

        XCTAssertEqual(vm.card?.messageId, text.messageId)
        XCTAssertEqual(vm.card?.phase, .ready, "别的消息的面板取消不影响这张卡")
        XCTAssertEqual(status(text.messageId), .sealed)
    }

    // ⑭ 多条待分享排队:一次只弹一个面板,上一个收起后才弹下一个;排队中的不提前标记。
    func testSharesQueueOneAtATime() throws {
        let vm = makeVM(EchoThreadCrypto())
        try seedImages(id: "m1", peer: "alice", count: 1)
        try seedImages(id: "m2", peer: "alice", count: 2)

        vm.mediaSealed(messageId: "m1", peerId: "alice", shareText: "🔒 1\n🔒W")
        vm.mediaSealed(messageId: "m2", peerId: "alice", shareText: "🔒 2\n🔒W")
        XCTAssertEqual(vm.presentedShare?.messageId, "m1")
        XCTAssertEqual(vm.card?.messageId, "m2", "卡片显示最新一条")

        let first = try XCTUnwrap(vm.presentedShare)
        vm.shareReported(first, completed: true)
        XCTAssertEqual(status("m1"), .shared)
        XCTAssertEqual(status("m2"), .sealed)
        XCTAssertEqual(vm.card?.messageId, "m2", "标的不是当前卡,卡片不动")
        XCTAssertEqual(vm.sharedNotice, 1, "排队那条完成也提示「已分享」")

        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertEqual(vm.presentedShare?.messageId, "m2")
        XCTAssertEqual(vm.card?.phase, .sharing, "第一条面板收起不影响第二条卡片")
    }

    // ⑮ 同一条已在展示中,再点分享不重复入队;取消后是「未发出」,再点分享恰好新弹一次。
    func testShareCardDoesNotDuplicatePendingRequest() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")
        vm.shareCard()
        let first = try XCTUnwrap(vm.presentedShare)
        vm.shareCard()
        vm.presentedShare = nil
        vm.shareSheetDismissed()

        XCTAssertNil(vm.presentedShare, "重复点击不应排出第二个面板")
        XCTAssertEqual(vm.card?.phase, .notSent)

        vm.shareCard()
        let second = try XCTUnwrap(vm.presentedShare)
        XCTAssertNotEqual(second.id, first.id)
        XCTAssertEqual(second.messageId, card.messageId)
        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertNil(vm.presentedShare, "再点一次只产生一个新请求")
    }

    // ⑮b 别的模态在台上时不请求分享面板;解除后才弹(不会卡死在 presenting)。
    func testShareWaitsWhileAnotherModalIsUp() async throws {
        let vm = makeVM(EchoThreadCrypto())
        try seedImages(id: "m1", peer: "alice", count: 1)
        vm.setPresentationBlocked(true)

        vm.mediaSealed(messageId: "m1", peerId: "alice", shareText: "🔒 1\n🔒W")
        XCTAssertNil(vm.presentedShare)
        XCTAssertEqual(vm.card?.phase, .sharing)

        vm.shareCard()   // 用户再点也不重复入队
        vm.setPresentationBlocked(false)
        XCTAssertEqual(vm.presentedShare?.messageId, "m1")

        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertNil(vm.presentedShare, "只排了一个")
        XCTAssertEqual(vm.card?.phase, .notSent)
    }

    // ⑮c 复制卡片会撤掉这条还在排队的分享,之后不会再为它弹面板。
    func testCopyOrDismissDropsQueuedShare() async throws {
        let vm = makeVM(EchoThreadCrypto())
        try seedImages(id: "m0", peer: "alice", count: 1)
        try seedImages(id: "m1", peer: "alice", count: 1)
        vm.mediaSealed(messageId: "m0", peerId: "alice", shareText: "🔒 0\n🔒W")   // 占住面板
        vm.mediaSealed(messageId: "m1", peerId: "alice", shareText: "🔒 1\n🔒W")   // 排队
        XCTAssertEqual(vm.presentedShare?.messageId, "m0")

        _ = vm.copyCard()   // 复制 m1
        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertNil(vm.presentedShare, "已复制的那条不该再弹面板")
    }

    // ⑮d 收起卡片撤掉排队中的请求。
    func testDismissDropsQueuedShare() async throws {
        let vm = makeVM(EchoThreadCrypto())
        try seedImages(id: "m0", peer: "alice", count: 1)
        vm.mediaSealed(messageId: "m0", peerId: "alice", shareText: "🔒 0\n🔒W")   // 占住面板
        _ = try await sealText(vm, "hi")
        vm.shareCard()      // 文字卡排队
        vm.dismissSealed()
        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertNil(vm.presentedShare, "收起的卡不该再弹面板")
    }

    // ⑮d' 删掉封缄卡上的那条(UAT B1):卡片收起、它排队的面板撤掉、「复制」不再交出已删消息的文本。
    func testDeletingTheCardMessageClosesTheCardAndDropsItsQueuedShare() async throws {
        let vm = makeVM(EchoThreadCrypto())
        try seedImages(id: "m0", peer: "alice", count: 1)
        vm.mediaSealed(messageId: "m0", peerId: "alice", shareText: "🔒 0\n🔒W")   // 占住面板
        let card = try await sealText(vm, "hi")
        vm.shareCard()      // 文字卡排队
        vm.messageDeleted(card.messageId)
        XCTAssertNil(vm.card)
        XCTAssertNil(vm.copyCard(), "已删消息的分享文本不能再被复制")
        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertNil(vm.presentedShare, "已删消息不该再弹面板")
    }

    // ⑮d'' 删掉别的消息:卡片不动。
    func testDeletingAnotherMessageLeavesTheCard() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let first = try await sealText(vm, "one")
        let second = try await sealText(vm, "two")
        vm.messageDeleted(first.messageId)
        XCTAssertEqual(vm.card?.messageId, second.messageId)
    }

    // ⑮e 完成记录有上限,且上限内晚到的 true 仍只标一次。
    func testCompletedRequestsBounded() throws {
        let vm = makeVM(EchoThreadCrypto())
        for i in 0..<20 {
            try seedImages(id: "q\(i)", peer: "bob", count: 1)
            vm.mediaSealed(messageId: "q\(i)", peerId: "bob", shareText: "🔒 \(i)\n🔒W")
            let r = try XCTUnwrap(vm.presentedShare)
            vm.shareReported(r, completed: true)
            vm.shareReported(r, completed: true)
            vm.presentedShare = nil
            vm.shareSheetDismissed()
        }
        XCTAssertEqual(vm.sharedNotice, 0, "转发到别的会话不在源线程提示")
        XCTAssertTrue((0..<20).allSatisfy { status("q\($0)", peer: "bob") == .shared })
        XCTAssertLessThanOrEqual(vm.completedRequestCountForTesting, 8)
    }

    // ⑰ 文字 wire 随消息落库;气泡长按「分享」走同一队列、完成才标已分享;「复制」立即标已复制。
    func testTextWirePersistedAndBubbleActions() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")
        vm.dismissSealed()   // 卡片没了,wire 仍在消息上
        let stored = try XCTUnwrap(store.message(id: card.messageId, peerId: "alice"))
        XCTAssertEqual(stored.wire.map { MediaShareText.forText($0, site: site) }, card.shareText)

        XCTAssertTrue(vm.shareMessage(stored))
        let request = try XCTUnwrap(vm.presentedShare)
        XCTAssertEqual(request.text, card.shareText)
        vm.shareReported(request, completed: false)
        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertEqual(status(card.messageId), .sealed, "取消不标")

        XCTAssertTrue(vm.shareMessage(stored))
        vm.shareReported(try XCTUnwrap(vm.presentedShare), completed: true)
        XCTAssertEqual(status(card.messageId), .shared)

        let card2 = try await sealText(vm, "second")
        let stored2 = try XCTUnwrap(store.message(id: card2.messageId, peerId: "alice"))
        XCTAssertEqual(vm.copyMessage(stored2), card2.shareText)
        XCTAssertEqual(status(card2.messageId), .copied)
        XCTAssertNil(vm.card, "复制的正是当前卡那条,卡片收起")
    }

    // ⑱ 没有 wire 的旧消息、收到的消息、媒体消息都不给气泡交出动作。
    func testHandOffTextOnlyForOwnTextWithWire() {
        let old = ChatMessage(id: "o", peerId: "alice", direction: .outgoing, body: "x", timestamp: Date(), status: .sealed)
        let incoming = ChatMessage(id: "i", peerId: "alice", direction: .incoming, body: "x", timestamp: Date(),
                                   status: .received, wire: "🔒W")
        let own = ChatMessage(id: "s", peerId: "alice", direction: .outgoing, body: "x", timestamp: Date(),
                              status: .sealed, wire: "🔒W")
        XCTAssertNil(ThreadViewModel.handOffText(old))
        XCTAssertNil(ThreadViewModel.handOffText(incoming))
        XCTAssertEqual(ThreadViewModel.handOffText(own), MediaShareText.forText("🔒W", site: site))
    }

    // ⑯ 文字摘要:首个非空行、去首尾空白、超长截断加省略号。
    func testTextSummary() {
        XCTAssertEqual(ThreadViewModel.SealCard.textSummary("\n  你好  \n第二行"), "你好")
        let long = String(repeating: "字", count: 40)
        XCTAssertEqual(ThreadViewModel.SealCard.textSummary(long),
                       String(repeating: "字", count: ThreadViewModel.SealCard.summaryLimit) + "…")
    }

    // ⑲ 每次复制都提示「已复制」;复制从不提示「已分享」,状态停在已复制。
    func testCopyingTwiceShowsCopiedEachTimeNeverShared() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")
        let stored = try XCTUnwrap(store.message(id: card.messageId, peerId: "alice"))

        XCTAssertNotNil(vm.copyMessage(stored))
        XCTAssertEqual(vm.copiedNotice, 1)
        XCTAssertNotNil(vm.copyMessage(stored))
        XCTAssertEqual(vm.copiedNotice, 2)
        XCTAssertEqual(vm.sharedNotice, 0)
        XCTAssertEqual(status(card.messageId), .copied)
    }

    // ⑲b 重复分享一条已分享的消息,面板完成也不再提示。
    func testReSharingAlreadySharedMessageShowsNoNotice() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")
        vm.shareCard()
        vm.shareReported(try XCTUnwrap(vm.presentedShare), completed: true)
        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertEqual(vm.sharedNotice, 1)
        let stored = try XCTUnwrap(store.message(id: card.messageId, peerId: "alice"))

        XCTAssertTrue(vm.shareMessage(stored))
        vm.shareReported(try XCTUnwrap(vm.presentedShare), completed: true)
        XCTAssertEqual(vm.sharedNotice, 1)
        XCTAssertEqual(vm.copiedNotice, 0)
        XCTAssertEqual(status(card.messageId), .shared)
    }

    // ⑲d 卡片复制收尾后标已复制,绝不显示/提示已分享。
    func testCopyCardThenMarkCopiedNeverShowsShared() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")
        let copy = try XCTUnwrap(vm.copyCard())
        vm.markCopied(messageId: copy.messageId, peerId: copy.peerId)

        XCTAssertEqual(status(card.messageId), .copied)
        XCTAssertEqual(vm.sharedNotice, 0)
        XCTAssertEqual(vm.copiedNotice, 1)
    }

    // ⑲e 先复制、再从气泡分享且面板完成 → 升级为已分享,并提示一次。
    func testShareCompletionAfterCopyUpgradesToSharedAndNotifies() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")
        let copy = try XCTUnwrap(vm.copyCard())
        vm.markCopied(messageId: copy.messageId, peerId: copy.peerId)
        let stored = try XCTUnwrap(store.message(id: card.messageId, peerId: "alice"))

        XCTAssertTrue(vm.shareMessage(stored))
        vm.shareReported(try XCTUnwrap(vm.presentedShare), completed: true)

        XCTAssertEqual(status(card.messageId), .shared)
        XCTAssertEqual(vm.sharedNotice, 1)
        XCTAssertEqual(vm.copiedNotice, 1)
    }

    // ⑲f 先分享完成、再复制:状态保持已分享(不降级),只提示「已复制」。
    func testCopyAfterShareKeepsShared() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")
        vm.shareCard()
        vm.shareReported(try XCTUnwrap(vm.presentedShare), completed: true)
        let stored = try XCTUnwrap(store.message(id: card.messageId, peerId: "alice"))

        XCTAssertNotNil(vm.copyMessage(stored))
        XCTAssertEqual(status(card.messageId), .shared)
        XCTAssertEqual(vm.sharedNotice, 1)
        XCTAssertEqual(vm.copiedNotice, 1)
    }

    // ⑲c 收到的媒体「复制密文」:只提示「已复制」,不改状态、不记偏好。
    func testCopyingIncomingMediaWireShowsCopied() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "cc-threadvm-\(UUID().uuidString)"))
        let vm = ThreadViewModel(peerId: "alice", service: makeService(crypto: EchoThreadCrypto()), preferences: defaults)
        let incoming = ChatMessage(id: "in1", peerId: "alice", direction: .incoming, body: "🔒 x\n🔒W",
                                   timestamp: Date(), status: .received, kind: .image, media: [])
        try store.append(incoming)

        XCTAssertEqual(vm.copyMediaWire(incoming), "🔒 x\n🔒W")
        XCTAssertEqual(vm.sharedNotice, 0)
        XCTAssertEqual(vm.copiedNotice, 1)
        XCTAssertNil(defaults.string(forKey: ThreadViewModel.sealActionKey))
    }

    // ⑳ 终审 4:气泡长按分享/复制与卡片按钮一样记住交出偏好(对齐 Android)。
    func testBubbleShareAndCopyRememberSealAction() async throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "cc-threadvm-\(UUID().uuidString)"))
        let vm = ThreadViewModel(peerId: "alice", service: makeService(crypto: EchoThreadCrypto()), preferences: defaults)
        let card = try await sealText(vm, "hi")
        vm.dismissSealed()
        let stored = try XCTUnwrap(store.message(id: card.messageId, peerId: "alice"))

        XCTAssertTrue(vm.shareMessage(stored))
        XCTAssertEqual(defaults.string(forKey: ThreadViewModel.sealActionKey), SealCardAction.share.rawValue)

        XCTAssertNotNil(vm.copyMessage(stored))
        XCTAssertEqual(defaults.string(forKey: ThreadViewModel.sealActionKey), SealCardAction.copy.rawValue)

        try seedImages(id: "m1", peer: "alice", count: 1)
        defaults.removeObject(forKey: ThreadViewModel.sealActionKey)
        XCTAssertNotNil(vm.copyMediaWire(try XCTUnwrap(store.message(id: "m1", peerId: "alice"))))
        XCTAssertEqual(defaults.string(forKey: ThreadViewModel.sealActionKey), SealCardAction.copy.rawValue)
        XCTAssertEqual(status("m1"), .copied)
    }

    // ⑳b 卡片按钮也记偏好。
    func testCardButtonsRememberSealAction() async throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "cc-threadvm-\(UUID().uuidString)"))
        let vm = ThreadViewModel(peerId: "alice", service: makeService(crypto: EchoThreadCrypto()), preferences: defaults)
        _ = try await sealText(vm, "hi")
        vm.shareCard()
        XCTAssertEqual(defaults.string(forKey: ThreadViewModel.sealActionKey), SealCardAction.share.rawValue)
        XCTAssertNotNil(vm.copyCard())
        XCTAssertEqual(defaults.string(forKey: ThreadViewModel.sealActionKey), SealCardAction.copy.rawValue)
    }

    // MARK: - 加密即分享 / 卡片副行 / 状态行重开卡片 / 草稿来件

    func testSealAutoRequestsShareWhenPreferenceIsShare() async throws {
        let vm = makeVM(EchoThreadCrypto(), preferring: .share)
        let card = try await sealText(vm, "hi")
        XCTAssertEqual(vm.presentedShare?.messageId, card.messageId)
        XCTAssertEqual(vm.presentedShare?.text, card.shareText)
        XCTAssertEqual(vm.card?.phase, .sharing)
        XCTAssertEqual(status(card.messageId), .sealed, "弹面板不算分享")
    }

    func testSealDoesNotPresentShareWhenPreferenceIsCopy() async throws {
        let vm = makeVM(EchoThreadCrypto(), preferring: .copy)
        _ = try await sealText(vm, "hi")
        XCTAssertNil(vm.presentedShare)
        XCTAssertEqual(vm.card?.phase, .ready)
    }

    func testCardCaptionNamesRecipientAndNotSent() async throws {
        let vm = makeVM(EchoThreadCrypto())
        let card = try await sealText(vm, "hi")
        XCTAssertEqual(card.caption(recipientName: "小明"), L10n.cardSendTo("小明"))

        let request = try XCTUnwrap(vm.presentedShare)
        vm.shareReported(request, completed: false)
        vm.presentedShare = nil
        vm.shareSheetDismissed()
        XCTAssertEqual(vm.card?.caption(recipientName: "小明"), L10n.statusNotSentTapAgain)
    }

    func testReopenCardForCopiedMessage() async throws {
        let vm = makeVM(EchoThreadCrypto(), preferring: .copy)
        let card = try await sealText(vm, "hello")
        let copy = try XCTUnwrap(vm.copyCard())
        vm.markCopied(messageId: copy.messageId, peerId: copy.peerId)
        XCTAssertNil(vm.card)
        let stored = try XCTUnwrap(store.message(id: card.messageId, peerId: "alice"))
        XCTAssertTrue(MessageStatusLine.isStatusTappable(stored))

        vm.reopenCard(for: stored)

        XCTAssertEqual(vm.card?.messageId, card.messageId)
        XCTAssertEqual(vm.card?.phase, .ready)
        XCTAssertEqual(vm.card?.shareText, card.shareText)
        XCTAssertEqual(vm.card?.summary, "hello")
        XCTAssertNil(vm.presentedShare, "重开卡片不自动弹面板")
    }

    func testReopenCardForSealedMedia() throws {
        let vm = makeVM(EchoThreadCrypto())
        try seedImages(id: "m1", peer: "alice", count: 2)
        let stored = try XCTUnwrap(store.message(id: "m1", peerId: "alice"))

        vm.reopenCard(for: stored)

        XCTAssertEqual(vm.card?.messageId, "m1")
        XCTAssertEqual(vm.card?.shareText, stored.body)
        XCTAssertEqual(vm.card?.phase, .ready)
        XCTAssertNil(vm.presentedShare)
    }

    func testReopenCardIgnoresSharedAndIncoming() async throws {
        let vm = makeVM(EchoThreadCrypto(), preferring: .copy)
        let card = try await sealText(vm, "hi")
        vm.dismissSealed()
        _ = makeService(crypto: EchoThreadCrypto()).markShared(messageId: card.messageId, peerId: "alice")
        let shared = try XCTUnwrap(store.message(id: card.messageId, peerId: "alice"))
        let incoming = ChatMessage(id: "in", peerId: "alice", direction: .incoming, body: "x", timestamp: Date(),
                                   status: .received, wire: "🔒W")
        let oldOwn = ChatMessage(id: "old", peerId: "alice", direction: .outgoing, body: "x", timestamp: Date(),
                                 status: .sealed)
        for message in [shared, incoming, oldOwn] {
            XCTAssertFalse(MessageStatusLine.isStatusTappable(message), message.id)
            vm.reopenCard(for: message)
            XCTAssertNil(vm.card, message.id)
        }
    }

    func testTakeIntakeFromDraftClearsPastedWire() {
        let vm = makeVM(EchoThreadCrypto(), kindOf: { $0.hasPrefix("🔒FAKE") ? .session : .unknown })
        vm.draft = "🔒FAKE:alice:x"
        XCTAssertEqual(vm.takeIntakeFromDraft(), "🔒FAKE:alice:x")
        XCTAssertEqual(vm.draft, "")
    }

    func testTakeIntakeFromDraftTakesPairingCode() {
        let vm = makeVM(EchoThreadCrypto(), kindOf: { $0.hasPrefix("🔒PAIRINV") ? .pairingBundle : .unknown })
        let raw = "🔒 header · open it\n🔒PAIRINV:abc"
        vm.draft = raw
        XCTAssertEqual(vm.takeIntakeFromDraft(), raw)
        XCTAssertEqual(vm.draft, "")
    }

    func testTakeIntakeFromDraftKeepsOrdinaryText() {
        let vm = makeVM(EchoThreadCrypto(), kindOf: { $0.hasPrefix("🔒FAKE") ? .session : .unknown })
        for text in ["明天见", "🔒 看起来像但不是", ""] {
            vm.draft = text
            XCTAssertNil(vm.takeIntakeFromDraft(), text)
            XCTAssertEqual(vm.draft, text)
        }
    }

    func testSessionLostMessage() async throws {
        let vm = makeVM(ThrowingThreadCrypto())
        vm.draft = "hello"
        vm.seal()
        await poll { vm.sendError != nil }
        XCTAssertEqual(vm.sendError, L10n.mediaFailureSessionLost)
    }

    // MARK: - 媒体重试(先分享、后上传 spec §1.3)

    private final class ScheduleLog { var ids: [String] = [] }

    private func makeSender(log: ScheduleLog, mediaCrypto: CountingMediaCrypto) -> (MediaSender, URL) {
        let mediaRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-threadvm-media-\(UUID().uuidString)", isDirectory: true)
        var counter = 0
        let sender = MediaSender(store: store, crypto: EchoThreadCrypto(), mediaCrypto: mediaCrypto,
                                 transport: FakeMediaTransport(), files: MediaFiles(root: mediaRoot),
                                 activity: MediaActivity(),
                                 uploadScheduler: { log.ids.append($0) },
                                 now: { Date(timeIntervalSince1970: 1_000) },
                                 newId: { counter += 1; return "media\(counter)" })
        return (sender, mediaRoot)
    }

    private func pickedImage() -> PreparedMedia {
        PreparedMedia(kind: .image, plaintext: Data(repeating: 0xAB, count: 2_000), durMs: 0, width: 10, height: 10)
    }

    // 已分享(「对方还看不到 · 点击重新上传」)的重试只重排上传:不出封缄卡、不弹分享面板、不改分享文本、
    // 不重新加密;也不顶掉输入区上方别的卡片。
    func testRetryOfSharedMediaOnlyReschedulesUpload() async throws {
        let log = ScheduleLog()
        let mediaCrypto = CountingMediaCrypto()
        let (sender, mediaRoot) = makeSender(log: log, mediaCrypto: mediaCrypto)
        defer { try? FileManager.default.removeItem(at: mediaRoot) }
        let vm = makeVM(EchoThreadCrypto(), preferring: .copy)
        guard case let .sealed(id, body) = await sender.seal(to: "alice", media: [pickedImage()]) else {
            return XCTFail("封缄失败")
        }
        try store.updateMediaItem(messageId: id, peerId: "alice", index: 0) { $0.state = .failed }
        let textCard = try await sealText(vm, "别顶掉我")
        let seals = mediaCrypto.sealCount

        await vm.retryMedia(messageId: id, sender: sender)

        XCTAssertEqual(log.ids, [id, id], "封缄一次 + 重试一次,都只交给上传引擎")
        XCTAssertEqual(vm.card, textCard, "卡片不变")
        XCTAssertNil(vm.presentedShare, "不弹分享面板")
        XCTAssertEqual(store.message(id: id, peerId: "alice")?.body, body, "分享文本不变")
        XCTAssertEqual(mediaCrypto.sealCount, seals, "不重新加密")
    }

    // 还没分享过(加密阶段失败)的重试照旧:重新封缄成功 → 封缄卡 + 分享面板。
    func testRetryOfUnsharedMediaSealsAndPresentsShare() async throws {
        let log = ScheduleLog()
        let mediaCrypto = CountingMediaCrypto()
        mediaCrypto.failSeal(onCall: 1)
        let (sender, mediaRoot) = makeSender(log: log, mediaCrypto: mediaCrypto)
        defer { try? FileManager.default.removeItem(at: mediaRoot) }
        let vm = makeVM(EchoThreadCrypto())
        guard case let .failed(id) = await sender.seal(to: "alice", media: [pickedImage()]) else {
            return XCTFail("应当加密失败")
        }

        await vm.retryMedia(messageId: id, sender: sender)

        XCTAssertEqual(vm.card?.messageId, id)
        XCTAssertEqual(vm.presentedShare?.messageId, id)
        XCTAssertEqual(log.ids, [id])
    }
}
