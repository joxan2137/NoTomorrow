import Foundation
import Observation
import SwiftData

/// What the rest timer is counting down towards — drives the pill label and the "Up next" card.
struct UpNextTarget: Equatable {
    var exerciseName: String
    var setIndex: Int
    var setCount: Int
    var weightKg: Double
    var reps: Int
    var bestKg: Double?
    var bestReps: Int?

    var setLabel: String { String(localized: "timer.setOf \(setIndex) \(setCount)") }
}

/// Derived state for one workout in progress: which exercise is open, ghost values from the last time
/// each exercise was done, PR hints, and the set/exercise mutations behind the table.
@Observable
final class ActiveWorkoutModel {
    let workout: Workout
    private let context: ModelContext

    /// Exercise currently expanded (others collapse to a 60 pt row).
    var expandedExerciseID: PersistentIdentifier?
    /// Set whose "beats your best" hint is showing, with the best-before it beat.
    var hintSetID: PersistentIdentifier?
    var hintBest: (weightKg: Double, reps: Int)?
    /// Cached "previous" sets per exercise (rows of the most recent earlier workout that had it).
    private var previousRows: [PersistentIdentifier: [SetEntry]] = [:]
    private var previousLast: [PersistentIdentifier: SetEntry?] = [:]
    /// Filled when the rest timer starts, read by RestTimerView.
    var upNext: UpNextTarget?

    init(workout: Workout, context: ModelContext) {
        self.workout = workout
        self.context = context
        expandedExerciseID = workout.sortedExercises.first { !$0.isDone }?.persistentModelID
            ?? workout.sortedExercises.first?.persistentModelID
        reloadPrevious()
    }

    // MARK: Derived

    var exercises: [WorkoutExercise] { workout.sortedExercises }

    /// 1-based position of the open exercise, for "Exercise i of n".
    var currentExerciseIndex: Int {
        let list = exercises
        if let id = expandedExerciseID, let i = list.firstIndex(where: { $0.persistentModelID == id }) { return i + 1 }
        return min(list.count, (list.firstIndex { !$0.isDone } ?? 0) + 1)
    }

    /// Row number shown in the Set column: warm-ups do not count.
    func setNumber(for set: SetEntry, in exercise: WorkoutExercise) -> Int {
        var n = 0
        for s in exercise.sortedSets {
            if s.kind != .warmup { n += 1 }
            if s.persistentModelID == set.persistentModelID { break }
        }
        return n
    }

    /// First uncompleted set of an exercise — the row that gets the highlighted cells.
    func currentSetID(in exercise: WorkoutExercise) -> PersistentIdentifier? {
        exercise.sortedSets.first { !$0.isCompleted }?.persistentModelID
    }

    // MARK: Previous values

    /// Same-row set from the last earlier workout that had this exercise, else the most recent completed set.
    func previous(for set: SetEntry, in exercise: WorkoutExercise) -> (weightKg: Double, reps: Int)? {
        guard let ex = exercise.exercise else { return nil }
        let rows = previousRows[ex.persistentModelID] ?? []
        if let i = exercise.sortedSets.firstIndex(where: { $0.persistentModelID == set.persistentModelID }), i < rows.count {
            return (rows[i].weightKg, rows[i].reps)
        }
        if let last = lastSet(for: exercise) { return (last.weightKg, last.reps) }
        return nil
    }

    /// "Last: 80 kg × 8" source for the exercise header.
    func lastSet(for exercise: WorkoutExercise) -> SetEntry? {
        guard let ex = exercise.exercise else { return nil }
        if let cached = previousLast[ex.persistentModelID] { return cached }
        let found = RecordService.lastSet(for: ex, excluding: workout) ?? fetchedLastSet(for: ex)
        previousLast[ex.persistentModelID] = found
        return found
    }

    func reloadPrevious() {
        previousRows = [:]
        previousLast = [:]
        for we in exercises {
            guard let ex = we.exercise else { continue }
            let myID = workout.persistentModelID
            let earlier = ex.usages
                .filter { $0.workout?.persistentModelID != myID && $0.workout?.endedAt != nil }
                .max { ($0.workout?.startedAt ?? .distantPast) < ($1.workout?.startedAt ?? .distantPast) }
            let rows = earlier?.sortedSets.filter(\.isCompleted) ?? fetchedRows(for: ex)
            previousRows[ex.persistentModelID] = rows
        }
    }

    /// Fallback when the `usages` inverse is empty: walk finished workouts newest-first.
    private func fetchedRows(for exercise: Exercise) -> [SetEntry] {
        let myID = workout.persistentModelID
        var d = FetchDescriptor<Workout>(predicate: #Predicate { $0.endedAt != nil }, sortBy: [SortDescriptor(\.startedAt, order: .reverse)])
        d.fetchLimit = 50
        let workouts = (try? context.fetch(d)) ?? []
        for w in workouts where w.persistentModelID != myID {
            if let we = w.sortedExercises.first(where: { $0.exercise?.id == exercise.id }) {
                return we.sortedSets.filter(\.isCompleted)
            }
        }
        return []
    }

    private func fetchedLastSet(for exercise: Exercise) -> SetEntry? {
        fetchedRows(for: exercise).filter { $0.kind != .warmup }.max { ($0.completedAt ?? .distantPast) < ($1.completedAt ?? .distantPast) }
    }

    // MARK: Mutations

    /// Ticks a set: stamps `completedAt`, evaluates records, returns the rest-timer target (nil when the next set is a drop set).
    @discardableResult
    func complete(_ set: SetEntry, in exercise: WorkoutExercise, restTimer: RestTimerController) -> Bool {
        if set.weightKg == 0 && set.reps == 0, let prev = previous(for: set, in: exercise) {
            set.weightKg = prev.weightKg
            set.reps = prev.reps
        }
        set.completedAt = .now
        let result = RecordService.mark(set: set, in: context)
        if result.isPR || result.isSetRecord, let best = result.bestBefore {
            hintSetID = set.persistentModelID
            hintBest = (best.weightKg, best.reps)
        } else if hintSetID == set.persistentModelID {
            hintSetID = nil
        }
        try? context.save()

        let target = nextTarget(after: set, in: exercise)
        let autoStart = UserDefaults.standard.object(forKey: "nt.rest.autoStart") as? Bool ?? true
        if let target, !target.isDrop, autoStart {
            upNext = target.upNext
            restTimer.start(seconds: exercise.restSeconds, exerciseName: target.upNext.exerciseName,
                            nextSetLabel: "\(target.upNext.setLabel) · \(Fmt.set(target.upNext.weightKg, target.upNext.reps))",
                            workoutName: workout.name)
            return true
        }
        return false
    }

    func uncomplete(_ set: SetEntry) {
        set.completedAt = nil
        set.isPR = false
        set.isSetRecord = false
        if hintSetID == set.persistentModelID { hintSetID = nil }
        try? context.save()
    }

    func setKind(_ kind: SetKind, for set: SetEntry) {
        set.kind = kind
        try? context.save()
    }

    func addSet(to exercise: WorkoutExercise) {
        let sets = exercise.sortedSets
        let order = (sets.last?.order ?? -1) + 1
        let new: SetEntry
        if let last = sets.last {
            new = SetEntry(order: order, kind: last.kind == .warmup ? .normal : last.kind, weightKg: last.weightKg, reps: last.reps)
        } else {
            new = SetEntry(order: order)
        }
        exercise.sets.append(new)
        try? context.save()
    }

    func remove(_ exercise: WorkoutExercise) {
        if expandedExerciseID == exercise.persistentModelID { expandedExerciseID = nil }
        let id = exercise.persistentModelID
        workout.exercises.removeAll { $0.persistentModelID == id }
        context.delete(exercise)
        for (i, we) in exercises.enumerated() { we.order = i }
        try? context.save()
    }

    func toggleExpanded(_ exercise: WorkoutExercise) {
        expandedExerciseID = expandedExerciseID == exercise.persistentModelID ? nil : exercise.persistentModelID
    }

    // MARK: Finish

    /// Moment Finish was tapped. `endedAt` is written only on Done, because the presenting cover closes
    /// as soon as the workout stops being active — the summary must render before that happens.
    private(set) var finishedAt: Date?

    /// Marks today attended when it is a gym day and remembers the finish time. Idempotent.
    func finish() {
        let now = Date.now
        finishedAt = now
        if let schedule = AttendanceService.schedule(in: context), schedule.isGymDay(Calendar.current.isoWeekday(for: now)) {
            AttendanceService.markAttended(day: now, context: context)
        }
    }

    /// Done on the summary: stamp the end, save, release the session (which closes the cover).
    func commitFinish(session: WorkoutSessionController) {
        workout.endedAt = finishedAt ?? .now
        try? context.save()
        session.end()
    }

    /// "Edit sets" from the summary: back to the table, nothing to revert.
    func reopen() {
        finishedAt = nil
    }

    /// Drops an empty workout. The row is deleted after the cover has animated out so nothing renders a deleted model.
    func discard(session: WorkoutSessionController) {
        let context = self.context
        let workout = self.workout
        session.end()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
            context.delete(workout)
            try? context.save()
        }
    }

    // MARK: Next target

    private struct NextTarget {
        var upNext: UpNextTarget
        var isDrop: Bool
    }

    private func nextTarget(after set: SetEntry, in exercise: WorkoutExercise) -> NextTarget? {
        let sets = exercise.sortedSets
        if let next = sets.first(where: { $0.order > set.order && !$0.isCompleted }) {
            return NextTarget(upNext: makeUpNext(next, in: exercise, fallback: set), isDrop: next.kind == .drop)
        }
        // Exercise done: rest before the next exercise that still has work.
        let list = exercises
        guard let i = list.firstIndex(where: { $0.persistentModelID == exercise.persistentModelID }) else { return nil }
        for we in list[(i + 1)...] {
            if let next = we.sortedSets.first(where: { !$0.isCompleted }) {
                let fallback = we.sortedSets.last(where: \.isCompleted) ?? lastSet(for: we)
                return NextTarget(upNext: makeUpNext(next, in: we, fallback: fallback), isDrop: false)
            }
        }
        // Nothing left: still rest, aimed at this exercise's numbers.
        return NextTarget(upNext: makeUpNext(set, in: exercise, fallback: set), isDrop: false)
    }

    private func makeUpNext(_ next: SetEntry, in exercise: WorkoutExercise, fallback: SetEntry?) -> UpNextTarget {
        let sets = exercise.sortedSets
        let index = (sets.firstIndex { $0.persistentModelID == next.persistentModelID } ?? 0) + 1
        let weight = next.weightKg > 0 ? next.weightKg : (fallback?.weightKg ?? previous(for: next, in: exercise)?.weightKg ?? 0)
        let reps = next.reps > 0 ? next.reps : (fallback?.reps ?? previous(for: next, in: exercise)?.reps ?? 0)
        let best = exercise.exercise.flatMap { RecordService.bestSet(for: $0) }
        return UpNextTarget(exerciseName: exercise.exercise?.localizedName ?? "",
                            setIndex: index, setCount: sets.count,
                            weightKg: weight, reps: reps,
                            bestKg: best?.weightKg, bestReps: best?.reps)
    }
}
