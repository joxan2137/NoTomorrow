import SwiftUI
import SwiftData

@main
struct NoTomorrowApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var appState = AppState()
    @State private var restTimer = RestTimerController()
    @State private var session = WorkoutSessionController()

    /// The SwiftData store. When it cannot be opened the app shows `StoreErrorView` instead of silently running on
    /// an empty in-memory store (see `StoreLoader`).
    @State private var store = StoreLoader()

    var body: some Scene {
        WindowGroup {
            StoreRoot(store: store)
                .environment(store)
                .environment(appState)
                .environment(restTimer)
                .environment(session)
                .preferredColorScheme(.dark)
                .tint(NT.Colors.ink)
        }
    }
}

/// Puts the loader's current container into the environment. A view (not the scene) reads it, so a recovered store
/// replaces the placeholder for the whole hierarchy.
private struct StoreRoot: View {
    let store: StoreLoader

    var body: some View {
        RootView().modelContainer(store.container)
    }
}
