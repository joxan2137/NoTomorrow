package app.notomorrow.feature.workouttrain

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.feature.workout.CustomExercises
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeWorkoutDao
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Editing and deleting a custom exercise from the picker (`CustomExerciseEditor`, the picker's context menu). */
class CustomExerciseEditTest {

    private val mine = ExerciseEntity(id = "custom-1", name = "Cable fly", primaryMuscles = listOf("chest"), isCustom = true)
    private val bundled = ExerciseEntity(id = "Barbell_Squat", name = "Barbell Squat")

    @Test
    fun `save trims the name and replaces muscle and equipment`() = runBlocking {
        val dao = FakeExerciseDao(listOf(mine))
        assertTrue(CustomExercises.save(dao, mine, "  Low cable fly ", muscle = null, equipment = "cable"))
        val row = dao.byId(mine.id)!!
        assertEquals("Low cable fly", row.name)
        assertEquals(emptyList(), row.primaryMuscles)
        assertEquals("cable", row.equipment)
        assertTrue(row.isCustom)
    }

    @Test
    fun `a blank name is not saved`() = runBlocking {
        val dao = FakeExerciseDao(listOf(mine))
        assertNull(CustomExercises.edited(mine, "   ", "chest", null))
        assertFalse(CustomExercises.save(dao, mine, "", "chest", null))
        assertEquals("Cable fly", dao.byId(mine.id)!!.name)
    }

    @Test
    fun `only an unused custom exercise can be deleted`() = runBlocking {
        val exercises = FakeExerciseDao(listOf(mine, bundled))
        val workouts = FakeWorkoutDao()
        assertTrue(CustomExercises.canDelete(mine, emptySet()))
        assertFalse(CustomExercises.canDelete(bundled, emptySet()), "the library stays")
        assertFalse(CustomExercises.canDelete(mine, setOf(mine.id)))

        workouts.insertWorkout(WorkoutEntity(id = "w", name = "Push", startedAt = 0))
        workouts.insertWorkoutExercise(WorkoutExerciseEntity(workoutId = "w", exerciseId = mine.id, order = 0))
        assertEquals(listOf(mine.id), workouts.observeUsedExerciseIds().first())
        assertFalse(CustomExercises.delete(exercises, workouts, mine), "a used exercise is kept")
        assertTrue(exercises.byId(mine.id) != null)

        workouts.deleteWorkoutById("w")
        assertTrue(CustomExercises.delete(exercises, workouts, mine))
        assertNull(exercises.byId(mine.id))
        assertFalse(CustomExercises.delete(exercises, workouts, bundled))
        assertTrue(exercises.byId(bundled.id) != null)
    }
}
