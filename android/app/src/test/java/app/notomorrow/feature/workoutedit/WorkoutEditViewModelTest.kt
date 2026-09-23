package app.notomorrow.feature.workoutedit

import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.feature.workout.WorkoutEditViewModel
import app.notomorrow.feature.workout.WorkoutEditor
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.FakeAttendanceDao
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeScheduleDao
import app.notomorrow.service.FakeWorkoutDao
import app.notomorrow.service.RecordService
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
 * The detail sheet's model (`WorkoutEditModel`): the workout on show, the draft's lifecycle
 * (begin, dirty, save, cancel), Add exercise from the picker, and Delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutEditViewModelTest {

    private val zone = ZoneOffset.UTC
    private val now = 1_790_000_000_000L
    private val day = 86_400_000L

    private val workouts = FakeWorkoutDao()
    private val exercises = FakeExerciseDao(
        listOf(ExerciseEntity(id = BENCH, name = "Bench"), ExerciseEntity(id = SQUAT, name = "Squat", primaryMuscles = listOf("quadriceps"))),
    )
    private val stores = WorkoutEditor.Stores(
        workoutDao = workouts,
        exerciseDao = exercises,
        recordService = RecordService(workouts),
        attendanceService = AttendanceService(FakeAttendanceDao(), FakeScheduleDao(GymScheduleEntity()), FakeProfileDao(), zone),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model() = WorkoutEditViewModel(stores, FakeProfileDao(), zone, { Locale.UK }, { now })

    private suspend fun finished(id: String, start: Long, exerciseId: String, sets: List<Pair<Double, Int>>) {
        workouts.insertWorkout(WorkoutEntity(id = id, name = "Push A", startedAt = start, endedAt = start + 3_600_000))
        val entry = workouts.insertWorkoutExercise(WorkoutExerciseEntity(workoutId = id, exerciseId = exerciseId, order = 0))
        sets.forEachIndexed { index, (kg, reps) ->
            workouts.insertSet(
                SetEntryEntity(workoutExerciseId = entry, order = index, weightKg = kg, reps = reps, completedAt = start + (index + 1) * 60_000L),
            )
        }
    }

    @Test
    fun `a draft starts clean, gets dirty, saves and goes back to reading`() = runTest {
        finished("w", now - day, BENCH, listOf(80.0 to 5))
        val model = model()
        model.show("w")
        runCurrent()
        assertEquals("w", model.shown.value?.workout?.id)

        model.beginEditing()
        runCurrent()
        val edit = assertNotNull(model.edit.value)
        assertFalse(edit.isDirty)
        assertFalse(edit.canSave(now), "nothing to save yet")

        model.setName("Push B")
        assertTrue(model.edit.value!!.canSave(now))
        var saved = false
        model.save { saved = true }
        runCurrent()

        assertTrue(saved)
        assertNull(model.edit.value, "back to read mode")
        assertEquals("Push B", workouts.workout("w")?.name)
        assertEquals("Push B", model.shown.value?.workout?.name, "the sheet follows the save")
    }

    @Test
    fun `cancel drops the draft and a refused tick reports it`() = runTest {
        finished("w", now - day, BENCH, listOf(80.0 to 5))
        val model = model()
        model.show("w")
        model.beginEditing()
        runCurrent()
        val exercise = model.edit.value!!.draft.exercises.single()

        model.addSet(exercise.id)
        val added = model.edit.value!!.draft.exercises.single().sets.last()
        assertTrue(added.id < 0, "an unsaved row has a temporary id")
        model.setReps(exercise.id, added.id, 0)
        assertFalse(model.toggleDone(exercise.id, added.id), "no reps: the tick is refused")

        model.endEditing()
        assertNull(model.edit.value)
        assertEquals(1, workouts.sets.value.size, "nothing reached the store")
    }

    @Test
    fun `an exercise added from the picker gets its last finished session's first working set`() = runTest {
        finished("legs", now - 5 * day, SQUAT, listOf(100.0 to 5, 105.0 to 3))
        finished("w", now - day, BENCH, listOf(80.0 to 5))
        val model = model()
        model.show("w")
        model.beginEditing()
        runCurrent()

        model.append(listOf(SQUAT, BENCH))
        runCurrent()

        val draft = model.edit.value!!.draft
        assertEquals(listOf(BENCH, SQUAT), draft.exercises.map { it.exerciseId }, "an exercise already in is not added twice")
        val squat = draft.exercises.last()
        assertEquals(listOf(100.0 to 5), squat.sets.map { it.weightKg to it.reps })
        assertTrue(squat.sets.single().isLogged)
        assertEquals(120, squat.restSeconds)
        assertEquals("Squat", squat.name)
    }

    @Test
    fun `delete removes the workout and showing another drops the edit`() = runTest {
        finished("a", now - 2 * day, BENCH, listOf(80.0 to 5))
        finished("b", now - day, BENCH, listOf(85.0 to 5))
        val model = model()
        model.show("a")
        model.beginEditing()
        runCurrent()

        model.show("b")
        assertNull(model.edit.value)

        model.show(null)
        model.delete("a")
        runCurrent()
        assertNull(workouts.workout("a"))
        assertTrue(workouts.sets.value.single().isPR, "b is now the first record")
    }

    /** Workout reads that wait for [gate] once it is closed: a slow load of the editor's draft. */
    private class SlowReads(private val inner: FakeWorkoutDao) : WorkoutDao by inner {
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun workoutWithExercises(id: String): WorkoutWithExercises? {
            gate?.await()
            return inner.workoutWithExercises(id)
        }
    }

    @Test
    fun `a draft still loading when the sheet closes never comes back with it`() = runTest {
        finished("w", now - day, BENCH, listOf(80.0 to 5))
        val reads = SlowReads(workouts)
        val model = WorkoutEditViewModel(
            WorkoutEditor.Stores(reads, exercises, RecordService(workouts), stores.attendanceService),
            FakeProfileDao(),
            zone,
            { Locale.UK },
            { now },
        )
        model.show("w")
        runCurrent()

        val gate = CompletableDeferred<Unit>().also { reads.gate = it }
        model.beginEditing() // Edit, and the sheet is swiped away while the workout is read…
        model.show(null)
        model.show("w") // …then opened again on the same workout.
        gate.complete(Unit)
        runCurrent()

        assertNull(model.edit.value, "the sheet opens reading, not in the old sheet's editor")

        // A load that finishes while its sheet is up does open the editor.
        model.beginEditing()
        runCurrent()
        assertNotNull(model.edit.value)
    }

    @Test
    fun `a sheet opened again starts reading, and the same sheet recomposed keeps its edit`() = runTest {
        finished("w", now - day, BENCH, listOf(80.0 to 5))
        val model = model()
        model.show("w")
        model.beginEditing()
        runCurrent()
        model.setName("Push B")

        model.show("w")
        assertEquals("Push B", model.edit.value?.draft?.name, "the sheet recomposed: the edit stays")

        model.show(null)
        model.show("w")
        assertNull(model.edit.value, "a fresh presentation starts in read mode")
    }

    private companion object {
        const val BENCH = "Barbell_Bench_Press_-_Medium_Grip"
        const val SQUAT = "Barbell_Squat"
    }
}
