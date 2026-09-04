import SwiftUI

/// Wordmark, tagline, language switch, Get started. No progress header here.
struct WelcomeView: View {
    @Environment(AppState.self) private var appState
    @Bindable var model: OnboardingModel

    private var language: Binding<String> {
        Binding(
            get: { appState.languageOverride ?? OBL10n.systemLanguage },
            set: { appState.languageOverride = $0 }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: "NO")
                    .font(NT.Fonts.display(132))
                    .foregroundStyle(NT.Colors.ember)
                Text(verbatim: "TOMORROW")
                    .font(NT.Fonts.display(100))
                    .foregroundStyle(NT.Colors.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 40)
            .accessibilityElement(children: .combine)

            Text("onboarding.tagline")
                .font(NT.Fonts.title2)
                .foregroundStyle(NT.Colors.ink)
                .padding(.top, 36)
            Text("onboarding.subtitle")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink2)
                .padding(.top, 10)

            Spacer(minLength: 24)

            OBSegmented(options: [
                .init(value: "en", title: "settings.english"),
                .init(value: "pl", title: "settings.polish"),
            ], selection: language)
            .obLabeled("onboarding.language", spacing: 10)

            VStack(spacing: 14) {
                PrimaryButton(title: "onboarding.getStarted") { model.startStandard() }
                Button { model.startWithPair() } label: {
                    HStack(spacing: 6) {
                        Text("onboarding.haveCode").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                        Text("onboarding.pairNow").font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: NT.Size.control)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressScale())
            }
            .padding(.top, 12)
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.bottom, 8)
        .ntScreenBackground()
    }
}
