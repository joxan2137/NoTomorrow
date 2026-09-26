import Foundation
import SwiftData

/// A lift's all-time records, read off the same working sets `LiftSummary` holds (so the numbers match the lift's
/// progress page): the best Epley e1RM (`RecordService.bestSet`'s rule), the heaviest set (`LiftSummary.heaviest`)
/// and the set with the most weight × reps.
struct LiftRecords: Equatable {
    /// The set behind the best e1RM; its e1RM is `bestE1RMKg`, the lift's `current`.
    var bestE1RM: LiftSet
    var bestE1RMKg: Double
    var heaviest: LiftSet
    var bestVolume: LiftSet

    /// Nil without a set that counts (weight and reps above zero).
    init?(sets: [LiftSet]) {
        let counted = sets.filter { $0.weightKg > 0 && $0.reps > 0 }
        guard let e1RM = Self.bestE1RM(in: counted),
              let heaviest = Self.heaviest(in: counted),
              let volume = Self.bestVolume(in: counted) else { return nil }
        self.bestE1RM = e1RM
        self.bestE1RMKg = e1RM.estimatedOneRepMax
        self.heaviest = heaviest
        self.bestVolume = volume
    }

    /// Highest e1RM (ties → heavier).
    static func bestE1RM(in sets: [LiftSet]) -> LiftSet? {
        sets.max { lhs, rhs in
            if lhs.estimatedOneRepMax != rhs.estimatedOneRepMax { return lhs.estimatedOneRepMax < rhs.estimatedOneRepMax }
            return lhs.weightKg < rhs.weightKg
        }
    }

    /// Heaviest weight (ties → more reps).
    static func heaviest(in sets: [LiftSet]) -> LiftSet? {
        sets.max { lhs, rhs in
            if lhs.weightKg != rhs.weightKg { return lhs.weightKg < rhs.weightKg }
            return lhs.reps < rhs.reps
        }
    }

    /// Most weight × reps (ties → heavier).
    static func bestVolume(in sets: [LiftSet]) -> LiftSet? {
        sets.max { lhs, rhs in
            if lhs.volumeKg != rhs.volumeKg { return lhs.volumeKg < rhs.volumeKg }
            return lhs.weightKg < rhs.weightKg
        }
    }

    /// The lifts with a PR, in the order the Records screen lists them: most recent PR first, the order
    /// `ProgressModel` already sorts `lifts` in.
    static func withPRs(_ lifts: [LiftSummary]) -> [LiftRecordEntry] {
        lifts.compactMap { lift -> LiftRecordEntry? in
            guard lift.lastPR != nil, let records = LiftRecords(sets: lift.sets) else { return nil }
            return LiftRecordEntry(lift: lift, records: records)
        }
    }
}

/// One row of the Records screen.
struct LiftRecordEntry: Identifiable {
    let lift: LiftSummary
    let records: LiftRecords
    var id: PersistentIdentifier { lift.id }
}

extension LiftSet {
    /// Epley, as `SetEntry.estimatedOneRepMax`.
    var estimatedOneRepMax: Double {
        guard reps > 0, weightKg > 0 else { return 0 }
        if reps == 1 { return weightKg }
        return weightKg * (1 + Double(reps) / 30)
    }

    var volumeKg: Double { weightKg * Double(reps) }
}
