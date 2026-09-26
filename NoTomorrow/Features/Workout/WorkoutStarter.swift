import Foundation
import SwiftData

/// Builds `Workout` graphs (exercises + set rows) from a routine or from nothing, and hands them to the session.
/// The one start path for Train and Today, so both build the same workout.
@MainActor
enum WorkoutStarter {

    /// What a Start tap asks for.
    enum Request {
        case routine(Routine)
        case empty
    }

    /// Whether a Start tap may go ahead. Only one workout runs at a time: while one is in progress the caller
    /// offers Resume, and "Discard it and start new" only when that workout has no completed sets.
    enum Gate {
        case clear
        case blocked(active: Workout, canDiscard: Bool)
    }

    /// A Start blocked by the workout in progress, while the user decides (`WorkoutStartConflictDialog`).
    struct Conflict: Identifiable {
        let id = UUID()
        let active: Workout
        /// Read when the Start was tapped, so the dialog never reads a row a discard is deleting.
        let activeName: String
        let canDiscard: Bool
        let request: Request

        init(active: Workout, canDiscard: Bool, request: Request) {
            self.active = active
            self.activeName = active.name
            self.canDiscard = canDiscard
            self.request = request
        }
    }

    /// Starts right away, or leaves the conflict on the session for the tab shell to ask about.
    static func requestStart(_ request: Request, in context: ModelContext, session: WorkoutSessionController) {
        switch gate(in: context, session: session) {
        case .clear:
            start(request, in: context, session: session)
        case .blocked(let active, let canDiscard):
            session.startConflict = Conflict(active: active, canDiscard: canDiscard, request: request)
        }
    }

    /// "Discard it and start new" in the conflict dialog. The dialog is part of the tab shell, not a presented alert,
    /// so this runs at the tap: the old workout and its mini bar go at once and the new workout's cover comes up
    /// straight away. With completed sets in the old workout it refuses and brings that one back instead.
    /// The caller skips the rest timer first.
    @discardableResult
    static func resolveByDiscarding(_ conflict: Conflict, in context: ModelContext,
                                    session: WorkoutSessionController) -> Workout {
        session.startConflict = nil
        guard discardForNewStart(conflict.active, in: context, session: session) else { return conflict.active }
        session.hideMiniBarWhileSwapping()
        return start(conflict.request, in: context, session: session)
    }

    static func gate(in context: ModelContext, session: WorkoutSessionController) -> Gate {
        guard let active = session.activeWorkout(in: context) else { return .clear }
        return .blocked(active: active, canDiscard: active.completedSetCount == 0)
    }

    @discardableResult
    static func start(_ request: Request, in context: ModelContext, session: WorkoutSessionController) -> Workout {
        switch request {
        case .routine(let routine): start(routine: routine, in: context, session: session)
        case .empty: startEmpty(in: context, session: session)
        }
    }

    /// "Discard it and start new": drops the workout in progress (only when nothing was completed in it) and starts
    /// `request`. With completed sets it refuses and brings the running workout back instead.
    /// The caller skips the rest timer first.
    @discardableResult
    static func discardAndStart(_ active: Workout, then request: Request,
                                in context: ModelContext, session: WorkoutSessionController) -> Workout {
        guard discardForNewStart(active, in: context, session: session) else { return active }
        return start(request, in: context, session: session)
    }

    /// The first half of "Discard it and start new": drops the workout in progress at once, so the mini bar goes
    /// with it, and returns true. With completed sets it refuses, brings the running workout back and returns false.
    static func discardForNewStart(_ active: Workout, in context: ModelContext, session: WorkoutSessionController) -> Bool {
        guard active.completedSetCount == 0 else {
            session.expand()
            return false
        }
        session.discard(active, context: context)
        return true
    }

    /// Starts a workout from a routine: one `WorkoutExercise` per item, `targetSets` rows each,
    /// prefilled from the last completed sets of that exercise (target reps when it was never done).
    @discardableResult
    static func start(routine: Routine, in context: ModelContext, session: WorkoutSessionController) -> Workout {
        let workout = Workout(name: routine.name)
        context.insert(workout)
        let defaultRest = defaultRestSeconds(in: context)
        var order = 0
        for item in routine.sortedItems {
            guard let exercise = item.exercise else { continue }
            let rest = item.restSeconds > 0
                ? item.restSeconds
                : RoutineSeeder.restSeconds(for: exercise.id, defaultRest: defaultRest)
            let added = append(exercise, to: workout, order: order, setCount: max(1, item.targetSets),
                               targetReps: item.targetReps, restSeconds: rest, in: context)
            added.supersetGroup = item.supersetGroup
            order += 1
        }
        // An item whose exercise was deleted may have split a superset.
        let started = workout.sortedExercises
        for (entry, group) in zip(started, Superset.normalized(started.map(\.supersetGroup))) { entry.supersetGroup = group }
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
    /// Rows copy the last session's sets row by row; an exercise never done gets `targetReps` and no weight.
    /// Rest defaults to the user's Rest length setting (heavy compounds a little longer).
    @discardableResult
    static func append(_ exercise: Exercise, to workout: Workout, order: Int, setCount: Int = 3, targetReps: Int = 0,
                       restSeconds: Int? = nil, in context: ModelContext) -> WorkoutExercise {
        let rest = restSeconds ?? RoutineSeeder.restSeconds(for: exercise.id, defaultRest: defaultRestSeconds(in: context))
        let workoutExercise = WorkoutExercise(order: order, exercise: exercise, restSeconds: rest)
        context.insert(workoutExercise)
        workoutExercise.workout = workout

        let template = lastCompletedSets(for: exercise, excluding: workout)
        for index in 0..<setCount {
            let source = index < template.count ? template[index] : template.last
            let set = SetEntry(order: index, kind: .normal, weightKg: source?.weightKg ?? 0, reps: source?.reps ?? targetReps)
            context.insert(set)
            set.workoutExercise = workoutExercise
        }
        exercise.lastUsedAt = .now
        return workoutExercise
    }

    /// The user's default rest (Settings > Rest timer > Rest length), 90 s before a profile exists.
    static func defaultRestSeconds(in context: ModelContext) -> Int {
        var descriptor = FetchDescriptor<UserProfile>()
        descriptor.fetchLimit = 1
        let value = (try? context.fetch(descriptor))?.first?.defaultRestSeconds ?? 0
        return value > 0 ? value : RoutineSeeder.defaultRestSeconds
    }

    /// Completed working sets from the most recent finished workout that did `exercise`, in row order
    /// (the same session the table's Previous column reads).
    static func lastCompletedSets(for exercise: Exercise, excluding current: Workout? = nil) -> [SetEntry] {
        let candidates = exercise.usages.filter { usage in
            guard let workout = usage.workout, workout.endedAt != nil else { return false }
            if let current, workout.persistentModelID == current.persistentModelID { return false }
            return usage.sets.contains { $0.isCompleted && $0.kind != .warmup && $0.reps > 0 }
        }
        guard let latest = candidates.max(by: { ($0.workout?.startedAt ?? .distantPast) < ($1.workout?.startedAt ?? .distantPast) }) else {
            return []
        }
        return latest.sortedSets.filter { $0.isCompleted && $0.kind != .warmup && $0.reps > 0 }
    }
}
