package app.notomorrow.service

import app.notomorrow.feature.workout.MachineLabelReader
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Machine placard text → library exercise (`docs/machine-scan.md`). The same cases run on iOS in
 * `MachineLabelMatcherTests.swift`, against the same bundled library.
 */
class MachineLabelMatcherTest {

    private companion object {
        val library: MachineLabelMatcher by lazy {
            val json = Json { ignoreUnknownKeys = true }
            val records = json.decodeFromString<List<ExerciseLibrary.Record>>(
                File("src/main/assets/${ExerciseLibrary.EXERCISES_ASSET}").readText(),
            )
            val polish = json.decodeFromString<Map<String, String>>(
                File("src/main/assets/${ExerciseLibrary.EXERCISES_PL_ASSET}").readText(),
            )
            MachineLabelMatcher(records.map { MachineLabelMatcher.Candidate(it.id, it.name, polish[it.id], it.equipment) })
        }
    }

    private fun best(vararg lines: Pair<String, Double>, excluding: Set<String> = emptySet()): String? =
        library.match(lines.map { MachineLabelMatcher.Line(it.first, it.second) }, excluding).firstOrNull()?.id

    @Test
    fun `the placard name wins over the fine print`() {
        assertEquals(
            "Seated_Leg_Curl",
            best(
                "SEATED LEG CURL" to 1.0,
                "Adjust seat so knees align with pivot. Press legs down slowly" to 0.35,
                "Technogym" to 0.5,
            ),
        )
        assertEquals(
            "Leg_Extensions",
            best("LEG EXTENSION" to 1.0, "Selection" to 0.5, "1. Adjust back pad 2. Extend legs" to 0.3),
        )
        assertEquals("Leg_Press", best("TECHNOGYM" to 1.0, "Leg Press" to 0.8))
    }

    @Test
    fun `a branded machine`() {
        assertEquals("nt_hs_iso_lateral_row", best("HAMMER STRENGTH" to 0.6, "ISO-LATERAL ROW" to 1.0))
    }

    @Test
    fun `synonyms and plurals`() {
        assertEquals("Butterfly", best("Pectoral Machine" to 1.0, "Technogym" to 0.5))
        assertEquals("Triceps_Pushdown", best("TRICEPS PUSH DOWN" to 1.0))
        assertEquals("Ab_Crunch_Machine", best("ABDOMINAL CRUNCH" to 1.0))
        assertEquals("Thigh_Abductor", best("ABDUCTOR" to 1.0))
        assertEquals("Standing_Calf_Raises", best("CALF RAISE" to 1.0, "standing" to 0.6))
    }

    @Test
    fun `prefers the machine over the free-weight version`() {
        assertEquals("Machine_Bicep_Curl", best("BICEPS CURL" to 1.0))
    }

    @Test
    fun `an OCR misread still matches`() {
        assertEquals("Leg_Extensions", best("LEG EXTENSIQN" to 1.0))
    }

    @Test
    fun `safety text alone matches nothing`() {
        assertNull(best("Keep hands clear of moving parts. Max user weight 150 kg" to 1.0))
        assertNull(best())
    }

    @Test
    fun `an excluded exercise is skipped`() {
        assertNotEquals("Leg_Press", best("LEG PRESS" to 1.0, excluding = setOf("Leg_Press")))
    }

    @Test
    fun `at most three matches, best first`() {
        val matches = library.match(listOf(MachineLabelMatcher.Line("LEG CURL", 1.0)))
        assertTrue(matches.size <= MachineLabelMatcher.MAX_MATCHES)
        assertEquals(matches.map { it.score }.sortedDescending(), matches.map { it.score })
        assertTrue(matches.all { it.score >= MachineLabelMatcher.MINIMUM_SCORE })
    }

    @Test
    fun tokens() {
        assertEquals(listOf("lat", "pulldown"), MachineLabelMatcher.tokens("Lat Pull-Down"))
        assertEquals(listOf("leg", "press"), MachineLabelMatcher.tokens("Leg Presses 2"))
        assertEquals(listOf("rear", "deltoid", "fly"), MachineLabelMatcher.tokens("Rear Delt Flyes"))
        assertEquals(listOf("wyciskanie", "nog"), MachineLabelMatcher.tokens("Wyciskanie nóg"))
    }

    @Test
    fun `line weights are relative to the tallest line`() {
        assertEquals(
            listOf(MachineLabelMatcher.Line("BIG", 1.0), MachineLabelMatcher.Line("small", 0.25)),
            MachineLabelReader.weighted(listOf("BIG" to 40.0, "small" to 10.0, "  " to 50.0)),
        )
    }
}
