package app.notomorrow.service

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `Services/RoutineSeeder.swift`: the three starter routines, seeded once, from whatever
 * of the fifteen ids the library actually has.
 */
class RoutineSeederTest {

    private val allIds = RoutineSeeder.TEMPLATES.flatMap { it.exerciseIds }

    private fun library(ids: List<String> = allIds) =
        FakeExerciseDao(ids.map { ExerciseEntity(id = it, name = it.replace('_', ' ')) })

    @Test
    fun `the templates are Push A, Pull A and Legs with five exercises each`() {
        assertEquals(listOf("Push A", "Pull A", "Legs"), RoutineSeeder.TEMPLATES.map { it.name })
        assertTrue(RoutineSeeder.TEMPLATES.all { it.exerciseIds.size == 5 })
        assertEquals(15, allIds.size)
        assertEquals(15, allIds.toSet().size)
    }

    @Test
    fun `seeding writes three ordered routines with their items in template order`() = runBlocking {
        val routines = FakeRoutineDao()
        RoutineSeeder(routines, library()).seedIfNeeded()

        assertEquals(listOf(0, 1, 2), routines.routines.value.map { it.order }.sorted())
        assertEquals(
            listOf("Push A", "Pull A", "Legs"),
            routines.routines.value.sortedBy { it.order }.map { it.name },
        )
        assertEquals(15, routines.items.value.size)

        val push = routines.routines.value.first { it.name == "Push A" }
        val items = routines.items(push.id)
        assertEquals(RoutineSeeder.TEMPLATES[0].exerciseIds, items.map { it.exerciseId })
        assertEquals(listOf(0, 1, 2, 3, 4), items.map { it.order })
        assertTrue(items.all { it.targetSets == 3 && it.targetReps == 8 })
        assertTrue(items.all { it.restSeconds == RoutineSeeder.INHERIT_REST }, "rest follows the setting")
    }

    @Test
    fun `squat, deadlift and bench variants rest longer`() {
        assertEquals(120, RoutineSeeder.restSeconds("Barbell_Squat"))
        assertEquals(120, RoutineSeeder.restSeconds("Romanian_Deadlift"))
        assertEquals(120, RoutineSeeder.restSeconds("Barbell_Bench_Press_-_Medium_Grip"))
        assertEquals(90, RoutineSeeder.restSeconds("Triceps_Pushdown"))
        assertEquals(90, RoutineSeeder.restSeconds("Leg_Press"))
    }

    @Test
    fun `heavy lifts rest 30 s over the default, capped at 10 min`() {
        assertEquals(180, RoutineSeeder.restSeconds("Barbell_Squat", defaultRest = 150))
        assertEquals(150, RoutineSeeder.restSeconds("Leg_Press", defaultRest = 150))
        assertEquals(600, RoutineSeeder.restSeconds("Barbell_Deadlift", defaultRest = 600))
        assertEquals(45, RoutineSeeder.restSeconds("Barbell_Bench_Press_-_Medium_Grip", defaultRest = 15))
    }

    @Test
    fun `legacy seeded rests switch to inherit, custom ones stay`() = runBlocking {
        val routines = FakeRoutineDao()
        routines.insertRoutineWithItems(
            RoutineEntity(id = "push", name = "Push A", order = 0),
            listOf(
                RoutineItemEntity(routineId = "push", exerciseId = "Barbell_Bench_Press_-_Medium_Grip", order = 0, restSeconds = 120),
                RoutineItemEntity(routineId = "push", exerciseId = "Triceps_Pushdown", order = 1, restSeconds = 90),
                RoutineItemEntity(routineId = "push", exerciseId = "Side_Lateral_Raise", order = 2, restSeconds = 75),
                RoutineItemEntity(routineId = "push", exerciseId = "Barbell_Shoulder_Press", order = 3, restSeconds = 120),
                RoutineItemEntity(routineId = "push", exerciseId = null, order = 4, restSeconds = 90),
            ),
        )

        val changed = RoutineSeeder(routines, library()).inheritDefaultRest()

        assertEquals(2, changed)
        assertEquals(listOf(0, 0, 75, 120, 90), routines.items("push").map { it.restSeconds })
        assertEquals(0, RoutineSeeder(routines, library()).inheritDefaultRest(), "a second pass finds nothing")
    }

    @Test
    fun `missing ids are skipped, not stubbed`() = runBlocking {
        val routines = FakeRoutineDao()
        val partial = allIds.filterNot { it == "Pullups" || it == "Leg_Press" }
        RoutineSeeder(routines, library(partial)).seedIfNeeded()

        assertEquals(13, routines.items.value.size)
        val pull = routines.routines.value.first { it.name == "Pull A" }
        val items = routines.items(pull.id)
        assertEquals(listOf(0, 1, 2, 3), items.map { it.order }) // orders stay contiguous
        assertTrue(items.none { it.exerciseId == "Pullups" })
    }

    @Test
    fun `nothing is written when the library has not been imported yet`() = runBlocking {
        val routines = FakeRoutineDao()
        RoutineSeeder(routines, FakeExerciseDao()).seedIfNeeded()
        assertTrue(routines.routines.value.isEmpty())
        assertTrue(routines.items.value.isEmpty())
    }

    @Test
    fun `seeding is a no-op once a routine exists`() = runBlocking {
        val routines = FakeRoutineDao()
        routines.insertRoutine(RoutineEntity(id = "mine", name = "My split", order = 0))
        RoutineSeeder(routines, library()).seedIfNeeded()
        assertEquals(1, routines.routines.value.size)
        assertTrue(routines.items.value.isEmpty())
    }

    @Test
    fun `the elapsed helper never goes negative`() {
        assertEquals(90.0, WorkoutSessionController.elapsedSeconds(1_000_000, 1_090_000), 0.0)
        assertEquals(0.0, WorkoutSessionController.elapsedSeconds(1_000_000, 999_000), 0.0)
    }
}
