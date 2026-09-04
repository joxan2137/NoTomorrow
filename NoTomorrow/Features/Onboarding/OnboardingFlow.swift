import SwiftUI

// STUB — replace this file with the real onboarding (see docs/architecture.md).
struct OnboardingFlow: View {
    @Environment(AppState.self) private var appState
    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            VStack(alignment: .leading, spacing: 6) {
                Text("NO").font(NT.Fonts.display(132)).foregroundStyle(NT.Colors.ember)
                Text("TOMORROW").font(NT.Fonts.display(100)).foregroundStyle(NT.Colors.ink).minimumScaleFactor(0.6).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Spacer()
            PrimaryButton(title: "onboarding.getStarted") { appState.hasOnboarded = true }
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.bottom, 12)
        .ntScreenBackground()
    }
}
