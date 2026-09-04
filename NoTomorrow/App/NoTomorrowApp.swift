import SwiftUI
import SwiftData

@main
struct NoTomorrowApp: App {
    @State private var appState = AppState()
    @State private var restTimer = RestTimerController()
    @State private var session = WorkoutSessionController()

    let container: ModelContainer = {
        let schema = Schema(NoTomorrowSchema.models)
        let config = ModelConfiguration("NoTomorrow", schema: schema, isStoredInMemoryOnly: false)
        do {
            return try ModelContainer(for: schema, configurations: [config])
        } catch {
            // A broken store on a dev build is not worth a crash loop; fall back to memory.
            let memory = ModelConfiguration(isStoredInMemoryOnly: true)
            return try! ModelContainer(for: schema, configurations: [memory])
        }
    }()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(appState)
                .environment(restTimer)
                .environment(session)
                .preferredColorScheme(.dark)
                .tint(NT.Colors.ink)
        }
        .modelContainer(container)
    }
}
