package app.notomorrow.designsystem.effects

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The hand-transcribed orb engine against upstream's golden vectors (`orbs-golden-subset.json`:
 * the `searching` and `connecting` cases of thinking-orbs' `spec/orbs-golden.json`), the same way
 * ThinkingOrbsKit's `OrbGoldenTests` checks the Swift port.
 *
 * Dots are compared as a multiset (rounded to the file's 6 decimals, then sorted) plus a far → near
 * assertion, because draw order within a group of equal-depth dots is not part of the contract;
 * lines are compared in order.
 */
class OrbEngineTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/orbs-golden-subset.json")).bufferedReader().use { it.readText() },
    ).jsonObject

    @Test
    fun matchesGoldenVectors() {
        val tol = golden.getValue("tolerance").jsonPrimitive.double
        val failures = ArrayList<String>()
        var checked = 0
        val cases = golden.getValue("cases").jsonArray
        assertEquals(16, cases.size)

        for (element in cases) {
            val c = element.jsonObject
            val key = c.getValue("key").jsonPrimitive.content
            val state = OrbState.entries.first { it.raw == c.getValue("state").jsonPrimitive.content }
            val size = OrbSize.entries.first { it.points == c.getValue("size").jsonPrimitive.int }
            val t = c.getValue("t").jsonPrimitive.double
            val frame = orbFrame(resolveOrbPreset(state, size), size.points.toDouble(), t)

            val dotCount = c.getValue("dotCount").jsonPrimitive.int
            val lineCount = c.getValue("lineCount").jsonPrimitive.int
            if (frame.dots.size != dotCount || frame.lines.size != lineCount) {
                failures += "$key: ${frame.dots.size}/${frame.lines.size} dots/lines, expected $dotCount/$lineCount"
                continue
            }

            fun q6(v: Double) = Math.round(v * 1_000_000) / 1_000_000.0
            val lex = Comparator<List<Double>> { a, b ->
                for (i in a.indices) if (a[i] != b[i]) return@Comparator a[i].compareTo(b[i])
                0
            }
            val mine = frame.dots.map { d -> listOf(d.x, d.y, d.z, d.r, d.white, d.a).map(::q6) }.sortedWith(lex)
            val flat = c.getValue("dots").jsonArray.map { it.jsonPrimitive.double }
            val theirs = (0 until dotCount).map { i -> flat.subList(i * 6, i * 6 + 6).map(::q6) }.sortedWith(lex)
            val fields = listOf("x", "y", "z", "r", "white", "a")
            for (i in mine.indices) {
                for (f in 0 until 6) {
                    checked++
                    if (abs(mine[i][f] - theirs[i][f]) > tol && failures.size < 25) {
                        failures += "$key dot$i.${fields[f]}: got ${mine[i][f]}, expected ${theirs[i][f]}"
                    }
                }
            }
            for (i in 1 until frame.dots.size) {
                if (frame.dots[i].z < frame.dots[i - 1].z) {
                    failures += "$key: dots not z-sorted at $i"
                    break
                }
            }

            val lines = c["lines"]?.jsonArray?.map { it.jsonPrimitive.double } ?: emptyList()
            frame.lines.forEachIndexed { i, l ->
                val actual = listOf(l.x1, l.y1, l.x2, l.y2, l.white, l.a, l.w)
                for (f in 0 until 7) {
                    checked++
                    if (abs(actual[f] - lines[i * 7 + f]) > tol && failures.size < 25) {
                        failures += "$key line$i[$f]: got ${actual[f]}, expected ${lines[i * 7 + f]}"
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} mismatch(es) of $checked values:\n" + failures.joinToString("\n"))
        assertEquals(9947, checked) // every dot and line field in the subset
    }

    @Test
    fun presetsScaleCountsLikeTheSpec() {
        // globe 64: 17 × 44 lattice at count 0.42 → √0.42 per axis, rounded away from zero
        val globe = resolveOrbPreset(OrbState.Searching, OrbSize.Px64)
        assertEquals(11.0, globe.opts["latRings"])
        assertEquals(29.0, globe.opts["lonDensity"])
        assertEquals(2.015, globe.speed)
        // web 20: 30 nodes × 0.25, 5 signals × 0.25 → 1.25 → 1
        val web = resolveOrbPreset(OrbState.Connecting, OrbSize.Px20)
        assertEquals(8.0, web.opts["nodeN"])
        assertEquals(1.0, web.opts["signals"])
    }

    @Test
    fun roundAwayMatchesSwift() {
        assertEquals(3.0, roundAway(2.5))
        assertEquals(-3.0, roundAway(-2.5))
        assertEquals(2.0, roundAway(2.4999))
    }
}
