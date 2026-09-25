package app.notomorrow.designsystem.effects

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The CPU port of the liquid-metal material (which steers the glow halo and tints it) against the
 * AGSL shader itself: `metal-material-samples.json` holds 72 points of `LIQUID_METAL_AGSL` rendered
 * through Skia's runtime effects, 8-bit read back, so the tolerance covers the quantisation.
 */
class MetalMaterialTest {
    private val samples = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/metal-material-samples.json")).bufferedReader().use { it.readText() },
    ).jsonObject

    @Test
    fun cpuSamplerMatchesShader() {
        val tol = samples.getValue("tolerance").jsonPrimitive.double
        val cases = samples.getValue("cases").jsonArray
        assertEquals(72, cases.size)
        val failures = ArrayList<String>()
        for (element in cases) {
            val c = element.jsonObject
            val preset = LiquidMetalPreset.valueOf(c.getValue("preset").jsonPrimitive.content)
            val time = c.getValue("time").jsonPrimitive.double
            val u = c.getValue("u").jsonPrimitive.double
            val v = c.getValue("v").jsonPrimitive.double
            val expected = c.getValue("rgb").jsonArray.map { it.jsonPrimitive.double }
            val actual = sampleMetal(preset, u, v, time)
            for (i in 0 until 3) {
                if (abs(actual[i] - expected[i]) > tol) {
                    failures += "$preset t=$time ($u, $v) ch$i: got ${actual[i]}, shader ${expected[i]}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun windowIsCappedForWideShapes() {
        // a 353 × 52 pt search bar at the pill's zoom: 72 % of the sheet across, 52 / 64 down
        val m = sheetMapping(353f, 52f, 1.6f)
        assertEquals(0.14f, m.ox, 1e-5f)
        assertEquals(0.72f / 353f, m.sx, 1e-7f)
        assertEquals(0.5f - 0.5f * (52f / 64f), m.oy, 1e-5f)
        // the upstream demo pill (140 × 40) keeps its own window
        val pill = sheetMapping(140f, 40f, 1.6f)
        assertEquals(0.1875f, pill.ox, 1e-5f)
        assertEquals(0.1875f, pill.oy, 1e-5f)
    }
}
