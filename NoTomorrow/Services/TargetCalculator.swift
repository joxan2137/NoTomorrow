import Foundation

/// Daily calorie and macro targets from body weight + goal.
/// Mifflin–St Jeor (male, 30 y, 178 cm assumed when unknown), activity 1.55, goal adjustment,
/// protein by g/kg, fat 0.9 g/kg, carbs take the remainder. kcal rounded to the nearest 50; nothing goes below 0.
enum TargetCalculator {

    struct Targets: Equatable {
        var kcal: Int
        var proteinG: Int
        var carbsG: Int
        var fatG: Int
    }

    static let assumedAgeYears = 30.0
    static let assumedHeightCm = 178.0
    static let assumedBodyWeightKg = 80.0
    static let activityFactor = 1.55
    static let fatGramsPerKg = 0.9

    static func kcalAdjustment(for goal: TrainingGoal) -> Double {
        switch goal {
        case .buildMuscle: 300
        case .loseFat: -400
        case .maintain: 0
        }
    }

    static func proteinGramsPerKg(for goal: TrainingGoal) -> Double {
        switch goal {
        case .buildMuscle: 2.2
        case .loseFat: 2.4
        case .maintain: 1.8
        }
    }

    /// Basal metabolic rate (Mifflin–St Jeor, male).
    static func bmr(bodyWeightKg: Double, heightCm: Double = assumedHeightCm, ageYears: Double = assumedAgeYears) -> Double {
        10 * bodyWeightKg + 6.25 * heightCm - 5 * ageYears + 5
    }

    static func targets(bodyWeightKg: Double?, goal: TrainingGoal) -> Targets {
        let weight: Double = {
            if let bodyWeightKg, bodyWeightKg > 0 { return bodyWeightKg }
            return assumedBodyWeightKg
        }()
        let maintenance = bmr(bodyWeightKg: weight) * activityFactor
        let rawKcal = maintenance + kcalAdjustment(for: goal)
        let kcal = max(0, Int((rawKcal / 50).rounded()) * 50)

        let protein = max(0, Int((proteinGramsPerKg(for: goal) * weight).rounded()))
        let fat = max(0, Int((fatGramsPerKg * weight).rounded()))
        let remainder = Double(kcal) - Double(protein) * 4 - Double(fat) * 9
        let carbs = max(0, Int((remainder / 4).rounded()))

        return Targets(kcal: kcal, proteinG: protein, carbsG: carbs, fatG: fat)
    }

    /// Convenience for the profile editor: applies the suggestion to a profile in place.
    static func apply(to profile: UserProfile) {
        let t = targets(bodyWeightKg: profile.bodyWeightKg, goal: profile.goal)
        profile.calorieGoal = t.kcal
        profile.proteinGoalG = t.proteinG
        profile.carbsGoalG = t.carbsG
        profile.fatGoalG = t.fatG
    }
}
