package app.notomorrow.feature.workouttrain

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.feature.workout.WorkoutStarter
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeRoutineDao
import app.notomorrow.service.FakeWorkoutDao
import app.notomorrow.service.WorkoutSessionController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The start path shared by Train and Today (`WorkoutStarterTests.swift`): rest from the Rest
 * length setting, target-reps prefill, the one-workout-at-a-time gate and "Discard it and start
 * new".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutStartFlowTest {

    private val bench = "Barbell_Bench_Press_-_Medium_Grip"
    private val pushdown = "Triceps_Pushdown"
    private val curl = "Barbell_Curl"

    private val workouts = FakeWorkoutDao()
    private val routines = FakeRoutineDao { id -> ExerciseEntity(id = id, name = id) }
    private val exercises = FakeExerciseDao(listOf(bench, pushdown, curl).map { ExerciseEntity(id = it, name = it) })
    private val profile = FakeProfileDao(UserProfileEntity(name = "Jan", defaultRestSeconds = 150))
    private val stores = WorkoutStarter.Stores(routines, workouts, exercises, profile)

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

    private suspend fun seedPush() {
        routines.insertRoutineWithItems(
            RoutineEntity(id = "push", name = "Push A", order = 0),
            listOf(
                RoutineItemEntity(routineId = "push", exerciseId = bench, order = 0, targetSets = 3, targetReps = 5, restSeconds = 0),
                RoutineItemEntity(routineId = "push", exerciseId = pushdown, order = 1, targetSets = 2, targetReps = 12, restSeconds = 0),
                RoutineItemEntity(routineId = "push", exerciseId = curl, order = 2, targetSets = 2, targetReps = 10, restSeconds = 75),
            ),
        )
    }

    @Test
    fun `a routine start takes rest from the setting and reps from the routine`() = runTest {
        seedPush()
        val session = newSession()

        val id = WorkoutStarter.start(WorkoutStarter.Request.Routine("push"), stores, session, now = 10_000)

        assertNotNull(id)
        assertEquals(id, session.activeWorkoutId.value)
        assertTrue(session.showsActiveWorkout.value)
        val rows = workouts.workoutExercises(id)
        assertEquals(listOf(bench, pushdown, curl), rows.map { it.exerciseId })
        assertEquals(listOf(180, 150, 75), rows.map { it.restSeconds }, "heavy +30, inherit, own value")
        assertEquals(listOf(5, 5, 5), workouts.sets(rows[0].id).map { it.reps })
        assertEquals(listOf(12, 12), workouts.sets(rows[1].id).map { it.reps })
        assertTrue(workouts.sets(rows[0].id).all { it.weightKg == 0.0 })
    }

    @Test
    fun `a routine start copies the last session row by row`() = runTest {
        seedPush()
        workouts.seed("last", startedAt = 1_000, endedAt = 5_000, exerciseId = bench, completed = 2, total = 2, weightKg = 82.5, reps = 6)
        val session = newSession()

        val id = WorkoutStarter.start(WorkoutStarter.Request.Routine("push"), stores, session, now = 10_000)!!

        val benchRow = workouts.workoutExercises(id).first { it.exerciseId == bench }
        assertEquals(listOf(82.5, 82.5, 82.5), workouts.sets(benchRow.id).map { it.weightKg })
        assertEquals(listOf(6, 6, 6), workouts.sets(benchRow.id).map { it.reps })
    }

    @Test
    fun `an exercise added mid-workout rests the default`() = runTest {
        workouts.seed("w", startedAt = 1_000, total = 0)

        val rowId = WorkoutStarter.append(
            workoutDao = workouts,
            exerciseDao = exercises,
            workoutId = "w",
            exerciseId = pushdown,
            order = 1,
            defaultRest = WorkoutStarter.defaultRestSeconds(profile),
        )

        assertEquals(150, workouts.exercises.value.first { it.id == rowId }.restSeconds)
    }

    @Test
    fun `the gate is clear with nothing running`() = runTest {
        val session = newSession()
        assertIs<WorkoutStarter.Gate.Clear>(WorkoutStarter.gate(workouts, session))
    }

    @Test
    fun `the gate blocks while a workout runs, discardable only when empty`() = runTest {
        workouts.seed("empty", startedAt = 1_000)
        val session = newSession()
        session.begin("empty")

        val empty = WorkoutStarter.gate(workouts, session)
        assertIs<WorkoutStarter.Gate.Blocked>(empty)
        assertEquals("empty", empty.active.id)
        assertTrue(empty.canDiscard)

        workouts.updateSetCompletion(workouts.sets.value.first().id, 2_000)
        val logged = WorkoutStarter.gate(workouts, session)
        assertIs<WorkoutStarter.Gate.Blocked>(logged)
        assertFalse(logged.canDiscard)
    }

    @Test
    fun `discard and start leaves exactly one unfinished workout`() = runTest {
        workouts.seed("empty", startedAt = 1_000)
        val session = newSession()
        session.begin("empty")
        session.collapse()
        val active = workouts.workout("empty")!!

        val id = WorkoutStarter.discardAndStart(active, WorkoutStarter.Request.Empty("Trening"), stores, session, now = 9_000)

        assertNotNull(id)
        assertNotEquals("empty", id)
        assertEquals(id, session.activeWorkoutId.value)
        assertTrue(session.showsActiveWorkout.value)
        advanceTimeBy(WorkoutSessionController.DISCARD_DELAY_MS + 1)
        runCurrent()
        assertNull(workouts.workout("empty"))
        assertEquals(listOf(id), workouts.activeWorkouts().map { it.id })
    }

    @Test
    fun `discard and start refuses a workout with a completed set and resumes it`() = runTest {
        workouts.seed("logged", startedAt = 1_000, completed = 1)
        val session = newSession()
        session.begin("logged")
        session.collapse()
        val active = workouts.workout("logged")!!

        val id = WorkoutStarter.discardAndStart(active, WorkoutStarter.Request.Empty("Trening"), stores, session)

        assertEquals("logged", id)
        assertEquals("logged", session.activeWorkoutId.value)
        assertTrue(session.showsActiveWorkout.value)
        assertEquals(1, workouts.workouts.value.size, "nothing new was started")
    }
}
