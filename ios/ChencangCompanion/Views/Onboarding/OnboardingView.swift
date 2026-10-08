import SwiftUI
import ChencangShared

/// 密信 App onboarding:品牌幕(即时建身份,无表单)→ 名字幕(可跳过)→
/// 机制幕(发消息→复制→粘贴图示)→ 之后是 RootView 切到的空态
/// 首页(见 `ConversationListView.emptyState`)。键盘启用引导移出关键路径,挪去
/// 设置(Task 9)——键盘现在只是配件,不再是 onboarding 必经步骤。
///
/// RootView 的显示闸门是 `MixinAppModel.showOnboarding`,不是
/// `identityStore.identity == nil`——identity 在幕 1 建号成功那一刻就已非 nil,
/// 早于后两幕被看到、早于「我明白了」被点,拿 identity 直接判会让后两幕在真机上
/// 几乎不可能渲染出来(2026-08 controller 裁决修复)。机制幕「我明白了」在这里
/// 显式把 `model.showOnboarding` 置 false 收尾。
struct OnboardingView: View {
    @StateObject var viewModel: OnboardingViewModel
    @EnvironmentObject private var model: MixinAppModel
    /// 当前幕,存 `@SceneStorage`:引导还在显示期间,场景被系统回收重建后回到同一幕。真正显示哪一幕由
    /// `viewModel.restoredAct(stored:)` 裁决(没有身份一律品牌幕);收尾时重置回品牌幕。
    @SceneStorage("onboarding.act") private var actRaw = OnboardingViewModel.Act.brand.rawValue
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Moyu.Palette.surfaceBase.ignoresSafeArea()

            Group {
                switch act {
                case .brand:
                    BrandActView(viewModel: viewModel, onCreated: { go(viewModel.actAfterIdentity()) })
                case .name:
                    NamePromptView(
                        onContinue: { go(viewModel.answerName($0)) },
                        onSkip: { go(viewModel.answerName(nil)) })
                case .mechanism:
                    MechanismActView(onDone: finishOnboarding)
                }
            }
            .transition(.opacity)
            .padding(Moyu.Space.xl)

            if viewModel.state == .creatingIdentity {
                Color.black.opacity(0.4).ignoresSafeArea()
                ProgressView(L10n.onboardingGenerating)
                    .padding(Moyu.Space.xl)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Moyu.Radius.card, style: .continuous))
            }
        }
    }

    private var act: OnboardingViewModel.Act { viewModel.restoredAct(stored: actRaw) }

    private func go(_ next: OnboardingViewModel.Act) {
        guard !reduceMotion else {
            actRaw = next.rawValue
            return
        }
        withAnimation(.easeInOut(duration: Moyu.Motion.standard / 1000)) {
            actRaw = next.rawValue
        }
    }

    /// 机制幕「我明白了」:结束 onboarding。RootView 下一帧就切到会话列表
    /// (空态 = 第三幕)。
    private func finishOnboarding() {
        actRaw = OnboardingViewModel.Act.brand.rawValue
        model.finishOnboarding()
    }
}

/// 品牌幕:品牌 + 一键建身份。创建即时、无表单——进行中态直接沿用 VM 的
/// `.creatingIdentity` phase(外层 `OnboardingView` 画 spinner 遮罩),成功后
/// 回调让父视图幕间转场。
private struct BrandActView: View {
    @ObservedObject var viewModel: OnboardingViewModel
    let onCreated: () -> Void

    var body: some View {
        VStack(spacing: Moyu.Space.xl) {
            Spacer()

            // 品牌猫当 App 标志，纯装饰（下面紧跟 App 名）。
            BrandCat(color: Moyu.Palette.accentPrimary)
                .frame(width: 96, height: 96)

            VStack(spacing: Moyu.Space.s) {
                Text(L10n.appName)
                    .font(moyuFont(Moyu.FontSize.display, weight: .semibold))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                Text(L10n.onboardingTagline)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
            }

            if let errorMessage = viewModel.errorMessage {
                Text(errorMessage)
                    .font(moyuFont(Moyu.FontSize.caption))
                    .foregroundStyle(Moyu.Palette.statusDanger)
                    .multilineTextAlignment(.center)
            }

            Spacer()

            Button {
                Task {
                    await viewModel.createIdentity()
                    if viewModel.state == .complete {
                        onCreated()
                    }
                }
            } label: {
                Text(L10n.onboardingCreateKeys)
                    .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Moyu.Space.s)
            }
            .buttonStyle(.borderedProminent)
            .tint(Moyu.Palette.accentPrimary)
            .disabled(viewModel.state == .creatingIdentity)
        }
    }
}

/// 机制幕:机制说明,旧 PairingExplainer 重画。三行图示——发消息/复制/粘贴
/// ——用 HStack(icon + 文案)排出,`Space.l` 行间距。「我明白了」调用父视图给的
/// `onDone`,把 `model.showOnboarding` 置 false 收尾——不能自己拿 identity 判断
/// (那正是原来会跳过本幕的 bug)。
private struct MechanismActView: View {
    let onDone: () -> Void

    private let steps: [(icon: String, text: String)] = [
        // 发消息 / 复制 / 粘贴。
        ("paperplane.fill", L10n.onboardingStepEncrypt),
        ("doc.on.doc", L10n.onboardingStepSend),
        ("doc.on.clipboard", L10n.onboardingStepDecrypt)
    ]

    var body: some View {
        VStack(spacing: Moyu.Space.xl) {
            Spacer()

            Text(L10n.onboardingHowTitle)
                .font(moyuFont(Moyu.FontSize.title, weight: .semibold))
                .foregroundStyle(Moyu.Palette.textPrimary)

            VStack(alignment: .leading, spacing: Moyu.Space.l) {
                ForEach(steps, id: \.icon) { step in
                    HStack(spacing: Moyu.Space.m) {
                        Image(systemName: step.icon)
                            .font(moyuFont(Moyu.FontSize.title, weight: .semibold))
                            .foregroundStyle(Moyu.Palette.accentPrimary)
                            .frame(width: Moyu.FontSize.title)
                        Text(step.text)
                            .font(moyuFont(Moyu.FontSize.body))
                            .foregroundStyle(Moyu.Palette.textPrimary)
                    }
                }
                Text(L10n.onboardingNetworkNote)
                    .font(moyuFont(Moyu.FontSize.callout))
                    .foregroundStyle(Moyu.Palette.textSecondary)
            }

            Spacer()

            Button(action: onDone) {
                Text(L10n.onboardingGotIt)
                    .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Moyu.Space.s)
            }
            .buttonStyle(.borderedProminent)
            .tint(Moyu.Palette.accentPrimary)
        }
    }
}
