package app.notomorrow.service

import app.notomorrow.data.entity.ExerciseEntity
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `an exact name in either language, filtered out or not, means no duplicate Create row`() {
        val list = listOf(exercise("belt", "Belt Squat", namePL = "Przysiad z pasem", muscles = listOf("quadriceps")))
        assertTrue(ExerciseLibrary.hasName(list, "belt squat "))
        assertTrue(ExerciseLibrary.hasName(list, "PRZYSIAD Z PASEM"))
        assertFalse(ExerciseLibrary.hasName(list, "belt"))
        assertFalse(ExerciseLibrary.hasName(list, "  "))
    }

    @Test
    fun `folding flattens every Polish letter, the stroked l included, whatever the phone's locale`() {
        assertEquals("acelnoszz acelnoszz", ExerciseLibrary.fold("ąćęłńóśźż ĄĆĘŁŃÓŚŹŻ"))
        val turkish = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("lydki i wioslowanie", ExerciseLibrary.fold("ŁYDKI I WIOSŁOWANIE"))
        } finally {
            Locale.setDefault(turkish)
        }
    }

    @Test
    fun `Polish names are found typed without diacritics`() {
        val list = listOf(
            exercise("bench", "Barbell Bench Press - Medium Grip", namePL = "Wyciskanie sztangi na ławce płaskiej"),
            exercise("row", "Alternating Kettlebell Row", namePL = "Naprzemienne wiosłowanie kettlebell"),
            exercise("calf", "Standing Calf Raises", namePL = "Wspięcia na łydki stojąc"),
        )
        assertEquals(listOf("row"), ExerciseLibrary.filter(list, "wioslowanie").map { it.id })
        assertEquals(listOf("bench"), ExerciseLibrary.filter(list, "lawce plaskiej").map { it.id })
        assertEquals(listOf("bench"), ExerciseLibrary.filter(list, "WYCISKANIE ławce").map { it.id })
        assertEquals(listOf("calf"), ExerciseLibrary.filter(list, "lydki").map { it.id })
    }

    // MARK: Import, once per library version

    private fun bundled(vararg ids: String, polish: Map<String, String> = emptyMap()) =
        ExerciseLibrary.Bundled(ids.map { ExerciseLibrary.Record(id = it, name = it.uppercase()) }, polish)

    @Test
    fun `the import runs when the stamp differs or the library is empty`() {
        assertTrue(ExerciseLibrary.needsImport(null, 876))
        assertTrue(ExerciseLibrary.needsImport(ExerciseLibrary.LIBRARY_VERSION - 1, 876))
        assertTrue(ExerciseLibrary.needsImport(ExerciseLibrary.LIBRARY_VERSION, 0))
        assertFalse(ExerciseLibrary.needsImport(ExerciseLibrary.LIBRARY_VERSION, 876))
    }

    @Test
    fun `the import runs once per library version, not on every launch`() = runBlocking {
        val dao = FakeExerciseDao()
        val stamp = ExerciseLibrary.VersionStamp.InMemory()
        var loads = 0
        val load: suspend () -> ExerciseLibrary.Bundled? = { loads++; bundled("a", "b", polish = mapOf("a" to "Ą")) }

        ExerciseLibrary(dao, stamp, load).importIfNeeded()
        assertEquals(1, loads)
        assertEquals(ExerciseLibrary.LIBRARY_VERSION, stamp.value)
        assertEquals(listOf("a", "b"), dao.rows.value.map { it.id })
        assertEquals("Ą", dao.rows.value.first { it.id == "a" }.namePL)

        // A cold launch: a new instance, the same stamp.
        ExerciseLibrary(dao, stamp, load).importIfNeeded()
        assertEquals(1, loads, "the 1 MB parse is skipped")
    }

    @Test
    fun `a new library version backfills without duplicates`() = runBlocking {
        val dao = FakeExerciseDao(
            listOf(
                ExerciseEntity(id = "a", name = "A", lastUsedAt = 5),
                ExerciseEntity(id = "custom-1", name = "Mine", isCustom = true),
            ),
        )
        val stamp = ExerciseLibrary.VersionStamp.InMemory(ExerciseLibrary.LIBRARY_VERSION - 1)

        ExerciseLibrary(dao, stamp) { bundled("a", "b", polish = mapOf("a" to "Ą", "b" to "Bę")) }.importIfNeeded()

        assertEquals(listOf("a", "custom-1", "b"), dao.rows.value.map { it.id })
        assertEquals(5L, dao.rows.value.first { it.id == "a" }.lastUsedAt, "an existing row keeps its history")
        assertEquals("Ą", dao.rows.value.first { it.id == "a" }.namePL, "the Polish name is backfilled")
        assertEquals(ExerciseLibrary.LIBRARY_VERSION, stamp.value)
    }

    @Test
    fun `an empty library imports despite the stamp, and a failed read leaves the stamp alone`() = runBlocking {
        val dao = FakeExerciseDao(listOf(ExerciseEntity(id = "custom-1", name = "Mine", isCustom = true)))
        val stamp = ExerciseLibrary.VersionStamp.InMemory(ExerciseLibrary.LIBRARY_VERSION)
        ExerciseLibrary(dao, stamp) { bundled("a") }.importIfNeeded()
        assertEquals(setOf("a", "custom-1"), dao.rows.value.map { it.id }.toSet(), "custom rows do not count as the library")

        val broken = ExerciseLibrary.VersionStamp.InMemory()
        ExerciseLibrary(FakeExerciseDao(), broken) { null }.importIfNeeded()
        assertNull(broken.value, "the next launch tries again")
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
        assertEquals(989, records.size)
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
