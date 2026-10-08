import Foundation

/// 引导期间到达的向导请求(配对链接、扩展交来的配对码……)先暂存,引导结束再弹,
/// 免得向导叠在引导页上、或在没有身份时开始配对。只留最后一个(后来的覆盖先来的)。
public struct OnboardingGate: Equatable, Sendable {
    private var held: WizardEntry?

    public init() {}

    /// 引导中 → 暂存并返回 nil;否则原样返回,由调用方立即弹出。
    public mutating func offer(_ entry: WizardEntry, onboarding: Bool) -> WizardEntry? {
        guard onboarding else { return entry }
        held = entry
        return nil
    }

    /// 取出暂存的请求并清空。
    public mutating func release() -> WizardEntry? {
        defer { held = nil }
        return held
    }
}
