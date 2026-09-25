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
    /// The user's unit for the rest card (weights above are kg).
    var unit: WeightUnit = .kg
    /// Seconds to hold, for a timed exercise.
    var seconds: Int = 0
    var bestSeconds: Int?
    var tracking: ExerciseTracking = .weightReps

    var setLabel: String { String(localized: "timer.setOf \(setIndex) \(setCount)") }
}

/// Derived state for one workout in progress: which exercise is open, ghost values from the last time
/// each exercise was done, the suggested weight, PR hints, and the set/exercise mutations behind the table.
/// Owned by `WorkoutSessionController` (not the view), so it survives collapsing the workout into the mini bar.
@Observable
@MainActor
final class ActiveWorkoutModel {
    /// Weight and reps (or seconds held) of a set, copied out of SwiftData so caches never hold a model a history edit
    /// may delete.
    struct SetValue: Equatable {
        var weightKg: Double
        var reps: Int
        var seconds: Int = 0
    }

    /// The rows of the last session with an exercise, split the way the table numbers them: warm-ups apart,
    /// working sets (reps, or seconds for a timed exercise, > 0) counted 1, 2, 3… So "Previous" lines up with the
    /// prefill even when warm-ups were logged.
    struct PreviousRows: Equatable {
        var warmups: [SetValue]
        var working: [SetValue]
        /// The working sets that are not drop sets, in row order: what the suggested weight reads.
        var normal: [SetValue]

        init(warmups: [SetValue] = [], working: [SetValue] = [], normal: [SetValue] = []) {
            self.warmups = warmups
            self.working = working
            self.normal = normal
        }

        /// From that session's completed sets, in row order.
        init(completed sets: [SetEntry]) {
            warmups = sets.filter { $0.kind == .warmup }.map(SetValue.init)
            working = sets.filter { $0.kind != .warmup && $0.amount > 0 }.map(SetValue.init)
            normal = sets.filter { $0.kind != .warmup && $0.kind != .drop && $0.amount > 0 }.map(SetValue.init)
        }

        /// The row at the same position: the k-th warm-up for a warm-up, the k-th working set otherwise.
        func value(at slot: Slot) -> SetValue? {
            let rows = slot.isWarmup ? warmups : working
            return slot.index < rows.count ? rows[slot.index] : nil
        }
    }

    /// Where a set sits in its table: the k-th warm-up, or the k-th set that is not one (0-based).
    struct Slot: Equatable {
        var isWarmup: Bool
        var index: Int
    }

    /// Progressive overload for one exercise: the weight to try today and the session it builds on (weights in kg).
    struct Suggestion: Equatable {
        /// The previous session's top weight (every one of its sets was at it).
        var fromKg: Double
        var toKg: Double
        /// That session's reps, in row order ("8 · 8 · 8").
        var reps: [Int]
    }

    let workout: Workout
    private let context: ModelContext

    /// Exercise currently expanded (others collapse to a 60 pt row).
    var expandedExerciseID: PersistentIdentifier?
    /// Set whose "beats your best" hint is showing, with the best-before it beat.
    var hintSetID: PersistentIdentifier?
    var hintBest: SetValue?
    /// Cached "previous" rows per exercise (from the most recent other finished workout that did it).
    private var previousRows: [PersistentIdentifier: PreviousRows] = [:]
    private var previousLast: [PersistentIdentifier: SetValue?] = [:]
    /// Target reps per exercise from the routine this workout was started from (none for an ad-hoc workout).
    private var targetReps: [PersistentIdentifier: Int] = [:]
    /// The user's weight unit: cells, Previous, "Last:", the rest card and the summary show it (stored in kg).
    private(set) var unit: WeightUnit = .kg
    /// Filled when the rest timer starts, read by RestTimerView.
    var upNext: UpNextTarget?
    /// The "DONE." summary is on screen instead of the table (the workout is finished, `endedAt` stamped).
    var showsSummary = false

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

    /// The exercise the user is on, for the mini bar: the open one, else the first with work left, else the last.
    var currentExercise: WorkoutExercise? { Self.currentExercise(in: exercises, expandedID: expandedExerciseID) }

    static func currentExercise(in exercises: [WorkoutExercise], expandedID: PersistentIdentifier?) -> WorkoutExercise? {
        if let expandedID, let open = exercises.first(where: { $0.persistentModelID == expandedID }) { return open }
        return exercises.first { !$0.isDone } ?? exercises.last
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

    /// Position of `id` among its kind in `sets` (row order): warm-ups and the other sets are counted apart.
    static func slot(of id: PersistentIdentifier, in sets: [SetEntry]) -> Slot? {
        var warmups = 0, others = 0
        for s in sets {
            let isWarmup = s.kind == .warmup
            if s.persistentModelID == id { return Slot(isWarmup: isWarmup, index: isWarmup ? warmups : others) }
            if isWarmup { warmups += 1 } else { others += 1 }
        }
        return nil
    }

    /// First uncompleted set of an exercise — the row that gets the highlighted cells.
    func currentSetID(in exercise: WorkoutExercise) -> PersistentIdentifier? {
        exercise.sortedSets.first { !$0.isCompleted }?.persistentModelID
    }

    // MARK: Previous values

    /// Same-position set from the last session with this exercise (warm-ups against warm-ups, working sets by
    /// number), else, for a working set, the most recent completed set.
    func previous(for set: SetEntry, in exercise: WorkoutExercise) -> SetValue? {
        guard let ex = exercise.exercise,
              let slot = Self.slot(of: set.persistentModelID, in: exercise.sortedSets) else { return nil }
        if let aligned = previousRows[ex.persistentModelID]?.value(at: slot) { return aligned }
        return slot.isWarmup ? nil : lastSet(for: exercise)
    }

    /// Fills an open row's empty cells from Previous (the table shows what a tick will log): the weight, and the reps
    /// or, for a timed exercise, the seconds.
    func prefillFromPrevious(_ set: SetEntry, in exercise: WorkoutExercise) {
        guard !set.isCompleted, set.weightKg == 0 || set.amount == 0,
              let previous = previous(for: set, in: exercise) else { return }
        if set.weightKg == 0, previous.weightKg > 0 { set.weightKg = previous.weightKg }
        if set.tracking == .duration {
            if set.seconds == 0, previous.seconds > 0 { set.seconds = previous.seconds }
        } else if set.reps == 0, previous.reps > 0 {
            set.reps = previous.reps
        }
    }

    /// "Last: 80 kg × 8" source for the exercise header.
    func lastSet(for exercise: WorkoutExercise) -> SetValue? {
        guard let ex = exercise.exercise else { return nil }
        if let cached = previousLast[ex.persistentModelID] { return cached }
        let found = (RecordService.lastSet(for: ex, excluding: workout) ?? fetchedLastSet(for: ex)).map(SetValue.init)
        previousLast[ex.persistentModelID] = found
        return found
    }

    /// Re-reads Previous / Last, the routine's target reps and the unit. On init, on every expand (history may have
    /// been edited meanwhile) and after exercises are added.
    func reloadPrevious() {
        previousRows = [:]
        previousLast = [:]
        unit = (try? context.fetch(FetchDescriptor<UserProfile>()))?.first?.units ?? .kg
        targetReps = routineTargetReps()
        for we in exercises {
            guard let ex = we.exercise else { continue }
            let myID = workout.persistentModelID
            let earlier = ex.usages
                .filter { usage in
                    guard let other = usage.workout, other.persistentModelID != myID, other.endedAt != nil else { return false }
                    return usage.sets.contains { $0.isCompleted && $0.kind != .warmup && $0.amount > 0 }
                }
                .max { ($0.workout?.startedAt ?? .distantPast) < ($1.workout?.startedAt ?? .distantPast) }
            let rows = earlier?.sortedSets.filter(\.isCompleted) ?? fetchedRows(for: ex)
            previousRows[ex.persistentModelID] = PreviousRows(completed: rows)
        }
    }

    /// Fallback when the `usages` inverse is empty: walk finished workouts newest-first.
    private func fetchedRows(for exercise: Exercise) -> [SetEntry] {
        let myID = workout.persistentModelID
        var d = FetchDescriptor<Workout>(predicate: #Predicate { $0.endedAt != nil }, sortBy: [SortDescriptor(\.startedAt, order: .reverse)])
        d.fetchLimit = 50
        let workouts = (try? context.fetch(d)) ?? []
        for w in workouts where w.persistentModelID != myID {
            if let we = w.sortedExercises.first(where: { we in
                we.exercise?.id == exercise.id && we.sets.contains { $0.isCompleted && $0.kind != .warmup && $0.amount > 0 }
            }) {
                return we.sortedSets.filter(\.isCompleted)
            }
        }
        return []
    }

    private func fetchedLastSet(for exercise: Exercise) -> SetEntry? {
        fetchedRows(for: exercise).filter { $0.kind != .warmup && $0.amount > 0 }
            .max(by: RecordService.completedEarlier)
    }

    /// Target reps per exercise of the routine named like this workout (the name is the only link a workout keeps,
    /// as for the suggested routine); the first item wins when a routine lists an exercise twice.
    private func routineTargetReps() -> [PersistentIdentifier: Int] {
        let name = workout.name
        var d = FetchDescriptor<Routine>(predicate: #Predicate { $0.name == name }, sortBy: [SortDescriptor(\.order)])
        d.fetchLimit = 1
        guard let routine = (try? context.fetch(d))?.first else { return [:] }
        var targets: [PersistentIdentifier: Int] = [:]
        for item in routine.sortedItems {
            guard let ex = item.exercise, item.targetReps > 0, targets[ex.persistentModelID] == nil else { continue }
            targets[ex.persistentModelID] = item.targetReps
        }
        return targets
    }

    // MARK: Suggested weight

    /// Weights closer than this (1 g) are one weight: a number typed in lb comes back from kg with a rounding tail.
    private static let sameWeight = 0.001

    /// Progressive overload from the previous session's sets of an exercise (completed, not warm-ups, not drop sets):
    /// the top weight plus one step (2.5 kg, or 5 lb for lb users) when there are at least two such sets, all at one
    /// weight above zero, and every one reached `targetReps` (the routine's target, when there is one) or, without a
    /// target, none has fewer reps than the first. Otherwise nil.
    static func suggestion(previous: [SetValue], targetReps: Int?, unit: WeightUnit) -> Suggestion? {
        guard previous.count >= 2, let first = previous.first, first.weightKg > 0,
              previous.allSatisfy({ abs($0.weightKg - first.weightKg) < sameWeight }) else { return nil }
        let minReps = targetReps.flatMap { $0 > 0 ? $0 : nil } ?? first.reps
        guard previous.allSatisfy({ $0.reps >= minReps }) else { return nil }
        let step = unit == .kg ? 2.5 : SetInput.kg(fromDisplay: 5, unit: .lb)
        return Suggestion(fromKg: first.weightKg, toKg: first.weightKg + step, reps: previous.map(\.reps))
    }

    /// The suggestion stays up while an open set (not a warm-up, not a drop set) still has the previous top weight;
    /// "Use" moves them all off it, so the line goes. `openWeights` are those sets' weights in kg.
    static func showsSuggestion(_ suggestion: Suggestion, openWeights: [Double]) -> Bool {
        openWeights.contains { abs($0 - suggestion.fromKg) < sameWeight }
    }

    /// The suggestion for an expanded exercise, while it applies. None for a timed exercise (it reads reps).
    func suggestion(for exercise: WorkoutExercise) -> Suggestion? {
        guard let ex = exercise.exercise, ex.tracking != .duration,
              let suggestion = Self.suggestion(previous: previousRows[ex.persistentModelID]?.normal ?? [],
                                               targetReps: targetReps[ex.persistentModelID], unit: unit),
              Self.showsSuggestion(suggestion, openWeights: Self.openNormalSets(of: exercise).map(\.weightKg))
        else { return nil }
        return suggestion
    }

    /// "Use": every open set of the exercise (not a warm-up, not a drop set) takes the suggested weight. Nothing
    /// changes a weight without this tap.
    func useSuggestion(_ suggestion: Suggestion, in exercise: WorkoutExercise) {
        for set in Self.openNormalSets(of: exercise) { set.weightKg = suggestion.toKg }
        try? context.save()
    }

    private static func openNormalSets(of exercise: WorkoutExercise) -> [SetEntry] {
        exercise.sortedSets.filter { !$0.isCompleted && $0.kind != .warmup && $0.kind != .drop }
    }

    // MARK: Mutations

    /// Ticks a set: stamps `completedAt`, evaluates records and starts the rest (not before a drop set).
    /// An empty row takes Previous; a row that still has no reps (no seconds, for a timed exercise) is not logged
    /// (no "0 × 0" sets) and stays open, so the caller can send the user to that cell. Returns whether a rest started.
    @discardableResult
    func complete(_ set: SetEntry, in exercise: WorkoutExercise, restTimer: RestTimerController) -> Bool {
        if set.weightKg == 0 && set.amount == 0, let prev = previous(for: set, in: exercise) {
            set.weightKg = prev.weightKg
            if set.tracking == .duration { set.seconds = prev.seconds } else { set.reps = prev.reps }
        }
        guard set.amount > 0 else { return false }
        set.completedAt = .now
        let result = RecordService.mark(set: set, in: context)
        if result.isPR || result.isSetRecord, let best = result.bestBefore {
            hintSetID = set.persistentModelID
            hintBest = SetValue(best)
        } else if hintSetID == set.persistentModelID {
            hintSetID = nil
        }
        try? context.save()

        let target = nextTarget(after: set, in: exercise)
        let autoStart = UserDefaults.standard.object(forKey: "nt.rest.autoStart") as? Bool ?? true
        if let target, !target.isDrop, autoStart {
            upNext = target.upNext
            restTimer.start(seconds: exercise.restSeconds, exerciseName: target.upNext.exerciseName,
                            nextSetLabel: "\(target.upNext.setLabel) · \(target.upNext.setText)",
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

    /// "Delete set" from the kind menu: removes the row and renumbers the rest. Records are re-derived on Finish.
    /// Progress (open under the mini bar) reloads, so it drops a deleted completed set.
    func removeSet(_ set: SetEntry, in exercise: WorkoutExercise) {
        let id = set.persistentModelID
        if hintSetID == id { hintSetID = nil }
        exercise.sets.removeAll { $0.persistentModelID == id }
        context.delete(set)
        for (i, s) in exercise.sortedSets.enumerated() { s.order = i }
        try? context.save()
        NotificationCenter.default.post(name: .workoutHistoryDidChange, object: nil)
    }

    func addSet(to exercise: WorkoutExercise) {
        let sets = exercise.sortedSets
        let order = (sets.last?.order ?? -1) + 1
        let new: SetEntry
        if let last = sets.last {
            new = SetEntry(order: order, kind: last.kind == .warmup ? .normal : last.kind, weightKg: last.weightKg,
                           reps: last.reps, seconds: last.seconds)
        } else {
            new = SetEntry(order: order)
        }
        exercise.sets.append(new)
        try? context.save()
    }

    /// Removes an exercise and its sets. Progress reloads, as after `removeSet`.
    func remove(_ exercise: WorkoutExercise) {
        if expandedExerciseID == exercise.persistentModelID { expandedExerciseID = nil }
        let id = exercise.persistentModelID
        workout.exercises.removeAll { $0.persistentModelID == id }
        // Its sets go first (cascade is not reliable on iOS 17), as in `WorkoutEditor`.
        for set in Array(exercise.sets) { context.delete(set) }
        context.delete(exercise)
        for (i, we) in exercises.enumerated() { we.order = i }
        try? context.save()
        NotificationCenter.default.post(name: .workoutHistoryDidChange, object: nil)
    }

    func toggleExpanded(_ exercise: WorkoutExercise) {
        expandedExerciseID = expandedExerciseID == exercise.persistentModelID ? nil : exercise.persistentModelID
    }

    // MARK: Finish

    /// Finish: stamps the end now, so a kill on the summary can never stretch the workout, marks the day it started
    /// attended when at least one set was done, and shows the summary. The session keeps the workout (by id, not by
    /// `endedAt == nil`) until Done, so the summary stays up.
    /// Any day counts, scheduled or not (a make-up day, an extra session), and the day is the start's: a session
    /// from 22:30 to 00:20 is the evening it began. The status also goes to the backend (`AttendanceSync`), so the
    /// partner sees it and the server's 21:00 skip check stays quiet.
    /// Records are re-derived for this workout's exercises, so a kind changed, a set unticked or deleted after its
    /// tick leaves no stale PR on the summary or in history.
    /// With nothing done, a day an earlier Finish of this workout counted ("Edit sets", every set unticked) is
    /// reverted, locally and on the backend.
    func finish(now: Date = .now) {
        workout.endedAt = now
        try? context.save()
        RecordService.rebuild(exerciseIDs: Set(workout.exercises.compactMap { $0.exercise?.id }), in: context)
        if workout.completedSetCount > 0 {
            AttendanceService.markAttended(day: workout.startedAt, context: context)
            AttendanceSync.report(day: workout.startedAt, status: .attended)
        } else {
            WorkoutEditor.releaseAttendance(of: workout, in: context, today: now)
        }
        try? context.save()
        showsSummary = true
    }

    /// Done on the summary: release the session (which closes the full screen). The end is already stamped.
    func commitFinish(session: WorkoutSessionController) {
        try? context.save()
        session.end()
    }

    /// "Edit sets" from the summary: the workout is back in progress.
    func reopen() {
        workout.endedAt = nil
        try? context.save()
        showsSummary = false
    }

    /// Drops an empty workout (the session deletes the row once the full screen has animated out, and reverts a day
    /// an earlier Finish of it counted).
    @discardableResult
    func discard(session: WorkoutSessionController) -> Task<Void, Never> {
        session.discard(workout, context: context)
    }

    // MARK: Next target

    private struct NextTarget {
        var upNext: UpNextTarget
        var isDrop: Bool
    }

    private func nextTarget(after set: SetEntry, in exercise: WorkoutExercise) -> NextTarget? {
        let sets = exercise.sortedSets
        if let next = sets.first(where: { $0.order > set.order && !$0.isCompleted }) {
            return NextTarget(upNext: makeUpNext(next, in: exercise, fallback: SetValue(set)),
                              isDrop: next.kind == .drop)
        }
        // Exercise done: rest before the next exercise that still has work.
        let list = exercises
        guard let i = list.firstIndex(where: { $0.persistentModelID == exercise.persistentModelID }) else { return nil }
        for we in list[(i + 1)...] {
            if let next = we.sortedSets.first(where: { !$0.isCompleted }) {
                let fallback = we.sortedSets.last(where: \.isCompleted).map(SetValue.init)
                    ?? lastSet(for: we)
                return NextTarget(upNext: makeUpNext(next, in: we, fallback: fallback), isDrop: false)
            }
        }
        // Nothing left: still rest, aimed at this exercise's numbers.
        return NextTarget(upNext: makeUpNext(set, in: exercise, fallback: SetValue(set)),
                          isDrop: false)
    }

    private func makeUpNext(_ next: SetEntry, in exercise: WorkoutExercise, fallback: SetValue?) -> UpNextTarget {
        let sets = exercise.sortedSets
        let index = (sets.firstIndex { $0.persistentModelID == next.persistentModelID } ?? 0) + 1
        let weight = next.weightKg > 0 ? next.weightKg : (fallback?.weightKg ?? previous(for: next, in: exercise)?.weightKg ?? 0)
        let reps = next.reps > 0 ? next.reps : (fallback?.reps ?? previous(for: next, in: exercise)?.reps ?? 0)
        let seconds = next.seconds > 0 ? next.seconds : (fallback?.seconds ?? previous(for: next, in: exercise)?.seconds ?? 0)
        let best = exercise.exercise.flatMap { RecordService.bestSet(for: $0) }
        return UpNextTarget(exerciseName: exercise.exercise?.localizedName ?? "",
                            setIndex: index, setCount: sets.count,
                            weightKg: weight, reps: reps,
                            bestKg: best?.weightKg, bestReps: best?.reps, unit: unit,
                            seconds: seconds, bestSeconds: best?.seconds,
                            tracking: exercise.exercise?.tracking ?? .weightReps)
    }
}

extension ActiveWorkoutModel.SetValue {
    init(_ set: SetEntry) {
        self.init(weightKg: set.weightKg, reps: set.reps, seconds: set.seconds)
    }
}

extension UpNextTarget {
    /// "80 × 8", "BW × 12", "45 s": the set coming up, in its exercise's type.
    var setText: String { Fmt.set(weightKg, reps, seconds: seconds, tracking: tracking, unit: unit) }

    /// The best set before it, in the same form; nil before there is one.
    var bestText: String? {
        guard let bestKg, let bestReps else { return nil }
        return Fmt.set(bestKg, bestReps, seconds: bestSeconds ?? 0, tracking: tracking, unit: unit)
    }
}
