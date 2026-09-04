import Foundation
import SwiftData

/// Builds `Workout` graphs (exercises + set rows) from a routine or from nothing, and hands them to the session.
enum WorkoutStarter {

    /// Starts a workout from a routine: one `WorkoutExercise` per item, `targetSets` rows each,
    /// prefilled from the last completed sets of that exercise (empty rows when it was never done).
    @discardableResult
    static func start(routine: Routine, in context: ModelContext, session: WorkoutSessionController) -> Workout {
        let workout = Workout(name: routine.name)
        context.insert(workout)
        var order = 0
        for item in routine.sortedItems {
            guard let exercise = item.exercise else { continue }
            append(exercise, to: workout, order: order, setCount: max(1, item.targetSets),
                   restSeconds: item.restSeconds, in: context)
            order += 1
        }
        try? context.save()
        session.begin(workout)
        return workout
    }

    /// Starts an empty workout named "Workout".
    @discardableResult
    static func startEmpty(in context: ModelContext, session: WorkoutSessionController) -> Workout {
        let workout = Workout(name: String(localized: "workout.defaultName"))
        context.insert(workout)
        try? context.save()
        session.begin(workout)
        return workout
    }

    /// Appends one exercise (with prefilled rows) to an existing workout and marks it used.
    @discardableResult
    static func append(_ exercise: Exercise, to workout: Workout, order: Int, setCount: Int = 3,
                       restSeconds: Int? = nil, in context: ModelContext) -> WorkoutExercise {
        let rest = restSeconds ?? RoutineSeeder.restSeconds(for: exercise.id)
        let workoutExercise = WorkoutExercise(order: order, exercise: exercise, restSeconds: rest)
        context.insert(workoutExercise)
        workoutExercise.workout = workout

        let template = lastCompletedSets(for: exercise, excluding: workout)
        for index in 0..<setCount {
            let source = index < template.count ? template[index] : template.last
            let set = SetEntry(order: index, kind: .normal, weightKg: source?.weightKg ?? 0, reps: source?.reps ?? 0)
            context.insert(set)
            set.workoutExercise = workoutExercise
        }
        exercise.lastUsedAt = .now
        return workoutExercise
    }

    /// Completed working sets from the most recent earlier workout that contains `exercise`, in row order.
    static func lastCompletedSets(for exercise: Exercise, excluding current: Workout? = nil) -> [SetEntry] {
        let candidates = exercise.usages.filter { usage in
            guard let workout = usage.workout else { return false }
            if let current, workout.persistentModelID == current.persistentModelID { return false }
            return usage.sets.contains { $0.isCompleted && $0.kind != .warmup && $0.reps > 0 }
        }
        guard let latest = candidates.max(by: { ($0.workout?.startedAt ?? .distantPast) < ($1.workout?.startedAt ?? .distantPast) }) else {
            return []
        }
        return latest.sortedSets.filter { $0.isCompleted && $0.kind != .warmup && $0.reps > 0 }
    }
}
