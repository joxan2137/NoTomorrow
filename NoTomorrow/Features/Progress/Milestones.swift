import Foundation

/// Lifetime milestones (Progress > Lifts "Milestones" card, `MilestonesView`, the finish screen's "New milestone"
/// line), derived from the finished workouts alone: nothing is stored. Each milestone is a tier of one track —
/// workouts completed, total volume, the longest run of ISO weeks with a workout, and three lifts against body
/// weight — and is either achieved by the workout that crossed it or shows how far along it is.
enum Milestones {

    enum Kind: String, CaseIterable, Hashable {
        case workouts, volume, weekStreak, bench, squat, deadlift

        /// The three lifts measured against body weight.
        static let lifts: [Kind] = [.bench, .squat, .deadlift]
        var isLift: Bool { Self.lifts.contains(self) }
    }

    // MARK: Tiers

    static let workoutTiers: [Int] = [1, 10, 25, 50, 100, 250, 500]
    /// 1 t, 10 t, 50 t, 100 t, 500 t, 1000 t — in kilograms.
    static let volumeTiersKg: [Double] = [1_000, 10_000, 50_000, 100_000, 500_000, 1_000_000]
    static let weekStreakTiers: [Int] = [4, 12, 26, 52]
    /// Body-weight multiple each lift is measured against.
    static func multiplier(_ kind: Kind) -> Double? {
        switch kind {
        case .bench: return 1
        case .squat: return 1.5
        case .deadlift: return 2
        default: return nil
        }
    }

    /// The catalog exercises (free-exercise-db ids) that count as each lift: the barbell competition movements.
    static let liftExerciseIDs: [Kind: Set<String>] = [
        .bench: ["Barbell_Bench_Press_-_Medium_Grip", "Bench_Press_-_Powerlifting"],
        .squat: ["Barbell_Squat", "Barbell_Full_Squat"],
        .deadlift: ["Barbell_Deadlift"],
    ]

    static func lift(forExerciseID id: String) -> Kind? {
        Kind.lifts.first { liftExerciseIDs[$0]?.contains(id) == true }
    }

    // MARK: Input / output

    /// One finished workout with at least one completed set.
    struct Session: Equatable {
        var workoutID: UUID
        var startedAt: Date
        /// Working-set volume (warm-ups left out, as `Workout.totalVolumeKg`).
        var volumeKg: Double
        /// Heaviest completed working set of each lift in the workout.
        var heaviestKg: [Kind: Double] = [:]
    }

    struct Achievement: Equatable {
        var workoutID: UUID
        var date: Date
    }

    struct Milestone: Identifiable, Equatable {
        let kind: Kind
        /// Workouts, kilograms, weeks — for a lift, the kilograms to lift (`multiplier` × body weight).
        let target: Double
        /// Where the track stands now, in `target`'s unit (a lift: its heaviest set).
        let current: Double
        let achieved: Achievement?

        var id: String { "\(kind.rawValue)-\(target)" }
        var isAchieved: Bool { achieved != nil }
        /// 0…1 toward `target`; 1 once achieved.
        var progress: Double {
            if isAchieved { return 1 }
            guard target > 0 else { return 0 }
            return min(1, max(0, current / target))
        }
        var multiplier: Double? { Milestones.multiplier(kind) }
    }

    // MARK: Evaluation

    /// Every tier of every track, in track order then tier order. The lifts are left out without a body weight.
    static func evaluate(sessions: [Session], bodyWeightKg: Double?, calendar: Calendar = .current) -> [Milestone] {
        let ordered = sessions.sorted { lhs, rhs in
            if lhs.startedAt != rhs.startedAt { return lhs.startedAt < rhs.startedAt }
            return lhs.workoutID.uuidString < rhs.workoutID.uuidString
        }
        var result: [Milestone] = []
        result += countTrack(.workouts, tiers: workoutTiers.map(Double.init), ordered: ordered) { index, _ in
            Double(index + 1)
        }
        var runningVolume = 0.0
        let volumes = ordered.map { session -> Double in
            runningVolume += session.volumeKg
            return runningVolume
        }
        result += countTrack(.volume, tiers: volumeTiersKg, ordered: ordered) { index, _ in volumes[index] }
        let streaks = longestStreaks(ordered.map(\.startedAt), calendar: calendar)
        result += countTrack(.weekStreak, tiers: weekStreakTiers.map(Double.init), ordered: ordered) { index, _ in
            Double(streaks[index])
        }
        if let bodyWeightKg, bodyWeightKg > 0 {
            for kind in Kind.lifts {
                guard let factor = Self.multiplier(kind) else { continue }
                let target = factor * bodyWeightKg
                var best = 0.0
                var achieved: Achievement?
                for session in ordered {
                    let heaviest = session.heaviestKg[kind] ?? 0
                    best = max(best, heaviest)
                    if achieved == nil, heaviest >= target {
                        achieved = Achievement(workoutID: session.workoutID, date: session.startedAt)
                    }
                }
                result.append(Milestone(kind: kind, target: target, current: best, achieved: achieved))
            }
        }
        return result
    }

    /// A monotonic track: `value(index, session)` is where the track stands after `ordered[index]`; each tier is
    /// achieved by the first session whose value reaches it.
    private static func countTrack(_ kind: Kind, tiers: [Double], ordered: [Session],
                                   value: (Int, Session) -> Double) -> [Milestone] {
        var current = 0.0
        var achieved: [Double: Achievement] = [:]
        for (index, session) in ordered.enumerated() {
            current = max(current, value(index, session))
            for tier in tiers where achieved[tier] == nil && current >= tier {
                achieved[tier] = Achievement(workoutID: session.workoutID, date: session.startedAt)
            }
        }
        return tiers.map { Milestone(kind: kind, target: $0, current: current, achieved: achieved[$0]) }
    }

    /// For each date (oldest first), the longest run so far of consecutive ISO weeks with a workout — the
    /// training calendar's `weekStreak` rule, counted over the whole history instead of back from today.
    static func longestStreaks(_ dates: [Date], calendar: Calendar = .current) -> [Int] {
        var result: [Int] = []
        var lastWeek: Date?
        var run = 0
        var longest = 0
        for date in dates {
            let week = calendar.startOfISOWeek(for: date)
            if let lastWeek, week == lastWeek {
                // Same week: the run stands.
            } else if let lastWeek, let next = calendar.date(byAdding: .day, value: 7, to: lastWeek),
                      calendar.startOfISOWeek(for: next) == week {
                run += 1
            } else {
                run = 1
            }
            lastWeek = week
            longest = max(longest, run)
            result.append(longest)
        }
        return result
    }

    // MARK: Reading the list

    /// The milestones `workoutID` crossed, in track order.
    static func crossed(by workoutID: UUID, in milestones: [Milestone]) -> [Milestone] {
        milestones.filter { $0.achieved?.workoutID == workoutID }
    }

    /// The most recently achieved milestone (a later tier wins a tie: same workout, bigger number).
    static func latest(_ milestones: [Milestone]) -> Milestone? {
        var best: Milestone?
        for milestone in milestones {
            guard let achieved = milestone.achieved else { continue }
            if let current = best?.achieved, current.date > achieved.date { continue }
            best = milestone
        }
        return best
    }

    /// The next tier of each track still to reach, closest to done first (ties keep track order).
    static func next(_ milestones: [Milestone]) -> [Milestone] {
        var seen = Set<Kind>()
        let upcoming = milestones.filter { milestone in
            guard !milestone.isAchieved, !seen.contains(milestone.kind) else { return false }
            seen.insert(milestone.kind)
            return true
        }
        return upcoming.enumerated()
            .sorted { lhs, rhs in
                if lhs.element.progress != rhs.element.progress { return lhs.element.progress > rhs.element.progress }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }

    /// Kilograms shown in `unit`: the pound equivalent for lb users, as `Fmt.volume` / `Fmt.weight` convert.
    static func displayAmount(_ kg: Double, unit: WeightUnit) -> Double {
        unit == .kg ? kg : kg * Fmt.lbPerKg
    }
}
