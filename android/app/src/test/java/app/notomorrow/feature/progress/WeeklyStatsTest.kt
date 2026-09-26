package app.notomorrow.feature.progress

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.model.SetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Progress weekly stats (`WeeklyStatsTests.swift`): eight weeks on the region's first weekday, every metric per week. */
class WeeklyStatsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val today: LocalDate = LocalDate.of(2026, 9, 26)   // a Saturday

    private fun at(day: LocalDate, hour: Int = 18): Instant =
        day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant()

    private fun session(day: LocalDate, minutes: Double = 60.0, volume: Double = 1000.0, sets: Int = 10) =
        WeeklyStats.Session(at(day), minutes * 60, volume, sets)

    private var setId = 1L

    private fun row(
        workoutId: String,
        day: LocalDate,
        weightKg: Double = 100.0,
        reps: Int = 5,
        kind: SetKind = SetKind.Normal,
        ended: Boolean = true,
        minutes: Long = 60,
    ) = CompletedSetRow(
        setId = setId++,
        setOrder = 0,
        kind = kind,
        weightKg = weightKg,
        reps = reps,
        completedAt = at(day).toEpochMilli(),
        isPR = false,
        isSetRecord = false,
        rpe = null,
        workoutExerciseId = 1,
        workoutExerciseOrder = 0,
        exerciseId = "bench",
        workoutId = workoutId,
        workoutName = "Push A",
        workoutStartedAt = at(day).toEpochMilli(),
        workoutEndedAt = if (ended) at(day).toEpochMilli() + minutes * 60_000 else null,
    )

    @Test
    fun `eight weeks, oldest first, ending with this week on Monday`() {
        val weeks = WeeklyStats.weeks(emptyList(), today, DayOfWeek.MONDAY, zone)
        assertEquals(8, weeks.size)
        assertEquals(LocalDate.of(2026, 9, 21), weeks.last().weekStart)
        assertEquals(LocalDate.of(2026, 8, 3), weeks.first().weekStart)
        assertEquals(1, weeks.count { it.isCurrent })
        assertTrue(weeks.last().isCurrent)
        assertTrue(weeks.all { it.workouts == 0 && it.volumeKg == 0.0 && it.durationSeconds == 0.0 && it.sets == 0 })
    }

    @Test
    fun `weeks follow the region's first weekday`() {
        val sunday = LocalDate.of(2026, 9, 20)
        val saturdayBefore = LocalDate.of(2026, 9, 19)
        val sessions = listOf(session(sunday), session(saturdayBefore))

        val sundayFirst = WeeklyStats.weeks(sessions, today, DayOfWeek.SUNDAY, zone)
        assertEquals(sunday, sundayFirst.last().weekStart)
        assertEquals("Sunday opens the week", 1, sundayFirst.last().workouts)
        assertEquals("Saturday closes the one before", 1, sundayFirst[6].workouts)

        val mondayFirst = WeeklyStats.weeks(sessions, today, DayOfWeek.MONDAY, zone)
        assertEquals(0, mondayFirst.last().workouts)
        assertEquals("Monday-first: both belong to last week", 2, mondayFirst[6].workouts)
    }

    @Test
    fun `sums every metric per week`() {
        val weeks = WeeklyStats.weeks(
            listOf(
                session(LocalDate.of(2026, 9, 22), minutes = 50.0, volume = 4000.0, sets = 18),
                session(LocalDate.of(2026, 9, 24), minutes = 70.0, volume = 6000.0, sets = 22),
                session(LocalDate.of(2026, 9, 15), minutes = 45.0, volume = 2500.0, sets = 12),
                session(LocalDate.of(2026, 7, 1)),   // older than eight weeks
            ),
            today, DayOfWeek.MONDAY, zone,
        )
        val current = weeks[7]
        assertEquals(2, current.workouts)
        assertEquals(10_000.0, current.volumeKg, 0.0)
        assertEquals(120 * 60.0, current.durationSeconds, 0.0)
        assertEquals(40, current.sets)
        assertEquals(1.0, weeks[6].value(WeeklyStats.Metric.Workouts), 0.0)
        assertEquals(2500.0, weeks[6].value(WeeklyStats.Metric.Volume), 0.0)
        assertEquals(45 * 60.0, weeks[6].value(WeeklyStats.Metric.Duration), 0.0)
        assertEquals(12.0, weeks[6].value(WeeklyStats.Metric.Sets), 0.0)
        assertEquals(3, weeks.sumOf { it.workouts })
    }

    @Test
    fun `change against last week`() {
        val weeks = WeeklyStats.weeks(
            listOf(
                session(LocalDate.of(2026, 9, 22), volume = 5000.0),
                session(LocalDate.of(2026, 9, 15), volume = 4000.0),
            ),
            today, DayOfWeek.MONDAY, zone,
        )
        assertEquals(0.25, WeeklyStats.change(weeks, WeeklyStats.Metric.Volume)!!, 1e-9)
        assertEquals(0.0, WeeklyStats.change(weeks, WeeklyStats.Metric.Workouts)!!, 1e-9)
        val lonely = WeeklyStats.weeks(listOf(session(LocalDate.of(2026, 9, 22))), today, DayOfWeek.MONDAY, zone)
        assertNull("nothing to compare against", WeeklyStats.change(lonely, WeeklyStats.Metric.Sets))
    }

    @Test
    fun `sessions come from finished workouts, volume without warm-ups, sets with them`() {
        val day = LocalDate.of(2026, 9, 22)
        val sessions = WeeklyStats.sessions(
            listOf(
                row("w1", day, weightKg = 100.0, reps = 5),
                row("w1", day, weightKg = 80.0, reps = 10),
                row("w1", day, weightKg = 40.0, reps = 10, kind = SetKind.Warmup),
                row("w2", day, ended = false),
                row("w3", day.minusDays(1), minutes = 45),
            ),
        ).sortedBy { it.startedAt }
        assertEquals(2, sessions.size)
        val w1 = sessions.last()
        assertEquals(1300.0, w1.volumeKg, 0.0)
        assertEquals(3, w1.sets)
        assertEquals(3600.0, w1.durationSeconds, 0.0)
        assertEquals(45 * 60.0, sessions.first().durationSeconds, 0.0)
    }
}
