import Foundation
import SwiftData

/// Personal records. A PR is a new best Epley e1RM or a new heaviest weight for the exercise;
/// a set record is the most reps ever done at that exact weight. Warm-ups never count, in either direction.
/// For a timed exercise "reps" read as seconds held (the longest hold at a weight) and there is no e1RM.
enum RecordService {

    struct Evaluation {
        var isPR: Bool
        var isSetRecord: Bool
        var bestBefore: SetEntry?
    }

    /// Compares `set` against every completed working set of the same exercise done before it
    /// (earlier sets of the current workout included, later ones excluded). Does not mutate the set.
    static func evaluate(set: SetEntry, in context: ModelContext) -> (isPR: Bool, isSetRecord: Bool, bestBefore: SetEntry?) {
        guard let exercise = set.workoutExercise?.exercise, set.kind != .warmup, set.amount > 0 else {
            return (false, false, nil)
        }
        let previous = previousSets(for: exercise, before: set)
        let best = previous.max(by: betterSetLast)
        // The first logged working set of an exercise is its first record.
        guard !previous.isEmpty else { return (true, false, nil) }

        let maxE1RM = previous.map(\.estimatedOneRepMax).max() ?? 0
        let maxWeight = previous.map(\.weightKg).max() ?? 0
        let isPR = set.estimatedOneRepMax > maxE1RM || set.weightKg > maxWeight

        var isSetRecord = false
        if !isPR {
            let sameWeight = previous.filter { $0.weightKg == set.weightKg }
            if let maxAmount = sameWeight.map(\.amount).max() {
                isSetRecord = set.amount > maxAmount
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
            .filter { $0.isCompleted && $0.kind != .warmup && $0.amount > 0 }
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

    /// Highest-e1RM completed set ever (ties → heavier weight, then more reps or the longer hold).
    static func bestSet(for exercise: Exercise) -> SetEntry? {
        completedSets(for: exercise).max(by: betterSetLast)
    }

    /// "Best set" order: e1RM, then weight, then reps / seconds. The last tie-break is what ranks sets at body weight
    /// and timed sets, which have no e1RM.
    static func betterSetLast(_ lhs: SetEntry, _ rhs: SetEntry) -> Bool {
        if lhs.estimatedOneRepMax != rhs.estimatedOneRepMax { return lhs.estimatedOneRepMax < rhs.estimatedOneRepMax }
        if lhs.weightKg != rhs.weightKg { return lhs.weightKg < rhs.weightKg }
        return lhs.amount < rhs.amount
    }

    /// Heaviest completed set ever (ties → more reps).
    static func heaviestSet(for exercise: Exercise) -> SetEntry? {
        completedSets(for: exercise).max { lhs, rhs in
            if lhs.weightKg != rhs.weightKg { return lhs.weightKg < rhs.weightKg }
            return lhs.reps < rhs.reps
        }
    }

    /// Completed set with the most reps (the longest hold, for a timed exercise) ever (ties → heavier).
    static func mostRepsSet(for exercise: Exercise) -> SetEntry? {
        completedSets(for: exercise).max { lhs, rhs in
            if lhs.amount != rhs.amount { return lhs.amount < rhs.amount }
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
            .max(by: completedEarlier)
    }

    /// Completion order: by `completedAt`; sets done at the same instant (logged in the workout editor, which gives
    /// added rows their neighbour's time) by their exercise's position in the workout, then by row. So the last of
    /// them is the bottom row, the way the records rule orders ties.
    static func completedEarlier(_ lhs: SetEntry, _ rhs: SetEntry) -> Bool {
        let l = lhs.completedAt ?? .distantPast
        let r = rhs.completedAt ?? .distantPast
        if l != r { return l < r }
        let lEntry = lhs.workoutExercise?.order ?? 0
        let rEntry = rhs.workoutExercise?.order ?? 0
        if lEntry != rEntry { return lEntry < rEntry }
        return lhs.order < rhs.order
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

    // MARK: - Rebuild

    /// One set of one exercise, as the records rule sees it.
    struct RecordRow {
        var id: AnyHashable
        /// The `WorkoutExercise` it belongs to: sets ticked at the same instant are ordered by row only inside it.
        var group: AnyHashable
        var order: Int
        var completedAt: Date?
        var kind: SetKind
        var weightKg: Double
        var reps: Int
        var seconds: Int = 0
        var tracking: ExerciseTracking = .weightReps

        /// Reps, or seconds for a timed exercise, as `SetEntry.amount`.
        var amount: Int { ExerciseTracking.amount(reps: reps, seconds: seconds, tracking: tracking) }

        /// Epley, as `SetEntry.estimatedOneRepMax`.
        var estimatedOneRepMax: Double {
            guard reps > 0, weightKg > 0, tracking != .duration else { return 0 }
            if reps == 1 { return weightKg }
            return weightKg * (1 + Double(reps) / 30)
        }
    }

    struct RecordFlags: Equatable {
        var isPR: Bool
        var isSetRecord: Bool
    }

    /// Flags for every row of ONE exercise, exactly as `evaluate` would have set them had each completed working set
    /// been ticked in `completedAt` order (same tie rule: equal timestamps only see earlier rows of the same
    /// `WorkoutExercise`). Open sets, warm-ups and 0-rep (0-second) sets get no flag.
    static func rebuildFlags(_ rows: [RecordRow]) -> [AnyHashable: RecordFlags] {
        var result: [AnyHashable: RecordFlags] = [:]
        for row in rows { result[row.id] = RecordFlags(isPR: false, isSetRecord: false) }

        let working = rows.filter { $0.completedAt != nil && $0.kind != .warmup && $0.amount > 0 }
        let byInstant = Dictionary(grouping: working) { $0.completedAt ?? .distantPast }
        var seen = 0
        var maxE1RM = 0.0
        var maxWeight = 0.0
        var repsAtWeight: [Double: Int] = [:]

        for instant in byInstant.keys.sorted() {
            let tie = byInstant[instant] ?? []
            for row in tie {
                let local = tie.filter { $0.group == row.group && $0.order < row.order }
                // The first logged working set of an exercise is its first record.
                guard seen + local.count > 0 else {
                    result[row.id] = RecordFlags(isPR: true, isSetRecord: false)
                    continue
                }
                let priorE1RM = max(maxE1RM, local.map(\.estimatedOneRepMax).max() ?? 0)
                let priorWeight = max(maxWeight, local.map(\.weightKg).max() ?? 0)
                let isPR = row.estimatedOneRepMax > priorE1RM || row.weightKg > priorWeight
                var isSetRecord = false
                if !isPR {
                    let localReps = local.filter { $0.weightKg == row.weightKg }.map(\.amount).max()
                    if let best = [repsAtWeight[row.weightKg], localReps].compactMap({ $0 }).max() {
                        isSetRecord = row.amount > best
                    }
                }
                result[row.id] = RecordFlags(isPR: isPR, isSetRecord: isSetRecord)
            }
            for row in tie {
                seen += 1
                maxE1RM = max(maxE1RM, row.estimatedOneRepMax)
                maxWeight = max(maxWeight, row.weightKg)
                repsAtWeight[row.weightKg] = max(repsAtWeight[row.weightKg] ?? 0, row.amount)
            }
        }
        return result
    }

    /// Re-derives the flags of every set of these exercises (all workouts) and writes the ones that changed.
    /// Run after a finished workout is edited or deleted, and on Finish. The caller saves. Returns the rows changed.
    @discardableResult
    static func rebuild(exerciseIDs: Set<String>, in context: ModelContext) -> Int {
        guard !exerciseIDs.isEmpty else { return 0 }
        // Grouped from one fetch rather than `Exercise.usages`, whose inverse can come back empty on iOS 17.
        let all = (try? context.fetch(FetchDescriptor<SetEntry>())) ?? []
        var byExercise: [String: [SetEntry]] = [:]
        for set in all {
            guard let id = set.workoutExercise?.exercise?.id, exerciseIDs.contains(id) else { continue }
            byExercise[id, default: []].append(set)
        }
        var changed = 0
        for sets in byExercise.values {
            let rows = sets.map { set in
                RecordRow(id: set.persistentModelID,
                          group: set.workoutExercise.map { AnyHashable($0.persistentModelID) } ?? AnyHashable(0),
                          order: set.order, completedAt: set.completedAt, kind: set.kind,
                          weightKg: set.weightKg, reps: set.reps, seconds: set.seconds, tracking: set.tracking)
            }
            let flags = rebuildFlags(rows)
            for set in sets {
                guard let f = flags[set.persistentModelID],
                      set.isPR != f.isPR || set.isSetRecord != f.isSetRecord else { continue }
                set.isPR = f.isPR
                set.isSetRecord = f.isSetRecord
                changed += 1
            }
        }
        return changed
    }
}
