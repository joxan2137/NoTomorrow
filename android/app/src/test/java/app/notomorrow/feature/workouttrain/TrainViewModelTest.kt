package app.notomorrow.feature.workouttrain

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.feature.workout.HistoryWeek
import app.notomorrow.feature.workout.TrainViewModel
import app.notomorrow.feature.workout.WorkoutStarter
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeRoutineDao
import app.notomorrow.service.FakeWorkoutDao
import app.notomorrow.service.WorkoutSessionController
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
 * Also the Up next card's routine (Today's suggestion, left out of the list) and the history weeks.
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

    // MARK: - Up next

    @Test
    fun `up next is the routine after the last one done and the list holds the others`() = runTest {
        routines.insertRoutines(
            listOf(RoutineEntity("push", "Push A", order = 0), RoutineEntity("pull", "Pull A", order = 1), RoutineEntity("legs", "Legs", order = 2)),
        )
        routines.insertItems(
            listOf(RoutineItemEntity(routineId = "pull", exerciseId = "Pullups", order = 0, targetSets = 4, targetReps = 6)),
        )
        workouts.seed("done", startedAt = 1_000, endedAt = 2_000, name = "Push A", completed = 3)
        val session = newSession()
        val model = TrainViewModel(stores, session, skipRest = {})
        backgroundScope.launch { model.state.collect {} }
        runCurrent()

        val state = model.state.value
        assertEquals("pull", state.upNext?.id)
        assertEquals(listOf(Triple("Pullups", 4, 6)), state.upNext?.items?.map { Triple(it.exercise.id, it.targetSets, it.targetReps) })
        assertEquals(listOf("push", "legs"), state.routines.map { it.id }, "the up-next routine is not listed twice")
        assertTrue(state.hasRoutines)
        assertFalse(state.isWorkoutInProgress)

        workouts.seed("live", startedAt = 3_000, name = "Pull A")
        session.begin("live")
        runCurrent()
        assertTrue(model.state.value.isWorkoutInProgress, "the card offers Resume")
    }

    // MARK: - History weeks

    @Test
    fun `history groups by ISO week`() {
        val today = LocalDate.of(2026, 9, 24)   // Thursday
        assertEquals(HistoryWeek.ThisWeek, HistoryWeek.of(LocalDate.of(2026, 9, 21), today), "Monday opens the week")
        assertEquals(HistoryWeek.LastWeek, HistoryWeek.of(LocalDate.of(2026, 9, 20), today), "Sunday closes the one before")
        assertEquals(HistoryWeek.LastWeek, HistoryWeek.of(LocalDate.of(2026, 9, 14), today))
        assertEquals(HistoryWeek.Earlier, HistoryWeek.of(LocalDate.of(2026, 9, 13), today))

        val newestFirst = listOf(23, 21, 18, 2).map { LocalDate.of(2026, 9, it) }
        val groups = HistoryWeek.grouped(newestFirst, today) { it }
        assertEquals(listOf(HistoryWeek.ThisWeek, HistoryWeek.LastWeek, HistoryWeek.Earlier), groups.map { it.first })
        assertEquals(
            listOf(listOf(23, 21), listOf(18), listOf(2)),
            groups.map { (_, days) -> days.map { it.dayOfMonth } },
            "each keeps its order",
        )
        assertEquals(
            listOf(HistoryWeek.Earlier),
            HistoryWeek.grouped(listOf(LocalDate.of(2026, 9, 2)), today) { it }.map { it.first },
            "empty weeks are left out",
        )
    }
}
