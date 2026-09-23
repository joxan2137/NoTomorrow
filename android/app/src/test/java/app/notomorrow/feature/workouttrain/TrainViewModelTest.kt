package app.notomorrow.feature.workouttrain

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.feature.workout.TrainViewModel
import app.notomorrow.feature.workout.WorkoutStarter
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeRoutineDao
import app.notomorrow.service.FakeWorkoutDao
import app.notomorrow.service.WorkoutSessionController
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Train tab's start guard ("Workout in progress"): the action sheet dismisses itself before it
 * runs the tapped action, so "Discard it and start new" must work from the start it was shown for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelTest {

    private val workouts = FakeWorkoutDao()
    private val routines = FakeRoutineDao { id -> ExerciseEntity(id = id, name = id) }
    private val exercises = FakeExerciseDao(emptyList())
    private val profile = FakeProfileDao(UserProfileEntity(name = "Jan", defaultRestSeconds = 150))
    private val stores = WorkoutStarter.Stores(routines, workouts, exercises, profile)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.newSession() =
        WorkoutSessionController(
            object : WorkoutSessionController.Store {
                override suspend fun activeWorkoutId(): String? = null
                override suspend fun setActiveWorkoutId(id: String?) = Unit
                override suspend fun discarding(): Set<String> = emptySet()
                override suspend fun setDiscarding(ids: Set<String>) = Unit
            },
            workouts,
            backgroundScope,
        ).also { it.awaitRestored() }

    @Test
    fun `discard and start works after the sheet dismissed the prompt`() = runTest {
        workouts.seed("empty", startedAt = 1_000)
        val session = newSession()
        session.begin("empty")
        session.collapse()
        var restSkips = 0
        val model = TrainViewModel(stores, session, skipRest = { restSkips++ })

        model.startEmpty("Trening")
        val blocked = assertNotNull(model.blockedStart.value, "a start while a workout runs asks first")
        assertTrue(blocked.canDiscard)

        // `NtActionSheet` runs `onDismiss()` before the action's `onClick()`.
        model.dismissBlockedStart()
        model.discardActiveAndStart(blocked)
        advanceTimeBy(WorkoutSessionController.DISCARD_DELAY_MS + 1)
        runCurrent()

        assertNull(model.blockedStart.value)
        assertNull(workouts.workout("empty"), "the empty workout was discarded")
        val active = workouts.activeWorkouts()
        assertEquals(1, active.size, "exactly one unfinished workout")
        assertNotEquals("empty", active.single().id)
        assertEquals(active.single().id, session.activeWorkoutId.value)
        assertTrue(session.showsActiveWorkout.value, "the new workout opens full screen")
        assertEquals(1, restSkips)
    }

    @Test
    fun `cancel leaves the running workout alone`() = runTest {
        workouts.seed("empty", startedAt = 1_000)
        val session = newSession()
        session.begin("empty")
        session.collapse()
        val model = TrainViewModel(stores, session, skipRest = {})

        model.startEmpty("Trening")
        assertNotNull(model.blockedStart.value)
        model.dismissBlockedStart()
        runCurrent()

        assertNull(model.blockedStart.value)
        assertEquals(listOf("empty"), workouts.activeWorkouts().map { it.id })
        assertEquals("empty", session.activeWorkoutId.value)
    }
}
