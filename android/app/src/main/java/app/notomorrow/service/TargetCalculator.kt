package app.notomorrow.service

import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.model.TrainingGoal
import app.notomorrow.util.Fmt

/**
 * Daily calorie and macro targets from body weight + goal — 1:1 port of
 * `NoTomorrow/Services/TargetCalculator.swift`.
 *
 * Mifflin–St Jeor (male, 30 y, 178 cm assumed when unknown), activity 1.55, goal
 * adjustment, protein by g/kg, fat 0.9 g/kg, carbs take the remainder. kcal is rounded
 * to the nearest 50; nothing goes below 0.
 *
 * Every rounding step is Swift's `Double.rounded()` (half **away from zero**), which is
 * [Fmt.roundHalfAwayFromZero], not `Math.round` (half up) and not `kotlin.math.round`
 * (half up for negatives).
 */
object TargetCalculator {

    /** `TargetCalculator.Targets`. */
    data class Targets(
        val kcal: Int,
        val proteinG: Int,
        val carbsG: Int,
        val fatG: Int,
    )

    const val ASSUMED_AGE_YEARS: Double = 30.0
    const val ASSUMED_HEIGHT_CM: Double = 178.0
    const val ASSUMED_BODY_WEIGHT_KG: Double = 80.0
    const val ACTIVITY_FACTOR: Double = 1.55
    const val FAT_GRAMS_PER_KG: Double = 0.9

    /** `kcalAdjustment(for:)` — +300 bulking, −400 cutting, 0 maintaining. */
    fun kcalAdjustment(goal: TrainingGoal): Double = when (goal) {
        TrainingGoal.BuildMuscle -> 300.0
        TrainingGoal.LoseFat -> -400.0
        TrainingGoal.Maintain -> 0.0
    }

    /** `proteinGramsPerKg(for:)`. */
    fun proteinGramsPerKg(goal: TrainingGoal): Double = when (goal) {
        TrainingGoal.BuildMuscle -> 2.2
        TrainingGoal.LoseFat -> 2.4
        TrainingGoal.Maintain -> 1.8
    }

    /** Basal metabolic rate (Mifflin–St Jeor, male). */
    fun bmr(
        bodyWeightKg: Double,
        heightCm: Double = ASSUMED_HEIGHT_CM,
        ageYears: Double = ASSUMED_AGE_YEARS,
    ): Double = 10 * bodyWeightKg + 6.25 * heightCm - 5 * ageYears + 5

    /**
     * The full suggestion. A `null` or non-positive body weight falls back to
     * [ASSUMED_BODY_WEIGHT_KG], so the onboarding preview always has numbers.
     */
    fun targets(bodyWeightKg: Double?, goal: TrainingGoal): Targets {
        val weight = if (bodyWeightKg != null && bodyWeightKg > 0) bodyWeightKg else ASSUMED_BODY_WEIGHT_KG
        val maintenance = bmr(weight) * ACTIVITY_FACTOR
        val rawKcal = maintenance + kcalAdjustment(goal)
        val kcal = maxOf(0, Fmt.roundHalfAwayFromZero(rawKcal / 50).toInt() * 50)

        val protein = maxOf(0, Fmt.roundHalfAwayFromZero(proteinGramsPerKg(goal) * weight).toInt())
        val fat = maxOf(0, Fmt.roundHalfAwayFromZero(FAT_GRAMS_PER_KG * weight).toInt())
        val remainder = kcal.toDouble() - protein * 4.0 - fat * 9.0
        val carbs = maxOf(0, Fmt.roundHalfAwayFromZero(remainder / 4).toInt())

        return Targets(kcal = kcal, proteinG = protein, carbsG = carbs, fatG = fat)
    }

    /**
     * `apply(to:)` for the profile editor. Room entities are immutable data classes, so
     * this returns the updated copy instead of mutating in place — the caller persists it
     * with `ProfileDao.upsert`.
     */
    fun applied(profile: UserProfileEntity): UserProfileEntity {
        val t = targets(profile.bodyWeightKg, profile.goal)
        return profile.copy(
            calorieGoal = t.kcal,
            proteinGoalG = t.proteinG,
            carbsGoalG = t.carbsG,
            fatGoalG = t.fatG,
        )
    }
}
