import Foundation

/// The rep-max table on a lift's progress page: for 1, 3, 5, 8, 10 and 12 reps, the heaviest weight actually lifted
/// for at least that many reps, and what the best e1RM (Epley) predicts for that many.
enum RepMax {
    static let repCounts = [1, 3, 5, 8, 10, 12]

    struct Row: Equatable {
        var reps: Int
        /// Heaviest set with at least `reps` reps, nil when none.
        var best: LiftSet?
        /// Epley inverted: e1RM / (1 + reps / 30); the e1RM itself for a single.
        var estimatedKg: Double
    }

    static func estimate(e1RM: Double, reps: Int) -> Double {
        guard e1RM > 0, reps > 0 else { return 0 }
        return reps == 1 ? e1RM : e1RM / (1 + Double(reps) / 30)
    }

    static func rows(sets: [LiftSet], e1RM: Double) -> [Row] {
        repCounts.map { reps in
            let best = sets.filter { $0.reps >= reps && $0.weightKg > 0 }
                .max { ($0.weightKg, $0.reps) < ($1.weightKg, $1.reps) }
            return Row(reps: reps, best: best, estimatedKg: estimate(e1RM: e1RM, reps: reps))
        }
    }
}
