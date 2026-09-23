import ActivityKit
import Foundation

/// Live Activity payload for the rest timer. Shared between the app and the widget extension.
struct RestTimerAttributes: ActivityAttributes {
    /// Everything that can change while one activity is up (a set ticked mid-rest moves "up next").
    struct ContentState: Codable, Hashable {
        var endDate: Date
        var totalSeconds: Int
        var isPaused: Bool
        var exerciseName: String
        var nextSetLabel: String      // "Set 3 of 3 · 85 × 7"
    }

    var workoutName: String       // "Push A"
    /// Localized by the app (the extension has no string catalog): "Rest" / "Przerwa".
    var restLabel: String
    /// Shown once the rest has run out: "Rest is over. Go." / "Koniec przerwy. Lecimy."
    var overLabel: String

    /// Tapping the Lock Screen / Dynamic Island opens the workout with the rest sheet (`AppState.Route.restTimer`).
    static let deepLink = URL(string: "notomorrow://workout/rest")!
}
