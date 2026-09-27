package app.notomorrow.feature.workoutactive

import app.notomorrow.feature.workout.WorkoutRest
import app.notomorrow.util.LocaleProvider
import io.mockk.verify
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/** The exercise menu's Rest timer: its options, the single check, and what a pick stores (`WorkoutRestTests.swift`). */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutRestTest {

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

    // MARK: Options

    @Test
    fun `default comes first then the routine lengths`() {
        val options = WorkoutRest.options(current = 90, defaultSeconds = 90)
        assertEquals(WorkoutRest.Option(90, isDefault = true), options.first())
        assertEquals(listOf(30, 45, 60, 75, 90, 120, 150, 180, 240, 300), options.drop(1).map { it.seconds })
        assertFalse(options.drop(1).any { it.isDefault })
    }

    @Test
    fun `an odd current length gets its own row in order`() {
        val options = WorkoutRest.options(current = 100, defaultSeconds = 90)
        assertEquals(listOf(30, 45, 60, 75, 90, 100, 120, 150, 180, 240, 300), options.drop(1).map { it.seconds })
        // The default is not duplicated when it is itself an odd length.
        assertEquals(1, WorkoutRest.options(current = 130, defaultSeconds = 130).count { it.seconds == 130 })
    }

    @Test
    fun `exactly one option is checked`() {
        fun checked(current: Int, defaultSeconds: Int) = WorkoutRest.options(current, defaultSeconds)
            .filter { WorkoutRest.isChecked(it, current, defaultSeconds) }
        // The default length ticks Default, not the 1:30 row.
        assertEquals(listOf(WorkoutRest.Option(90, isDefault = true)), checked(90, 90))
        assertEquals(listOf(WorkoutRest.Option(60, isDefault = false)), checked(60, 90))
        assertEquals(listOf(WorkoutRest.Option(100, isDefault = false)), checked(100, 90))
    }

    // MARK: Model

    @Test
    fun `default follows the rest setting and heavy lifts`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        harness.seed(exerciseIds = listOf("Dumbbell_Curl", "Barbell_Squat"))
        val model = harness.model(defaultRest = 120)
        runCurrent()
        // Heavy compounds rest 30 s longer.
        assertEquals(listOf(120, 150), model.state.value.exercises.map { it.defaultRestSeconds })
    }

    @Test
    fun `a pick is stored on this workout exercise and the next rest counts it down`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val entry = harness.seed(exerciseIds = listOf("Dumbbell_Curl")).single()
        val model = harness.model()
        runCurrent()
        model.setRest(entry, 45)
        runCurrent()
        assertEquals(45, harness.workouts.workoutExercises("w").single().restSeconds)
        assertEquals(45, model.state.value.exercises.single().restSeconds)
        // A zero length is ignored (the timer never runs shorter than 5 s).
        model.setRest(entry, 0)
        runCurrent()
        assertEquals(45, model.state.value.exercises.single().restSeconds)

        model.complete(harness.workouts.sets(entry).first().id)
        runCurrent()
        verify(exactly = 1) { harness.restTimer.start(45, any(), any(), any()) }
    }
}
