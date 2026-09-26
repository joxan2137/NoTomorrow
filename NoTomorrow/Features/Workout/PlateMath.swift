import Foundation

/// Which plates go on each side of the bar for a target weight. Everything is in the user's unit (kg or lb):
/// gyms stock plates in one or the other, so a 100 kg target is never loaded with pound plates.
enum PlateMath {

    /// Plates a gym usually has, heaviest first, per unit; the count on the rack is treated as unlimited.
    static func plates(for unit: WeightUnit) -> [Double] {
        unit == .kg ? [25, 20, 15, 10, 5, 2.5, 1.25] : [45, 35, 25, 10, 5, 2.5]
    }

    /// Bar choices, the standard men's bar first.
    static func bars(for unit: WeightUnit) -> [Double] {
        unit == .kg ? [20, 15, 10] : [45, 35, 15]
    }

    /// Heaviest target the calculator loads (above any real lift); a bigger number is a typo, not a bar to draw.
    static func maxTarget(for unit: WeightUnit) -> Double { unit == .kg ? 500 : 1100 }

    struct Load: Equatable {
        /// One side of the bar, heaviest first (the other side mirrors it).
        var perSide: [Double]
        /// Bar plus both sides: what the loaded bar actually weighs.
        var total: Double
        /// Target minus total: over 0 when the plates cannot make the target exactly.
        var shortBy: Double
        /// The target is lighter than the empty bar.
        var isBelowBar: Bool
        /// The target is over the calculator's limit: nothing is loaded.
        var isOverMax: Bool = false

        var isExact: Bool { !isBelowBar && !isOverMax && shortBy < 0.001 }

        /// "2 × 20" style groups for the per-side list, heaviest first.
        var groups: [(plate: Double, count: Int)] {
            var result: [(plate: Double, count: Int)] = []
            for plate in perSide {
                if let last = result.last, last.plate == plate {
                    result[result.count - 1].count += 1
                } else {
                    result.append((plate, 1))
                }
            }
            return result
        }
    }

    /// Greedy fill, heaviest plate first, never over the target (exact for standard plate sets).
    /// A target at or below the bar loads nothing, and so does one over `limit`. Works in hundredths so 1.25 steps
    /// add up exactly.
    static func load(target: Double, bar: Double, plates: [Double], limit: Double = .infinity) -> Load {
        if target > limit {
            return Load(perSide: [], total: bar, shortBy: 0, isBelowBar: false, isOverMax: true)
        }
        let scale = 100.0
        let barUnits = Int((bar * scale).rounded())
        let targetUnits = Int((max(0, target) * scale).rounded())
        var perSideUnits = max(0, targetUnits - barUnits) / 2
        var perSide: [Double] = []
        for plate in plates.sorted(by: >) {
            let plateUnits = Int((plate * scale).rounded())
            guard plateUnits > 0 else { continue }
            while perSideUnits >= plateUnits {
                perSide.append(plate)
                perSideUnits -= plateUnits
            }
        }
        let loadedUnits = barUnits + 2 * perSide.reduce(0) { $0 + Int(($1 * scale).rounded()) }
        let total = Double(loadedUnits) / scale
        return Load(perSide: perSide, total: total, shortBy: max(0, Double(targetUnits - loadedUnits) / scale),
                    isBelowBar: targetUnits < barUnits)
    }
}

/// Warm-up ramp for an exercise's working weight (the exercise menu's "Add warm-up sets"), in the user's unit.
/// Barbell lifts start with the empty bar; everything else ramps from half the working weight.
/// Weights round down to what plates can make (2.5 kg / 5 lb); steps that land on the same weight are dropped.
enum WarmupPlan {
    struct Step: Equatable {
        var weight: Double
        var reps: Int
    }

    static func increment(for unit: WeightUnit) -> Double { unit == .kg ? 2.5 : 5 }

    static func isBarbell(_ equipment: String?) -> Bool {
        guard let equipment = equipment?.lowercased() else { return false }
        return equipment == "barbell" || equipment.contains("curl bar")
    }

    static func steps(working: Double, unit: WeightUnit, equipment: String?) -> [Step] {
        let step = increment(for: unit)
        guard working >= step * 4 else { return [] }
        let ramp: [(ratio: Double, reps: Int)]
        var result: [Step] = []
        if isBarbell(equipment) {
            let bar = PlateMath.bars(for: unit)[0]
            guard working > bar + step else { return [] }
            result.append(Step(weight: bar, reps: 10))
            ramp = [(0.5, 5), (0.7, 3), (0.85, 1)]
        } else {
            ramp = [(0.5, 8), (0.75, 4)]
        }
        for (ratio, reps) in ramp {
            let weight = (working * ratio / step + 1e-9).rounded(.down) * step
            guard working > weight, weight > (result.last?.weight ?? 0) else { continue }
            result.append(Step(weight: weight, reps: reps))
        }
        return result
    }
}
