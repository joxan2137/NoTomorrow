import UIKit
import UserNotifications

/// Installs the notification-center delegate before launch finishes, so a tap that cold-starts the app is routed.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = NotificationRouter.shared
        return true
    }
}

/// Notification-center delegate.
/// Taps on the "rest is over" notification become `AppState.Route.activeWorkout`, buffered until the UI connects
/// (cold start). In the foreground that notification shows as a banner only while the workout is collapsed into the
/// mini bar; with the full workout on screen the in-app haptic already says it. Other notifications keep the
/// system default of not showing in the foreground.
@MainActor
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationRouter()

    private var sink: ((AppState.Route) -> Void)?
    private var pending: AppState.Route?
    private var isWorkoutOnScreen: () -> Bool = { false }

    /// Called once the app state exists; flushes a route that arrived before.
    func connect(isWorkoutOnScreen: @escaping () -> Bool, sink: @escaping (AppState.Route) -> Void) {
        self.isWorkoutOnScreen = isWorkoutOnScreen
        self.sink = sink
        if let pending {
            self.pending = nil
            sink(pending)
        }
    }

    func deliver(_ route: AppState.Route) {
        if let sink { sink(route) } else { pending = route }
    }

    // MARK: Rules

    nonisolated static func route(forNotification identifier: String) -> AppState.Route? {
        identifier == RestTimerController.notificationID ? .activeWorkout : nil
    }

    nonisolated static func presentation(forNotification identifier: String, workoutOnScreen: Bool) -> UNNotificationPresentationOptions {
        guard identifier == RestTimerController.notificationID, !workoutOnScreen else { return [] }
        return [.banner, .list, .sound]
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let identifier = notification.request.identifier
        Task { @MainActor in
            let workoutOnScreen = self.isWorkoutOnScreen()
            // Over the full workout the banner (and its sound) is suppressed, so the chime is played here instead.
            if identifier == RestTimerController.notificationID, workoutOnScreen { RestChime.play() }
            completionHandler(Self.presentation(forNotification: identifier, workoutOnScreen: workoutOnScreen))
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let identifier = response.notification.request.identifier
        Task { @MainActor in
            if let route = Self.route(forNotification: identifier) { self.deliver(route) }
            completionHandler()
        }
    }
}
