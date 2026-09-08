package app.notomorrow.service

import app.notomorrow.data.entity.ExerciseEntity
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The pure half of `Services/ExerciseLibrary.swift`: the muscle-group chips, the
 * picker's folding/ordering/filtering and `Exercise.localizedName`. The bundled
 * `assets/exercises.json` is parsed with the real schema so a library refresh that
 * changes a field fails here rather than on a device.
 */
class ExerciseLibraryTest {

    private fun exercise(
        id: String,
        name: String,
        namePL: String? = null,
        muscles: List<String> = emptyList(),
        lastUsedAt: Long? = null,
    ) = ExerciseEntity(
        id = id,
        name = name,
        namePL = namePL,
        primaryMuscles = muscles,
        lastUsedAt = lastUsedAt,
    )

    private val en = Locale.US
    private val pl = Locale.forLanguageTag("pl-PL")

    // MARK: Muscle groups

    @Test
    fun `muscle groups map onto free-exercise-db values exactly as iOS`() {
        assertTrue(ExerciseLibrary.MuscleGroup.All.muscles.isEmpty())
        assertEquals(setOf("chest"), ExerciseLibrary.MuscleGroup.Chest.muscles)
        assertEquals(
            setOf("lats", "middle back", "lower back", "traps"),
            ExerciseLibrary.MuscleGroup.Back.muscles,
        )
        assertEquals(
            setOf("quadriceps", "hamstrings", "glutes", "calves", "adductors", "abductors"),
            ExerciseLibrary.MuscleGroup.Legs.muscles,
        )
        assertEquals(setOf("shoulders"), ExerciseLibrary.MuscleGroup.Shoulders.muscles)
        assertEquals(setOf("biceps", "triceps", "forearms"), ExerciseLibrary.MuscleGroup.Arms.muscles)
        assertEquals(setOf("abdominals"), ExerciseLibrary.MuscleGroup.Core.muscles)
    }

    @Test
    fun `neck belongs to no chip`() {
        assertTrue(ExerciseLibrary.MuscleGroup.entries.none { "neck" in it.muscles })
    }

    @Test
    fun `raw values round-trip`() {
        for (group in ExerciseLibrary.MuscleGroup.entries) {
            assertEquals(group, ExerciseLibrary.MuscleGroup.from(group.raw))
        }
    }

    // MARK: Folding, sorting, filtering

    @Test
    fun `folding ignores case and diacritics`() {
        assertEquals(ExerciseLibrary.fold("Ławka"), ExerciseLibrary.fold("lawka"))
        assertEquals(ExerciseLibrary.fold("PRZYSIAD"), ExerciseLibrary.fold("przysiad"))
        assertTrue(ExerciseLibrary.fold("Wyciskanie sztangi").contains("sztangi"))
    }

    @Test
    fun `the picker sorts recently used first, then alphabetically`() {
        val list = listOf(
            exercise("c", "Curl"),
            exercise("a", "Ab Roller"),
            exercise("z", "Zercher Squat", lastUsedAt = 10),
        )
        assertEquals(listOf("z", "a", "c"), ExerciseLibrary.sorted(list).map { it.id })
    }

    @Test
    fun `every query token has to match, across both languages`() {
        val list = listOf(
            exercise("bench", "Barbell Bench Press", namePL = "Wyciskanie sztangi leżąc"),
            exercise("squat", "Barbell Squat", namePL = "Przysiad ze sztangą"),
        )
        assertEquals(listOf("bench"), ExerciseLibrary.filter(list, "bench press").map { it.id })
        assertEquals(listOf("bench"), ExerciseLibrary.filter(list, "wyciskanie").map { it.id })
        assertEquals(listOf("squat"), ExerciseLibrary.filter(list, "PRZYSIAD").map { it.id })
        assertEquals(2, ExerciseLibrary.filter(list, "barbell").size)
        assertTrue(ExerciseLibrary.filter(list, "bench squat").isEmpty())
        assertEquals(2, ExerciseLibrary.filter(list, "   ").size)
    }

    @Test
    fun `the muscle chip filters on primary muscles only`() {
        val list = listOf(
            exercise("bench", "Bench Press", muscles = listOf("chest")),
            exercise("row", "Barbell Row", muscles = listOf("middle back")),
            exercise("curl", "Curl", muscles = listOf("biceps")),
        )
        assertEquals(3, ExerciseLibrary.filter(list, "", ExerciseLibrary.MuscleGroup.All).size)
        assertEquals(listOf("row"), ExerciseLibrary.filter(list, "", ExerciseLibrary.MuscleGroup.Back).map { it.id })
        assertEquals(listOf("curl"), ExerciseLibrary.filter(list, "", ExerciseLibrary.MuscleGroup.Arms).map { it.id })
        assertTrue(ExerciseLibrary.filter(list, "row", ExerciseLibrary.MuscleGroup.Chest).isEmpty())
    }

    // MARK: localizedName

    @Test
    fun `the Polish name is used under a Polish locale only`() {
        val row = exercise("bench", "Barbell Bench Press", namePL = "Wyciskanie sztangi leżąc")
        assertEquals("Wyciskanie sztangi leżąc", row.localizedName(pl))
        assertEquals("Barbell Bench Press", row.localizedName(en))
    }

    @Test
    fun `an empty or missing Polish name falls back to English`() {
        assertEquals("Curl", exercise("c", "Curl").localizedName(pl))
        assertEquals("Curl", exercise("c", "Curl", namePL = "").localizedName(pl))
    }

    // MARK: The bundled asset

    @Test
    fun `the bundled library parses with the record schema`() {
        val file = File("src/main/assets/${ExerciseLibrary.EXERCISES_ASSET}")
        if (!file.exists()) return // running outside the module directory
        val json = Json { ignoreUnknownKeys = true }
        val records = json.decodeFromString<List<ExerciseLibrary.Record>>(file.readText())
        assertEquals(900, records.size)
        assertTrue(records.all { it.id.isNotEmpty() && it.name.isNotEmpty() })

        val plFile = File("src/main/assets/${ExerciseLibrary.EXERCISES_PL_ASSET}")
        if (!plFile.exists()) return
        val polish = json.decodeFromString<Map<String, String>>(plFile.readText())
        assertEquals(records.size, polish.size)
        assertNotNull(polish[records.first().id])

        // Every seeded routine id must exist in the library, or the seeder silently skips it.
        val ids = records.map { it.id }.toSet()
        val missing = RoutineSeeder.TEMPLATES.flatMap { it.exerciseIds }.filterNot { it in ids }
        assertEquals(emptyList(), missing)
    }
}
