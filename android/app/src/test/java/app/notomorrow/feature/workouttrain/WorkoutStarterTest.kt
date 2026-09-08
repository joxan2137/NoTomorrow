package app.notomorrow.feature.workouttrain

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.feature.workout.WorkoutStarter
import app.notomorrow.model.SetKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure half of `WorkoutStarter` — the prefill rule of
 * `NoTomorrow/Features/Workout/WorkoutStarter.swift`, which is the only real logic the Train
 * feature owns (everything else is a Room read or a `RecordService` call).
 */
class WorkoutStarterTest {

    @Test
    fun `template is the latest usage in row order`() {
        val rows = listOf(
            row(setId = 1, workoutExerciseId = 10, workoutId = "old", startedAt = 1_000, order = 1, weight = 60.0, reps = 8),
            row(setId = 2, workoutExerciseId = 10, workoutId = "old", startedAt = 1_000, order = 0, weight = 50.0, reps = 10),
            row(setId = 3, workoutExerciseId = 20, workoutId = "new", startedAt = 5_000, order = 1, weight = 82.5, reps = 6),
            row(setId = 4, workoutExerciseId = 20, workoutId = "new", startedAt = 5_000, order = 0, weight = 80.0, reps = 8),
        )

        val template = WorkoutStarter.templateSets(rows)

        assertEquals(listOf(4L, 3L), template.map { it.setId })
        assertEquals(listOf(80.0, 82.5), template.map { it.weightKg })
    }

    @Test
    fun `warm-ups and zero-rep sets never seed a template`() {
        val rows = listOf(
            row(setId = 1, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 0, weight = 40.0, reps = 12, kind = SetKind.Warmup),
            row(setId = 2, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 1, weight = 90.0, reps = 0),
            row(setId = 3, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 2, weight = 70.0, reps = 5),
        )

        assertEquals(listOf(3L), WorkoutStarter.templateSets(rows).map { it.setId })
    }

    @Test
    fun `drop and failure sets do seed a template`() {
        val rows = listOf(
            row(setId = 1, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 0, weight = 70.0, reps = 5, kind = SetKind.Drop),
            row(setId = 2, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 1, weight = 60.0, reps = 4, kind = SetKind.Failure),
        )

        assertEquals(listOf(1L, 2L), WorkoutStarter.templateSets(rows).map { it.setId })
    }

    @Test
    fun `the current workout is excluded`() {
        val rows = listOf(
            row(setId = 1, workoutExerciseId = 10, workoutId = "past", startedAt = 1_000, order = 0, weight = 60.0, reps = 8),
            row(setId = 2, workoutExerciseId = 20, workoutId = "current", startedAt = 9_000, order = 0, weight = 100.0, reps = 1),
        )

        val template = WorkoutStarter.templateSets(rows, excludingWorkoutId = "current")

        assertEquals(listOf(1L), template.map { it.setId })
    }

    @Test
    fun `no history gives empty rows`() {
        val sets = WorkoutStarter.prefilledSets(3, WorkoutStarter.templateSets(emptyList()))

        assertEquals(3, sets.size)
        assertTrue(sets.all { it.weightKg == 0.0 && it.reps == 0 })
        assertEquals(listOf(0, 1, 2), sets.map { it.order })
        assertTrue(sets.all { it.kind == SetKind.Normal })
    }

    @Test
    fun `rows past the template repeat its last entry`() {
        val template = listOf(
            row(setId = 1, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 0, weight = 80.0, reps = 8),
            row(setId = 2, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 1, weight = 82.5, reps = 6),
        )

        val sets = WorkoutStarter.prefilledSets(4, template)

        assertEquals(listOf(80.0, 82.5, 82.5, 82.5), sets.map { it.weightKg })
        assertEquals(listOf(8, 6, 6, 6), sets.map { it.reps })
    }

    @Test
    fun `a shorter set count truncates the template`() {
        val template = listOf(
            row(setId = 1, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 0, weight = 80.0, reps = 8),
            row(setId = 2, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 1, weight = 82.5, reps = 6),
            row(setId = 3, workoutExerciseId = 10, workoutId = "w", startedAt = 1_000, order = 2, weight = 85.0, reps = 4),
        )

        assertEquals(listOf(80.0), WorkoutStarter.prefilledSets(1, template).map { it.weightKg })
    }

    /** `Workout.duration` — clamped at zero and frozen once the workout ends. */
    @Test
    fun `duration uses the end when there is one`() {
        assertEquals(
            90.0,
            app.notomorrow.feature.workout.workoutDuration(1_000_000, 1_090_000, now = 9_999_999),
        )
        assertEquals(
            30.0,
            app.notomorrow.feature.workout.workoutDuration(1_000_000, null, now = 1_030_000),
        )
        assertEquals(
            0.0,
            app.notomorrow.feature.workout.workoutDuration(1_000_000, 900_000),
        )
    }

    private fun row(
        setId: Long,
        workoutExerciseId: Long,
        workoutId: String,
        startedAt: Long,
        order: Int,
        weight: Double,
        reps: Int,
        kind: SetKind = SetKind.Normal,
        workoutExerciseOrder: Int = 0,
    ) = CompletedSetRow(
        setId = setId,
        setOrder = order,
        kind = kind,
        weightKg = weight,
        reps = reps,
        completedAt = startedAt + order,
        isPR = false,
        isSetRecord = false,
        rpe = null,
        workoutExerciseId = workoutExerciseId,
        workoutExerciseOrder = workoutExerciseOrder,
        exerciseId = "Barbell_Bench_Press_-_Medium_Grip",
        workoutId = workoutId,
        workoutName = "Push A",
        workoutStartedAt = startedAt,
        workoutEndedAt = null,
    )
}
