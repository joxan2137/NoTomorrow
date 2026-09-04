import Foundation
import Observation
import ActivityKit
import UserNotifications
import AVFoundation
import UIKit

/// Rest timer that survives backgrounding and termination.
/// Truth is an absolute `endDate` persisted in UserDefaults; the UI derives the remaining time from it.
/// Runs a Live Activity (Lock Screen / Dynamic Island) and a local notification for the moment it ends.
@Observable
final class RestTimerController {
    private(set) var endDate: Date?
    private(set) var totalSeconds: Int = 90
    private(set) var exerciseName: String = ""
    private(set) var nextSetLabel: String = ""
    private(set) var workoutName: String = ""

    var isRunning: Bool { if let endDate { return endDate > .now } else { return false } }
    var remaining: TimeInterval { max(0, (endDate ?? .now).timeIntervalSinceNow) }
    var progress: Double { totalSeconds > 0 ? 1 - remaining / Double(totalSeconds) : 1 }

    private var activity: Activity<RestTimerAttributes>?
    private let notificationId = "nt.rest.end"

    init() {
        restore()
    }

    // MARK: Control

    func start(seconds: Int, exerciseName: String, nextSetLabel: String, workoutName: String) {
        totalSeconds = max(5, seconds)
        endDate = Date.now.addingTimeInterval(Double(totalSeconds))
        self.exerciseName = exerciseName
        self.nextSetLabel = nextSetLabel
        self.workoutName = workoutName
        persist()
        scheduleNotification()
        Task { await startOrUpdateActivity() }
        Haptics.tap()
    }

    func adjust(by delta: Int) {
        guard let end = endDate else { return }
        let newEnd = max(Date.now.addingTimeInterval(1), end.addingTimeInterval(Double(delta)))
        totalSeconds = max(5, totalSeconds + delta)
        endDate = newEnd
        persist()
        scheduleNotification()
        Task { await startOrUpdateActivity() }
        Haptics.tap()
    }

    func skip() {
        endDate = nil
        persist()
        cancelNotification()
        Task { await endActivity() }
    }

    /// Called by the UI when remaining hits zero.
    func finishIfElapsed() {
        guard let end = endDate, end <= .now else { return }
        endDate = nil
        persist()
        Task { await endActivity() }
        Haptics.success()
    }

    // MARK: Persistence

    private enum Keys {
        static let end = "nt.rest.endDate"
        static let total = "nt.rest.total"
        static let exercise = "nt.rest.exercise"
        static let next = "nt.rest.next"
        static let workout = "nt.rest.workout"
    }

    private func persist() {
        let d = UserDefaults.standard
        d.set(endDate?.timeIntervalSince1970, forKey: Keys.end)
        d.set(totalSeconds, forKey: Keys.total)
        d.set(exerciseName, forKey: Keys.exercise)
        d.set(nextSetLabel, forKey: Keys.next)
        d.set(workoutName, forKey: Keys.workout)
    }

    private func restore() {
        let d = UserDefaults.standard
        if let ts = d.object(forKey: Keys.end) as? Double {
            let end = Date(timeIntervalSince1970: ts)
            endDate = end > .now ? end : nil
        }
        totalSeconds = max(5, d.integer(forKey: Keys.total))
        exerciseName = d.string(forKey: Keys.exercise) ?? ""
        nextSetLabel = d.string(forKey: Keys.next) ?? ""
        workoutName = d.string(forKey: Keys.workout) ?? ""
        activity = Activity<RestTimerAttributes>.activities.first
        if endDate == nil { Task { await endActivity() } }
    }

    // MARK: Notifications

    private func scheduleNotification() {
        guard let endDate else { return }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [notificationId])
        let content = UNMutableNotificationContent()
        content.title = String(localized: "timer.notification.title")
        content.body = nextSetLabel.isEmpty ? exerciseName : "\(exerciseName) · \(nextSetLabel)"
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, endDate.timeIntervalSinceNow), repeats: false)
        center.add(UNNotificationRequest(identifier: notificationId, content: content, trigger: trigger))
    }

    private func cancelNotification() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [notificationId])
    }

    // MARK: Live Activity

    private func startOrUpdateActivity() async {
        guard let endDate, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let state = RestTimerAttributes.ContentState(endDate: endDate, totalSeconds: totalSeconds, isPaused: false)
        if let activity {
            await activity.update(ActivityContent(state: state, staleDate: endDate.addingTimeInterval(60)))
            return
        }
        let attributes = RestTimerAttributes(exerciseName: exerciseName, nextSetLabel: nextSetLabel, workoutName: workoutName)
        activity = try? Activity.request(attributes: attributes, content: ActivityContent(state: state, staleDate: endDate.addingTimeInterval(60)))
    }

    private func endActivity() async {
        for a in Activity<RestTimerAttributes>.activities {
            await a.end(nil, dismissalPolicy: .immediate)
        }
        activity = nil
    }
}

enum Haptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
}
