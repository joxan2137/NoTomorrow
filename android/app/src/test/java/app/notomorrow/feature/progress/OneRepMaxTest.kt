package app.notomorrow.feature.progress

import app.notomorrow.feature.workout.WarmupPlan
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** 1RM percentages (`OneRepMaxTests.swift`): plate rounding, reps consistent with the rep-max table, Epley. */
class OneRepMaxTest {

    @Test
    fun `estimate matches the set e1RM`() {
        assertEquals(100.0, OneRepMax.estimate(100.0, 1))
        assertEquals(Fmt.epley(100.0, 5), OneRepMax.estimate(100.0, 5))
        assertEquals(120.0, OneRepMax.estimate(90.0, 10), 0.001)
        assertEquals(0.0, OneRepMax.estimate(0.0, 5))
        assertEquals(0.0, OneRepMax.estimate(100.0, 0))
    }

    @Test
    fun `reps per percentage`() {
        assertEquals(listOf(100, 95, 90, 85, 80, 75, 70, 65, 60, 50), OneRepMax.percentages)
        assertEquals(listOf(1, 1, 3, 5, 7, 10, 12, 16, 20, 30), OneRepMax.percentages.map { OneRepMax.reps(it) })
    }

    @Test
    fun `reps agree with the rep-max table`() {
        val e1RM = 120.0
        for (percent in OneRepMax.percentages.filter { it < 100 }) {
            val reps = OneRepMax.reps(percent)
            val target = e1RM * percent / 100.0
            assertTrue(RepMax.estimate(e1RM, reps) >= target - 1e-9, "$percent %")
            assertTrue(RepMax.estimate(e1RM, reps + 1) < target, "$percent %")
        }
    }

    @Test
    fun `rounding to plates`() {
        assertEquals(117.5, OneRepMax.round(116.85, 2.5))
        assertEquals(110.0, OneRepMax.round(110.7, 2.5))
        assertEquals(112.5, OneRepMax.round(111.25, 2.5), "halves round up")
        assertEquals(190.0, OneRepMax.round(191.25, 5.0))
        assertEquals(42.0, OneRepMax.round(42.0, 0.0))
    }

    @Test
    fun `rows in kilograms`() {
        val rows = OneRepMax.rows(123.0, WarmupPlan.increment(WeightUnit.Kg))
        assertEquals(OneRepMax.percentages, rows.map { it.percent })
        assertEquals(listOf(122.5, 117.5, 110.0, 105.0, 97.5, 92.5, 85.0, 80.0, 75.0, 62.5), rows.map { it.weight })
        assertEquals(1, rows.first().reps)
    }

    @Test
    fun `rows in pounds`() {
        val rows = OneRepMax.rows(225.0, WarmupPlan.increment(WeightUnit.Lb))
        assertEquals(
            listOf(225.0, 215.0, 205.0, 190.0, 180.0, 170.0, 160.0, 145.0, 135.0, 115.0),
            rows.map { it.weight },
        )
    }

    @Test
    fun `no rows without an e1RM`() {
        assertEquals(emptyList(), OneRepMax.rows(0.0, 2.5))
    }
}
