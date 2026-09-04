import ActivityKit
import Foundation

/// Live Activity payload for the rest timer. Shared between the app and the widget extension.
struct RestTimerAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var endDate: Date
        var totalSeconds: Int
        var isPaused: Bool
    }

    var exerciseName: String
    var nextSetLabel: String      // "Set 3 of 3 · 85 × 7"
    var workoutName: String       // "Push A"
}
