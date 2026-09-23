package app.notomorrow.net

import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIFood
import app.notomorrow.net.dto.NtJson
import app.notomorrow.net.dto.LabelReading
import app.notomorrow.service.AIFinalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tolerant AI decoder (`docs/android-architecture.md`, "Tests"): the backend sends `proteinG`,
 * Claude replies with `protein_g`, the mock uses `protein`, and any of them may arrive as a JSON
 * string. `isGuess` defaults to `false` like `AIFood.init(from:)` in `Services/BackendClient.swift`
 * (contract §11). One deliberate divergence stays pinned: `grams` / `kcal` default to 0 when absent
 * (Swift throws `keyNotFound`).
 *
 * Backend v2 (contract §8): every v2 field is optional, so an old server's v1 answer still decodes,
 * and a malformed optional field is dropped instead of failing the estimate.
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
    fun `isGuess defaults to false like iOS`() {
        assertFalse(food("""{"name":"Oil","grams":10,"kcal":90,"confidence":0.3}""").isGuess)
        assertFalse(food("""{"name":"Rice","grams":10,"kcal":90,"confidence":0.9}""").isGuess)
    }

    @Test
    fun `an explicit isGuess is read in both spellings`() {
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

    // MARK: extractJsonObject

    @Test
    fun `extractJsonObject strips fences and surrounding prose`() {
        assertEquals("""{"foods":[]}""", AIFinalizer.extractJsonObject("```json\n{\"foods\":[]}\n```"))
        assertEquals("""{"foods":[]}""", AIFinalizer.extractJsonObject("```\n{\"foods\":[]}\n```"))
        assertEquals(
            """{"foods":[{"name":"a"}]}""",
            AIFinalizer.extractJsonObject("""Here is the plate: {"foods":[{"name":"a"}]} — hope that helps!"""),
        )
    }

    @Test
    fun `extractJsonObject returns null when there is no object`() {
        assertNull(AIFinalizer.extractJsonObject("I could not see the food."))
        assertNull(AIFinalizer.extractJsonObject("} {"))
    }

    // MARK: Backend v2

    private val v2 = """
        {"version":2,"foods":[{"name":"Pierogi ruskie","grams":210,"kcal":430.5,"protein":12.6,"carbs":60.9,
          "fat":14.7,"proteinG":12.6,"carbsG":60.9,"fatG":14.7,"confidence":0.65,"isGuess":false,"barcode":"",
          "nutritionSource":"generic_table","cooking":"boiled","genericKey":"pierogi_ruskie","portionCount":6,
          "portionUnit":"szt.","gramsPerUnit":35,"per100":{"kcal":205,"protein":6,"carbs":29,"fat":7,"alcohol":0},
          "adjustments":["generic_table","confidence_capped"]}],
         "totals":{"kcal":430.5,"protein":12.6,"carbs":60.9,"fat":14.7},"overallConfidence":0.65,
         "scaleReferenceUsed":"none","assumptions":["a"],"questions":[],
         "skipped":[{"index":1,"name":"Sos","reason":"invalid_grams"}]}
    """.trimIndent()

    @Test
    fun `a v2 answer decodes its per100, portion and provenance fields`() {
        val e = NtJson.decodeFromString(AIEstimate.serializer(), v2)
        val f = e.foods.single()
        assertEquals(205.0, f.per100!!.kcal, 0.0)
        assertEquals(6.0, f.portionCount!!, 0.0)
        assertEquals("szt.", f.portionUnit)
        assertEquals(35.0, f.gramsPerUnit!!, 0.0)
        assertEquals("generic_table", f.nutritionSource)
        assertEquals("boiled", f.cooking)
        assertEquals("pierogi_ruskie", f.genericKey)
        assertEquals("", f.barcode)
        assertEquals(listOf("generic_table", "confidence_capped"), f.adjustments)
        assertEquals(2, e.version)
        assertEquals(430.5, e.totals!!.kcal, 0.0)
        assertEquals("invalid_grams", e.skipped!!.single().reason)
        assertEquals("none", e.scaleReferenceUsed)
    }

    @Test
    fun `a v1 answer still decodes with the v2 fields null`() {
        val json = """{"foods":[{"name":"Rice","grams":180,"kcal":230,"proteinG":5,"carbsG":50,"fatG":1,
            "confidence":0.8,"isGuess":false}],"overallConfidence":0.8,"assumptions":[],"questions":[]}"""
        val e = NtJson.decodeFromString(AIEstimate.serializer(), json)
        val f = e.foods.single()
        assertNull(f.per100)
        assertNull(f.portionCount)
        assertNull(f.nutritionSource)
        assertNull(e.version)
        assertNull(e.totals)
        assertNull(e.skipped)
    }

    @Test
    fun `a malformed optional v2 field is dropped, not fatal`() {
        val f = food("""{"name":"a","grams":10,"kcal":10,"per100":{"kcal":"x"},"portionCount":"two","adjustments":[1]}""")
        assertNull(f.per100)
        assertNull(f.portionCount)
        assertNull(f.adjustments)
        assertEquals(10.0, f.kcal, 0.0)
    }

    @Test
    fun `rescaling with per100 follows the finalizer and keeps the count`() {
        val f = NtJson.decodeFromString(AIEstimate.serializer(), v2).foods.single()
        val scaled = f.scaled(245.04)
        assertEquals(245.0, scaled.grams, 0.0)
        assertEquals(502.3, scaled.kcal, 1e-9) // round1(205 × 245 / 100)
        assertEquals(6.0, scaled.portionCount!!, 0.0)
        assertEquals(40.8, scaled.gramsPerUnit!!, 1e-9) // round1(245 / 6)
        val seven = f.withCount(7.0)
        assertEquals(245.0, seven.grams, 0.0)
        assertEquals(7.0, seven.portionCount!!, 0.0)
        assertEquals(35.0, seven.gramsPerUnit!!, 0.0)
        val heavier = f.withGramsPerUnit(40.0)
        assertEquals(240.0, heavier.grams, 0.0)
        assertEquals(6.0, heavier.portionCount!!, 0.0)
        assertEquals("szt.", f.unitName)
        assertEquals(205.0, f.kcalPer100!!, 0.0)
    }

    @Test
    fun `an item without portion fields counts as one unit of its grams`() {
        val f = AIFood.of("Rice", grams = 150.0, kcal = 195.0, protein = 4.0, carbs = 42.0, fat = 0.4, confidence = 0.8)
        assertEquals(1.0, f.units, 0.0)
        assertEquals(150.0, f.unitGrams, 0.0)
        assertNull(f.unitName)
        assertEquals(130.0, f.kcalPer100!!, 1e-9)
        assertEquals(300.0, f.withCount(2.0).grams, 1e-9)
        assertEquals(390.0, f.withCount(2.0).kcal, 1e-9)
    }

    // MARK: Label reading

    @Test
    fun `a label reading decodes, including an illegible one`() {
        val legible = NtJson.decodeFromString(
            LabelReading.serializer(),
            """{"version":1,"legible":true,"unreadableReason":null,"basis":"per100g","energyFrom":"kcal","name":"Skyr",
               "brand":"Piątnica","per100":{"kcal":62,"protein":11,"carbs":4,"fat":0.2,"fiber":null,"sugar":4,"salt":0.13},
               "servingSizeG":150,"packageSizeG":null,"barcode":"","confidence":0.9,"needsReview":false}""",
        )
        assertTrue(legible.legible)
        val per100 = legible.per100!!
        assertEquals(62.0, per100.kcal, 0.0)
        assertNull(per100.fiber)
        assertEquals(0.13, per100.salt!!, 0.0)
        assertEquals(150.0, legible.servingSizeG!!, 0.0)
        assertEquals("Piątnica", legible.brand)

        val illegible = NtJson.decodeFromString(
            LabelReading.serializer(),
            """{"version":1,"legible":false,"unreadableReason":"illegible","basis":"per100g","energyFrom":null,
               "name":"Ciastka","brand":"","per100":null,"servingSizeG":null,"packageSizeG":null,"barcode":"",
               "confidence":0.5,"needsReview":false}""",
        )
        assertFalse(illegible.legible)
        assertEquals("illegible", illegible.unreadableReason)
        assertNull(illegible.per100)
        assertEquals("Ciastka", illegible.name)
    }
}
