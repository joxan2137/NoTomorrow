package app.notomorrow.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.notomorrow.model.TrainingGoal
import app.notomorrow.model.WeightUnit

/**
 * `UserProfile` (`Models.swift:37`) — a **singleton row**. `null` from
 * `ProfileDao.observe()` means onboarding has not run.
 *
 * Timestamps are epoch millis in the device zone; every enum is stored as its iOS raw
 * string through `Converters`.
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val name: String,
    val bodyWeightKg: Double? = null,
    val goal: TrainingGoal = TrainingGoal.BuildMuscle,
    val units: WeightUnit = WeightUnit.Kg,
    val calorieGoal: Int = 2600,
    val proteinGoalG: Int = 180,
    val carbsGoalG: Int = 300,
    val fatGoalG: Int = 80,
    val defaultRestSeconds: Int = 90,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SINGLETON_ID: Int = 0
    }
}

/**
 * `GymSchedule` (`Models.swift:66`) — a **singleton row**. Weekdays use ISO numbering
 * (1 = Monday … 7 = Sunday) and are stored sorted; `defaultMinuteOfDay` is minutes since
 * midnight (18:00 → 1080); `overrides` is keyed by ISO weekday.
 */
@Entity(tableName = "gym_schedule")
data class GymScheduleEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val weekdays: List<Int> = listOf(1, 3, 5),
    val defaultMinuteOfDay: Int = 18 * 60,
    val overrides: Map<Int, Int> = emptyMap(),
    val remindHourBefore: Boolean = true,
    val askIfSkippedAt21: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun minuteOfDay(isoWeekday: Int): Int = overrides[isoWeekday] ?: defaultMinuteOfDay

    fun isGymDay(isoWeekday: Int): Boolean = weekdays.contains(isoWeekday)

    /** The row as it must be persisted: singleton id, weekdays sorted. */
    fun normalized(): GymScheduleEntity =
        copy(id = SINGLETON_ID, weekdays = weekdays.sorted())

    companion object {
        const val SINGLETON_ID: Int = 0
        const val DEFAULT_MINUTE_OF_DAY: Int = 18 * 60
    }
}
