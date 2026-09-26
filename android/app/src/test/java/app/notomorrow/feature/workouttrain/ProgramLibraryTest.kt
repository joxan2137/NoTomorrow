package app.notomorrow.feature.workouttrain

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.feature.workout.ProgramKeys
import app.notomorrow.feature.workout.ProgramLibrary
import app.notomorrow.feature.workout.RoutineDraft
import app.notomorrow.feature.workout.RoutineStore
import app.notomorrow.feature.workout.TrainingProgram
import app.notomorrow.service.ExerciseLibrary
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeRoutineDao
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The built-in programs (`NoTomorrowTests/ProgramLibraryTests.swift`): the shared `programs.json`
 * decodes, every exercise id is in the bundled library, every line fits the routine editor, every
 * string has a resource in both languages, and "Add N routines" writes the routines with their
 * lines and unique names.
 */
class ProgramLibraryTest {

    private val exercises = FakeExerciseDao()
    private val routines = FakeRoutineDao { id -> exercises.rows.value.firstOrNull { it.id == id } }
    private val store = RoutineStore(routines, exercises)

    /** The same file the app bundles (`data/programs`, a test resource dir too). */
    private val programs: List<TrainingProgram> by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/${ProgramLibrary.ASSET}")) { "programs.json not on the classpath" }
            .bufferedReader().use { it.readText() }
        ProgramLibrary.decode(text)
    }

    private fun program(id: String) = programs.first { it.id == id }

    /** The English name the screen would resolve for a catalog key. */
    private val english: Map<String, String> by lazy { stringsXml("src/main/res/values/strings.xml") }

    private fun stringsXml(path: String): Map<String, String> {
        val file = File(path)
        assertTrue(file.exists(), "$path (tests run from the app module)")
        val row = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""")
        return row.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun localize(key: String): String = english.getValue(key.replace('.', '_'))

    private fun insertExercises(program: TrainingProgram) {
        exercises.rows.value = exercises.rows.value +
            program.exerciseIds.sorted().map { ExerciseEntity(id = it, name = it, primaryMuscles = listOf("chest")) }
    }

    private fun ordered() = routines.routines.value.sortedBy { it.order }

    // MARK: - Data

    @Test
    fun `the bundled programs decode`() {
        assertEquals(listOf("fullBody", "fiveByFive", "ppl", "upperLower", "fiveThreeOne", "dumbbellHome"), programs.map { it.id })
        programs.forEach { program ->
            assertTrue(program.routines.size in 2..6, program.id)
            assertTrue(program.daysPerWeek in 1..7, program.id)
            assertTrue(program.level in setOf("beginner", "intermediate"), program.id)
            assertEquals(program.routines.size, program.routines.map { it.id }.toSet().size, program.id)
        }
    }

    @Test
    fun `every program exercise id resolves in the bundled library`() {
        val file = File("src/main/assets/${ExerciseLibrary.EXERCISES_ASSET}")
        assertTrue(file.exists(), "exercises.json (tests run from the app module)")
        val ids = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<ExerciseLibrary.Record>>(file.readText())
            .mapTo(mutableSetOf()) { it.id }
        programs.forEach { program ->
            program.routines.forEach { day ->
                assertTrue(day.items.isNotEmpty(), "${program.id}/${day.id}")
                day.items.forEach { line ->
                    assertTrue(line.exercise in ids, "${program.id}/${day.id}: ${line.exercise}")
                }
            }
        }
    }

    @Test
    fun `every line fits the routine editor`() {
        programs.forEach { program ->
            program.routines.forEach { day ->
                assertEquals(day.items.size, day.items.map { it.exercise }.toSet().size, "${program.id}/${day.id} repeats")
                day.items.forEach { line ->
                    val where = "${program.id}/${day.id}/${line.exercise}"
                    assertTrue(line.sets in RoutineDraft.SET_RANGE, where)
                    assertTrue(line.reps in RoutineDraft.REP_RANGE, where)
                    assertTrue(line.rest in RoutineDraft.REST_OPTIONS, where)
                }
            }
        }
    }

    @Test
    fun `every program string has a resource in both languages`() {
        val polish = stringsXml("src/main/res/values-pl/strings.xml")
        val keys = programs.flatMap { it.localizationKeys + it.levelKey }.toSet()
        keys.forEach { key ->
            assertNotNull(ProgramKeys.text(key), key)
            val name = key.replace('.', '_')
            assertTrue(!english[name].isNullOrBlank(), "en $key")
            assertTrue(!polish[name].isNullOrBlank(), "pl $key")
        }
    }

    // MARK: - Drafts

    @Test
    fun `drafts localize names, keep targets and skip unknown exercises`() {
        val program = TrainingProgram(
            id = "p", name = "p.name", summary = "p.summary", level = "beginner", daysPerWeek = 2,
            routines = listOf(
                TrainingProgram.Day(
                    "a", "p.a",
                    listOf(TrainingProgram.Line("squat", 5, 5, 180), TrainingProgram.Line("gone", 3, 8, 90)),
                ),
                TrainingProgram.Day("b", "p.b", listOf(TrainingProgram.Line("press", 3, 10, 90))),
            ),
        )
        val known = mapOf("squat" to "Squat", "press" to "Press")
        val drafts = ProgramLibrary.drafts(
            program = program,
            taken = listOf("day a"),
            exercise = { id -> known[id]?.let { ProgramLibrary.ExerciseInfo(it, "quadriceps") } },
            localize = { if (it == "p.a") "Day A" else "Day B" },
        )
        assertEquals(listOf("Day A 2", "Day B"), drafts.map { it.name })
        assertEquals(listOf("squat"), drafts[0].items.map { it.exerciseId })
        val squat = drafts[0].items[0]
        assertEquals("Squat", squat.name)
        assertEquals(listOf(5, 5, 180), listOf(squat.sets, squat.reps, squat.restSeconds))
        assertEquals(listOf("press"), drafts[1].items.map { it.exerciseId })
    }

    // MARK: - Add

    @Test
    fun `adding a program creates its routines in order`() = runBlocking {
        val ppl = program("ppl")
        insertExercises(ppl)
        routines.routines.value = listOf(RoutineEntity(id = "mine", name = "Mine", order = 0, createdAt = 0))

        val added = store.addProgram(ppl, ::localize, Locale.ENGLISH)

        assertEquals(ppl.routines.size, added.size)
        val all = ordered()
        assertEquals(listOf("Mine", "PPL Push", "PPL Pull", "PPL Legs"), all.map { it.name })
        assertEquals(all.indices.toList(), all.map { it.order })
        all.drop(1).zip(ppl.routines).forEach { (routine, day) ->
            val items = routines.items(routine.id).sortedBy { it.order }
            assertEquals(day.items.map { it.exercise }, items.map { it.exerciseId }, day.id)
            assertEquals(day.items.map { it.sets }, items.map { it.targetSets }, day.id)
            assertEquals(day.items.map { it.reps }, items.map { it.targetReps }, day.id)
            assertEquals(day.items.map { it.rest }, items.map { it.restSeconds }, day.id)
        }
    }

    @Test
    fun `adding a program twice gives unique names`() = runBlocking {
        val fiveByFive = program("fiveByFive")
        insertExercises(fiveByFive)

        store.addProgram(fiveByFive, ::localize, Locale.ENGLISH)
        store.addProgram(fiveByFive, ::localize, Locale.ENGLISH)

        val names = ordered().map { it.name }
        assertEquals(listOf("5×5 A", "5×5 B", "5×5 A 2", "5×5 B 2"), names)
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size)
    }

    @Test
    fun `adding without the library skips empty routines`() = runBlocking {
        val added = store.addProgram(program("upperLower"), ::localize, Locale.ENGLISH)
        assertTrue(added.isEmpty())
        assertTrue(routines.routines.value.isEmpty())
    }
}
