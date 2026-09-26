package app.notomorrow.feature.progress

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.Localizer
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Lifetime milestones (`MilestonesTests.swift`): tiers, which workout crossed each, progress and the lb display. */
class MilestonesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private var nextId = 1

    private fun session(
        day: LocalDate,
        volume: Double = 0.0,
        lifts: Map<Milestones.Kind, Double> = emptyMap(),
    ) = Milestones.Session(
        workoutId = "w${nextId++}",
        startedAt = day.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli(),
        day = day,
        volumeKg = volume,
        heaviestKg = lifts,
    )

    private fun List<Milestones.Milestone>.tier(kind: Milestones.Kind, target: Double) =
        first { it.kind == kind && it.target == target }

    @Test
    fun `tiers and lifts`() {
        assertEquals(listOf(1, 10, 25, 50, 100, 250, 500), Milestones.WORKOUT_TIERS)
        assertEquals(listOf(1e3, 1e4, 5e4, 1e5, 5e5, 1e6), Milestones.VOLUME_TIERS_KG)
        assertEquals(listOf(4, 12, 26, 52), Milestones.WEEK_STREAK_TIERS)
        assertEquals(1.0, Milestones.multiplier(Milestones.Kind.Bench))
        assertEquals(1.5, Milestones.multiplier(Milestones.Kind.Squat))
        assertEquals(2.0, Milestones.multiplier(Milestones.Kind.Deadlift))
        assertNull(Milestones.multiplier(Milestones.Kind.Workouts))
        assertEquals(Milestones.Kind.Bench, Milestones.lift("Barbell_Bench_Press_-_Medium_Grip"))
        assertEquals(Milestones.Kind.Squat, Milestones.lift("Barbell_Squat"))
        assertEquals(Milestones.Kind.Deadlift, Milestones.lift("Barbell_Deadlift"))
        assertNull(Milestones.lift("Dumbbell_Bench_Press"))
        assertNull(Milestones.lift(null))
    }

    @Test
    fun `without a body weight the lifts are left out`() {
        val all = Milestones.evaluate(emptyList(), null)
        assertEquals(7 + 6 + 4, all.size)
        assertFalse(all.any { it.kind.isLift })
        assertEquals(7 + 6 + 4 + 3, Milestones.evaluate(emptyList(), 80.0).size)
        assertNull(Milestones.latest(all))
        assertEquals(80.0, Milestones.bodyWeight(80.0, 75.0))
        assertEquals(75.0, Milestones.bodyWeight(null, 75.0))
        assertNull(Milestones.bodyWeight(null, 0.0))
    }

    @Test
    fun `workout count crossings`() {
        val sessions = (1..12).map { session(LocalDate.of(2026, 1, it)) }
        // Given out of order: evaluation sorts by start.
        val all = Milestones.evaluate(sessions.reversed(), null)
        assertEquals(LocalDate.of(2026, 1, 1), all.tier(Milestones.Kind.Workouts, 1.0).achieved?.day)
        assertEquals(sessions[9].workoutId, all.tier(Milestones.Kind.Workouts, 10.0).achieved?.workoutId)
        val next = all.tier(Milestones.Kind.Workouts, 25.0)
        assertNull(next.achieved)
        assertEquals(12.0, next.current, 0.0)
        assertEquals(12.0 / 25, next.progress, 1e-9)
        val crossed = Milestones.crossed(sessions[9].workoutId, all)
        assertEquals(listOf(Milestones.Kind.Workouts), crossed.map { it.kind })
        assertEquals(10.0, crossed.single().target, 0.0)
    }

    @Test
    fun `volume crosses on the workout that tops the tier`() {
        val a = session(LocalDate.of(2026, 3, 2), volume = 600.0)
        val b = session(LocalDate.of(2026, 3, 4), volume = 500.0)     // 1 100 → 1 t
        val c = session(LocalDate.of(2026, 3, 6), volume = 9_000.0)   // 10 100 → 10 t
        val all = Milestones.evaluate(listOf(c, a, b), null)
        assertEquals(b.workoutId, all.tier(Milestones.Kind.Volume, 1_000.0).achieved?.workoutId)
        assertEquals(c.workoutId, all.tier(Milestones.Kind.Volume, 10_000.0).achieved?.workoutId)
        val next = all.tier(Milestones.Kind.Volume, 50_000.0)
        assertEquals(10_100.0, next.current, 1e-6)
        assertFalse(next.isAchieved)
        assertEquals(listOf(10_000.0), Milestones.crossed(c.workoutId, all).map { it.target })
    }

    @Test
    fun `longest streak keeps the best run`() {
        // Weeks of 5, 12, 19, 26 Jan (4 in a row), a gap, then 2 more.
        val days = listOf(
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 13),
            LocalDate.of(2026, 1, 21), LocalDate.of(2026, 1, 28), LocalDate.of(2026, 2, 16),
            LocalDate.of(2026, 2, 23),
        )
        assertEquals(listOf(1, 1, 2, 3, 4, 4, 4), Milestones.longestStreaks(days))
        val sessions = days.map { session(it) }
        val all = Milestones.evaluate(sessions, null)
        assertEquals(sessions[4].workoutId, all.tier(Milestones.Kind.WeekStreak, 4.0).achieved?.workoutId)
        val twelve = all.tier(Milestones.Kind.WeekStreak, 12.0)
        assertEquals(4.0, twelve.current, 0.0)
        assertNull(twelve.achieved)
    }

    @Test
    fun `streak across the year boundary`() {
        val days = listOf(LocalDate.of(2025, 12, 22), LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 5))
        assertEquals(listOf(1, 2, 3), Milestones.longestStreaks(days))
    }

    @Test
    fun `lifts against body weight`() {
        val a = session(
            LocalDate.of(2026, 4, 1),
            lifts = mapOf(Milestones.Kind.Bench to 70.0, Milestones.Kind.Squat to 100.0, Milestones.Kind.Deadlift to 150.0),
        )
        val b = session(LocalDate.of(2026, 4, 8), lifts = mapOf(Milestones.Kind.Bench to 80.0, Milestones.Kind.Squat to 110.0))
        val all = Milestones.evaluate(listOf(a, b), 80.0)
        val bench = all.first { it.kind == Milestones.Kind.Bench }
        assertEquals(80.0, bench.target, 0.0)
        assertEquals(b.workoutId, bench.achieved?.workoutId)
        val squat = all.first { it.kind == Milestones.Kind.Squat }
        assertEquals(120.0, squat.target, 0.0)
        assertEquals(110.0, squat.current, 0.0)
        assertNull(squat.achieved)
        val deadlift = all.first { it.kind == Milestones.Kind.Deadlift }
        assertEquals(160.0, deadlift.target, 0.0)
        assertEquals(150.0, deadlift.current, 0.0)
        assertEquals(listOf(Milestones.Kind.Bench), Milestones.crossed(b.workoutId, all).map { it.kind })
    }

    @Test
    fun `latest and next`() {
        val a = session(LocalDate.of(2026, 5, 1), volume = 1_200.0)
        val b = session(LocalDate.of(2026, 5, 3), volume = 100.0)
        val all = Milestones.evaluate(listOf(a, b), null)
        // a crossed 1 workout and 1 t; b crossed nothing, so the latest is a's last track.
        assertEquals(Milestones.Kind.Volume, Milestones.latest(all)?.kind)
        // Closest to done first: 1/4 weeks (0.25), 2/10 workouts (0.2), 1 300 / 10 000 (0.13).
        assertEquals(
            listOf(Milestones.Kind.WeekStreak, Milestones.Kind.Workouts, Milestones.Kind.Volume),
            Milestones.next(all).map { it.kind },
        )
    }

    private var setId = 1L

    private fun row(workoutId: String, day: LocalDate, exerciseId: String?, weightKg: Double, reps: Int,
                    kind: SetKind = SetKind.Normal, ended: Boolean = true): CompletedSetRow {
        val start = day.atStartOfDay(zone).plusHours(18).toInstant().toEpochMilli()
        return CompletedSetRow(
            setId = setId++, setOrder = 0, kind = kind, weightKg = weightKg, reps = reps, completedAt = start,
            isPR = false, isSetRecord = false, rpe = null, workoutExerciseId = 1, workoutExerciseOrder = 0,
            exerciseId = exerciseId, workoutId = workoutId, workoutName = "Push A", workoutStartedAt = start,
            workoutEndedAt = if (ended) start + 3_600_000 else null,
        )
    }

    @Test
    fun `sessions from completed sets`() {
        val day = LocalDate.of(2026, 6, 1)
        val rows = listOf(
            row("a", day, "Barbell_Bench_Press_-_Medium_Grip", 100.0, 5),
            row("a", day, "Barbell_Bench_Press_-_Medium_Grip", 140.0, 1, kind = SetKind.Warmup),
            row("a", day, "Barbell_Bench_Press_-_Medium_Grip", 105.0, 0),
            row("a", day, "Barbell_Squat", 120.0, 3),
            row("a", day, "Lat_Pulldown", 60.0, 10),
            row("open", day, "Barbell_Squat", 200.0, 1, ended = false),
        )
        val sessions = Milestones.sessions(rows, zone)
        val a = sessions.single()
        assertEquals("a", a.workoutId)
        assertEquals(day, a.day)
        // Warm-up left out of volume and of the heaviest set; a 0-rep set is no lift.
        assertEquals(500.0 + 360.0 + 600.0, a.volumeKg, 1e-9)
        assertEquals(mapOf(Milestones.Kind.Bench to 100.0, Milestones.Kind.Squat to 120.0), a.heaviestKg)
    }

    // MARK: Copy

    private val en = Locale.ENGLISH
    private val strings = Localizer { id, args ->
        val pattern = when (id) {
            S.milestone_volume_s -> "%s lifted"
            S.milestone_squat_s -> "Squat %s body weight"
            NtKeys.workoutCount(50) -> "%d workouts"
            NtKeys.weekStreak(12) -> "%d-week streak"
            else -> error("unexpected string $id")
        }
        String.format(en, pattern, *args)
    }

    @Test
    fun `lb users see the pound equivalent`() {
        assertEquals(1_000.0, Milestones.displayAmount(1_000.0, WeightUnit.Kg), 0.0)
        assertEquals(2_204.6226, Milestones.displayAmount(1_000.0, WeightUnit.Lb), 1e-3)
        val ton = Milestones.Milestone(Milestones.Kind.Volume, 1_000.0, 450.0, null)
        assertEquals("1,000${Fmt.NBSP}kg lifted", Milestones.title(ton, WeightUnit.Kg, strings, en))
        assertEquals("2,205${Fmt.NBSP}lb lifted", Milestones.title(ton, WeightUnit.Lb, strings, en))
        assertEquals("992 / 2,205${Fmt.NBSP}lb", Milestones.progressText(ton, WeightUnit.Lb, en))
    }

    @Test
    fun `titles and progress text`() {
        val workouts = Milestones.Milestone(Milestones.Kind.Workouts, 50.0, 32.0, null)
        assertEquals("50 workouts", Milestones.title(workouts, WeightUnit.Kg, strings, en))
        assertEquals("32 / 50", Milestones.progressText(workouts, WeightUnit.Kg, en))
        val streak = Milestones.Milestone(Milestones.Kind.WeekStreak, 12.0, 4.0, null)
        assertEquals("12-week streak", Milestones.title(streak, WeightUnit.Kg, strings, en))
        val squat = Milestones.Milestone(Milestones.Kind.Squat, 120.0, 110.0, null)
        assertEquals("Squat 1.5× body weight", Milestones.title(squat, WeightUnit.Kg, strings, en))
        assertEquals("1,5×", Milestones.multiple(squat, Locale.forLanguageTag("pl")))
        assertEquals("110 / 120${Fmt.NBSP}kg", Milestones.progressText(squat, WeightUnit.Kg, en))
        assertTrue(Milestones.Milestone(Milestones.Kind.Bench, 80.0, 100.0, null).progress == 1.0)
    }
}
