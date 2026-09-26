package app.notomorrow.feature.progress

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Pure half of the Progress training calendar ([TrainingCalendarCard]) — 1:1 port of
 * `TrainingCalendar.swift`: the month grid, how bright a day is, the weekly streak, and the
 * finished workouts per day it all reads.
 */
object TrainingCalendar {

    /** The weeks of [month], Monday first: `weeks[w][d]` is ISO weekday `d + 1`, `null` outside the month. */
    fun weeks(month: YearMonth): List<List<LocalDate?>> {
        val first = month.atDay(1)
        // ISO: Monday = 0 … Sunday = 6.
        val lead = Fmt.isoWeekday(first) - 1
        val cells = MutableList<LocalDate?>(lead) { null }
        for (offset in 0 until month.lengthOfMonth()) cells += first.plusDays(offset.toLong())
        while (cells.size % 7 != 0) cells += null
        return cells.chunked(7)
    }

    /** 0 = no workout, then 1…4 by completed sets that day (1–9, 10–17, 18–25, 26+). */
    fun level(sets: Int): Int = when {
        sets < 1 -> 0
        sets < 10 -> 1
        sets < 18 -> 2
        sets < 26 -> 3
        else -> 4
    }

    /**
     * Consecutive ISO weeks, ending with this one (or last week, when this week has none yet),
     * with at least one workout.
     */
    fun weekStreak(workoutDays: Collection<LocalDate>, today: LocalDate): Int {
        val weeks = workoutDays.mapTo(mutableSetOf()) { Fmt.startOfIsoWeek(it) }
        var week = Fmt.startOfIsoWeek(today)
        if (week !in weeks) week = week.minusWeeks(1)
        var streak = 0
        while (week in weeks) {
            streak += 1
            week = week.minusWeeks(1)
        }
        return streak
    }

    /** One calendar day: its finished workouts (newest first) and their completed sets, warm-ups included. */
    data class Day(val workoutIds: List<String>, val sets: Int)

    /**
     * The finished workouts with a completed set, by the day they started — the card's
     * `Dictionary(grouping:)` over `Workout.completedSetCount > 0`, read off the completed-set rows.
     */
    fun days(rows: List<CompletedSetRow>, zone: ZoneId): Map<LocalDate, Day> =
        rows.filter { it.workoutEndedAt != null }
            .groupBy { Instant.ofEpochMilli(it.workoutStartedAt).atZone(zone).toLocalDate() }
            .mapValues { (_, sets) ->
                Day(
                    workoutIds = sets.sortedByDescending { it.workoutStartedAt }.map { it.workoutId }.distinct(),
                    sets = sets.size,
                )
            }
}
