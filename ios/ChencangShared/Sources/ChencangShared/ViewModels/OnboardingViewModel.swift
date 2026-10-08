import Foundation

@MainActor
public final class OnboardingViewModel: ObservableObject {
    public enum State: Equatable {
        case welcome
        case creatingIdentity
        case complete
    }

    @Published public private(set) var state: State = .welcome
    @Published public private(set) var errorMessage: String?

    /// 引导三幕:品牌 → 你的名字 → 机制。原始值给视图的 `@SceneStorage`,进程重建后停在原幕。
    public enum Act: String, Equatable, Sendable {
        case brand, name, mechanism
    }

    private let identityStore: IdentityStore
    private let namePromptDone: () -> Bool
    private let saveName: (String?) -> Void

    /// `namePromptDone` / `saveName` 必须显式注入(没有默认值,免得默默关掉问名字):
    /// `saveName(nil)` = 跳过,只记「问过了」;非 nil = 存昵称并记「问过了」。
    public init(identityStore: IdentityStore, namePromptDone: @escaping () -> Bool, saveName: @escaping (String?) -> Void) {
        self.identityStore = identityStore
        self.namePromptDone = namePromptDone
        self.saveName = saveName
    }

    /// 该显示哪一幕:没有身份一律从品牌幕开始(账号被抹掉后残留的旧值不能跳过建号);有身份时采用存下的幕,
    /// 存的值缺失或不认识 → 品牌幕。
    public nonisolated static func resolveAct(stored: String?, identityExists: Bool) -> Act {
        guard identityExists, let stored, let act = Act(rawValue: stored) else { return .brand }
        return act
    }

    public func restoredAct(stored: String?) -> Act {
        Self.resolveAct(stored: stored, identityExists: identityStore.hasPersistedIdentity())
    }

    /// 身份建好后去哪一幕:没问过名字 → 名字幕;问过了 → 机制幕。
    public func actAfterIdentity() -> Act {
        namePromptDone() ? .mechanism : .name
    }

    /// 名字幕「继续」(`raw` 为输入)/「跳过」(`nil`)。规范化后为空 = 跳过。返回下一幕。
    public func answerName(_ raw: String?) -> Act {
        saveName(raw.flatMap { normalizeMyName(clampMyNameInput($0)) }.flatMap { $0.isEmpty ? nil : $0 })
        return .mechanism
    }

    public func createIdentity() async {
        state = .creatingIdentity
        errorMessage = nil
        do {
            try await identityStore.loadOrCreate()
            state = .complete
        } catch {
            errorMessage = error.localizedDescription
            state = .welcome
        }
    }
}
