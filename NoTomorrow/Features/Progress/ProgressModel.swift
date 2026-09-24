import Foundation
import SwiftData
import Observation

// MARK: - Value types

/// Best e1RM of one workout for one exercise.
struct E1RMPoint: Identifiable, Hashable {
    let workoutID: PersistentIdentifier
    let date: Date
    let e1RM: Double
    let isPR: Bool
    var id: PersistentIdentifier { workoutID }
}

/// One completed working set, copied out of SwiftData when the summary is built. A summary outlives the rows it was
/// built from (a set deleted in the active table or the editor), so it never reads a `SetEntry` again.
struct LiftSet: Hashable {
    let weightKg: Double
    let reps: Int
    let completedAt: Date?
    /// When it was done, for display: `completedAt`, else its workout's start.
    let date: Date

    init(weightKg: Double, reps: Int, completedAt: Date?, date: Date) {
        self.weightKg = weightKg
        self.reps = reps
        self.completedAt = completedAt
        self.date = date
    }

    init(_ set: SetEntry) {
        self.init(weightKg: set.weightKg, reps: set.reps, completedAt: set.completedAt,
                  date: set.completedAt ?? set.workoutExercise?.workout?.startedAt ?? .now)
    }
}

/// One exercise the user has actually trained.
struct LiftSummary: Identifiable {
    let exercise: Exercise
    let name: String
    /// Name of the most recent workout that contained the exercise ("Push A").
    let context: String?
    let lastPR: Date?
    let lastSession: Date
    /// Oldest first.
    let history: [E1RMPoint]
    /// Sets that count: completed, working, reps > 0. Value copies, never live models.
    let sets: [LiftSet]

    var id: PersistentIdentifier { exercise.persistentModelID }
    var current: Double { history.map(\.e1RM).max() ?? 0 }
    var prInLast30Days: Bool {
        guard let lastPR else { return false }
        return ProgressPhrase.daysSince(lastPR) < 30
    }

    /// Points inside the range; falls back to everything when the range holds fewer than two.
    func points(in range: ProgressRange) -> [E1RMPoint] {
        guard let start = range.start else { return history }
        let inRange = history.filter { $0.date >= start }
        return inRange.count >= 2 ? inRange : history.suffix(max(2, inRange.count))
    }

    /// Best e1RM now minus the best known at the start of the range.
    func delta(in range: ProgressRange) -> Double {
        guard let start = range.start else {
            return current - (history.first?.e1RM ?? current)
        }
        let before = history.filter { $0.date < start }.map(\.e1RM).max()
        if let before { return current - before }
        guard let first = history.first(where: { $0.date >= start }) else { return 0 }
        return current - first.e1RM
    }

    func sessions(in range: ProgressRange) -> Int {
        guard let start = range.start else { return history.count }
        return history.filter { $0.date >= start }.count
    }

    var thisWeekVolume: Double {
        let weekStart = Calendar.current.startOfISOWeek(for: .now)
        return sets
            .filter { ($0.completedAt ?? .distantPast) >= weekStart }
            .reduce(0) { $0 + $1.weightKg * Double($1.reps) }
    }

    var heaviest: LiftSet? {
        sets.max { lhs, rhs in
            if lhs.weightKg != rhs.weightKg { return lhs.weightKg < rhs.weightKg }
            return lhs.reps < rhs.reps
        }
    }

    var mostReps: LiftSet? {
        sets.max { lhs, rhs in
            if lhs.reps != rhs.reps { return lhs.reps < rhs.reps }
            return lhs.weightKg < rhs.weightKg
        }
    }
}

struct WeekVolume: Identifiable {
    let weekStart: Date
    let volumeKg: Double
    let isCurrent: Bool
    var id: Date { weekStart }
}

struct BodyStats {
    var entries: [BodyWeightEntry] = []          // oldest first
    var latest: BodyWeightEntry? { entries.last }
    /// Change vs the reading closest to (and not after) 28 days ago.
    var delta4w: Double?
    var loggedLast28: Int = 0
    /// 7-day trailing moving average, aligned with `entries`.
    var smoothed: [Double] = []

    var isEmpty: Bool { entries.isEmpty }
}

/// Completed sets per muscle this ISO week, for the "Muscles this week" card. Muscle names are free-exercise-db's,
/// the same ones `muscle_model.json` draws ("chest", "middle back").
struct MuscleWeek: Equatable {
    /// Muscle → sets; only muscles with at least one. A set counts once for each primary muscle of its exercise.
    var setsByMuscle: [String: Int] = [:]
    /// Completed sets this week, each counted once.
    var totalSets = 0

    /// The muscles the "not trained yet" line checks, in the order it lists them.
    static let keyMuscles = ["chest", "shoulders", "lats", "quadriceps", "hamstrings"]

    /// The five most-trained muscles, most sets first; ties by name so the order is stable.
    var top: [(muscle: String, sets: Int)] {
        setsByMuscle
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .prefix(5)
            .map { (muscle: $0.key, sets: $0.value) }
    }

    /// Key muscles still at zero sets; empty until something was logged this week.
    var notTrainedYet: [String] {
        guard totalSets > 0 else { return [] }
        return Self.keyMuscles.filter { (setsByMuscle[$0] ?? 0) == 0 }
    }
}

// MARK: - Model

/// Everything the Progress tab shows, derived from SwiftData in one pass. Call `reload` on appear and after edits.
@Observable
final class ProgressModel {
    private(set) var lifts: [LiftSummary] = []
    private(set) var weekly: [WeekVolume] = []
    private(set) var body = BodyStats()
    private(set) var muscles = MuscleWeek()
    private(set) var unit: WeightUnit = .kg
    private(set) var hasCompletedSets = false

    var lastPRDate: Date? { lifts.compactMap(\.lastPR).max() }

    var thisWeekVolume: Double { weekly.last?.volumeKg ?? 0 }
    var lastWeekVolume: Double { weekly.dropLast().last?.volumeKg ?? 0 }
    var weekOverWeek: Double? {
        guard lastWeekVolume > 0 else { return nil }
        return (thisWeekVolume - lastWeekVolume) / lastWeekVolume
    }

    func lift(for exercise: Exercise) -> LiftSummary? {
        lifts.first { $0.exercise.persistentModelID == exercise.persistentModelID }
    }

    func reload(_ context: ModelContext) {
        unit = (try? context.fetch(FetchDescriptor<UserProfile>()))?.first?.units ?? .kg
        let sets = (try? context.fetch(FetchDescriptor<SetEntry>())) ?? []
        let workouts = (try? context.fetch(FetchDescriptor<Workout>())) ?? []
        let weights = (try? context.fetch(FetchDescriptor<BodyWeightEntry>(sortBy: [SortDescriptor(\.day)]))) ?? []
        lifts = Self.buildLifts(from: sets)
        hasCompletedSets = !lifts.isEmpty
        weekly = Self.buildWeekly(from: workouts)
        muscles = Self.buildMuscleWeek(from: workouts, since: Calendar.current.startOfISOWeek(for: .now))
        body = Self.buildBody(from: weights)
    }

    // MARK: Lifts

    private static func buildLifts(from sets: [SetEntry]) -> [LiftSummary] {
        var byExercise: [PersistentIdentifier: (exercise: Exercise, sets: [SetEntry])] = [:]
        for set in sets where set.isCompleted && set.kind != .warmup && set.reps > 0 && set.weightKg > 0 {
            guard let exercise = set.workoutExercise?.exercise else { continue }
            byExercise[exercise.persistentModelID, default: (exercise, [])].sets.append(set)
        }

        let summaries: [LiftSummary] = byExercise.values.compactMap { entry in
            var best: [PersistentIdentifier: (date: Date, e1RM: Double, isPR: Bool)] = [:]
            var latestWorkout: (date: Date, name: String)?
            for set in entry.sets {
                guard let workout = set.workoutExercise?.workout else { continue }
                let id = workout.persistentModelID
                let e1RM = set.estimatedOneRepMax
                var current = best[id] ?? (workout.startedAt, 0, false)
                if e1RM > current.e1RM { current.e1RM = e1RM }
                if set.isPR { current.isPR = true }
                best[id] = current
                if latestWorkout == nil || workout.startedAt > latestWorkout!.date {
                    latestWorkout = (workout.startedAt, workout.name)
                }
            }
            let history = best.map { E1RMPoint(workoutID: $0.key, date: $0.value.date, e1RM: $0.value.e1RM, isPR: $0.value.isPR) }
                .sorted { $0.date < $1.date }
            guard let last = history.last else { return nil }
            let lastPR = entry.sets.filter(\.isPR).compactMap(\.completedAt).max()
            return LiftSummary(
                exercise: entry.exercise,
                name: entry.exercise.localizedName,
                context: latestWorkout?.name,
                lastPR: lastPR,
                lastSession: last.date,
                history: history,
                sets: entry.sets.map(LiftSet.init)
            )
        }

        return summaries.sorted { lhs, rhs in
            switch (lhs.lastPR, rhs.lastPR) {
            case let (l?, r?) where l != r: return l > r
            case (.some, .none): return true
            case (.none, .some): return false
            default: return lhs.lastSession > rhs.lastSession
            }
        }
    }

    // MARK: Weekly volume

    private static func buildWeekly(from workouts: [Workout]) -> [WeekVolume] {
        let cal = Calendar.current
        let thisWeek = cal.startOfISOWeek(for: .now)
        var totals: [Date: Double] = [:]
        for workout in workouts where workout.endedAt != nil {
            let week = cal.startOfISOWeek(for: workout.startedAt)
            totals[week, default: 0] += workout.totalVolumeKg
        }
        return (0..<8).reversed().compactMap { offset in
            guard let week = cal.date(byAdding: .weekOfYear, value: -offset, to: thisWeek) else { return nil }
            return WeekVolume(weekStart: week, volumeKg: totals[week] ?? 0, isCurrent: offset == 0)
        }
    }

    // MARK: Muscles this week

    /// Completed sets of the finished workouts started since `weekStart`, per primary muscle of each set's exercise.
    /// Every set counts towards `totalSets` once, warm-ups included, as `Workout.completedSetCount` does.
    static func buildMuscleWeek(from workouts: [Workout], since weekStart: Date) -> MuscleWeek {
        var week = MuscleWeek()
        for workout in workouts where workout.endedAt != nil && workout.startedAt >= weekStart {
            for entry in workout.exercises {
                let done = entry.sets.filter(\.isCompleted).count
                guard done > 0 else { continue }
                week.totalSets += done
                for muscle in Set(entry.exercise?.primaryMuscles ?? []) {
                    week.setsByMuscle[muscle, default: 0] += done
                }
            }
        }
        return week
    }

    // MARK: Body weight

    private static func buildBody(from entries: [BodyWeightEntry]) -> BodyStats {
        var stats = BodyStats()
        stats.entries = entries
        guard let latest = entries.last else { return stats }
        let cal = Calendar.current
        let today = cal.startOfDay(for: .now)
        let fourWeeksAgo = cal.date(byAdding: .day, value: -28, to: today) ?? today
        stats.loggedLast28 = entries.filter { $0.day > fourWeeksAgo }.count
        let baseline = entries.last { $0.day <= fourWeeksAgo } ?? entries.first
        if let baseline, baseline.day != latest.day {
            stats.delta4w = latest.kg - baseline.kg
        }
        stats.smoothed = entries.indices.map { i in
            let day = entries[i].day
            let windowStart = cal.date(byAdding: .day, value: -6, to: day) ?? day
            let window = entries[0...i].filter { $0.day >= windowStart }
            return window.reduce(0) { $0 + $1.kg } / Double(window.count)
        }
        return stats
    }
}
