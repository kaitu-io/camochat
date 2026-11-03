import SwiftUI
import ChencangShared

/// 老用户升级后的一次性使用条款闸门:同意前不进主界面。条款版本加一时会再次出现。
struct TermsGateView: View {
    @EnvironmentObject private var model: MixinAppModel
    @State private var showTerms = false

    var body: some View {
        ZStack {
            Moyu.Palette.surfaceBase.ignoresSafeArea()
            VStack(spacing: Moyu.Space.xl) {
                Spacer()
                Text(L10n.termsGateTitle)
                    .font(moyuFont(Moyu.FontSize.title, weight: .semibold))
                    .foregroundStyle(Moyu.Palette.textPrimary)
                Text(L10n.termsGateBody)
                    .font(moyuFont(Moyu.FontSize.body))
                    .foregroundStyle(Moyu.Palette.textSecondary)
                    .multilineTextAlignment(.center)
                Button(L10n.termsView) { showTerms = true }
                    .font(moyuFont(Moyu.FontSize.body))
                    .tint(Moyu.Palette.accentPrimary)
                Spacer()
                Button {
                    model.acceptTerms()
                } label: {
                    Text(L10n.termsGateAccept)
                        .font(moyuFont(Moyu.FontSize.body, weight: .medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Moyu.Space.s)
                }
                .buttonStyle(.borderedProminent)
                .tint(Moyu.Palette.accentPrimary)
            }
            .padding(Moyu.Space.xl)
        }
        .sheet(isPresented: $showTerms) {
            SafariView(url: AppInfo.termsURL(site: ConfigRepository.shared.current().shareSite)).ignoresSafeArea()
        }
    }
}
