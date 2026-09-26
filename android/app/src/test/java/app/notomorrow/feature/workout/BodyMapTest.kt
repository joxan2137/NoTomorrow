package app.notomorrow.feature.workout

import app.notomorrow.service.ExerciseLibrary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `BodyMapTests.swift`: the body map's parsed shapes, the tap hit test, the exercise facts and the
 * form cues file. The iOS and Android copies of both JSON files must stay identical.
 */
class BodyMapTest {
    private val regions = BodyMap.decode(File("src/main/assets/muscle_model.json").readText())

    @Test fun everyRegionParsesInsideTheBox() {
        assertTrue(regions.isNotEmpty())
        for (region in regions) {
            val (minX, minY, maxX, maxY) = region.shape.bounds().toList()
            assertTrue(region.muscle, maxX - minX > 0.5f && maxY - minY > 0.5f)
            assertTrue(region.muscle, minX >= 0f && minY >= 0f && maxX <= BodyMap.WIDTH && maxY <= BodyMap.HEIGHT)
            assertTrue(region.view, region.view == "front" || region.view == "back")
        }
    }

    @Test fun parserReadsMoveLineCurveAndClose() {
        val shape = SvgShape.parse("M10 10C10 20 20 20 20 10L15 0Z")
        val (minX, minY, maxX, _) = shape.bounds().toList()
        assertEquals(10f, minX, 0.01f)
        assertEquals(0f, minY, 0.01f)
        assertEquals(20f, maxX, 0.01f)
        assertTrue(shape.contains(15f, 10f))
        assertFalse(shape.contains(25f, 10f))
        assertEquals(listOf(-1f, -2f, 3f, 4f), SvgShape.parse("M-1-2L3,4Z").bounds().toList())
    }

    @Test fun tapFindsTheMuscleUnderTheFinger() {
        // Drawn at 400 × 600, so the model's units double.
        assertEquals("chest", BodyMap.muscleAt(regions, 2 * 38.8f, 2 * 68f, 400f, 600f))
        assertEquals("lats", BodyMap.muscleAt(regions, 2 * 134.3f, 2 * 100f, 400f, 600f))
        assertNull("the head is body, not muscle", BodyMap.muscleAt(regions, 2 * 50f, 2 * 20f, 400f, 600f))
        assertNull(BodyMap.muscleAt(regions, 2f, 2f, 400f, 600f))
    }

    @Test fun mapCoversEveryMuscleInTheLibrary() {
        val json = Json { ignoreUnknownKeys = true }
        val records = json.decodeFromString<List<ExerciseLibrary.Record>>(File("src/main/assets/exercises.json").readText())
        val drawn = regions.map { it.muscle }.toSet()
        for (record in records) {
            assertTrue(record.id, drawn.containsAll(record.primaryMuscles + record.secondaryMuscles))
        }
        for (muscle in listOf("chest", "shoulders", "biceps", "abdominals", "quadriceps", "calves")) {
            assertTrue(muscle, regions.any { it.muscle == muscle && it.view == "front" })
        }
        for (muscle in listOf("traps", "lats", "middle back", "lower back", "triceps", "glutes", "hamstrings", "calves")) {
            assertTrue(muscle, regions.any { it.muscle == muscle && it.view == "back" })
        }
    }

    @Test fun iosAndAndroidShareTheSameFiles() {
        for (name in listOf("muscle_model.json", "form_cues.json")) {
            assertEquals(name, File("../../NoTomorrow/Resources/$name").readText(), File("src/main/assets/$name").readText())
        }
    }

    @Test fun exerciseFactsSkipUnknownValues() {
        assertEquals(2, exerciseFactKey(level = "advanced", mechanic = null, force = "push").size)
        assertEquals(0, exerciseFactKey(level = null, mechanic = "other", force = "sideways").size)
    }

    @Test fun formCuesCoverKnownExercisesInBothLanguages() {
        val json = Json { ignoreUnknownKeys = true }
        val ids = json.decodeFromString<List<ExerciseLibrary.Record>>(File("src/main/assets/exercises.json").readText())
            .map { it.id }.toSet()
        val all = FormCues.decode(File("src/main/assets/form_cues.json").readText())
        assertTrue(all.size > 30)
        for ((id, byLanguage) in all) {
            assertTrue(id, id in ids)
            val en = byLanguage.getValue("en")
            val pl = byLanguage["pl"]
            assertNotNull("$id pl", pl)
            pl!!
            assertTrue(id, en.cues.isNotEmpty())
            assertEquals(id, en.cues.size, pl.cues.size)
            assertEquals(id, en.mistakes.size, pl.mistakes.size)
        }
        assertTrue(FormCues.cues(all, "Barbell_Squat", "pl")!!.cues.first().startsWith("Przed"))
        assertNotNull("falls back to English", FormCues.cues(all, "Barbell_Squat", "de"))
    }
}
