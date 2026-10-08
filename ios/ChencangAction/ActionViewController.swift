import UIKit
import SwiftUI
import UniformTypeIdentifiers
import ChencangShared

/// Action Extension「陈仓解密」—— iOS 对等 Android 的 PROCESS_TEXT 收件路径。
/// 聊天软件里选中一段「🔒…」加密消息,走系统分享/操作面板选中本扩展,在**扩展进程**
/// 内解密(推进 DR 棘轮并立即写回 Keychain)、把明文追加进 App Group 收件箱,
/// 用户点「在陈仓里回复 / 打开陈仓查看」时把他带回主 App 落到发件人会话——一次解密,主 App 只 drain 不重解。
///
/// 选中的是配对码时:扩展**不握手**,只在用户点「打开陈仓添加」时把配对码放进 `PendingIntakeStore`
/// 再拉起主 App(`camo://intake`),由主 App 的向导处理。扩展只做这两种写入,不写 `ChatStore`、
/// 不写配对记录、不调用 `IntakeRouter`。
///
/// 扩展内存 ≤30MB(spec §7),不做重活。
@MainActor
public class ActionViewController: UIViewController {
    private var hostingController: UIHostingController<ActionCardView>?
    private var currentState: ActionCardState = .decrypting

    public override func viewDidLoad() {
        super.viewDidLoad()
        present(state: .decrypting)
        loadInputText { [weak self] text in
            Task { @MainActor in
                await self?.decrypt(text)
            }
        }
    }

    /// 从系统分享面板的第一个 attachment 里取纯文本。拿不到就当空字符串处理——
    /// `ActionDecryptor.open("")` 会走 incomplete 分支,呈现失败卡而不是崩溃。
    private func loadInputText(_ completion: @escaping (String) -> Void) {
        guard let item = extensionContext?.inputItems.first as? NSExtensionItem,
              let provider = item.attachments?.first,
              provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier)
        else {
            completion("")
            return
        }
        provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { data, _ in
            let text = (data as? String)
                ?? (data as? Data).flatMap { String(data: $0, encoding: .utf8) }
                ?? ""
            completion(text)
        }
    }

    private func decrypt(_ text: String) async {
        let decryptor = ActionDecryptor(
            crypto: SessionThreadCrypto(),
            readContacts: { (try? AppGroupSync().readContacts()) ?? [] },
            inbox: AppGroupInbox(),
            hasAwaitingInvites: {
                // 只读 App Group 里的待办库;不构造 PendingInviteStore(它会迁移 / 写盘)。
                !awaitingInvites(PendingInviteStore.readRecords(),
                                 nowMillis: Int64(Date().timeIntervalSince1970 * 1000)).isEmpty
            }
        )
        switch await decryptor.open(text) {
        case let .opened(opened):
            present(state: .opened(opened))
        case let .pairingCode(kind, wire):
            present(state: .pairingCode(kind, wire: wire))
        case let .failed(failure):
            present(state: .failed(failure))
        }
    }

    private func present(state: ActionCardState, showManualOpenHint: Bool = false) {
        currentState = state
        let card = ActionCardView(
            state: state,
            showManualOpenHint: showManualOpenHint,
            onOpenThread: { [weak self] in self?.openThreadTapped() },
            onHandOffPairing: { [weak self] in self?.handOffPairingTapped() },
            onNextStep: { [weak self] step in self?.nextStepTapped(step) },
            onDone: { [weak self] in self?.finish() }
        )
        if let hostingController {
            hostingController.rootView = card
        } else {
            let host = UIHostingController(rootView: card)
            addChild(host)
            view.addSubview(host.view)
            host.view.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                host.view.topAnchor.constraint(equalTo: view.topAnchor),
                host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            ])
            host.didMove(toParent: self)
            hostingController = host
        }
    }

    private func openThreadTapped() {
        guard case let .opened(opened) = currentState else { return }
        openOrHint(ThreadOpenURL.make(peerId: opened.peerId, highlight: opened.entryId))
    }

    private func handOffPairingTapped() {
        guard case let .pairingCode(_, wire) = currentState else { return }
        do {
            try PendingIntakeStore().put(wire: wire)
        } catch {
            // 交接槽写不进去(App Group 不可用):拉起主 App 也取不到配对码,不能假装交接成功。
            present(state: .failed(.saveFailed))
            return
        }
        openOrHint(IntakeOpenURL.make(.pendingIntake))
    }

    private func nextStepTapped(_ step: IntakeNextStep) {
        switch step {
        case .addContact: openOrHint(IntakeOpenURL.make(.addContact))
        case .openApp: openOrHint(IntakeOpenURL.make(.home))
        }
    }

    /// 拉起主 App;成功才收起扩展(终审 F1),拉不起来就留着卡片并给出手动打开的提示
    /// (消息已进收件箱 / 配对码已进交接槽,不会丢)。
    private func openOrHint(_ url: URL) {
        let card = currentState
        openHostApp(url) { [weak self] opened in
            guard let self else { return }
            if opened {
                self.finish()
            } else {
                self.present(state: card, showManualOpenHint: true)
            }
        }
    }

    /// 扩展里拉起主 App(终审 F1)。
    ///
    /// iOS 18 起,老的同步 `-[UIApplication openURL:]` 被系统强制返回 NO、什么也不做(控制台会打
    /// 「BUG IN CLIENT OF UIKIT … needs to migrate to open(_:options:completionHandler:)」)——原来的
    /// `perform(openURL:)` 在 iOS 26 真机 3/3 次都停在备忘录。现在沿 responder 链找到扩展进程里的
    /// `UIApplication`,调用非弃用的 `open(_:options:completionHandler:)`。这个 API 在扩展目标里被
    /// `NS_EXTENSION_UNAVAILABLE` 挡在编译期,所以按 selector 取实现、转成对应的 C 函数指针类型调用
    /// (`perform(_:with:)` 传不了三个参数)。`NSExtensionContext.open` 只对 Today/iMessage 扩展有效,
    /// Action 扩展调用恒为 false,不能用。
    ///
    /// ⚠️ 苹果并未为 Action 扩展背书「打开宿主 App」,行为可能随系统版本变化:失败时由调用方保留卡片、
    /// 给出手动打开的提示——消息已经写进收件箱(配对码已进交接槽),用户自己切回主 App 就能看到。
    private func openHostApp(_ url: URL, completion: @escaping @MainActor (Bool) -> Void) {
        typealias OpenURLOptionsCompletion = @convention(c) (
            AnyObject, Selector, NSURL, NSDictionary, (@convention(block) (Bool) -> Void)?
        ) -> Void
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let current = responder {
            if let application = current as? UIApplication, application.responds(to: selector) {
                let open = unsafeBitCast(application.method(for: selector), to: OpenURLOptionsCompletion.self)
                let handler: @convention(block) (Bool) -> Void = { opened in
                    Task { @MainActor in completion(opened) }
                }
                open(application, selector, url as NSURL, NSDictionary(), handler)
                return
            }
            responder = current.next
        }
        completion(false)
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: nil)
    }
}
