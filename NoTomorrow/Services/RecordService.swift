import Foundation
import SwiftData

/// Personal records. A PR is a new best Epley e1RM or a new heaviest weight for the exercise;
/// a set record is the most reps ever done at that exact weight. Warm-ups never count, in either direction.
enum RecordService {

    struct Evaluation {
        var isPR: Bool
        var isSetRecord: Bool
        var bestBefore: SetEntry?
    }

    /// Compares `set` against every completed working set of the same exercise done before it
    /// (earlier sets of the current workout included, later ones excluded). Does not mutate the set.
    static func evaluate(set: SetEntry, in context: ModelContext) -> (isPR: Bool, isSetRecord: Bool, bestBefore: SetEntry?) {
        guard let exercise = set.workoutExercise?.exercise, set.kind != .warmup, set.reps > 0 else {
            return (false, false, nil)
        }
        let previous = previousSets(for: exercise, before: set)
        let best = previous.max { lhs, rhs in
            if lhs.estimatedOneRepMax != rhs.estimatedOneRepMax { return lhs.estimatedOneRepMax < rhs.estimatedOneRepMax }
            return lhs.weightKg < rhs.weightKg
        }
        // The first logged working set of an exercise is its first record.
        guard !previous.isEmpty else { return (true, false, nil) }

        let maxE1RM = previous.map(\.estimatedOneRepMax).max() ?? 0
        let maxWeight = previous.map(\.weightKg).max() ?? 0
        let isPR = set.estimatedOneRepMax > maxE1RM || set.weightKg > maxWeight

        var isSetRecord = false
        if !isPR {
            let sameWeight = previous.filter { $0.weightKg == set.weightKg }
            if let maxReps = sameWeight.map(\.reps).max() {
                isSetRecord = set.reps > maxReps
            }
        }
        return (isPR, isSetRecord, best)
    }

    /// Evaluates and writes `isPR` / `isSetRecord` onto the set.
    @discardableResult
    static func mark(set: SetEntry, in context: ModelContext) -> Evaluation {
        let result = evaluate(set: set, in: context)
        set.isPR = result.isPR
        set.isSetRecord = result.isSetRecord
        return Evaluation(isPR: result.isPR, isSetRecord: result.isSetRecord, bestBefore: result.bestBefore)
    }

    // MARK: - Lookups

    /// Every completed working set of `exercise`, across all workouts.
    static func completedSets(for exercise: Exercise) -> [SetEntry] {
        exercise.usages
            .flatMap(\.sets)
            .filter { $0.isCompleted && $0.kind != .warmup && $0.reps > 0 }
    }

    /// Completed working sets of the same exercise finished before `set` (excluding `set` itself and anything later).
    static func previousSets(for exercise: Exercise, before set: SetEntry) -> [SetEntry] {
        let cutoff = set.completedAt ?? .now
        return completedSets(for: exercise).filter { other in
            guard other.persistentModelID != set.persistentModelID, let at = other.completedAt else { return false }
            if at != cutoff { return at < cutoff }
            // Same timestamp (same workout, batch-ticked): fall back to row order within the same exercise.
            if other.workoutExercise?.persistentModelID == set.workoutExercise?.persistentModelID {
                return other.order < set.order
            }
            return false
        }
    }

    /// Highest-e1RM completed set ever (ties → heavier weight).
    static func bestSet(for exercise: Exercise) -> SetEntry? {
        completedSets(for: exercise).max { lhs, rhs in
            if lhs.estimatedOneRepMax != rhs.estimatedOneRepMax { return lhs.estimatedOneRepMax < rhs.estimatedOneRepMax }
            return lhs.weightKg < rhs.weightKg
        }
    }

    /// Heaviest completed set ever (ties → more reps).
    static func heaviestSet(for exercise: Exercise) -> SetEntry? {
        completedSets(for: exercise).max { lhs, rhs in
            if lhs.weightKg != rhs.weightKg { return lhs.weightKg < rhs.weightKg }
            return lhs.reps < rhs.reps
        }
    }

    /// Completed set with the most reps ever (ties → heavier).
    static func mostRepsSet(for exercise: Exercise) -> SetEntry? {
        completedSets(for: exercise).max { lhs, rhs in
            if lhs.reps != rhs.reps { return lhs.reps < rhs.reps }
            return lhs.weightKg < rhs.weightKg
        }
    }

    /// Most recently completed set of `exercise` outside `workout` — the "Last: 80 × 8" line and the prefill.
    static func lastSet(for exercise: Exercise, excluding workout: Workout? = nil) -> SetEntry? {
        completedSets(for: exercise)
            .filter { set in
                guard let workout else { return true }
                return set.workoutExercise?.workout?.persistentModelID != workout.persistentModelID
            }
            .max { ($0.completedAt ?? .distantPast) < ($1.completedAt ?? .distantPast) }
    }

    /// Best e1RM per workout, oldest first. Date = workout start.
    static func e1RMHistory(for exercise: Exercise) -> [(date: Date, e1RM: Double)] {
        var best: [PersistentIdentifier: (date: Date, e1RM: Double)] = [:]
        for set in completedSets(for: exercise) {
            guard let workout = set.workoutExercise?.workout else { continue }
            let id = workout.persistentModelID
            let e1RM = set.estimatedOneRepMax
            if let current = best[id] {
                if e1RM > current.e1RM { best[id] = (workout.startedAt, e1RM) }
            } else {
                best[id] = (workout.startedAt, e1RM)
            }
        }
        return best.values.sorted { $0.date < $1.date }
    }

    /// Date of the most recent PR set, if any.
    static func lastPRDate(for exercise: Exercise) -> Date? {
        completedSets(for: exercise).filter(\.isPR).compactMap(\.completedAt).max()
    }

    /// Every PR / set-record set of a workout, PRs first, in row order.
    static func records(in workout: Workout) -> [SetEntry] {
        let all = workout.sortedExercises.flatMap(\.sortedSets).filter { $0.isCompleted && ($0.isPR || $0.isSetRecord) }
        return all.filter(\.isPR) + all.filter { !$0.isPR }
    }
}
