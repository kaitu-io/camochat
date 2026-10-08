package app.chencang.android.ui.onboarding

/**
 * Onboarding state machine. Drives the OnboardingScreen UI:
 *  - [NeedsIdentity] — 幕 1(品牌):"创建我的密钥" button visible
 *  - [Generating]    — 幕 1:spinner; identity generation in flight
 *  - [Failed]        — 幕 1:error text + retry button
 *  - [Done]          — identity exists; the act (see [OnboardingAct]) moves on to
 *                       Name, then Mechanism. Caller's `onDone` only fires once the
 *                       user taps Mechanism's「我明白了」
 */
sealed interface OnboardingState {
    data object NeedsIdentity : OnboardingState
    data object Generating : OnboardingState
    data object Done : OnboardingState
    data class Failed(val message: String) : OnboardingState
}

/** 引导的幕：品牌 → 问名字 → 机制。随 SavedStateHandle 保存，重建后留在原幕。 */
enum class OnboardingAct { Brand, Name, Mechanism }
