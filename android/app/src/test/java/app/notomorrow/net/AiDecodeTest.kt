package app.notomorrow.net

import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIFood
import app.notomorrow.net.dto.NtJson
import app.notomorrow.service.AIEstimatePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tolerant AI decoder (`docs/android-architecture.md`, "Tests"): the backend sends `proteinG`,
 * Claude replies with `protein_g`, the mock uses `protein`, and any of them may arrive as a JSON
 * string. Two deliberate divergences from `AIFood.init(from:)` in `Services/BackendClient.swift`
 * are pinned here because the spec asks for them and Swift does not do them:
 *
 *  - `isGuess` falls back to `confidence < 0.5` (Swift falls back to `false`);
 *  - `grams` / `kcal` default to 0 when absent (Swift throws `keyNotFound`).
 */
class AiDecodeTest {

    private fun food(json: String): AIFood = NtJson.decodeFromString(AIFood.serializer(), json)

    // MARK: Key tolerance

    @Test
    fun `macro keys are accepted in all three spellings`() {
        val camel = food("""{"name":"Rice","grams":180,"kcal":230,"proteinG":5,"carbsG":50,"fatG":1,"confidence":0.8}""")
        val snake = food("""{"name":"Rice","grams":180,"kcal":230,"protein_g":5,"carbs_g":50,"fat_g":1,"confidence":0.8}""")
        val plain = food("""{"name":"Rice","grams":180,"kcal":230,"protein":5,"carbs":50,"fat":1,"confidence":0.8}""")
        for (f in listOf(camel, snake, plain)) {
            assertEquals(5.0, f.protein, 0.0001)
            assertEquals(50.0, f.carbs, 0.0001)
            assertEquals(1.0, f.fat, 0.0001)
        }
    }

    @Test
    fun `grams accepts estimated_grams and the camel form`() {
        assertEquals(120.0, food("""{"name":"a","estimated_grams":120,"kcal":100,"confidence":0.9}""").grams, 0.0001)
        assertEquals(120.0, food("""{"name":"a","estimatedGrams":120,"kcal":100,"confidence":0.9}""").grams, 0.0001)
    }

    @Test
    fun `kcal accepts calories and name accepts name_en`() {
        val f = food("""{"name_en":"Chicken breast","grams":150,"calories":248,"confidence":0.9}""")
        assertEquals("Chicken breast", f.name)
        assertEquals(248.0, f.kcal, 0.0001)
    }

    @Test
    fun `numbers may arrive as JSON strings`() {
        val f = food("""{"name":"a","grams":"180.5","kcal":"230","protein_g":"5.25","confidence":"0.8"}""")
        assertEquals(180.5, f.grams, 0.0001)
        assertEquals(230.0, f.kcal, 0.0001)
        assertEquals(5.25, f.protein, 0.0001)
        assertEquals(0.8, f.confidence, 0.0001)
    }

    // MARK: Defaults

    @Test
    fun `missing macros default to zero`() {
        val f = food("""{"name":"Olive oil","grams":14,"kcal":124,"confidence":0.3}""")
        assertEquals(0.0, f.protein, 0.0001)
        assertEquals(0.0, f.carbs, 0.0001)
        assertEquals(0.0, f.fat, 0.0001)
    }

    @Test
    fun `missing grams and kcal default to zero rather than throwing`() {
        val f = food("""{"name":"Sauce","confidence":0.6}""")
        assertEquals(0.0, f.grams, 0.0001)
        assertEquals(0.0, f.kcal, 0.0001)
    }

    @Test
    fun `missing confidence defaults to one half`() {
        assertEquals(0.5, food("""{"name":"a","grams":10,"kcal":10}""").confidence, 0.0001)
    }

    @Test
    fun `confidence is clamped to zero and one`() {
        assertEquals(1.0, food("""{"name":"a","grams":1,"kcal":1,"confidence":4}""").confidence, 0.0001)
        assertEquals(0.0, food("""{"name":"a","grams":1,"kcal":1,"confidence":-2}""").confidence, 0.0001)
    }

    // MARK: isGuess

    @Test
    fun `isGuess defaults to confidence below one half`() {
        assertTrue(food("""{"name":"Oil","grams":10,"kcal":90,"confidence":0.3}""").isGuess)
        assertFalse(food("""{"name":"Rice","grams":10,"kcal":90,"confidence":0.5}""").isGuess)
        assertFalse(food("""{"name":"Rice","grams":10,"kcal":90,"confidence":0.9}""").isGuess)
    }

    @Test
    fun `an explicit isGuess wins over the confidence fallback in both spellings`() {
        assertFalse(food("""{"name":"a","grams":1,"kcal":1,"confidence":0.1,"isGuess":false}""").isGuess)
        assertFalse(food("""{"name":"a","grams":1,"kcal":1,"confidence":0.1,"is_guess":false}""").isGuess)
        assertTrue(food("""{"name":"a","grams":1,"kcal":1,"confidence":0.9,"is_guess":true}""").isGuess)
    }

    // MARK: The plate

    @Test
    fun `overallConfidence defaults to the item mean`() {
        val json = """
            {"foods":[{"name":"a","grams":1,"kcal":1,"confidence":0.4},
                      {"name":"b","grams":1,"kcal":1,"confidence":0.8}]}
        """.trimIndent()
        val estimate = NtJson.decodeFromString(AIEstimate.serializer(), json)
        assertEquals(0.6, estimate.overallConfidence, 0.0001)
    }

    @Test
    fun `overallConfidence is clamped and accepts the snake form`() {
        val json = """{"items":[{"name":"a","grams":1,"kcal":1,"confidence":0.4}],"overall_confidence":1.7}"""
        assertEquals(1.0, NtJson.decodeFromString(AIEstimate.serializer(), json).overallConfidence, 0.0001)
    }

    @Test
    fun `totals sum the plate`() {
        val json = """
            {"foods":[{"name":"a","grams":100,"kcal":200,"protein_g":10,"carbs_g":20,"fat_g":5,"confidence":0.9},
                      {"name":"b","grams":50,"kcal":100,"protein_g":4,"carbs_g":8,"fat_g":2,"confidence":0.7}],
             "overallConfidence":0.8}
        """.trimIndent()
        val e = NtJson.decodeFromString(AIEstimate.serializer(), json)
        assertEquals(300.0, e.totalKcal, 0.0001)
        assertEquals(14.0, e.totalProtein, 0.0001)
        assertEquals(28.0, e.totalCarbs, 0.0001)
        assertEquals(7.0, e.totalFat, 0.0001)
    }

    @Test
    fun `scaled keeps kcal and macros proportional and ignores a zero-gram food`() {
        val f = AIFood.of("Rice", grams = 100.0, kcal = 200.0, protein = 10.0, carbs = 40.0, fat = 2.0, confidence = 0.9)
        val half = f.scaled(50.0)
        assertEquals(50.0, half.grams, 0.0001)
        assertEquals(100.0, half.kcal, 0.0001)
        assertEquals(5.0, half.protein, 0.0001)
        val zero = AIFood.of("Air", grams = 0.0, kcal = 0.0, protein = 0.0, carbs = 0.0, fat = 0.0, confidence = 0.5)
        assertEquals(0.0, zero.scaled(120.0).grams, 0.0001)
    }

    // MARK: extractJson

    @Test
    fun `extractJson strips fences and surrounding prose`() {
        assertEquals("""{"foods":[]}""", AIEstimatePrompt.extractJson("```json\n{\"foods\":[]}\n```"))
        assertEquals("""{"foods":[]}""", AIEstimatePrompt.extractJson("```\n{\"foods\":[]}\n```"))
        assertEquals(
            """{"foods":[{"name":"a"}]}""",
            AIEstimatePrompt.extractJson("""Here is the plate: {"foods":[{"name":"a"}]} — hope that helps!"""),
        )
    }

    @Test
    fun `extractJson returns null when there is no object`() {
        assertNull(AIEstimatePrompt.extractJson("I could not see the food."))
        assertNull(AIEstimatePrompt.extractJson("} {"))
    }
}
