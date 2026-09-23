package app.notomorrow.feature.fuelaiscan

import app.notomorrow.feature.fuel.AIScanConfidence
import app.notomorrow.feature.fuel.AIScanDerive
import app.notomorrow.feature.fuel.AIScanEdits
import app.notomorrow.net.dto.AIFood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of `AIScanModel` (`Features/Fuel/AIScanModel.swift`) — confidence banding,
 * proportional rescaling, the guess badge, the custom-grams parser and the log filter.
 */
class AIScanDeriveTest {

    private fun food(
        name: String = "Rice",
        grams: Double = 200.0,
        kcal: Double = 260.0,
        protein: Double = 6.0,
        carbs: Double = 56.0,
        fat: Double = 1.0,
        confidence: Double = 0.7,
        isGuess: Boolean = false,
    ) = AIFood(name, grams, kcal, protein, carbs, fat, confidence, isGuess, id = name)

    // MARK: - Confidence

    @Test
    fun `confidence bands match the Swift switch`() {
        assertEquals(AIScanConfidence.High, AIScanDerive.confidence(0.75))
        assertEquals(AIScanConfidence.High, AIScanDerive.confidence(1.0))
        assertEquals(AIScanConfidence.Medium, AIScanDerive.confidence(0.45))
        assertEquals(AIScanConfidence.Medium, AIScanDerive.confidence(0.7499))
        assertEquals(AIScanConfidence.Low, AIScanDerive.confidence(0.4499))
        assertEquals(AIScanConfidence.Low, AIScanDerive.confidence(0.0))
    }

    @Test
    fun `confidence bars are three two one`() {
        assertEquals(3, AIScanConfidence.High.bars)
        assertEquals(2, AIScanConfidence.Medium.bars)
        assertEquals(1, AIScanConfidence.Low.bars)
    }

    // MARK: - Totals

    @Test
    fun `totals sum every food`() {
        val foods = listOf(food(name = "a", kcal = 100.0, protein = 10.0), food(name = "b", kcal = 55.5, protein = 2.5))
        assertEquals(155.5, AIScanDerive.total(foods) { it.kcal }, 1e-9)
        assertEquals(12.5, AIScanDerive.total(foods) { it.protein }, 1e-9)
        assertEquals(0.0, AIScanDerive.total(emptyList()) { it.kcal }, 1e-9)
    }

    // MARK: - Guess badge

    @Test
    fun `guess badge shows for a flagged food or low confidence`() {
        assertTrue(AIScanDerive.showsGuess(food(isGuess = true, confidence = 0.9)))
        assertTrue(AIScanDerive.showsGuess(food(confidence = 0.39)))
        assertFalse(AIScanDerive.showsGuess(food(confidence = 0.4)))
        assertFalse(AIScanDerive.showsGuess(food(confidence = 0.85)))
    }

    // MARK: - Rescaling

    @Test
    fun `scaled grams round half away from zero`() {
        assertEquals(150.0, AIScanDerive.scaledGrams(200.0, 0.75), 1e-9)
        assertEquals(220.0, AIScanDerive.scaledGrams(200.0, 1.10), 1e-9)
        // 45 * 1.1 = 49.5 -> 50, not 49 (Swift `.rounded()`, not banker's rounding)
        assertEquals(50.0, AIScanDerive.scaledGrams(45.0, 1.10), 1e-9)
    }

    @Test
    fun `applying grams to a v1 item rescales kcal and macros proportionally`() {
        val edits = AIScanEdits(listOf(food(name = "rice", grams = 200.0, kcal = 260.0, protein = 6.0, carbs = 56.0, fat = 1.0)))
        val scaled = edits.setGrams("rice", 100.0).foods
        assertEquals(100.0, scaled[0].grams, 1e-9)
        assertEquals(130.0, scaled[0].kcal, 1e-9)
        assertEquals(3.0, scaled[0].protein, 1e-9)
        assertEquals(28.0, scaled[0].carbs, 1e-9)
        assertEquals(0.5, scaled[0].fat, 1e-9)
    }

    @Test
    fun `applying grams ignores unknown ids and non-positive portions`() {
        val edits = AIScanEdits(listOf(food(name = "rice")))
        assertEquals(edits, edits.setGrams("nope", 100.0))
        assertEquals(edits, edits.setGrams("rice", 0.0))
        assertEquals(edits, edits.setGrams("rice", -5.0))
    }

    @Test
    fun `the scale menu offers minus 25 minus 10 plus 10 plus 25`() {
        assertEquals(listOf(-0.25, -0.10, 0.10, 0.25), AIScanDerive.ScaleDeltas)
    }

    // MARK: - Custom grams

    @Test
    fun `custom grams accepts commas and rejects junk`() {
        assertEquals(120.0, AIScanDerive.customGrams("120")!!, 1e-9)
        assertEquals(12.5, AIScanDerive.customGrams(" 12,5 ")!!, 1e-9)
        assertEquals(12.5, AIScanDerive.customGrams("12.5")!!, 1e-9)
        assertNull(AIScanDerive.customGrams(""))
        assertNull(AIScanDerive.customGrams("0"))
        assertNull(AIScanDerive.customGrams("-30"))
        assertNull(AIScanDerive.customGrams("abc"))
    }

    // MARK: - Logging

    @Test
    fun `only foods with kcal or grams are logged`() {
        val foods = listOf(
            food(name = "a", grams = 100.0, kcal = 200.0),
            food(name = "b", grams = 0.0, kcal = 0.0),
            food(name = "c", grams = 0.0, kcal = 10.0),
            food(name = "d", grams = 30.0, kcal = 0.0),
        )
        assertEquals(listOf("a", "c", "d"), AIScanDerive.loggable(foods).map { it.name })
    }

    // MARK: - Tag anchors

    @Test
    fun `six detection anchors in the documented order`() {
        assertEquals(6, AIScanDerive.TagAnchors.size)
        assertEquals(0.11f to 0.13f, AIScanDerive.TagAnchors.first())
        assertEquals(0.40f to 0.42f, AIScanDerive.TagAnchors.last())
    }
}
