import Foundation
import Observation
import SwiftData

/// Holds the id of the workout in progress so any tab can get back to it.
/// The workout itself lives in SwiftData (`Workout.endedAt == nil`).
@Observable
final class WorkoutSessionController {
    var activeWorkoutID: PersistentIdentifier? {
        didSet { persist() }
    }
    var showsActiveWorkout = false

    init() {
        if let data = UserDefaults.standard.data(forKey: "nt.activeWorkoutID"),
           let id = try? JSONDecoder().decode(PersistentIdentifier.self, from: data) {
            activeWorkoutID = id
        }
    }

    func activeWorkout(in context: ModelContext) -> Workout? {
        if let id = activeWorkoutID, let w = context.model(for: id) as? Workout, w.isActive { return w }
        // Fallback: any unfinished workout (e.g. after reinstall of defaults).
        var descriptor = FetchDescriptor<Workout>(predicate: #Predicate { $0.endedAt == nil }, sortBy: [SortDescriptor(\.startedAt, order: .reverse)])
        descriptor.fetchLimit = 1
        let w = try? context.fetch(descriptor).first
        if let w { activeWorkoutID = w.persistentModelID }
        return w
    }

    func begin(_ workout: Workout) {
        activeWorkoutID = workout.persistentModelID
        showsActiveWorkout = true
    }

    func end() {
        activeWorkoutID = nil
        showsActiveWorkout = false
    }

    private func persist() {
        if let id = activeWorkoutID, let data = try? JSONEncoder().encode(id) {
            UserDefaults.standard.set(data, forKey: "nt.activeWorkoutID")
        } else {
            UserDefaults.standard.removeObject(forKey: "nt.activeWorkoutID")
        }
    }
}
