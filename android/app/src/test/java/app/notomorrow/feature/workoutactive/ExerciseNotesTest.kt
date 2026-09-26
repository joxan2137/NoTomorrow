package app.notomorrow.feature.workoutactive

import app.notomorrow.feature.workout.Rpe
import app.notomorrow.util.LocaleProvider
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

/** Exercise notes carry over as next session's placeholder; RPE is stored per set (`ExerciseNotesTests.swift`). */
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseNotesTest {

    private val day = 86_400_000L
    private val now = 100 * day

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

    /** A workout with "bench" started [daysAgo] days ago (finished an hour later), or the running one when `null`. */
    private suspend fun ActiveWorkoutHarness.workout(id: String, note: String, daysAgo: Int?): Long {
        val start = now - (daysAgo ?: 0) * day
        return seed(
            id = id,
            exerciseIds = listOf("bench"),
            startedAt = start,
            endedAt = daysAgo?.let { start + 3_600_000L },
            notes = note,
        ).single()
    }

    @Test
    fun `previous note is the newest non-empty one`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        harness.workout("a", "Seat 4", daysAgo = 7)
        harness.workout("b", "Seat 5, wide grip", daysAgo = 3)
        harness.workout("c", "  ", daysAgo = 1)
        harness.workout("w", "", daysAgo = null)
        val model = harness.model()
        runCurrent()
        assertEquals("Seat 5, wide grip", model.state.value.exercises.single().previousNote)
    }

    @Test
    fun `notes save as typed`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val entry = harness.workout("w", "", daysAgo = null)
        val model = harness.model()
        runCurrent()
        model.setNote(entry, "Seat 5")
        runCurrent()
        assertEquals("Seat 5", harness.workouts.workoutExercises("w").single().notes)
        assertEquals("Seat 5", model.state.value.exercises.single().notes)
    }

    @Test
    fun `set RPE stores and clears`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val entry = harness.workout("w", "", daysAgo = null)
        val set = harness.workouts.sets(entry).first().id
        val model = harness.model()
        runCurrent()
        model.setRpe(set, 8.5)
        runCurrent()
        assertEquals(8.5, harness.workouts.set(set)?.rpe)
        assertEquals(8.5, model.state.value.exercises.single().sets.first().rpe)
        model.setRpe(set, null)
        runCurrent()
        assertNull(harness.workouts.set(set)?.rpe)
        assertEquals(6.0, Rpe.options.first())
        assertEquals(10.0, Rpe.options.last())
        assertEquals(9, Rpe.options.size)
        assertEquals("8,5", Rpe.label(8.5, Locale.forLanguageTag("pl-PL")))
        assertEquals("8", Rpe.label(8.0, Locale.UK))
    }
}
