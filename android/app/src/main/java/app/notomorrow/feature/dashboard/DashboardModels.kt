package app.notomorrow.feature.dashboard

import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.DayState
import app.notomorrow.service.WeekDay
import java.time.Instant
import java.time.LocalDate

/**
 * The next scheduled gym session as the card needs it — port of `DashboardSession`
 * (`Features/Dashboard/DashboardModel.swift:7`).
 *
 * [at] is the absolute instant the session starts (the Swift `date`), [date] the calendar
 * day it falls on and [isToday] whether that day is the screen's day (**not** the wall
 * clock's — the screen is re-keyed at midnight and compares against its own day).
 */
data class DashboardSession(
    val at: Instant,
    val date: LocalDate,
    val minuteOfDay: Int,
    val isToday: Boolean,
)

/**
 * Today's intake summed from `MealEntry` rows — port of `FuelTotals`
 * (`Features/Dashboard/FuelSummaryRow.swift:4`).
 */
data class FuelTotals(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
) {
    companion object {
        fun of(entries: List<MealEntryEntity>): FuelTotals {
            var kcal = 0.0
            var protein = 0.0
            var carbs = 0.0
            var fat = 0.0
            for (entry in entries) {
                kcal += entry.kcal
                protein += entry.proteinG
                carbs += entry.carbsG
                fat += entry.fatG
            }
            return FuelTotals(kcal, protein, carbs, fat)
        }
    }
}

/**
 * Daily goals from the profile, with the same defaults iOS falls back to before
 * onboarding has written one (`FuelSummaryRow.swift:21`).
 */
data class FuelGoals(
    val kcal: Double = 2600.0,
    val protein: Double = 180.0,
    val carbs: Double = 300.0,
    val fat: Double = 80.0,
) {
    companion object {
        fun of(profile: UserProfileEntity?): FuelGoals = FuelGoals(
            kcal = (profile?.calorieGoal ?: 2600).toDouble(),
            protein = (profile?.proteinGoalG ?: 180).toDouble(),
            carbs = (profile?.carbsGoalG ?: 300).toDouble(),
            fat = (profile?.fatGoalG ?: 80).toDouble(),
        )
    }
}

/** One PR chip on the last-session row: exercise name + the set that beat the record. */
data class PrSet(
    val exerciseName: String,
    val weightKg: Double,
    val reps: Int,
)

/**
 * The most recent finished workout as `LastSessionRow` needs it — the flattened form of
 * `Workout` (name · duration · n PRs) plus one entry per PR set.
 */
data class LastSession(
    /** The workout the row opens in the detail sheet. */
    val workoutId: String,
    val name: String,
    /** `endedAt ?? startedAt`, the timestamp the day label is derived from. */
    val at: Instant,
    /** `Workout.duration` — seconds between `startedAt` and `endedAt`. */
    val durationSeconds: Double,
    val prCount: Int,
    val prSets: List<PrSet>,
)

/**
 * Everything `DashboardScreen` renders, in one immutable snapshot. The write-side lives in
 * [DashboardViewModel]; the composables only format.
 */
data class DashboardUiState(
    /** The calendar day the screen is keyed on. */
    val day: LocalDate = LocalDate.now(),
    /** `UserProfile.name` trimmed, or `"–"` when there is no profile yet. */
    val avatarInitial: String = FALLBACK_INITIAL,
    val week: List<WeekDay> = emptyList(),
    val session: DashboardSession? = null,
    /**
     * The routine the card names: today's finished workout once the session is today and one
     * exists (as the Kolega tab names it), else the suggested routine.
     */
    val routineName: String? = null,
    /** Not rendered: the routine `startWorkout` builds the session from. */
    val suggestedRoutineId: String? = null,
    /** Today's session is trained: "Today's session" and "Done" replace the eyebrow and countdown. */
    val sessionDone: Boolean = false,
    val isPaired: Boolean = false,
    val partnerName: String? = null,
    val myState: DayState = DayState.Rest,
    val myTime: Instant? = null,
    val partnerState: DayState = DayState.Rest,
    val partnerTime: Instant? = null,
    val hasActiveWorkout: Boolean = false,
    /** A finished workout with a completed set started today (`NextSessionCard.trainedToday`). */
    val trainedToday: Boolean = false,
    val isConfirming: Boolean = false,
    val totals: FuelTotals = FuelTotals(),
    val goals: FuelGoals = FuelGoals(),
    val lastSession: LastSession? = null,
    val units: WeightUnit = WeightUnit.Kg,
    val showsCantMakeIt: Boolean = false,
) {
    companion object {
        /** `DashboardView.avatarInitial` — an en dash when the name is empty. */
        const val FALLBACK_INITIAL: String = "–"
    }
}
