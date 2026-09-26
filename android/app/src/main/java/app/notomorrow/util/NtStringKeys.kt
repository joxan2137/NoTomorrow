package app.notomorrow.util

import androidx.annotation.StringRes
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import app.notomorrow.model.MeasurementKind
import app.notomorrow.model.TrainingGoal

/**
 * The iOS keys that are **built at runtime** (`"meal." + rawValue`,
 * `"cant.reason.\(rawValue)"`, `AttendanceService.labelKey`, …) turned into
 * exhaustive `when` maps over resource ids.
 *
 * No reflection, no `Resources.getIdentifier`: R8 must be able to see every
 * resource a screen can reach.
 */
object NtKeys {

    /** `"meal." + rawValue` — `MealSlot.label`. */
    @StringRes
    fun meal(slot: MealSlot): Int = when (slot) {
        MealSlot.Breakfast -> S.meal_breakfast
        MealSlot.Lunch -> S.meal_lunch
        MealSlot.Snack -> S.meal_snack
        MealSlot.Dinner -> S.meal_dinner
    }

    /** `"measure." + rawValue` — `Measurements.titleKey`. */
    @StringRes
    fun measure(kind: MeasurementKind): Int = when (kind) {
        MeasurementKind.Waist -> S.measure_waist
        MeasurementKind.Chest -> S.measure_chest
        MeasurementKind.Hips -> S.measure_hips
        MeasurementKind.Arm -> S.measure_arm
        MeasurementKind.Thigh -> S.measure_thigh
        MeasurementKind.Neck -> S.measure_neck
        MeasurementKind.BodyFat -> S.measure_bodyFat
    }

    /** `"goal.\(rawValue)"` */
    @StringRes
    fun goal(goal: TrainingGoal): Int = when (goal) {
        TrainingGoal.BuildMuscle -> S.goal_buildMuscle
        TrainingGoal.LoseFat -> S.goal_loseFat
        TrainingGoal.Maintain -> S.goal_maintain
    }

    /** `"fuel.source.\(rawValue)"` — `FoodSource.labelKey`. */
    @StringRes
    fun foodSource(source: FoodSource): Int = when (source) {
        FoodSource.OpenFoodFacts -> S.fuel_source_openFoodFacts
        FoodSource.Usda -> S.fuel_source_usda
        FoodSource.Custom -> S.fuel_source_custom
        FoodSource.AiEstimate -> S.fuel_source_aiEstimate
        FoodSource.QuickAdd -> S.fuel_source_quickAdd
    }

    /**
     * `"cant.reason.\(rawValue)"` — `BroDerived.reasonLabel`. Unknown reasons
     * are shown verbatim on iOS, so this returns `null` for them.
     */
    @StringRes
    fun cantReason(raw: String): Int? = when (raw.trim().lowercase()) {
        "sick" -> S.cant_reason_sick
        "work" -> S.cant_reason_work
        "tired" -> S.cant_reason_tired
        "family" -> S.cant_reason_family
        "none" -> S.cant_reason_none
        else -> null
    }

    /** `"weekday.\(mon…sun)"` for ISO weekday 1…7, clamped (`AttendanceService.labelKey`). */
    @StringRes
    fun weekday(isoWeekday: Int): Int = when (isoWeekday.coerceIn(1, 7)) {
        1 -> S.weekday_mon
        2 -> S.weekday_tue
        3 -> S.weekday_wed
        4 -> S.weekday_thu
        5 -> S.weekday_fri
        6 -> S.weekday_sat
        else -> S.weekday_sun
    }

    /** `"weekday.\(mon…sun).short"` for ISO weekday 1…7, clamped. */
    @StringRes
    fun weekdayShort(isoWeekday: Int): Int = when (isoWeekday.coerceIn(1, 7)) {
        1 -> S.weekday_mon_short
        2 -> S.weekday_tue_short
        3 -> S.weekday_wed_short
        4 -> S.weekday_thu_short
        5 -> S.weekday_fri_short
        6 -> S.weekday_sat_short
        else -> S.weekday_sun_short
    }

    /** `"muscleName.\(slug)"` — `null` means "capitalize the raw value" (`WorkoutStrings.muscle`). */
    @StringRes
    fun muscle(raw: String): Int? = when (slug(raw)) {
        "tibialis_anterior" -> app.notomorrow.R.string.muscleName_tibialis_anterior
        "abdominals" -> S.muscleName_abdominals
        "abductors" -> S.muscleName_abductors
        "adductors" -> S.muscleName_adductors
        "biceps" -> S.muscleName_biceps
        "calves" -> S.muscleName_calves
        "chest" -> S.muscleName_chest
        "forearms" -> S.muscleName_forearms
        "glutes" -> S.muscleName_glutes
        "hamstrings" -> S.muscleName_hamstrings
        "lats" -> S.muscleName_lats
        "lower_back" -> S.muscleName_lower_back
        "middle_back" -> S.muscleName_middle_back
        "neck" -> S.muscleName_neck
        "quadriceps" -> S.muscleName_quadriceps
        "shoulders" -> S.muscleName_shoulders
        "traps" -> S.muscleName_traps
        "triceps" -> S.muscleName_triceps
        else -> null
    }

    /** `"equipment.\(slug)"` — `null` means "capitalize the raw value". */
    @StringRes
    fun equipment(raw: String): Int? = when (slug(raw)) {
        "bands" -> S.equipment_bands
        "barbell" -> S.equipment_barbell
        "body_only" -> S.equipment_body_only
        "cable" -> S.equipment_cable
        "dumbbell" -> S.equipment_dumbbell
        "e_z_curl_bar" -> S.equipment_e_z_curl_bar
        "exercise_ball" -> S.equipment_exercise_ball
        "foam_roll" -> S.equipment_foam_roll
        "kettlebells" -> S.equipment_kettlebells
        "machine" -> S.equipment_machine
        "medicine_ball" -> S.equipment_medicine_ball
        "other" -> S.equipment_other
        else -> null
    }

    /**
     * `"workout.exerciseCount.\(one|few|many)"` — the catalog carries the three
     * Polish CLDR categories as plain strings, so the selector is ported from
     * `WorkoutStrings.pluralSuffix` rather than using `<plurals>`.
     */
    @StringRes
    fun exerciseCount(n: Int): Int = when (pluralCategory(n)) {
        PluralCategory.One -> S.workout_exerciseCount_one
        PluralCategory.Few -> S.workout_exerciseCount_few
        PluralCategory.Many -> S.workout_exerciseCount_many
    }

    /** `"workout.setCount.\(one|few|many)"` */
    @StringRes
    fun setCount(n: Int): Int = when (pluralCategory(n)) {
        PluralCategory.One -> S.workout_setCount_one
        PluralCategory.Few -> S.workout_setCount_few
        PluralCategory.Many -> S.workout_setCount_many
    }

    /** `"calendar.workoutCount.\(one|few|many)"` */
    @StringRes
    fun workoutCount(n: Int): Int = when (pluralCategory(n)) {
        PluralCategory.One -> S.calendar_workoutCount_one
        PluralCategory.Few -> S.calendar_workoutCount_few
        PluralCategory.Many -> S.calendar_workoutCount_many
    }

    /** `"calendar.weekStreak.\(one|few|many)"` */
    @StringRes
    fun weekStreak(n: Int): Int = when (pluralCategory(n)) {
        PluralCategory.One -> S.calendar_weekStreak_one
        PluralCategory.Few -> S.calendar_weekStreak_few
        PluralCategory.Many -> S.calendar_weekStreak_many
    }

    /** `"program.routineCount.\(one|few|many)"` — `WorkoutStrings.routines`. */
    @StringRes
    fun routineCount(n: Int): Int = when (pluralCategory(n)) {
        PluralCategory.One -> S.program_routineCount_one
        PluralCategory.Few -> S.program_routineCount_few
        PluralCategory.Many -> S.program_routineCount_many
    }

    /** `"program.add.\(one|few|many)"` — `WorkoutStrings.addRoutines`. */
    @StringRes
    fun addRoutines(n: Int): Int = when (pluralCategory(n)) {
        PluralCategory.One -> S.program_add_one
        PluralCategory.Few -> S.program_add_few
        PluralCategory.Many -> S.program_add_many
    }

    /** `"fuel.ai.provider.\(google|anthropic)"` — `AIScanModel.Upload.providerNameKey`. */
    @StringRes
    fun aiProviderName(anthropic: Boolean): Int =
        if (anthropic) S.fuel_ai_provider_anthropic else S.fuel_ai_provider_google

    enum class PluralCategory { One, Few, Many }

    /** `WorkoutStrings.pluralSuffix` — the Polish/English CLDR categories. */
    fun pluralCategory(n: Int): PluralCategory {
        if (n == 1) return PluralCategory.One
        val mod10 = n % 10
        val mod100 = n % 100
        if (mod10 in 2..4 && mod100 !in 12..14) return PluralCategory.Few
        return PluralCategory.Many
    }

    /** `WorkoutStrings.slug` — lowercase, spaces and dashes to underscores. */
    fun slug(raw: String): String = raw.lowercase().replace(' ', '_').replace('-', '_')
}
