package app.notomorrow.feature.workouttrain

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.feature.workout.ExerciseEquipment
import app.notomorrow.service.ExerciseLibrary
import app.notomorrow.service.FakeExerciseDao
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The exercise picker's equipment chips (`ExerciseEquipmentTests.swift`): every library value
 * lands on a chip, and the filter combines with the muscle chips and the search.
 */
class ExerciseEquipmentTest {

    private fun exercise(id: String, name: String, muscle: String, equipment: String?) =
        ExerciseEntity(id = id, name = name, primaryMuscles = listOf(muscle), equipment = equipment)

    private val library = listOf(
        exercise("bb-bench", "Barbell Bench Press", "chest", "barbell"),
        exercise("db-bench", "Dumbbell Bench Press", "chest", "dumbbell"),
        exercise("db-curl", "Dumbbell Curl", "biceps", "dumbbell"),
        exercise("ez-curl", "EZ-Bar Curl", "biceps", "e-z curl bar"),
        exercise("pushup", "Pushups", "chest", "body only"),
        exercise("roll", "Chest Roll", "chest", null),
    )

    private fun ids(
        query: String = "",
        group: ExerciseLibrary.MuscleGroup = ExerciseLibrary.MuscleGroup.All,
        equipment: ExerciseEquipment,
    ): Set<String> =
        ExerciseEquipment.filter(ExerciseLibrary.filter(library, query, group), equipment).map { it.id }.toSet()

    @Test
    fun `every library value maps to a chip`() {
        assertEquals(ExerciseEquipment.Barbell, ExerciseEquipment.of("barbell"))
        assertEquals(ExerciseEquipment.Barbell, ExerciseEquipment.of("e-z curl bar"))
        assertEquals(ExerciseEquipment.Dumbbell, ExerciseEquipment.of("dumbbell"))
        assertEquals(ExerciseEquipment.Machine, ExerciseEquipment.of("machine"))
        assertEquals(ExerciseEquipment.Cable, ExerciseEquipment.of("cable"))
        assertEquals(ExerciseEquipment.Bodyweight, ExerciseEquipment.of("body only"))
        assertEquals(ExerciseEquipment.Kettlebell, ExerciseEquipment.of("kettlebells"))
        assertEquals(ExerciseEquipment.Band, ExerciseEquipment.of("bands"))
        for (raw in listOf("other", "medicine ball", "exercise ball", "foam roll", null)) {
            assertEquals(ExerciseEquipment.Other, ExerciseEquipment.of(raw), raw)
        }
        assertTrue(ExerciseEquipment.All.matches(null))
        assertFalse(ExerciseEquipment.Dumbbell.matches("barbell"))
        // A custom exercise created under a chip stays under it.
        for (chip in ExerciseEquipment.entries.filter { it != ExerciseEquipment.All }) {
            assertEquals(chip, ExerciseEquipment.of(chip.representative))
        }
        assertNull(ExerciseEquipment.All.representative)
    }

    @Test
    fun `every bundled equipment value is a known one`() {
        val file = File("src/main/assets/${ExerciseLibrary.EXERCISES_ASSET}")
        if (!file.exists()) return // running outside the module directory
        val json = Json { ignoreUnknownKeys = true }
        val values = json.decodeFromString<List<ExerciseLibrary.Record>>(file.readText()).map { it.equipment }.toSet()
        val known = setOf(
            "barbell", "e-z curl bar", "dumbbell", "machine", "cable", "body only", "kettlebells", "bands",
            "other", "medicine ball", "exercise ball", "foam roll", null,
        )
        // A new value would silently land under Other: map it on purpose.
        assertEquals(emptySet(), values - known)
    }

    @Test
    fun `equipment combines with the muscle chip and the search`() {
        val chest = ExerciseLibrary.MuscleGroup.Chest
        assertEquals(setOf("db-bench", "db-curl"), ids(equipment = ExerciseEquipment.Dumbbell))
        assertEquals(setOf("db-bench"), ids(group = chest, equipment = ExerciseEquipment.Dumbbell))
        assertEquals(setOf("bb-bench"), ids(group = chest, equipment = ExerciseEquipment.Barbell))
        // The EZ bar counts as a barbell.
        assertEquals(
            setOf("ez-curl"),
            ids(group = ExerciseLibrary.MuscleGroup.Arms, equipment = ExerciseEquipment.Barbell),
        )
        // No equipment is Other.
        assertEquals(setOf("roll"), ids(group = chest, equipment = ExerciseEquipment.Other))
        assertEquals(
            setOf("bb-bench", "db-bench", "pushup", "roll"),
            ids(group = chest, equipment = ExerciseEquipment.All),
        )
        assertEquals(setOf("db-bench"), ids(query = "bench", equipment = ExerciseEquipment.Dumbbell))
    }

    @Test
    fun `a custom exercise takes the chip's equipment`() = runBlocking {
        val dao = FakeExerciseDao()
        val library = ExerciseLibrary(dao, ExerciseLibrary.VersionStamp.InMemory()) { null }
        val created = library.createCustom(
            "Kettlebell Halo",
            equipment = ExerciseEquipment.Kettlebell.representative,
        )
        assertEquals("kettlebells", created?.equipment)
        assertEquals("kettlebells", dao.rows.value.single().equipment)
    }
}
