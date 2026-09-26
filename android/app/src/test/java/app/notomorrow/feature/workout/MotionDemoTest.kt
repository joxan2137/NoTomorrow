package app.notomorrow.feature.workout

import app.notomorrow.service.ExerciseLibrary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `MotionDemoTests.swift`: the mannequin demos. Joint positions must match the reference
 * `scripts/anatomy/motion_engine.js` (fixture `motion_joints.json`, written by it), and every mapped
 * exercise must exist and lack photos.
 */
class MotionDemoTest {
    private val library = MotionLibrary.decode(File("src/main/assets/motions.json").readText())

    @Test fun jointsMatchTheReferenceEngine() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/motion_joints.json")!!.readText()).jsonObject
        for ((name, joints) in fixture) {
            val pattern = library.patterns.getValue(name)
            val solved = MotionLibrary.solve(MotionLibrary.pose(pattern, 0.5)!!, pattern.front)
            for ((joint, value) in joints.jsonObject) {
                val (x, y) = value.jsonArray.map { it.jsonPrimitive.double }
                val p = solved[joint] ?: error("$name.$joint missing")
                assertEquals("$name.$joint x", x, p.x, 0.01)
                assertEquals("$name.$joint y", y, p.y, 0.01)
            }
        }
    }

    @Test fun footAnchorKeepsTheNearAnklePlanted() {
        for ((name, pattern) in library.patterns) {
            if (pattern.frames.first().anchor != "foot" || pattern.front) continue
            for (t in listOf(0.0, 0.5, 1.0)) {
                val pose = MotionLibrary.pose(pattern, t)!!
                val ankle = MotionLibrary.solve(pose, false).getValue("anklen")
                assertEquals(name, pose.at.x, ankle.x, 0.01)
                assertEquals(name, pose.at.y, ankle.y, 0.01)
            }
        }
    }

    @Test fun everyMappedExerciseExistsAndHasNoPhotos() {
        val json = Json { ignoreUnknownKeys = true }
        val records = json.decodeFromString<List<ExerciseLibrary.Record>>(File("src/main/assets/exercises.json").readText())
            .associateBy { it.id }
        assertTrue(library.exercises.size > 90)
        for ((id, pattern) in library.exercises) {
            val record = records[id] ?: error("$id not in the library")
            assertTrue(id, record.images.isNullOrEmpty())
            assertTrue("$id -> $pattern", pattern in library.patterns)
            assertEquals(id, 2, library.patterns.getValue(pattern).frames.size)
        }
    }

    @Test fun hotSegmentsFollowPrimaryMuscles() {
        assertEquals(setOf("thigh"), MotionLibrary.hotSegments(listOf("quadriceps", "glutes")))
        assertEquals(setOf("torso", "upper"), MotionLibrary.hotSegments(listOf("chest", "triceps", "unknown")))
    }

    @Test fun phaseEasesBetweenThePoses() {
        assertEquals(0.0, MotionLibrary.phase(0.0, 2.0), 1e-9)
        assertEquals(1.0, MotionLibrary.phase(1.0, 2.0), 1e-9)
        assertEquals(0.5, MotionLibrary.phase(0.5, 2.0), 1e-9)
    }

    @Test fun iosAndAndroidShareTheMotionFile() {
        assertEquals(File("../../NoTomorrow/Resources/motions.json").readText(), File("src/main/assets/motions.json").readText())
    }
}
