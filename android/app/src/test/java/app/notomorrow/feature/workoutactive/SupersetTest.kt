package app.notomorrow.feature.workoutactive

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.feature.workout.RoutineDraft
import app.notomorrow.feature.workout.RoutineItemDraft
import app.notomorrow.feature.workout.RoutineStore
import app.notomorrow.feature.workout.Superset
import app.notomorrow.feature.workout.WorkoutStarter
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeRoutineDao
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.util.LocaleProvider
import io.mockk.verify
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

/**
 * Superset groups (link, unlink, normalize, letters) and the active workout moving between superset
 * exercises (`SupersetTests.swift`), plus the routine round trips that keep a superset: save and
 * reopen, start a workout, save a workout as a routine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupersetTest {

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

    // MARK: - Groups

    @Test
    fun `link, unlink and normalize`() {
        var groups: List<Int?> = listOf(null, null, null, null)
        groups = Superset.linkWithNext(groups, 0)
        assertEquals(listOf(1, 1, null, null), groups)
        groups = Superset.linkWithNext(groups, 2)
        assertEquals(listOf(1, 1, 2, 2), groups)
        groups = Superset.linkWithNext(groups, 1)
        assertEquals(listOf(1, 1, 1, 1), groups, "linking two supersets merges them")
        groups = Superset.unlink(groups, 3)
        assertEquals(listOf(1, 1, 1, null), groups)
        assertEquals(listOf(null, null, 1, 1, null), Superset.normalized(listOf(5, null, 7, 7, 5)), "lone members leave")
        assertEquals(listOf("A", "A", null, "B", "B"), Superset.letters(listOf(3, 3, null, 9, 9)))
        assertTrue(Superset.isLinkedToNext(listOf(1, 1, null), 0))
        assertFalse(Superset.isLinkedToNext(listOf(1, 1, null), 1))
    }

    @Test
    fun `routine draft keeps supersets together when edited`() {
        var draft = RoutineDraft("A", listOf("a", "b", "c").map { RoutineItemDraft.of(exerciseId = it, name = it) })
        draft = draft.linkingWithNext(draft.items[0].id)
        assertEquals(listOf("A", "A", null), draft.supersetLetters)
        draft = draft.moving(draft.items[1].id, 1)
        assertEquals(listOf(null, null, null), draft.items.map { it.supersetGroup }, "moved apart: no longer a superset")
        draft = draft.linkingWithNext(draft.items[1].id)
        draft = draft.removing(draft.items[2].id)
        assertEquals(listOf(null, null), draft.items.map { it.supersetGroup })
    }

    // MARK: - Active workout

    /** "bench" and "row" in superset 1, two open 60 × 10 sets each. */
    private suspend fun ActiveWorkoutHarness.supersetWorkout(): Pair<Long, Long> {
        val (bench, row) = seed(exerciseIds = listOf("bench", "row"), supersetGroup = 1)
        return bench to row
    }

    @Test
    fun `tick moves to the next superset exercise without rest`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val (bench, row) = harness.supersetWorkout()
        val model = harness.model()
        runCurrent()
        assertEquals("A", model.state.value.exercises.first().supersetLetter)

        model.complete(harness.workouts.sets(bench).first().id)
        runCurrent()

        assertEquals(row, model.state.value.expandedExerciseId)
        verify(exactly = 0) { harness.restTimer.start(any(), any(), any(), any()) }
    }

    @Test
    fun `round ends with rest and goes back to the first`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val (bench, row) = harness.supersetWorkout()
        val model = harness.model()
        runCurrent()
        model.complete(harness.workouts.sets(bench).first().id)
        runCurrent()
        model.complete(harness.workouts.sets(row).first().id)
        runCurrent()

        assertEquals(bench, model.state.value.expandedExerciseId)
        verify(exactly = 1) { harness.restTimer.start(90, any(), any(), any()) }
        val upNext = assertNotNull(model.state.value.upNext)
        assertEquals(2, upNext.setIndex, "aimed at bench's second set")
    }

    @Test
    fun `removing a member dissolves a two-exercise superset`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val (bench, row) = harness.supersetWorkout()
        val model = harness.model()
        runCurrent()
        model.removeExercise(row)
        runCurrent()
        assertNull(harness.workouts.workoutExercises("w").single { it.id == bench }.supersetGroup)
    }

    @Test
    fun `link and unlink from the exercise menu`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        harness.seed(exerciseIds = listOf("bench", "row", "curl"))
        val model = harness.model()
        runCurrent()
        val ids = model.state.value.exercises.map { it.id }
        assertEquals(listOf(true, true, false), model.state.value.exercises.map { it.canLinkNext })

        model.linkWithNext(ids[1])
        runCurrent()
        assertEquals(listOf(null, "A", "A"), model.state.value.exercises.map { it.supersetLetter })
        assertEquals(listOf(true, false, false), model.state.value.exercises.map { it.canLinkNext })

        model.unlinkSuperset(ids[2])
        runCurrent()
        assertEquals(listOf(null, null, null), harness.workouts.workoutExercises("w").map { it.supersetGroup })
    }

    // MARK: - Routines

    @Test
    fun `routines keep supersets through save, start and save as routine`() = runTest {
        val workouts = ActiveWorkoutHarness(backgroundScope).workouts
        val exercises = FakeExerciseDao(listOf("bench", "row", "curl").map { ExerciseEntity(id = it, name = it) })
        val routines = FakeRoutineDao { id -> exercises.rows.value.firstOrNull { it.id == id } }
        val store = RoutineStore(routines, exercises)

        var draft = RoutineDraft("A", listOf("bench", "row", "curl").map { RoutineItemDraft.of(exerciseId = it, name = it) })
        draft = draft.linkingWithNext(draft.items[1].id)
        val id = assertNotNull(store.save(draft, null))
        assertEquals(listOf(null, 1, 1), routines.items(id).map { it.supersetGroup })
        val reopened = RoutineStore.draft(of = assertNotNull(routines.routineWithItems(id)), locale = Locale.UK)
        assertEquals(listOf(null, "A", "A"), reopened.supersetLetters)

        val session = WorkoutSessionController(
            object : WorkoutSessionController.Store {
                override suspend fun activeWorkoutId(): String? = null
                override suspend fun setActiveWorkoutId(id: String?) = Unit
                override suspend fun discarding(): Set<String> = emptySet()
                override suspend fun setDiscarding(ids: Set<String>) = Unit
            },
            workouts,
            backgroundScope,
        ).also { it.awaitRestored() }
        val stores = WorkoutStarter.Stores(routines, workouts, exercises, FakeProfileDao(UserProfileEntity(name = "Jan")))
        val workoutId = assertNotNull(WorkoutStarter.start(stores, session, id))
        assertEquals(listOf(null, 1, 1), workouts.workoutExercises(workoutId).map { it.supersetGroup })

        // Log a set of each so every line is saved back, then save the workout as a routine.
        for (entry in workouts.workoutExercises(workoutId)) {
            val set = workouts.sets(entry.id).first()
            workouts.updateSet(set.copy(reps = 8, completedAt = 1L))
        }
        workouts.finishWorkout(workoutId, 2L)
        val graph = assertNotNull(workouts.workoutWithExercises(workoutId))
        val withExercises = graph.copy(
            exercises = graph.exercises.map { it.copy(exercise = exercises.rows.value.first { e -> e.id == it.workoutExercise.exerciseId }) },
        )
        val saved = RoutineStore.draft(from = withExercises, defaultRest = 90, takenNames = listOf("A"), locale = Locale.UK)
        assertEquals(listOf(null, "A", "A"), saved.supersetLetters)
    }

    @Test
    fun `a start drops a superset split by a deleted exercise`() = runTest {
        val workouts = ActiveWorkoutHarness(backgroundScope).workouts
        val exercises = FakeExerciseDao(listOf("bench", "curl").map { ExerciseEntity(id = it, name = it) })
        val routines = FakeRoutineDao { id -> exercises.rows.value.firstOrNull { it.id == id } }
        routines.insertRoutineWithItems(
            RoutineEntity(id = "r", name = "R", order = 0),
            listOf(
                RoutineItemEntity(routineId = "r", exerciseId = "bench", order = 0, supersetGroup = 1),
                RoutineItemEntity(routineId = "r", exerciseId = "gone", order = 1, supersetGroup = 1),
                RoutineItemEntity(routineId = "r", exerciseId = "curl", order = 2),
            ),
        )
        val session = WorkoutSessionController(
            object : WorkoutSessionController.Store {
                override suspend fun activeWorkoutId(): String? = null
                override suspend fun setActiveWorkoutId(id: String?) = Unit
                override suspend fun discarding(): Set<String> = emptySet()
                override suspend fun setDiscarding(ids: Set<String>) = Unit
            },
            workouts,
            backgroundScope,
        ).also { it.awaitRestored() }
        val stores = WorkoutStarter.Stores(routines, workouts, exercises, FakeProfileDao())
        val workoutId = assertNotNull(WorkoutStarter.start(stores, session, "r"))
        assertEquals(listOf(null, null), workouts.workoutExercises(workoutId).map { it.supersetGroup })
    }
}
