package app.notomorrow.feature.progress

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.designsystem.WeekVolumeBar
import app.notomorrow.model.SetKind
import app.notomorrow.util.S
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Pure half of the Progress "Weekly stats" card ([WeeklyStatsCard]) — port of `WeeklyStats.swift`:
 * finished workouts bucketed into the last eight weeks, each week carrying every metric the card
 * can switch between.
 *
 * Weeks start on the locale's first day of the week (the user's region), not ISO Monday. A workout
 * counts once it is finished and has a completed set, as on the training calendar; its volume
 * leaves warm-ups out (`Workout.totalVolumeKg`), its sets count every completed set
 * (`Workout.completedSetCount`).
 */
object WeeklyStats {

    enum class Metric(@param:StringRes val titleRes: Int) {
        Workouts(S.stats_workouts),
        Volume(S.workout_volume),
        Duration(S.workout_time),
        Sets(S.workout_sets),
    }

    /** One finished workout. */
    data class Session(
        val startedAt: Instant,
        val durationSeconds: Double,
        val volumeKg: Double,
        val sets: Int,
    )

    @Immutable
    data class Week(
        val weekStart: LocalDate,
        val isCurrent: Boolean,
        val workouts: Int = 0,
        val volumeKg: Double = 0.0,
        val durationSeconds: Double = 0.0,
        val sets: Int = 0,
    ) {
        /** The number the bars plot for [metric]: a count, kilograms or seconds. */
        fun value(metric: Metric): Double = when (metric) {
            Metric.Workouts -> workouts.toDouble()
            Metric.Volume -> volumeKg
            Metric.Duration -> durationSeconds
            Metric.Sets -> sets.toDouble()
        }

        /** The volume chart's bar, fed [metric]: its axis only labels the weeks. */
        fun toBar(metric: Metric): WeekVolumeBar = WeekVolumeBar(weekStart, value(metric), isCurrent)
    }

    const val WEEKS: Int = 8

    fun startOfWeek(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

    /**
     * One [Session] per finished workout in [rows] (completed sets only, so a workout without one
     * never appears): the `Workout` fields `WeeklyStatsCard` reads, rebuilt from the flat rows.
     */
    fun sessions(rows: List<CompletedSetRow>): List<Session> =
        rows.filter { it.workoutEndedAt != null }
            .groupBy { it.workoutId }
            .map { (_, sets) ->
                val first = sets.first()
                Session(
                    startedAt = Instant.ofEpochMilli(first.workoutStartedAt),
                    durationSeconds = ((first.workoutEndedAt ?: first.workoutStartedAt) - first.workoutStartedAt) / 1000.0,
                    volumeKg = sets.filter { it.kind != SetKind.Warmup }.sumOf { it.weightKg * it.reps },
                    sets = sets.size,
                )
            }

    /** The last [count] weeks, oldest first, ending with the week of [today]. Sessions outside them are ignored. */
    fun weeks(
        sessions: List<Session>,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
        zone: ZoneId = ZoneId.systemDefault(),
        count: Int = WEEKS,
    ): List<Week> {
        val thisWeek = startOfWeek(today, firstDayOfWeek)
        val starts = (0 until maxOf(count, 0)).reversed().map { thisWeek.minusWeeks(it.toLong()) }
        val byWeek = sessions.groupBy { startOfWeek(it.startedAt.atZone(zone).toLocalDate(), firstDayOfWeek) }
        return starts.map { start ->
            val inWeek = byWeek[start].orEmpty()
            Week(
                weekStart = start,
                isCurrent = start == thisWeek,
                workouts = inWeek.size,
                volumeKg = inWeek.sumOf { it.volumeKg },
                durationSeconds = inWeek.sumOf { maxOf(0.0, it.durationSeconds) },
                sets = inWeek.sumOf { it.sets },
            )
        }
    }

    /** This week against last week as a fraction (0.25 = +25 %); `null` when last week is zero. */
    fun change(weeks: List<Week>, metric: Metric): Double? {
        if (weeks.size < 2) return null
        val last = weeks[weeks.size - 2].value(metric)
        if (last <= 0.0) return null
        return (weeks.last().value(metric) - last) / last
    }
}
