package app.notomorrow.feature.workoutactive

import app.notomorrow.feature.workout.PreviousRows
import app.notomorrow.feature.workout.SetValue
import app.notomorrow.feature.workout.replacementSetValues
import app.notomorrow.model.SetKind
import app.notomorrow.util.LocaleProvider
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The active workout's exercise menu: Replace exercise (`ActiveWorkoutEditTests.swift`). */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWorkoutEditTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        LocaleProvider.override = { Locale.UK }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LocaleProvider.override = null
    }

    // MARK: - Replace

    @Test
    fun `replacement rows take the new exercise's previous or start empty`() {
        val previous = PreviousRows.of(emptyList()).copy(
            warmups = listOf(SetValue(20.0, 10)),
            working = listOf(SetValue(40.0, 12)),
        )
        val kinds = listOf(SetKind.Warmup, SetKind.Normal, SetKind.Normal)
        assertEquals(
            listOf(SetValue(20.0, 10), SetValue(40.0, 12), SetValue(42.5, 8)),
            replacementSetValues(kinds, previous, last = SetValue(42.5, 8)),
        )
        assertEquals(List(3) { SetValue(0.0, 0) }, replacementSetValues(kinds, null, null), "never done: empty rows")
    }

    @Test
    fun `replace keeps the slot, superset and rest and clears the numbers`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val ids = harness.seed(exerciseIds = listOf("bench", "row"), supersetGroup = 1, notes = "seat 4")
        val model = harness.model()
        runCurrent()
        assertTrue(model.state.value.exercises[1].canReplace)
        val before = harness.workouts.workoutExercises("w")[1]

        model.replaceExercise(ids[1], "pulldown")
        runCurrent()

        val rows = harness.workouts.workoutExercises("w")
        assertEquals(listOf("bench", "pulldown"), rows.map { it.exerciseId })
        assertEquals(before.copy(exerciseId = "pulldown", notes = ""), rows[1], "same id, order, superset and rest")
        assertEquals(listOf(1, 1), rows.map { it.supersetGroup })
        val sets = harness.workouts.sets(ids[1])
        assertEquals(2, sets.size, "open rows stay")
        assertTrue(sets.all { it.weightKg == 0.0 && it.reps == 0 && !it.isCompleted })
        assertEquals(listOf("A", "A"), model.state.value.exercises.map { it.supersetLetter })
    }

    @Test
    fun `replace prefills from the new exercise's previous session`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        harness.seed(id = "old", exerciseIds = listOf("incline"), startedAt = 0L, endedAt = 100L)
        val ids = harness.seed(exerciseIds = listOf("bench"), startedAt = 1_000L)
        harness.workouts.sets(ids[0]).forEach { harness.workouts.updateSet(it.copy(weightKg = 99.0, reps = 3)) }
        val model = harness.model()
        runCurrent()

        model.replaceExercise(ids[0], "incline")
        runCurrent()

        assertEquals(listOf(60.0 to 10, 60.0 to 10), harness.workouts.sets(ids[0]).map { it.weightKg to it.reps })
    }

    @Test
    fun `replace is blocked once a set is completed`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val ids = harness.seed(exerciseIds = listOf("bench", "row"))
        val first = harness.workouts.sets(ids[0]).first()
        harness.workouts.updateSet(first.copy(completedAt = 5L))
        val model = harness.model()
        runCurrent()

        assertFalse(model.state.value.exercises[0].canReplace)
        assertTrue(model.state.value.exercises[1].canReplace)
        model.replaceExercise(ids[0], "dips")
        runCurrent()

        assertEquals("bench", harness.workouts.workoutExercises("w")[0].exerciseId)
        assertEquals(listOf(60.0, 60.0), harness.workouts.sets(ids[0]).map { it.weightKg }, "nothing touched")
    }

    @Test
    fun `replace with the same exercise does nothing`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val ids = harness.seed(exerciseIds = listOf("bench"))
        val model = harness.model()
        runCurrent()

        model.replaceExercise(ids[0], "bench")
        runCurrent()

        assertEquals(listOf(10, 10), harness.workouts.sets(ids[0]).map { it.reps })
    }
}
