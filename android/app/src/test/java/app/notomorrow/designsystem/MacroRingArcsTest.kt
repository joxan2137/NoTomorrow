package app.notomorrow.designsystem

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** `macroRingArcs` — parity with `MacroRingTests.swift`. */
class MacroRingArcsTest {

    private fun assertArc(expected: MacroArc, actual: MacroArc) {
        assertEquals(expected.macro, actual.macro)
        assertEquals(expected.start, actual.start, 1e-5f)
        assertEquals(expected.end, actual.end, 1e-5f)
    }

    @Test
    fun arcsFollowEachMacroShareOfTheKcalGoal() {
        // 100 g protein = 400 kcal, 200 g carbs = 800 kcal, 40 g fat = 360 kcal of 2000.
        val arcs = macroRingArcs(protein = 100.0, carbs = 200.0, fat = 40.0, kcalGoal = 2000.0, gap = 0.01f)
        assertEquals(3, arcs.size)
        assertArc(MacroArc(0f, 0.19f, 0), arcs[0])
        assertArc(MacroArc(0.2f, 0.59f, 1), arcs[1])
        assertArc(MacroArc(0.6f, 0.77f, 2), arcs[2])
    }

    @Test
    fun pastTheGoalTheArcsScaleDownToAFullRing() {
        val arcs = macroRingArcs(protein = 250.0, carbs = 500.0, fat = 100.0, kcalGoal = 2000.0, gap = 0f)
        assertEquals(1f, arcs.last().end, 1e-5f)
        // 1000 : 2000 : 900 kcal keep their proportions.
        assertEquals(1000f / 3900f, arcs[0].end, 1e-5f)
    }

    @Test
    fun aMacroSmallerThanTheGapIsLeftOut() {
        val arcs = macroRingArcs(protein = 1.0, carbs = 100.0, fat = 0.0, kcalGoal = 2000.0, gap = 0.01f)
        assertEquals(listOf(1), arcs.map { it.macro })
    }

    @Test
    fun noGoalOrNothingEatenDrawsNoArcs() {
        assertTrue(macroRingArcs(100.0, 100.0, 10.0, kcalGoal = 0.0, gap = 0.01f).isEmpty())
        assertTrue(macroRingArcs(0.0, 0.0, 0.0, kcalGoal = 2000.0, gap = 0.01f).isEmpty())
    }
}
