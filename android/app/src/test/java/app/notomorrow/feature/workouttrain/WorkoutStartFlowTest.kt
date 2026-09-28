package app.notomorrow.feature.workouttrain

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.feature.workout.WorkoutStarter
import app.notomorrow.model.SetKind
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

    private suspend fun TestScope.newSession(dao: FakeWorkoutDao = workouts) =
        WorkoutSessionController(
            object : WorkoutSessionController.Store {
                override suspend fun activeWorkoutId(): String? = null
                override suspend fun setActiveWorkoutId(id: String?) = Unit
                override suspend fun discarding(): Set<String> = emptySet()
                override suspend fun setDiscarding(ids: Set<String>) = Unit
            },
            dao,
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
    fun `copy workout starts the logged sets again, none of them done`() = runTest {
        val dao = FakeWorkoutDao { id -> if (id == "gone") null else ExerciseEntity(id = id, name = id) }
        val copyStores = WorkoutStarter.Stores(routines, dao, exercises, profile)
        dao.insertWorkout(WorkoutEntity(id = "legs", name = "Legs", startedAt = 1_000, endedAt = 5_000))
        dao.insertWorkoutExerciseWithSets(
            WorkoutExerciseEntity(workoutId = "legs", exerciseId = bench, order = 0, restSeconds = 150, notes = "Belt on"),
            listOf(
                SetEntryEntity(workoutExerciseId = 0, order = 0, kind = SetKind.Warmup, weightKg = 60.0, reps = 5, completedAt = 1_100),
                SetEntryEntity(workoutExerciseId = 0, order = 1, weightKg = 100.0, reps = 5, completedAt = 1_200, isPR = true),
                SetEntryEntity(workoutExerciseId = 0, order = 2, weightKg = 100.0, reps = 4),
            ),
        )
        dao.insertWorkoutExerciseWithSets(
            WorkoutExerciseEntity(workoutId = "legs", exerciseId = "gone", order = 1, supersetGroup = 1),
            listOf(SetEntryEntity(workoutExerciseId = 0, order = 0, weightKg = 20.0, reps = 10, completedAt = 1_300)),
        )
        dao.insertWorkoutExerciseWithSets(
            WorkoutExerciseEntity(workoutId = "legs", exerciseId = curl, order = 2, restSeconds = 60, supersetGroup = 1),
            listOf(SetEntryEntity(workoutExerciseId = 0, order = 0, weightKg = 30.0, reps = 12)),
        )
        val session = newSession(dao)

        assertTrue(WorkoutStarter.canCopy(dao.workoutWithExercises("legs")!!))
        val id = WorkoutStarter.start(WorkoutStarter.Request.Copy("legs"), copyStores, session, now = 10_000)!!

        assertNotEquals("legs", id)
        assertEquals(id, session.activeWorkoutId.value)
        val copy = dao.workoutWithExercises(id)!!
        assertEquals("Legs", copy.workout.name)
        assertNull(copy.workout.endedAt)
        val rows = dao.workoutExercises(id)
        assertEquals(listOf(bench, curl), rows.map { it.exerciseId }, "an exercise gone from the library is left out")
        assertEquals(listOf(150, 60), rows.map { it.restSeconds })
        assertEquals(listOf<Int?>(null, null), rows.map { it.supersetGroup }, "a superset left alone is no superset")
        assertEquals("Belt on", rows[0].notes)
        val benchSets = dao.sets(rows[0].id)
        assertEquals(listOf(SetKind.Warmup, SetKind.Normal), benchSets.map { it.kind }, "the sets it logged")
        assertEquals(listOf(60.0, 100.0), benchSets.map { it.weightKg })
        assertEquals(listOf(5, 5), benchSets.map { it.reps })
        assertEquals(listOf(12), dao.sets(rows[1].id).map { it.reps }, "nothing done: the rows it had")
        assertTrue((benchSets + dao.sets(rows[1].id)).none { it.isCompleted || it.isPR })
        assertEquals(10_000L, exercises.byId(bench)?.lastUsedAt)
        val sourceRows = dao.workoutExercises("legs").map { it.id }
        assertEquals(3, dao.sets.value.count { it.isCompleted && it.workoutExerciseId in sourceRows }, "the source is left as it was")
    }

    @Test
    fun `copying a workout that is gone starts nothing`() = runTest {
        val session = newSession()
        assertNull(WorkoutStarter.start(WorkoutStarter.Request.Copy("nope"), stores, session))
        assertNull(session.activeWorkoutId.value)
    }

    @Test
    fun `copied rows fall back to every row, then to one empty row`() {
        val open = listOf(SetEntryEntity(workoutExerciseId = 7, order = 0, weightKg = 30.0, reps = 12))
        assertEquals(listOf(12), WorkoutStarter.copiedSets(open).map { it.reps })
        val empty = WorkoutStarter.copiedSets(emptyList())
        assertEquals(1, empty.size)
        assertEquals(SetKind.Normal, empty.single().kind)
        assertEquals(0L, empty.single().workoutExerciseId)
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
