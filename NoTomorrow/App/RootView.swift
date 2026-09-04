import SwiftUI
import SwiftData

struct RootView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.modelContext) private var modelContext

    var body: some View {
        Group {
            if appState.hasOnboarded {
                MainTabView()
            } else {
                OnboardingFlow()
            }
        }
        .environment(\.locale, AppLocale.effective(languageOverride: appState.languageOverride))
        .task {
            await ExerciseLibrary.shared.importIfNeeded(into: modelContext)
            RoutineSeeder.seedIfNeeded(context: modelContext)
        }
    }
}

struct MainTabView: View {
    @Environment(AppState.self) private var appState

    init() {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(NT.Colors.tabBar)
        appearance.shadowColor = UIColor(NT.Colors.hairline)
        let item = UITabBarItemAppearance()
        item.normal.iconColor = UIColor(NT.Colors.ink2)
        item.normal.titleTextAttributes = [.foregroundColor: UIColor(NT.Colors.ink2)]
        item.selected.iconColor = UIColor(NT.Colors.ink)
        item.selected.titleTextAttributes = [.foregroundColor: UIColor(NT.Colors.ink)]
        appearance.stackedLayoutAppearance = item
        appearance.inlineLayoutAppearance = item
        appearance.compactInlineLayoutAppearance = item
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }

    @Environment(WorkoutSessionController.self) private var session
    @Environment(\.modelContext) private var modelContext

    var body: some View {
        @Bindable var appState = appState
        @Bindable var session = session
        TabView(selection: $appState.selectedTab) {
            ForEach(AppTab.allCases) { tab in
                tabContent(tab)
                    .tabItem { Label(tab.titleKey, systemImage: tab.symbol) }
                    .tag(tab)
            }
        }
        // Presented from the tab shell (a stable ancestor) so starting a workout from any tab opens it reliably.
        .fullScreenCover(isPresented: $session.showsActiveWorkout) {
            if let workout = session.activeWorkout(in: modelContext) {
                ActiveWorkoutView(workout: workout)
            } else {
                NT.Colors.ground.ignoresSafeArea().onAppear { session.showsActiveWorkout = false }
            }
        }
    }

    @ViewBuilder
    private func tabContent(_ tab: AppTab) -> some View {
        switch tab {
        case .today: DashboardView()
        case .train: TrainView()
        case .fuel: FuelHomeView()
        case .progress: ProgressHomeView()
        case .bro: BroView()
        }
    }
}
