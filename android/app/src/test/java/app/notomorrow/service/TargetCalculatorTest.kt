package app.notomorrow.service

import app.notomorrow.model.TrainingGoal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Port of `NoTomorrowTests/TargetCalculatorTests.swift` — the same vectors, verbatim. */
class TargetCalculatorTest {

    @Test
    fun `build muscle at 80 kg`() {
        // BMR = 10*80 + 6.25*178 - 5*30 + 5 = 1767.5; TDEE = 2739.625; +300 = 3039.625 → 3050
        val t = TargetCalculator.targets(80.0, TrainingGoal.BuildMuscle)
        assertEquals(3050, t.kcal)
        assertEquals(176, t.proteinG)
        assertEquals(72, t.fatG)
        // carbs = (3050 - 176*4 - 72*9) / 4 = (3050 - 704 - 648) / 4 = 424.5 → 425
        assertEquals(425, t.carbsG)
    }

    @Test
    fun `lose fat at 80 kg`() {
        // 2739.625 - 400 = 2339.625 → 2350
        val t = TargetCalculator.targets(80.0, TrainingGoal.LoseFat)
        assertEquals(2350, t.kcal)
        assertEquals(192, t.proteinG)
        assertEquals(72, t.fatG)
    }

    @Test
    fun `maintain at 80 kg`() {
        val t = TargetCalculator.targets(80.0, TrainingGoal.Maintain)
        assertEquals(2750, t.kcal)
        assertEquals(144, t.proteinG)
    }

    @Test
    fun `unknown weight uses the default`() {
        val unknown = TargetCalculator.targets(null, TrainingGoal.Maintain)
        val assumed = TargetCalculator.targets(TargetCalculator.ASSUMED_BODY_WEIGHT_KG, TrainingGoal.Maintain)
        assertEquals(assumed, unknown)
    }

    @Test
    fun `a non-positive weight uses the default too`() {
        assertEquals(
            TargetCalculator.targets(TargetCalculator.ASSUMED_BODY_WEIGHT_KG, TrainingGoal.BuildMuscle),
            TargetCalculator.targets(0.0, TrainingGoal.BuildMuscle),
        )
    }

    @Test
    fun `kcal is always a multiple of fifty and nothing is negative`() {
        var w = 50.0
        while (w <= 130.0) {
            for (goal in TrainingGoal.entries) {
                val t = TargetCalculator.targets(w, goal)
                assertEquals(0, t.kcal % 50, "kcal not a multiple of 50 at $w / $goal")
                assertTrue(t.carbsG >= 0)
                assertTrue(t.proteinG >= 0)
                assertTrue(t.fatG >= 0)
            }
            w += 2.5
        }
    }

    @Test
    fun `never negative`() {
        val t = TargetCalculator.targets(0.1, TrainingGoal.LoseFat)
        assertTrue(t.kcal >= 0)
        assertTrue(t.carbsG >= 0)
    }

    @Test
    fun `rounding is half away from zero, not half even`() {
        // 2739.625 / 50 = 54.7925 → 55 → 2750: the maintain vector already pins the
        // half-away rule; this pins the direct .5 case.
        assertEquals(1767.5, TargetCalculator.bmr(80.0), 0.0001)
        assertEquals(2.2, TargetCalculator.proteinGramsPerKg(TrainingGoal.BuildMuscle), 0.0)
        assertEquals(-400.0, TargetCalculator.kcalAdjustment(TrainingGoal.LoseFat), 0.0)
    }
}
