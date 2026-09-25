import Foundation

/// The pure core of the Fuel history heat scale, shared with the widget extension (which has no `TrainingGoal` or
/// SwiftData). `FuelCalendar.level` / `permille` delegate here, so the app's grid, its tests and the widgets can
/// never disagree. The rules are in `docs/architecture.md`, "Heat scoring".
enum FuelHeat {
    /// kcal eaten as whole permille of the goal, clamped to 0…10 000 (0 when the goal is not positive). Integer
    /// band edges keep both platforms equal: as Doubles, 2200 / 2000 − 1 is 0.10000000000000009, not 0.10.
    static func permille(kcalEaten: Double, kcalGoal: Double) -> Int {
        guard kcalGoal > 0 else { return 0 }
        let ratio = kcalEaten / kcalGoal
        guard !ratio.isNaN else { return 0 }
        return Int((min(max(ratio, 0), 10) * 1000).rounded())   // half away from zero, like Android's Fmt.roundHalfAwayFromZero
    }

    /// 0 = nothing logged, 1 = far off … 4 = on target. `under` / `over` are the permille bands for levels 4, 3 and 2
    /// below and above the goal. Today, while still below its best band, reads as progress instead (under 50 % → 1,
    /// under 75 % → 2, otherwise 3), so the cell brightens as the day fills up.
    static func level(kcalEaten: Double, kcalGoal: Double, under: [Int], over: [Int],
                      hasEntries: Bool, isToday: Bool) -> Int {
        guard hasEntries else { return 0 }
        guard kcalGoal > 0, under.count == 3, over.count == 3 else { return 1 }
        let p = permille(kcalEaten: kcalEaten, kcalGoal: kcalGoal)
        if isToday && p < 1000 - under[0] { return min(3, max(1, p / 250)) }
        let deviation = abs(p - 1000)
        let steps = p >= 1000 ? over : under
        if deviation <= steps[0] { return 4 }
        if deviation <= steps[1] { return 3 }
        if deviation <= steps[2] { return 2 }
        return 1
    }
}
