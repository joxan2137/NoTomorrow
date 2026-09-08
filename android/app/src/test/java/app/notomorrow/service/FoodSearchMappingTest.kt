package app.notomorrow.service

import app.notomorrow.net.dto.NtJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `FoodSearchTest` from `docs/android-architecture.md`: the pure halves of the Open Food Facts
 * client — `barcodeForms`, `grams(fromLabel:)`, `LooseNumber` and the product → candidate mapping.
 */
class FoodSearchMappingTest {

    private fun product(json: String): OffProduct =
        OffProduct.from(NtJson.parseToJsonElement(json).jsonObject)

    // MARK: - barcodeForms

    @Test
    fun `13-digit EAN with a leading zero also tries the UPC-A form`() {
        assertEquals(
            listOf("0123456789012", "123456789012"),
            FoodSearchService.barcodeForms("0123456789012"),
        )
    }

    @Test
    fun `13-digit EAN without a leading zero has one form`() {
        assertEquals(listOf("5901234567890"), FoodSearchService.barcodeForms("5901234567890"))
    }

    @Test
    fun `12-digit UPC also tries the zero-padded EAN`() {
        assertEquals(
            listOf("012345678901", "0012345678901"),
            FoodSearchService.barcodeForms("012345678901"),
        )
    }

    @Test
    fun `8-digit EAN has one form`() {
        assertEquals(listOf("12345670"), FoodSearchService.barcodeForms("12345670"))
    }

    @Test
    fun `non-digits are stripped and an empty result yields no forms`() {
        assertEquals(listOf("5901234567890"), FoodSearchService.barcodeForms(" 590-1234 567890 "))
        assertEquals(emptyList<String>(), FoodSearchService.barcodeForms("abc"))
    }

    // MARK: - grams(fromLabel:)

    @Test
    fun `serving labels in grams and millilitres parse`() {
        assertEquals(30.0, FoodSearchService.grams("30 g")!!, 0.0001)
        assertEquals(1.5, FoodSearchService.grams("1,5 g")!!, 0.0001)
        assertNull(FoodSearchService.grams("250 ml"))
        assertEquals(30.0, FoodSearchService.grams("2 x 15g")!!, 0.0001)
        assertEquals(500.0, FoodSearchService.grams("500g")!!, 0.0001)
    }

    @Test
    fun `labels without a mass unit or a positive number are rejected`() {
        assertNull(FoodSearchService.grams("1 piece"))
        assertNull(FoodSearchService.grams("0 g"))
        assertNull(FoodSearchService.grams("one slice"))
    }

    // MARK: - LooseNumber

    @Test
    fun `LooseNumber accepts numbers, numeric strings and comma decimals`() {
        val o = NtJson.parseToJsonElement(
            """{"a":12.5,"b":"12,5","c":"12.5","d":"kJ","e":null}"""
        ).jsonObject
        assertEquals(12.5, LooseNumber.value(o["a"])!!, 0.0001)
        assertEquals(12.5, LooseNumber.value(o["b"])!!, 0.0001)
        assertEquals(12.5, LooseNumber.value(o["c"])!!, 0.0001)
        assertNull(LooseNumber.value(o["d"]))
        assertNull(LooseNumber.value(o["e"]))
        assertNull(LooseNumber.value(o["missing"]))
    }

    @Test
    fun `a numeric code decodes to its text form`() {
        val o = NtJson.parseToJsonElement("""{"code":737628064502,"other":"0123"}""").jsonObject
        assertEquals("737628064502", LooseNumber.text(o["code"]))
        assertEquals("0123", LooseNumber.text(o["other"]))
    }

    @Test
    fun `Polish product with kilojoules remains scannable`() {
        val p = product("""{"code":"5901234123457","product_name_pl":"Skyr naturalny","nutriments":{"energy_100g":"418,4","proteins_100g":10,"carbohydrates_100g":15,"fat_100g":0}}""")
        val candidate = FoodSearchService.candidate(p, "pl")!!
        assertEquals("Skyr naturalny", candidate.name)
        assertEquals(100.0, candidate.kcalPer100, 0.001)
        assertEquals(30.0, FoodSearchService.grams("1 porcja (30 g)")!!, 0.001)
        assertEquals(500.0, FoodSearchService.grams("0,5 kg")!!, 0.001)
    }

    // MARK: - candidate(from:locale:)

    @Test
    fun `a product without energy-kcal_100g is dropped`() {
        val p = product(
            """{"code":"1","product_name":"Rice","nutriments":{"proteins_100g":7}}"""
        )
        assertNull(FoodSearchService.candidate(p, "en"))
    }

    @Test
    fun `a product without a code or a name is dropped`() {
        val noCode = product("""{"product_name":"Rice","nutriments":{"energy-kcal_100g":350}}""")
        assertNull(FoodSearchService.candidate(noCode, "en"))
        val noName = product("""{"code":"1","nutriments":{"energy-kcal_100g":350}}""")
        assertNull(FoodSearchService.candidate(noName, "en"))
    }

    @Test
    fun `Polish name wins under lc=pl and English is the fallback`() {
        val p = product(
            """
            {"code":"5901234567890","product_name":" Rice ","product_name_pl":" Ryż ",
             "nutriments":{"energy-kcal_100g":350}}
            """.trimIndent()
        )
        assertEquals("Ryż", FoodSearchService.candidate(p, "pl")!!.name)
        assertEquals("Rice", FoodSearchService.candidate(p, "en")!!.name)
    }

    @Test
    fun `an empty Polish name falls back to English even under lc=pl`() {
        val p = product(
            """{"code":"1","product_name":"Rice","product_name_pl":"  ","nutriments":{"energy-kcal_100g":350}}"""
        )
        assertEquals("Rice", FoodSearchService.candidate(p, "pl")!!.name)
    }

    @Test
    fun `mapping normalises id, brand, macros and the image url`() {
        val p = product(
            """
            {"code":"5901234567890","product_name":"Skyr","brands":" Isey , Arla ","quantity":"170 g",
             "serving_quantity":"170","serving_size":"170 g","image_front_small_url":"https://x/y.jpg",
             "nutriments":{"energy-kcal_100g":"63","proteins_100g":11,"carbohydrates_100g":4,
                           "fat_100g":0.2,"fiber_100g":0}}
            """.trimIndent()
        )
        val c = FoodSearchService.candidate(p, "en")!!
        assertEquals("off:5901234567890", c.id)
        assertEquals("5901234567890", c.code)
        assertEquals("Isey", c.brand)
        assertEquals("170 g", c.quantity)
        assertEquals("170 g", c.servingLabel)
        assertEquals(170.0, c.servingSizeG!!, 0.0001)
        assertEquals(63.0, c.kcalPer100, 0.0001)
        assertEquals(11.0, c.proteinPer100, 0.0001)
        assertEquals(4.0, c.carbsPer100, 0.0001)
        assertEquals(0.2, c.fatPer100, 0.0001)
        assertEquals(0.0, c.fiberPer100!!, 0.0001)
        assertEquals("https://x/y.jpg", c.imageURL)
    }

    @Test
    fun `serving grams fall back to the label and non-positive values become null`() {
        val fromLabel = product(
            """{"code":"1","product_name":"X","serving_size":"30 g","nutriments":{"energy-kcal_100g":100}}"""
        )
        assertEquals(30.0, FoodSearchService.candidate(fromLabel, "en")!!.servingSizeG!!, 0.0001)

        val zero = product(
            """{"code":"1","product_name":"X","serving_quantity":0,"serving_size":"1 piece",
                "nutriments":{"energy-kcal_100g":100}}"""
        )
        assertNull(FoodSearchService.candidate(zero, "en")!!.servingSizeG)

        val missingMacros = product(
            """{"code":"1","product_name":"X","nutriments":{"energy-kcal_100g":100}}"""
        )
        val c = FoodSearchService.candidate(missingMacros, "en")!!
        assertEquals(0.0, c.proteinPer100, 0.0001)
        assertEquals(0.0, c.carbsPer100, 0.0001)
        assertEquals(0.0, c.fatPer100, 0.0001)
        assertNull(c.fiberPer100)
        assertNull(c.brand)
        assertNull(c.servingLabel)
    }

    // MARK: - languageCode

    @Test
    fun `only a pl-prefixed locale selects Polish`() {
        assertEquals("pl", FoodSearchService.languageCode("pl"))
        assertEquals("pl", FoodSearchService.languageCode("pl_PL"))
        assertEquals("pl", FoodSearchService.languageCode("PL-pl"))
        assertEquals("en", FoodSearchService.languageCode("en_US"))
        assertEquals("en", FoodSearchService.languageCode("de"))
    }
}
