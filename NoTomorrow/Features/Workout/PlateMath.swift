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

    struct Load: Equatable {
        /// One side of the bar, heaviest first (the other side mirrors it).
        var perSide: [Double]
        /// Bar plus both sides: what the loaded bar actually weighs.
        var total: Double
        /// Target minus total: over 0 when the plates cannot make the target exactly.
        var shortBy: Double
        /// The target is lighter than the empty bar.
        var isBelowBar: Bool

        var isExact: Bool { !isBelowBar && shortBy < 0.001 }

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
    /// A target at or below the bar loads nothing. Works in hundredths so 1.25 steps add up exactly.
    static func load(target: Double, bar: Double, plates: [Double]) -> Load {
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
