import Foundation
import UserNotifications

/// The rest timer's persisted state, in the App Group so the Break timer widget reads the same end date the app
/// counts down to. `RestTimerController` is the only writer in the app; the extension writes only through
/// `RestCommand.applyWithoutApp` (see there).
struct SharedRestState: Equatable {
    var endDate: Date?
    var totalSeconds: Int
    var exerciseName: String
    var nextSetLabel: String
    var workoutName: String

    /// Identifier of the "rest is over" notification (`RestTimerController.notificationID`).
    static let notificationID = "nt.rest.end"
    /// The chime (`scripts/sounds/rest_over.py`), bundled in the app; the notification plays it.
    static let soundName = "rest_over.caf"

    private enum Keys {
        static let end = "nt.rest.endDate"
        static let total = "nt.rest.total"
        static let exercise = "nt.rest.exercise"
        static let next = "nt.rest.next"
        static let workout = "nt.rest.workout"
        static let migrated = "nt.rest.inGroup"
    }

    /// The group defaults, or `.standard` when there is no group (the app then works as before, without widgets).
    private static var store: UserDefaults { SharedStore.defaults ?? .standard }

    static func load() -> SharedRestState {
        migrateIfNeeded()
        let d = store
        var end: Date?
        if let ts = d.object(forKey: Keys.end) as? Double { end = Date(timeIntervalSince1970: ts) }
        return SharedRestState(endDate: end,
                               totalSeconds: max(5, d.integer(forKey: Keys.total)),
                               exerciseName: d.string(forKey: Keys.exercise) ?? "",
                               nextSetLabel: d.string(forKey: Keys.next) ?? "",
                               workoutName: d.string(forKey: Keys.workout) ?? "")
    }

    func save() {
        let d = Self.store
        d.set(endDate?.timeIntervalSince1970, forKey: Keys.end)
        d.set(totalSeconds, forKey: Keys.total)
        d.set(exerciseName, forKey: Keys.exercise)
        d.set(nextSetLabel, forKey: Keys.next)
        d.set(workoutName, forKey: Keys.workout)
    }

    /// Before the widgets, the state lived in `UserDefaults.standard`; carry a running rest over once.
    private static func migrateIfNeeded() {
        guard let group = SharedStore.defaults, !group.bool(forKey: Keys.migrated) else { return }
        let old = UserDefaults.standard
        for key in [Keys.end, Keys.total, Keys.exercise, Keys.next, Keys.workout] {
            if group.object(forKey: key) == nil, let value = old.object(forKey: key) { group.set(value, forKey: key) }
        }
        group.set(true, forKey: Keys.migrated)
    }

    func isRunning(at now: Date = .now) -> Bool { (endDate ?? .distantPast) > now }

    /// "Exercise · Set 3 of 3 · 85 × 7", or whichever half exists.
    var notificationBody: String {
        [exerciseName, nextSetLabel].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// What a Break timer button asks for. The widget's `LiveActivityIntent`s run in the app, which hands these to
/// `RestTimerController`; `applyWithoutApp` is only the fallback for an extension that had to run one itself.
enum RestCommand: Equatable {
    case start(seconds: Int)
    case adjust(seconds: Int)
    case skip

    /// Updates the shared state and the notification without the app (no Live Activity, no haptic). The app adopts
    /// the result when it next comes to the foreground (`RestTimerController.syncFromStore`).
    func applyWithoutApp(now: Date = .now, overLabel: String) {
        var state = SharedRestState.load()
        switch self {
        case .start(let seconds):
            state.totalSeconds = max(5, seconds)
            state.endDate = now.addingTimeInterval(Double(state.totalSeconds))
        case .adjust(let delta):
            guard let end = state.endDate, end > now else { return }
            state.endDate = max(now.addingTimeInterval(1), end.addingTimeInterval(Double(delta)))
            state.totalSeconds = max(5, state.totalSeconds + delta)
        case .skip:
            state.endDate = nil
        }
        state.save()
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [SharedRestState.notificationID])
        center.removeDeliveredNotifications(withIdentifiers: [SharedRestState.notificationID])
        guard let end = state.endDate else { return }
        let content = UNMutableNotificationContent()
        content.title = overLabel
        content.body = state.notificationBody
        content.sound = UNNotificationSound(named: UNNotificationSoundName(SharedRestState.soundName))
        content.interruptionLevel = .timeSensitive
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, end.timeIntervalSince(now)), repeats: false)
        center.add(UNNotificationRequest(identifier: SharedRestState.notificationID, content: content, trigger: trigger))
    }
}

/// The three lengths the idle widget offers: 1:00, the default, 2:00 — or 1:00 · 1:30 · 2:00 when the default is one
/// of the ends. Mirrored by Android's `BreakPresets`.
enum BreakPresets {
    static func lengths(default seconds: Int) -> [Int] {
        let d = max(5, seconds)
        return d == 60 || d == 120 ? [60, 90, 120] : [60, d, 120].sorted()
    }
}
