import SwiftUI

/// Welcome → You → Schedule → Pair (or Pair first via "Pair now"). Draft state lives in `OnboardingModel`;
/// nothing is written to SwiftData until the last Continue / Not now.
struct OnboardingFlow: View {
    @Environment(AppState.self) private var appState
    @State private var model = OnboardingModel()

    var body: some View {
        ZStack {
            switch model.step {
            case .welcome:
                WelcomeView(model: model).transition(stepTransition)
            case .you:
                SetupYouView(model: model).transition(stepTransition)
            case .schedule:
                SetupScheduleView(model: model).transition(stepTransition)
            case .pair:
                SetupPairView(model: model).transition(stepTransition)
            }
        }
        .ntScreenBackground()
        .transformEnvironment(\.locale) { locale in
            // Live preview of the language picked on Welcome; the rest of the app switches on relaunch.
            if appState.languageOverride != nil { locale = AppLocale.effective(languageOverride: appState.languageOverride) }
        }
    }

    /// New step slides in from the side it was reached from; the old one fades out.
    private var stepTransition: AnyTransition {
        .asymmetric(
            insertion: .move(edge: model.movesForward ? .trailing : .leading).combined(with: .opacity),
            removal: .opacity
        )
    }
}
