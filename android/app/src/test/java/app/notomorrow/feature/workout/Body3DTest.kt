package app.notomorrow.feature.workout

import app.notomorrow.service.ExerciseLibrary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * `Body3DTests.swift`: the 3D muscle view's layers and tap hit test, the 3D demo clips, the exercise
 * facts and the form cues file. The iOS and Android copies of the rendered files must stay identical.
 */
class Body3DTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val meta = Body3D.decode(File("src/main/assets/body3d/body3d.json").readText())
    private val records = json.decodeFromString<List<ExerciseLibrary.Record>>(File("src/main/assets/exercises.json").readText())

    private fun labels(view: String): Body3D.LabelMap {
        val image = ImageIO.read(File("src/main/assets/body3d/$view-labels.png"))
        val raster = image.raster
        val bytes = ByteArray(image.width * image.height) { i -> raster.getSample(i % image.width, i / image.width, 0).toByte() }
        return Body3D.LabelMap(image.width, image.height, bytes)
    }

    @Test fun everyLayerFitsTheRenderAndExists() {
        assertEquals(listOf(520, 1000), meta.size)
        for ((muscle, views) in meta.layers) {
            assertTrue(muscle, muscle in meta.muscles)
            for ((view, rect) in views) {
                assertTrue(view, view in Body3D.VIEWS)
                val (x, y, w, h) = rect
                assertTrue("$muscle $view $rect", x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= meta.width && y + h <= meta.height)
                val file = File("src/main/assets/body3d/$view-${muscle.replace(' ', '_')}.png")
                assertTrue(file.path, file.exists())
                val image = ImageIO.read(file)
                assertEquals(file.path, w, image.width)
                assertEquals(file.path, h, image.height)
            }
        }
    }

    @Test fun bodyShowsEveryMuscleInTheLibrary() {
        for (record in records) {
            for (muscle in record.primaryMuscles + record.secondaryMuscles) {
                assertTrue("${record.id} $muscle", meta.layers[muscle].orEmpty().isNotEmpty())
            }
        }
        for (muscle in listOf("chest", "shoulders", "biceps", "abdominals", "quadriceps", "forearms")) {
            assertNotNull(muscle, meta.layers[muscle]?.get("front"))
        }
        for (muscle in listOf("traps", "lats", "middle back", "lower back", "triceps", "glutes", "hamstrings", "calves")) {
            assertNotNull(muscle, meta.layers[muscle]?.get("back"))
        }
    }

    @Test fun tapFindsTheMuscleUnderTheFinger() {
        val front = labels("front")
        val back = labels("back")
        // Drawn at half size, so the render's pixels halve.
        fun at(map: Body3D.LabelMap, x: Int, y: Int) = Body3D.muscleAt(meta, map, x / 2f, y / 2f, 260f, 500f)
        assertEquals("chest", at(front, 205, 290))
        assertEquals("quadriceps", at(front, 205, 610))
        assertEquals("lats", at(back, 190, 350))
        assertEquals("glutes", at(back, 300, 500))
        assertNull("the head is body, not muscle", at(front, 260, 120))
        assertNull(at(front, 8, 8))
    }

    @Test fun labelMapRoundsToTheNearestStep() {
        val map = Body3D.LabelMap(4, 1, byteArrayOf(0, 12, 17, (13 * 12 + 5).toByte()))
        assertNull(map.index(0, 0, 12))
        assertEquals(0, map.index(1, 0, 12))
        assertEquals(0, map.index(2, 0, 12))
        assertEquals(12, map.index(3, 0, 12))
        assertNull(map.index(4, 0, 12))
    }

    @Test fun demosCoverExercisesWithoutPhotos() {
        val clips = ExerciseDemos.decode(File("src/main/assets/demos/demos.json").readText())
        val byId = records.associateBy { it.id }
        assertTrue(clips.size > 90)
        for ((id, clip) in clips) {
            val record = byId[id]
            assertNotNull(id, record)
            assertTrue(id, record!!.images.orEmpty().isEmpty())
            for (ext in listOf("mp4", "jpg")) assertTrue("$clip.$ext", File("src/main/assets/demos/$clip.$ext").exists())
        }
    }

    @Test fun muscleStrengthsFollowTheirRoles() {
        assertEquals(1f, muscleModelStrength("chest", listOf("chest"), listOf("triceps")))
        assertEquals(0.45f, muscleModelStrength("triceps", listOf("chest"), listOf("triceps")))
        assertEquals(0f, muscleModelStrength("calves", listOf("chest"), listOf("triceps")))
        assertEquals(listOf(0f, 0.3f, 0.52f, 0.76f, 1f), (0..4).map(::muscleHeatStrength))
    }

    @Test fun iosAndAndroidShareTheSameFiles() {
        assertEquals(File("../../NoTomorrow/Resources/form_cues.json").readText(), File("src/main/assets/form_cues.json").readText())
        for ((ios, android) in listOf("Body3D" to "body3d", "Demos" to "demos")) {
            val mine = File("src/main/assets/$android").listFiles()!!.map { it.name }.sorted()
            assertEquals(android, File("../../NoTomorrow/Resources/$ios").listFiles()!!.map { it.name }.sorted(), mine)
            for (name in mine) {
                assertTrue(name, File("../../NoTomorrow/Resources/$ios/$name").readBytes().contentEquals(File("src/main/assets/$android/$name").readBytes()))
            }
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
