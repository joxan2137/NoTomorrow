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
        .task {
            await ExerciseLibrary.shared.importIfNeeded(into: modelContext)
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

    var body: some View {
        @Bindable var appState = appState
        TabView(selection: $appState.selectedTab) {
            ForEach(AppTab.allCases) { tab in
                tabContent(tab)
                    .tabItem { Label(tab.titleKey, systemImage: tab.symbol) }
                    .tag(tab)
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
