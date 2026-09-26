import Foundation

/// Percentages of a one-rep max (the lift page's "Percentages" and the 1RM calculator): for 100, 95 … 50 % of the
/// e1RM, the weight rounded to what plates make (2.5 kg / 5 lb, `WarmupPlan.increment`) and about how many reps it
/// allows. Everything is Epley, the same model as `SetEntry.estimatedOneRepMax` and the rep-max table (`RepMax`).
/// Weights are in the user's unit, so the rounding lands on real plates.
enum OneRepMax {
    static let percentages = [100, 95, 90, 85, 80, 75, 70, 65, 60, 50]

    struct Row: Equatable {
        var percent: Int
        /// `percent` of the e1RM, rounded to the nearest `step`.
        var weight: Double
        /// The most reps `RepMax.estimate` predicts at this percentage (1 at 100 %).
        var reps: Int
    }

    /// Epley, as `SetEntry.estimatedOneRepMax`: the weight itself for a single, 0 for no lift.
    static func estimate(weight: Double, reps: Int) -> Double {
        guard weight > 0, reps > 0 else { return 0 }
        return reps == 1 ? weight : weight * (1 + Double(reps) / 30)
    }

    /// The largest n with `RepMax.estimate(e1RM, n) >= percent % of e1RM`: n ≤ 30 × (100 − p) / p, at least 1.
    /// Integer arithmetic, so 75 % gives exactly 10.
    static func reps(atPercent percent: Int) -> Int {
        guard percent > 0, percent < 100 else { return 1 }
        return max(1, 30 * (100 - percent) / percent)
    }

    /// Nearest multiple of `step` (halves round up).
    static func round(_ value: Double, step: Double) -> Double {
        guard step > 0 else { return value }
        return (value / step).rounded(.toNearestOrAwayFromZero) * step
    }

    /// The table for `e1RM` (in the user's unit); empty without one.
    static func rows(e1RM: Double, step: Double) -> [Row] {
        guard e1RM > 0 else { return [] }
        return percentages.map { percent in
            Row(percent: percent, weight: round(e1RM * Double(percent) / 100, step: step),
                reps: reps(atPercent: percent))
        }
    }
}
