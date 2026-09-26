package app.notomorrow.feature.progress

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.model.SetKind
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Progress training calendar (`TrainingCalendarTests.swift`): month grid (Monday first), day levels and the weekly streak. */
class TrainingCalendarTest {

    private fun date(y: Int, m: Int, d: Int): LocalDate = LocalDate.of(y, m, d)

    @Test
    fun `September 2026 starts on Tuesday`() {
        val weeks = TrainingCalendar.weeks(YearMonth.of(2026, 9))
        assertEquals(5, weeks.size)
        assertNull(weeks[0][0], "Monday 31 Aug is outside the month")
        assertEquals(1, weeks[0][1]?.dayOfMonth)
        assertEquals(30, weeks[4][2]?.dayOfMonth)
        assertNull(weeks[4][3])
        assertTrue(weeks.all { it.size == 7 })
    }

    @Test
    fun levels() {
        assertEquals(
            listOf(0, 1, 1, 2, 2, 3, 3, 4, 4),
            listOf(0, 1, 9, 10, 17, 18, 25, 26, 60).map(TrainingCalendar::level),
        )
    }

    @Test
    fun `week streak counts back from this or last week`() {
        val today = date(2026, 9, 26) // Saturday
        val days = listOf(date(2026, 9, 21), date(2026, 9, 16), date(2026, 9, 8), date(2026, 8, 20))
        assertEquals(3, TrainingCalendar.weekStreak(days, today))
        val lastWeekOnly = listOf(date(2026, 9, 16), date(2026, 9, 9))
        assertEquals(2, TrainingCalendar.weekStreak(lastWeekOnly, today))
        assertEquals(0, TrainingCalendar.weekStreak(listOf(date(2026, 9, 1)), today))
    }

    @Test
    fun `days group finished workouts by the day they started, newest first`() {
        val zone = ZoneId.of("Europe/Warsaw")
        fun row(workout: String, startedAt: LocalDate, hour: Int, endedAt: Long?) = CompletedSetRow(
            setId = 0, setOrder = 0, kind = SetKind.Normal, weightKg = 60.0, reps = 8, completedAt = 1,
            isPR = false, isSetRecord = false, rpe = null, workoutExerciseId = 1, workoutExerciseOrder = 0,
            exerciseId = "bench", workoutId = workout, workoutName = workout,
            workoutStartedAt = startedAt.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
            workoutEndedAt = endedAt,
        )
        val day = date(2026, 9, 21)
        val rows = listOf(
            row("morning", day, 7, 1L), row("morning", day, 7, 1L),
            row("evening", day, 19, 1L),
            row("running", date(2026, 9, 22), 8, null),
        )
        val days = TrainingCalendar.days(rows, zone)
        assertEquals(setOf(day), days.keys, "an unfinished workout is not on the calendar")
        assertEquals(listOf("evening", "morning"), days.getValue(day).workoutIds)
        assertEquals(3, days.getValue(day).sets)
    }

    @Test
    fun `month title and counted strings`() {
        assertEquals("September 2026", Fmt.monthYear(date(2026, 9, 1), Locale.UK))
        assertEquals("wrzesień 2026", Fmt.monthYear(date(2026, 9, 1), Locale.forLanguageTag("pl-PL")))
        assertEquals(S.calendar_workoutCount_one, NtKeys.workoutCount(1))
        assertEquals(S.calendar_workoutCount_few, NtKeys.workoutCount(3))
        assertEquals(S.calendar_workoutCount_many, NtKeys.workoutCount(12))
        assertEquals(S.calendar_weekStreak_few, NtKeys.weekStreak(22))
        assertEquals(S.calendar_weekStreak_many, NtKeys.weekStreak(5))
    }
}
