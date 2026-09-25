import SwiftUI
import SwiftData

struct RootView: View {
    @Environment(AppState.self) private var appState
    @Environment(StoreLoader.self) private var store
    @Environment(WorkoutSessionController.self) private var session
    @Environment(\.modelContext) private var modelContext
    @State private var updateChecker = UpdateChecker.shared

    var body: some View {
        Group {
            if !store.isOpen {
                StoreErrorView()
            } else if appState.hasOnboarded {
                MainTabView()
            } else {
                OnboardingFlow()
            }
        }
        .overlay(alignment: .top) {
            if let tag = updateChecker.availableTag {
                UpdateBanner(tag: tag) {
                    withAnimation(.snappy) { updateChecker.dismiss() }
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.snappy, value: updateChecker.availableTag)
        .environment(\.locale, AppLocale.effective(languageOverride: appState.languageOverride))
        // Once per launch; silent when offline or rate-limited (`UpdateChecker`).
        .task { await updateChecker.checkOnce() }
        // Once per opened store: at launch, and again after StoreErrorView recovers one.
        .task(id: store.generation) {
            guard store.isOpen else { return }
            await ExerciseLibrary.importIfNeeded(into: modelContext)
            RoutineSeeder.seedIfNeeded(context: modelContext)
            RoutineSeeder.inheritDefaultRestIfNeeded(context: modelContext)
        }
        // "Delete account and data": the wipe runs once the tab shell and the Settings sheet have gone, so no
        // screen reads a deleted object (`LocalDataWipe`); also at launch, for an app killed in between.
        .task(id: appState.hasOnboarded) {
            guard !appState.hasOnboarded, LocalDataWipe.isPending() else { return }
            try? await Task.sleep(for: LocalDataWipe.settleDelay)
            guard !Task.isCancelled, store.isOpen, !appState.hasOnboarded else { return }
            LocalDataWipe.runPending(in: modelContext)
        }
        .onOpenURL { url in
            if let route = AppState.Route(url: url) { appState.pendingRoute = route }
        }
        .onAppear {
            let appState = appState
            let session = session
            // What is really on screen, not the request: a cover that could not present must not hide the banner.
            NotificationRouter.shared.connect(isWorkoutOnScreen: { session.isCoverOnScreen }) { route in
                appState.pendingRoute = route
            }
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
    @Environment(RestTimerController.self) private var restTimer
    @Environment(\.modelContext) private var modelContext

    var body: some View {
        @Bindable var appState = appState
        @Bindable var session = session
        TabView(selection: $appState.selectedTab) {
            ForEach(AppTab.allCases) { tab in
                tabContent(tab)
                    .workoutMiniBar(isEnabled: session.showsMiniBar)
                    .tabItem { Label(tab.titleKey, systemImage: tab.symbol) }
                    .tag(tab)
            }
        }
        // Presented from the tab shell (a stable ancestor) so starting a workout from any tab opens it reliably, by a
        // presenter the session can replace (`coverEpoch`) when SwiftUI has wedged it.
        .background {
            Color.clear
                .fullScreenCover(isPresented: $session.showsActiveWorkout) {
                    ActiveWorkoutCover()
                }
                .id(session.coverEpoch)
        }
        // A Start while a workout runs (Train): asked over the whole shell, so its actions run at the tap.
        .workoutStartConflictDialog(session: session, restTimer: restTimer, context: modelContext)
        // Closes a rest that runs out while the workout is collapsed (haptic, Live Activity ended, mini bar updated).
        .restTimerExpiry()
        .task { session.restore(in: modelContext) }
        .onChange(of: appState.pendingRoute, initial: true) { _, route in
            guard let route else { return }
            appState.pendingRoute = nil
            handle(route)
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

    /// Notification, Live Activity and deep-link routes. The workout opens over whatever tab is showing.
    private func handle(_ route: AppState.Route) {
        switch route {
        case .activeWorkout, .restTimer:
            guard session.activeWorkout(in: modelContext) != nil else { return }
            session.expand(restSheet: route == .restTimer && restTimer.isRunning)
        case .bro:
            appState.selectedTab = .bro
        case .settings:
            appState.selectedTab = .today   // the Settings sheet belongs to the Today screen
        }
    }
}

/// Content of the workout cover. Resolved by the session's id, not by "unfinished", so the summary of a just-finished
/// workout stays up until Done; then held for the rest of this presentation, so the workout keeps rendering while
/// the cover slides away after Done / Discard (the session has already let go by then).
/// Reports whether it is really in a window, so the session knows whether a presentation it asked for happened: a
/// cover SwiftUI could not present (a tab's sheet was up) still builds its content and fires `onAppear`.
private struct ActiveWorkoutCover: View {
    @Environment(WorkoutSessionController.self) private var session
    @Environment(\.modelContext) private var modelContext
    @State private var presented: Workout?

    var body: some View {
        // One container (not a Group, whose modifiers apply to each branch), so the switch from the workout to the
        // empty branch does not read as the cover going away.
        ZStack {
            if let workout = presented ?? session.workout(in: modelContext) {
                ActiveWorkoutView(workout: workout)
                    .onAppear { if presented == nil { presented = workout } }
            } else {
                NT.Colors.ground.ignoresSafeArea().onAppear { session.showsActiveWorkout = false }
            }
        }
        .background(WindowPresenceProbe { inWindow in
            if inWindow { session.coverDidAppear() } else { session.coverDidDisappear() }
        })
        .onDisappear { session.coverDidDisappear() }
    }
}

/// Calls `onChange(true)` when its view joins a window and `onChange(false)` when it leaves one.
private struct WindowPresenceProbe: UIViewRepresentable {
    var onChange: (Bool) -> Void

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        view.onChange = onChange
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.onChange = onChange
    }

    final class ProbeView: UIView {
        var onChange: ((Bool) -> Void)?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            onChange?(window != nil)
        }
    }
}
