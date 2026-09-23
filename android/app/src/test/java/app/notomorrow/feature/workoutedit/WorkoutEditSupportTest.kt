package app.notomorrow.feature.workoutedit

import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.feature.workout.ExerciseDraft
import app.notomorrow.feature.workout.SetDraft
import app.notomorrow.feature.workout.SetValue
import app.notomorrow.feature.workout.WorkoutDraft
import app.notomorrow.feature.workout.WorkoutTimeline
import app.notomorrow.feature.workout.setLabels
import app.notomorrow.feature.workout.toDraft
import app.notomorrow.feature.workout.toSectionUi
import app.notomorrow.model.SetKind
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The pure half of the workout editor (`WorkoutEditSupport.swift`, `WorkoutEditTests.swift`): the set
 * timeline, the draft's edits and its rendering as a set table.
 */
class WorkoutEditSupportTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val minute = 60_000L

    private fun set(
        id: Long,
        atMinute: Long? = null,
        done: Boolean = true,
        reps: Int = 5,
        kind: SetKind = SetKind.Normal,
        start: Long = 0,
    ) = SetDraft(
        id = id,
        sourceId = if (atMinute != null) id else null,
        kind = kind,
        weightKg = 50.0,
        reps = reps,
        isDone = done,
        originalCompletedAt = atMinute?.let { start + it * minute },
    )

    private fun exercise(id: Long, exerciseId: String, sets: List<SetDraft>) = ExerciseDraft(
        id = id,
        sourceId = id,
        exerciseId = exerciseId,
        name = exerciseId,
        primaryMuscle = null,
        restSeconds = 90,
        sets = sets,
    )

    private fun draft(start: Long = 0, durationMs: Long = 60 * minute, exercises: List<ExerciseDraft> = emptyList()) =
        WorkoutDraft(name = "A", notes = "", startedAt = start, durationMs = durationMs, exercises = exercises)

    // MARK: Timeline

    @Test
    fun `remap keeps an untouched time, shifts exactly and scales into range`() {
        val start = 1_000_000_000L
        val end = start + 3_600_000
        val t = start + 600_123
        assertEquals(t, WorkoutTimeline.remap(t, start, end, start, end))

        val day = 86_400_000L
        assertEquals(600_123L, WorkoutTimeline.remap(t, start, end, start - day, end - day) - (start - day))

        assertEquals(300_061L, WorkoutTimeline.remap(t, start, end, start, start + 1_800_000) - start, "60 → 30 min halves it")

        assertEquals(start + 1_800_000, WorkoutTimeline.remap(end + 120_000, start, end, start, start + 1_800_000), "clamped")
    }

    @Test
    fun `remap keeps ties`() {
        val start = 5_000_000_000L
        val end = start + 3_000_000
        val tie = start + 1_234_567
        val a = WorkoutTimeline.remap(tie, start, end, start - 7_777, end - 9_999)
        val b = WorkoutTimeline.remap(tie, start, end, start - 7_777, end - 9_999)
        assertEquals(a, b)
    }

    @Test
    fun `new rows borrow a neighbour's time`() {
        val start = 2_000_000_000L
        val first = listOf(set(1), set(2, atMinute = 10, start = start), set(3, atMinute = 12, start = start), set(4))
        val second = listOf(set(5), set(6))
        val d = draft(start = start, exercises = listOf(exercise(10, "a", first), exercise(20, "b", second)))

        val times = WorkoutTimeline.completedTimes(d, oldStart = start, oldEnd = start + 60 * minute)

        assertEquals(start + 10 * minute, times[1L], "no earlier row: the next timed one")
        assertEquals(start + 12 * minute, times[4L], "appended: the last timed row")
        assertEquals(start + 12 * minute, times[5L], "new exercise: the latest time above it")
        assertEquals(start + 12 * minute, times[6L])

        val lonely = draft(start = start, exercises = listOf(exercise(10, "a", listOf(set(1), set(2, done = false)))))
        val lonelyTimes = WorkoutTimeline.completedTimes(lonely, oldStart = start, oldEnd = start)
        assertEquals(start, lonelyTimes[1L], "nothing timed at all: the start")
        assertNull(lonelyTimes[2L], "open rows get no time")
    }

    // MARK: Draft

    @Test
    fun `duration steps snap to five minutes within 5 min to 12 h`() {
        var d = draft(durationMs = (52 * 60 + 37) * 1000L)
        d = d.steppingDuration(1)
        assertEquals(55 * minute, d.durationMs)
        d = d.steppingDuration(-1)
        assertEquals(50 * minute, d.durationMs)

        d = d.copy(durationMs = 3 * minute)
        assertFalse(d.canShorten)
        assertEquals(5 * minute, d.steppingDuration(1).durationMs)

        d = d.copy(durationMs = 800 * minute)
        assertFalse(d.canLengthen)
        assertEquals(720 * minute, d.steppingDuration(-1).durationMs, "never above 12 h")
    }

    @Test
    fun `day and time edits keep the rest of the start`() {
        val start = LocalDate.of(2026, 9, 20).atTime(18, 0, 37).atZone(zone).toInstant().toEpochMilli()
        val d = draft(start = start)

        val moved = d.withDay(LocalDate.of(2026, 9, 17), zone)
        val movedAt = java.time.Instant.ofEpochMilli(moved.startedAt).atZone(zone)
        assertEquals(LocalDate.of(2026, 9, 17), movedAt.toLocalDate())
        assertEquals(18, movedAt.hour)
        assertEquals(37, movedAt.second)
        assertEquals(start, moved.withDay(LocalDate.of(2026, 9, 20), zone).startedAt, "back to the same day: the exact start")
        assertSame(d, d.withDay(LocalDate.of(2026, 9, 20), zone))

        val retimed = java.time.Instant.ofEpochMilli(d.withTime(7, 15, zone).startedAt).atZone(zone)
        assertEquals(7, retimed.hour)
        assertEquals(15, retimed.minute)
        assertEquals(37, retimed.second)
        assertEquals(LocalDate.of(2026, 9, 20), retimed.toLocalDate(), "the time never moves the day")
        assertEquals(start, d.withTime(18, 0, zone).startedAt)
    }

    @Test
    fun `a time spanning a DST change keeps the wall clock`() {
        // 25 Oct 2026 is 25 h long in Warsaw: the day move keeps 18:00, not 17:00.
        val start = LocalDate.of(2026, 10, 26).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val moved = draft(start = start).withDay(LocalDate.of(2026, 10, 24), zone)
        assertEquals(18, java.time.Instant.ofEpochMilli(moved.startedAt).atZone(zone).hour)
    }

    @Test
    fun `time validity needs a past start and an end at most a minute ahead`() {
        val now = 10_000_000_000L
        val d = draft(start = now - 60 * minute, durationMs = 60 * minute)
        assertTrue(d.isTimeValid(now))
        assertTrue(d.copy(durationMs = 61 * minute).isTimeValid(now), "a minute of grace")
        assertFalse(d.copy(durationMs = 62 * minute).isTimeValid(now), "ends in the future")
        assertFalse(draft(start = now + minute, durationMs = 5 * minute).isTimeValid(now))
    }

    @Test
    fun `rows never log zero reps and added rows start done`() {
        var d = draft(exercises = listOf(exercise(10, "a", listOf(set(1, atMinute = 1, kind = SetKind.Warmup, reps = 8)))))

        d = d.addingSet(10, newId = -1)
        val added = d.exercises[0].sets[1]
        assertEquals(SetKind.Normal, added.kind, "a warm-up is copied as a normal set")
        assertEquals(50.0, added.weightKg)
        assertTrue(added.isLogged, "added rows start done")

        d = d.updatingSet(-1, 10) { it.copy(reps = 0) }
        assertFalse(d.exercises[0].sets[1].isLogged, "no reps, no ✓")
        assertNull(d.togglingDone(-1, 10), "an empty row can't be ticked")
        d = d.updatingSet(-1, 10) { it.copy(reps = 10) }
        assertTrue(d.exercises[0].sets[1].isLogged, "the tick survives retyping the reps")

        d = d.togglingDone(-1, 10)!!
        assertFalse(d.exercises[0].sets[1].isDone)
        assertTrue(d.togglingDone(-1, 10)!!.exercises[0].sets[1].isDone)
    }

    @Test
    fun `a legacy 0-rep set stays logged while it is left as it was`() {
        val graph = WorkoutWithExercises(
            WorkoutEntity(id = "w", name = "Push A", startedAt = 0, endedAt = 3_600_000),
            listOf(
                WorkoutExerciseWithSets(
                    WorkoutExerciseEntity(id = 7, workoutId = "w", exerciseId = "bench", order = 0),
                    exercise = null,
                    // Logged by a build before the no-"0 × 0" rule.
                    sets = listOf(SetEntryEntity(id = 8, workoutExerciseId = 7, order = 0, weightKg = 60.0, reps = 0, completedAt = 60_000)),
                ),
            ),
        )
        var d = graph.toDraft(Locale.UK).copy(name = "Push B")
        assertTrue(d.exercises[0].sets[0].isLogged, "a rename does not un-log it")

        d = d.togglingDone(8, 7)!!
        assertFalse(d.exercises[0].sets[0].isLogged)
        d = assertNotNull(d.togglingDone(8, 7), "unticked by mistake, it ticks back")
        assertTrue(d.exercises[0].sets[0].isLogged)

        d = d.updatingSet(8, 7) { it.copy(weightKg = 62.5) }
        assertFalse(d.exercises[0].sets[0].isLogged, "changed, it follows the no-0-reps rule")
        assertNull(d.updatingSet(8, 7) { it.copy(isDone = false) }.togglingDone(8, 7), "and can't be ticked without reps")
        d = d.updatingSet(8, 7) { it.copy(weightKg = 60.0) }
        assertTrue(d.exercises[0].sets[0].isLogged, "put back as it was, it counts again")
        d = d.updatingSet(8, 7) { it.copy(reps = 6) }
        assertTrue(d.exercises[0].sets[0].isLogged)
    }

    @Test
    fun `exercises move, go and never come in twice`() {
        var d = draft()
        var id = -1L
        for (exerciseId in listOf("a", "b", "c")) {
            d = d.appendingExercise(id--, id--, exerciseId, exerciseId, null, 90, SetValue(40.0, 10))
        }
        d = d.appendingExercise(id--, id--, "a", "a", null, 90, null)
        assertEquals(listOf("a", "b", "c"), d.exercises.map { it.exerciseId }, "no duplicates")

        d = d.movingExercise(d.exercises[2].id, -1)
        assertEquals(listOf("a", "c", "b"), d.exercises.map { it.exerciseId })
        assertSame(d, d.movingExercise(d.exercises[0].id, -1), "the first can't move up")
        d = d.removingExercise(d.exercises[1].id)
        assertEquals(listOf("a", "b"), d.exercises.map { it.exerciseId })
        assertEquals(listOf(10), d.exercises[0].sets.map { it.reps })
        assertTrue(d.exercises[0].sets[0].isLogged)
        assertEquals(-4L, d.lowestId, "a and b are left, with ids -1 … -4")
    }

    @Test
    fun `an edit undone by hand leaves the draft equal to the original (not dirty)`() {
        val original = draft(exercises = listOf(exercise(10, "a", listOf(set(1, atMinute = 1), set(2, atMinute = 2)))))
        assertTrue(original.deletingSet(2, 10) != original)
        assertEquals(original, original.togglingDone(1, 10)!!.togglingDone(1, 10)!!)
        assertEquals(original, original.copy(name = "B").copy(name = "A"))
        assertEquals(original, original.steppingDuration(1).copy(durationMs = original.durationMs))
    }

    @Test
    fun `the editor table numbers working sets and ticks only logged rows`() {
        val ui = exercise(
            10,
            "a",
            listOf(
                set(1, atMinute = 1, kind = SetKind.Warmup),
                set(2, atMinute = 2),
                set(3, reps = 0),
                set(4, atMinute = 4, kind = SetKind.Drop),
            ),
        ).toSectionUi()
        assertEquals(listOf(0, 1, 2, 3), ui.sets.map { it.number })
        assertEquals(listOf(true, true, false, true), ui.sets.map { it.isCompleted })
        assertTrue(ui.sets.all { it.previous == null && !it.isCurrent })
    }

    @Test
    fun `a stored workout becomes its draft`() {
        val graph = WorkoutWithExercises(
            WorkoutEntity(id = "w", name = "Push A", startedAt = 1_000, endedAt = 3_601_000, notes = "Felt strong"),
            listOf(
                WorkoutExerciseWithSets(
                    WorkoutExerciseEntity(id = 7, workoutId = "w", exerciseId = "bench", order = 0, restSeconds = 120),
                    exercise = null,
                    sets = listOf(
                        SetEntryEntity(id = 9, workoutExerciseId = 7, order = 1, weightKg = 80.0, reps = 5),
                        SetEntryEntity(id = 8, workoutExerciseId = 7, order = 0, weightKg = 60.0, reps = 5, completedAt = 61_000),
                    ),
                ),
            ),
        )
        val d = graph.toDraft(Locale.UK)
        assertEquals(3_600_000L, d.durationMs)
        assertEquals("Felt strong", d.notes)
        assertEquals(listOf(8L, 9L), d.exercises[0].sets.map { it.id })
        assertEquals(listOf(true, false), d.exercises[0].sets.map { it.isDone })
        assertEquals(61_000L, d.exercises[0].sets[0].originalCompletedAt)
        assertEquals(120, d.exercises[0].restSeconds)
    }

    @Test
    fun `detail chips number working sets and skip warm-ups`() {
        val sets = listOf(
            SetEntryEntity(id = 1, workoutExerciseId = 1, order = 0, kind = SetKind.Warmup),
            SetEntryEntity(id = 2, workoutExerciseId = 1, order = 1),
            SetEntryEntity(id = 3, workoutExerciseId = 1, order = 2, kind = SetKind.Drop),
            SetEntryEntity(id = 4, workoutExerciseId = 1, order = 3),
        )
        assertEquals(mapOf(1L to "W", 2L to "1", 3L to "D", 4L to "3"), setLabels(sets))
    }
}
