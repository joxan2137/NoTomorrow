package app.notomorrow.feature.fuel

import app.notomorrow.data.relation.DayKcal
import app.notomorrow.model.TrainingGoal
import app.notomorrow.service.Days
import app.notomorrow.util.Fmt
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Pure half of the Fuel history grid ([FuelCalendarSheet]): day scoring, the 26-week layout, the
 * stat tiles and the day key. Port of `FuelCalendar` (`Features/Fuel/FuelCalendar.swift`) — keep in
 * lockstep; `FuelCalendarTest` asserts the same vectors as iOS's `FuelCalendarTests`
 * (`contracts/fuel-calendar-vectors.json`, copied to the test resources).
 *
 * Framework-free: no Room, no Compose, so every rule runs on the JVM.
 */
object FuelCalendar {

    /** Columns in the grid, oldest first; the last one is the current week. */
    const val WEEKS = 26

    /** Days (before today) behind the "30-day avg" and "on target" tiles. */
    const val STATS_WINDOW = 30

    private const val HALF_DAY_MS = 12L * 60 * 60 * 1000

    // MARK: - Scoring

    /**
     * How far a day may miss its kcal goal, in permille of the goal, and still reach level 4, 3
     * and 2. [under] applies below the goal, [over] above it.
     */
    data class Tolerance(val under: List<Int>, val over: List<Int>)

    /**
     * Lose fat punishes going over hard and falling short gently; build muscle is the mirror;
     * maintain is symmetric. Level 4 is 90–103 % / 97–110 % / 95–105 % of the goal.
     */
    fun tolerance(goal: TrainingGoal): Tolerance = when (goal) {
        TrainingGoal.LoseFat -> Tolerance(under = listOf(100, 200, 350), over = listOf(30, 80, 150))
        TrainingGoal.BuildMuscle -> Tolerance(under = listOf(30, 80, 150), over = listOf(100, 200, 350))
        TrainingGoal.Maintain -> Tolerance(under = listOf(50, 100, 200), over = listOf(50, 100, 200))
    }

    /**
     * kcal eaten as whole permille of the goal, clamped to 0…10 000 (0 when the goal is not
     * positive). Integer band edges keep both platforms equal: as Doubles, 2200 / 2000 − 1 is
     * 0.10000000000000009, not 0.10. Rounds half away from zero, like Swift's `.rounded()`.
     */
    fun permille(kcalEaten: Double, kcalGoal: Double): Int {
        if (!(kcalGoal > 0)) return 0
        val ratio = kcalEaten / kcalGoal
        if (ratio.isNaN()) return 0
        return Fmt.roundHalfAwayFromZero(ratio.coerceIn(0.0, 10.0) * 1000).toInt()
    }

    /**
     * 0 = nothing logged, 1 = far off … 4 = on target. Today, while still below its best band,
     * reads as progress instead (under 50 % → 1, under 75 % → 2, otherwise 3), so the cell
     * brightens as the day fills up.
     */
    fun level(
        kcalEaten: Double,
        kcalGoal: Double,
        goal: TrainingGoal,
        hasEntries: Boolean,
        isToday: Boolean,
    ): Int {
        if (!hasEntries) return 0
        if (!(kcalGoal > 0)) return 1
        val p = permille(kcalEaten, kcalGoal)
        val tolerance = tolerance(goal)
        if (isToday && p < 1000 - tolerance.under[0]) return (p / 250).coerceIn(1, 3)
        val deviation = abs(p - 1000)
        val steps = if (p >= 1000) tolerance.over else tolerance.under
        return when {
            deviation <= steps[0] -> 4
            deviation <= steps[1] -> 3
            deviation <= steps[2] -> 2
            else -> 1
        }
    }

    // MARK: - Layout

    /** [month] is the first day of the month. */
    data class MonthLabel(val column: Int, val month: LocalDate)

    data class Layout(
        /** Monday of the oldest column. */
        val start: LocalDate,
        val today: LocalDate,
        /** `columns[c][r]`: week `c` (0 = oldest), ISO weekday `r + 1`; null after today. */
        val columns: List<List<LocalDate?>>,
        val monthLabels: List<MonthLabel>,
    ) {
        /** The column holding [day], or null outside `start…today`. */
        fun columnOf(day: LocalDate): Int? =
            if (day.isBefore(start) || day.isAfter(today)) null
            else (ChronoUnit.DAYS.between(start, day) / 7).toInt()
    }

    /**
     * Monday-first weeks (the app convention, and the Polish one) ending with the week that holds
     * [today]. A column is labelled with the month whose 1st it contains; column 0 also gets
     * `start`'s month when the first such label would come at column 2 or later.
     */
    fun layout(today: LocalDate, weeks: Int = WEEKS): Layout {
        val start = Fmt.startOfIsoWeek(today).minusWeeks((weeks - 1).toLong())
        val labels = mutableListOf<MonthLabel>()
        val columns = (0 until weeks).map { c ->
            (0 until 7).map { r ->
                val date = start.plusDays((c * 7 + r).toLong())
                if (date.dayOfMonth == 1) labels += MonthLabel(c, date)
                date.takeIf { !it.isAfter(today) }
            }
        }
        if ((labels.firstOrNull()?.column ?: Int.MAX_VALUE) >= 2) {
            labels.add(0, MonthLabel(0, start.withDayOfMonth(1)))
        }
        return Layout(start, today, columns, labels)
    }

    /**
     * Leading column to scroll to: the end (current week at the right edge) unless the selected
     * day would be off screen there, in which case the selected day is centred.
     */
    fun firstVisibleColumn(selected: Int?, visible: Int, count: Int): Int {
        val last = (count - visible).coerceAtLeast(0)
        if (selected == null || selected >= last) return last
        return (selected - visible / 2).coerceIn(0, last)
    }

    // MARK: - Stats

    data class Stats(val avg7: Double?, val avg30: Double?, val onTarget30: Int)

    /**
     * Averages over the logged days among the 7 / 30 days before today (today is still in
     * progress, so it is left out), and how many of those 30 days reached level 4. Days with
     * nothing logged are not on target.
     */
    fun stats(
        kcalByDay: Map<LocalDate, Double>,
        today: LocalDate,
        kcalGoal: Double,
        goal: TrainingGoal,
    ): Stats {
        fun window(n: Int): List<LocalDate> = (1..n).map { today.minusDays(it.toLong()) }
        fun average(n: Int): Double? {
            val values = window(n).mapNotNull { kcalByDay[it] }
            // Summed in window order, as Swift's `reduce(0, +)` does.
            return if (values.isEmpty()) null else values.fold(0.0) { sum, v -> sum + v } / values.size
        }
        val onTarget = window(STATS_WINDOW).count { day ->
            val kcal = kcalByDay[day] ?: return@count false
            level(kcal, kcalGoal, goal, hasEntries = true, isToday = false) == 4
        }
        return Stats(avg7 = average(7), avg30 = average(STATS_WINDOW), onTarget30 = onTarget)
    }

    // MARK: - Day keys & data

    /**
     * Local calendar day of a stored `meal_entry.day`. That value is local midnight at logging
     * time, so reading it through noon keeps it on the same calendar day after a time-zone change
     * of less than ±12 h.
     */
    fun dayKey(storedDay: Long, zone: ZoneId): LocalDate = Days.date(storedDay + HALF_DAY_MS, zone)

    /** Stored `day` values whose [dayKey] is [day]: the half-open range `[lower, upper)`. */
    data class StoredDayBounds(val lower: Long, val upper: Long)

    /** The exact inverse of [dayKey], DST days included. */
    fun storedDayBounds(day: LocalDate, zone: ZoneId): StoredDayBounds = StoredDayBounds(
        lower = Days.millis(day, zone) - HALF_DAY_MS,
        upper = Days.millis(day.plusDays(1), zone) - HALF_DAY_MS,
    )

    /**
     * kcal per day keyed by [dayKey] — `MealDao.observeKcalByDaySince` groups by the stored value,
     * so two stored midnights that fall on one day (after a zone change) are summed here. Days
     * with no entries are absent (level 0).
     */
    fun kcalByDay(rows: List<DayKcal>, zone: ZoneId): Map<LocalDate, Double> =
        rows.groupingBy { dayKey(it.day, zone) }.fold(0.0) { sum, row -> sum + row.kcal }
}
