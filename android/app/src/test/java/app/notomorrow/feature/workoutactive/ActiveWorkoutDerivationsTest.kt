package app.notomorrow.feature.workoutactive

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.feature.workout.ActiveWorkoutUiState
import app.notomorrow.feature.workout.ActiveWorkoutViewModel
import app.notomorrow.feature.workout.SetRowUi
import app.notomorrow.feature.workout.SetValue
import app.notomorrow.feature.workout.WorkoutExerciseUi
import app.notomorrow.model.SetKind
import app.notomorrow.util.LocaleProvider
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The pure derivations of `ActiveWorkoutModel.swift`: "Exercise i of n", the previous-workout
 * row selection and the completed-set count. The set cells and Previous alignment are in
 * `SetTableTest`.
 */
class ActiveWorkoutDerivationsTest {

    @Before
    fun fixLocale() {
        LocaleProvider.override = { Locale.UK }
    }

    @After
    fun clearLocale() {
        LocaleProvider.override = null
    }

    // MARK: - currentExerciseIndex

    @Test
    fun `open exercise wins`() {
        val exercises = listOf(exercise(id = 1, done = true), exercise(id = 2), exercise(id = 3))
        assertEquals(3, ActiveWorkoutViewModel.currentExerciseIndex(exercises, expandedId = 3L))
    }

    @Test
    fun `nothing open falls back to the first exercise with work left`() {
        val exercises = listOf(exercise(id = 1, done = true), exercise(id = 2), exercise(id = 3))
        assertEquals(2, ActiveWorkoutViewModel.currentExerciseIndex(exercises, expandedId = null))
    }

    @Test
    fun `every exercise done clamps to the list length`() {
        val exercises = listOf(exercise(id = 1, done = true), exercise(id = 2, done = true))
        assertEquals(1, ActiveWorkoutViewModel.currentExerciseIndex(exercises, expandedId = null))
    }

    @Test
    fun `an expanded id that is no longer in the list falls through`() {
        val exercises = listOf(exercise(id = 1, done = true), exercise(id = 2))
        assertEquals(2, ActiveWorkoutViewModel.currentExerciseIndex(exercises, expandedId = 99L))
    }

    @Test
    fun `an empty workout reports zero`() {
        assertEquals(0, ActiveWorkoutViewModel.currentExerciseIndex(emptyList(), expandedId = null))
    }

    // MARK: - Previous rows

    @Test
    fun `previous rows come from the newest finished earlier workout, in row order`() {
        val rows = listOf(
            row(workoutId = "old", startedAt = 100, endedAt = 200, order = 0, weight = 60.0),
            row(workoutId = "new", startedAt = 300, endedAt = 400, order = 1, weight = 82.5),
            row(workoutId = "new", startedAt = 300, endedAt = 400, order = 0, weight = 80.0),
            row(workoutId = "mine", startedAt = 500, endedAt = null, order = 0, weight = 90.0),
        )
        val previous = ActiveWorkoutViewModel.latestEarlierWorkoutRows(rows, excludingWorkoutId = "mine")
        assertEquals(listOf(80.0, 82.5), previous.map { it.weightKg })
    }

    @Test
    fun `an unfinished earlier workout is never the previous one`() {
        val rows = listOf(
            row(workoutId = "old", startedAt = 100, endedAt = 200, order = 0, weight = 60.0),
            row(workoutId = "abandoned", startedAt = 400, endedAt = null, order = 0, weight = 100.0),
        )
        val previous = ActiveWorkoutViewModel.latestEarlierWorkoutRows(rows, excludingWorkoutId = "mine")
        assertEquals(listOf(60.0), previous.map { it.weightKg })
    }

    @Test
    fun `a session with warm-ups only is not the previous one`() {
        val rows = listOf(
            row(workoutId = "old", startedAt = 100, endedAt = 200, order = 0, weight = 60.0),
            row(workoutId = "new", startedAt = 300, endedAt = 400, order = 0, weight = 20.0, kind = SetKind.Warmup),
        )
        val previous = ActiveWorkoutViewModel.latestEarlierWorkoutRows(rows, excludingWorkoutId = "mine")
        assertEquals(listOf(60.0), previous.map { it.weightKg })
    }

    @Test
    fun `warm-ups stay in the previous rows`() {
        val rows = listOf(
            row(workoutId = "old", startedAt = 100, endedAt = 200, order = 0, weight = 20.0, kind = SetKind.Warmup),
            row(workoutId = "old", startedAt = 100, endedAt = 200, order = 1, weight = 80.0),
        )
        val previous = ActiveWorkoutViewModel.latestEarlierWorkoutRows(rows, excludingWorkoutId = "mine")
        assertEquals(2, previous.size)
    }

    // MARK: - completedSetCount

    @Test
    fun `completed set count includes warm-ups`() {
        val state = ActiveWorkoutUiState(
            exercises = listOf(
                exercise(
                    id = 1,
                    sets = listOf(
                        setRow(id = 1, kind = SetKind.Warmup, completed = true),
                        setRow(id = 2, completed = true),
                        setRow(id = 3, completed = false),
                    ),
                ),
            ),
        )
        assertEquals(2, state.completedSetCount)
    }

    // MARK: - Fixtures

    private fun exercise(
        id: Long,
        done: Boolean = false,
        sets: List<SetRowUi> = emptyList(),
    ) = WorkoutExerciseUi(
        id = id,
        exerciseId = "Barbell_Bench_Press",
        name = "Bench",
        primaryMuscle = "chest",
        restSeconds = 90,
        setCount = sets.size,
        isDone = done,
        last = null,
        sets = sets,
    )

    private fun setRow(
        id: Long,
        kind: SetKind = SetKind.Normal,
        weight: Double = 0.0,
        reps: Int = 0,
        completed: Boolean = false,
        previous: SetValue? = null,
    ) = SetRowUi(
        id = id,
        order = id.toInt(),
        kind = kind,
        weightKg = weight,
        reps = reps,
        isCompleted = completed,
        number = id.toInt(),
        previous = previous,
        isCurrent = false,
    )

    private fun row(
        workoutId: String,
        startedAt: Long,
        endedAt: Long?,
        order: Int,
        weight: Double,
        kind: SetKind = SetKind.Normal,
    ) = CompletedSetRow(
        setId = order.toLong() + startedAt,
        setOrder = order,
        kind = kind,
        weightKg = weight,
        reps = 8,
        completedAt = startedAt + order,
        isPR = false,
        isSetRecord = false,
        rpe = null,
        workoutExerciseId = startedAt,
        workoutExerciseOrder = 0,
        exerciseId = "Barbell_Bench_Press",
        workoutId = workoutId,
        workoutName = "Push A",
        workoutStartedAt = startedAt,
        workoutEndedAt = endedAt,
    )
}
