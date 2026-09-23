package app.notomorrow.service

import app.notomorrow.R
import app.notomorrow.data.entity.makeFoodItem
import app.notomorrow.feature.fuel.PortionFood
import app.notomorrow.net.dto.NtJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * `FoodSearchMappingTests.swift`: Open Food Facts → app mapping (name fallbacks, estimated
 * nutrition, the found / partial / not-found split), the search-a-licious decoder, and the search
 * helpers (Lucene-safe terms, fallback rule, error messages, request window, local matching).
 */
class BarcodeLookupMappingTest {

    private fun outcome(fixture: String, locale: String = "pl"): BarcodeLookup =
        FoodSearchService.outcome(OffFixtures.response(fixture), locale)

    private fun outcomeOf(json: String, locale: String = "pl"): BarcodeLookup =
        FoodSearchService.outcome(OffProductResponse.from(NtJson.parseToJsonElement(json).jsonObject), locale)

    private fun found(fixture: String, locale: String = "pl") =
        (outcome(fixture, locale) as BarcodeLookup.Found).candidate

    private fun product(json: String): OffProduct = OffProduct.from(NtJson.parseToJsonElement(json).jsonObject)

    // MARK: - Fixtures

    @Test
    fun `an English-only name is rescued`() {
        val food = found(OffFixtures.NAME_ONLY_ENGLISH)
        assertEquals("off:5900056007181", food.id)
        assertEquals("Bagatelka", food.name)
        assertEquals("Kopernik Toruń", food.brand)
        assertEquals(399.0, food.kcalPer100, 0.0)
        assertEquals(6.6, food.proteinPer100, 0.0)
        assertEquals(73.0, food.carbsPer100, 0.0)
        assertEquals(8.4, food.fatPer100, 0.0)
        assertEquals(100.0, food.servingSizeG!!, 0.0)
        assertFalse(food.isEstimated)
        assertEquals("Bagatelka", found(OffFixtures.NAME_ONLY_ENGLISH, locale = "en").name)
    }

    @Test
    fun `a nameless product is named brand and quantity`() {
        val food = found(OffFixtures.NO_NAME_BRAND_QUANTITY)
        assertEquals("Pudliszki 200 g", food.name)
        assertEquals("Pudliszki", food.brand)
        assertEquals(104.0, food.kcalPer100, 0.0)
        assertEquals(2.5, food.fiberPer100!!, 0.0)
        assertEquals(200.0, food.servingSizeG!!, 0.0)
        assertFalse("an empty nutriments_estimated block changes nothing", food.isEstimated)
    }

    @Test
    fun `estimated nutrition is used and flagged`() {
        val food = found(OffFixtures.ESTIMATED_ONLY)
        assertEquals("Mini kiwi", food.name)
        assertEquals("Lidl", food.brand)
        assertEquals(60.5, food.kcalPer100, 0.0)
        assertEquals(0.88, food.proteinPer100, 0.0)
        assertEquals(11.0, food.carbsPer100, 0.0)
        assertEquals(0.6, food.fatPer100, 0.0)
        assertEquals(2.4, food.fiberPer100!!, 0.0)
        assertTrue(food.isEstimated)
        assertTrue(PortionFood.Candidate(food).isEstimated)
        assertFalse("the flag is not persisted", PortionFood.Item(food.makeFoodItem()).isEstimated)
    }

    @Test
    fun `a name without nutrition is partial`() {
        val result = outcome(OffFixtures.NAME_NO_NUTRITION)
        assertEquals(
            BarcodeLookup.Partial(
                ProductStub(
                    code = "2050401935713",
                    name = "Łosoś świeży",
                    brand = "MOWI",
                    quantity = "150 g",
                    servingSizeG = 150.0,
                    imageURL = "https://images.openfoodfacts.org/images/products/205/040/193/5713/front_pl.16.200.jpg",
                ),
            ),
            result,
        )
    }

    @Test
    fun `an empty stub and a missing product are not found`() {
        assertEquals(BarcodeLookup.NotFound, outcome(OffFixtures.EMPTY_STUB))
        assertEquals(BarcodeLookup.NotFound, outcome(OffFixtures.NOT_FOUND))
    }

    // MARK: - Names

    @Test
    fun `the Polish name chain`() {
        // lc=pl does not localise product_name, so the Polish field wins over a main name in another language.
        val kefir = product("""{"code":"1","lang":"en","product_name":"Natural Kefir","product_name_pl":"Kefir naturalny Polski","product_name_en":"Kefir"}""")
        assertEquals("Kefir naturalny Polski", FoodSearchService.displayName(kefir, "pl"))
        assertEquals("Kefir", FoodSearchService.displayName(kefir, "en"))

        // A Polish main name beats the English one; a Bulgarian main name does not.
        val polishMain = product("""{"code":"1","lang":"pl","product_name":"Mleko","product_name_en":"Milk"}""")
        assertEquals("Mleko", FoodSearchService.displayName(polishMain, "pl"))
        val bulgarianMain = product("""{"code":"1","lang":"bg","product_name":"Сирене","product_name_en":"White cheese"}""")
        assertEquals("White cheese", FoodSearchService.displayName(bulgarianMain, "pl"))
        assertEquals("Сирене", FoodSearchService.displayName(product("""{"code":"1","lang":"bg","product_name":"Сирене"}"""), "pl"))

        val generic = product("""{"code":"1","product_name":" ","generic_name_pl":"Ser żółty","generic_name":"Cheese"}""")
        assertEquals("Ser żółty", FoodSearchService.displayName(generic, "pl"))
        val abbreviated = product("""{"code":"1","abbreviated_product_name":"Nutella t.400"}""")
        assertEquals("Nutella t.400", FoodSearchService.displayName(abbreviated, "pl"))
        assertEquals("Kinga", FoodSearchService.displayName(product("""{"code":"1","brands":"Kinga"}"""), "pl"))
        assertNull(FoodSearchService.displayName(product("""{"code":"1","brands":" ","quantity":"700ml"}"""), "pl"))
    }

    @Test
    fun `brands decode from a string or an array`() {
        assertEquals("Lidl, Nergi", product("""{"brands":"Lidl, Nergi"}""").brands)
        val array = product("""{"brands":["Piątnica","Mlekpol"]}""")
        assertEquals("Piątnica, Mlekpol", array.brands)
        assertEquals("Piątnica", FoodSearchService.firstBrand(array))
        assertNull(product("""{"brands":{"pl":"x"}}""").brands)
        assertNull(product("""{"brands":["Piątnica",1]}""").brands)
    }

    // MARK: - Nutrition rules

    @Test
    fun `label values win over the estimate`() {
        val p = product("""{"code":"1","product_name":"x","nutriments":{"energy-kj_100g":"418,4"},"nutriments_estimated":{"energy-kcal_100g":300}}""")
        val n = FoodSearchService.per100(p)!!
        assertEquals(100.0, n.kcal, 0.001)
        assertFalse(n.isEstimated)
    }

    @Test
    fun `implausible label values are not replaced by the estimate`() {
        // The label block has energy, so it is judged on its own; bad crowd data becomes a label-form stub.
        val result = outcomeOf(
            """{"status":1,"product":{"code":"5","product_name":"Masło","nutriments":{"energy-kcal_100g":7440},"nutriments_estimated":{"energy-kcal_100g":744,"fat_100g":82}}}""",
        )
        assertEquals("Masło", (result as BarcodeLookup.Partial).stub.name)

        val badMacro = product("""{"code":"1","product_name":"x","nutriments":{"energy-kcal_100g":100,"fat_100g":120}}""")
        assertNull(FoodSearchService.per100(badMacro))
    }

    @Test
    fun `no energy anywhere is not a candidate`() {
        val p = product("""{"code":"1","product_name":"x","nutriments":{"proteins_100g":10},"nutriments_estimated":{}}""")
        assertNull(FoodSearchService.per100(p))
        assertNull(FoodSearchService.candidate(p, "pl"))
    }

    @Test
    fun `the code falls back to the response code`() {
        val result = outcomeOf("""{"code":"5900000000001","status":1,"product":{"product_name":"x","nutriments":{"energy-kcal_100g":50}}}""")
        assertEquals("off:5900000000001", (result as BarcodeLookup.Found).candidate.id)
    }

    // MARK: - search-a-licious

    @Test
    fun `search-a-licious hits decode`() {
        val hits = searchALiciousHits(OffFixtures.json(OffFixtures.SEARCH_A_LICIOUS))
        assertEquals(5, hits.size)
        val foods = hits.mapNotNull { FoodSearchService.candidate(it, "pl") }
        assertEquals(
            "the hit without nutriments is dropped",
            listOf("0444444143006", "5900531000935", "5900531050015", "5900512987378"),
            foods.map { it.code },
        )
        assertEquals("Serek wiejski", foods[0].name)
        assertEquals("Piątnica", foods[0].brand)
        assertEquals(97.0, foods[0].kcalPer100, 0.0)
        assertEquals("Serek wiejski bez laktozy", foods[3].name)
        assertNull(foods[3].brand)
    }

    @Test
    fun `search terms are Lucene-safe`() {
        assertEquals("serek wiejski", FoodSearchService.searchTerms("serek wiejski"))
        assertEquals("kawa latte", FoodSearchService.searchTerms("kawa: latte"))
        assertEquals("Coca Cola 0,5 l", FoodSearchService.searchTerms("Coca-Cola (0,5 l)"))
        assertEquals("jogurt grecki", FoodSearchService.searchTerms("\"jogurt\" +grecki*"))
        assertEquals("", FoodSearchService.searchTerms("!!"))
    }

    @Test
    fun `only server trouble falls back to the legacy search`() {
        assertTrue(FoodSearchService.fallsBack(FoodSearchError.Busy))
        assertTrue(FoodSearchService.fallsBack(FoodSearchError.Unreachable))
        assertTrue(FoodSearchService.fallsBack(FoodSearchError.BadResponse(400)))
        assertTrue(FoodSearchService.fallsBack(SerializationException("odd body")))
        assertFalse(FoodSearchService.fallsBack(FoodSearchError.RateLimited))
        assertFalse(FoodSearchService.fallsBack(FoodSearchError.Offline))
        assertFalse(FoodSearchService.fallsBack(CancellationException()))
    }

    // MARK: - Errors

    @Test
    fun `errors say what went wrong`() {
        assertEquals(FoodSearchError.RateLimited, FoodSearchError.from(429))
        assertEquals(FoodSearchError.Busy, FoodSearchError.from(503))
        assertEquals(FoodSearchError.Busy, FoodSearchError.from(502))
        assertEquals(FoodSearchError.BadResponse(404), FoodSearchError.from(404))
        assertEquals(FoodSearchError.Offline, FoodSearchError.transport(online = false))
        assertEquals(FoodSearchError.Unreachable, FoodSearchError.transport(online = true))
        assertTrue(FoodSearchError.RateLimited.isTransient)
        assertTrue(FoodSearchError.Busy.isTransient)
        assertFalse(FoodSearchError.Offline.isTransient)

        val messages = listOf(
            FoodSearchError.RateLimited,
            FoodSearchError.Busy,
            FoodSearchError.Offline,
            FoodSearchError.Unreachable,
        ).map { it.messageRes }
        assertEquals("rate limit, busy, offline and no answer each read differently", 4, messages.toSet().size)
        assertEquals(R.string.fuel_search_error_rateLimited, FoodSearchError.RateLimited.messageRes)
        assertEquals(R.string.fuel_search_error_busy, FoodSearchError.Busy.messageRes)
        assertEquals(R.string.error_network, FoodSearchError.Offline.messageRes)
        assertEquals(R.string.fuel_search_error_network, FoodSearchError.Unreachable.messageRes)
        assertEquals(
            FoodSearchError.messageRes(FoodSearchError.Unreachable),
            FoodSearchError.messageRes(IllegalStateException("anything else")),
        )
    }

    @Test
    fun `a rate-limited scan reads as busy, not as too many searches`() {
        assertEquals(R.string.fuel_search_error_busy, FoodSearchError.lookupMessageRes(FoodSearchError.RateLimited))
        assertEquals(R.string.fuel_search_error_busy, FoodSearchError.lookupMessageRes(FoodSearchError.Busy))
        assertEquals(R.string.error_network, FoodSearchError.lookupMessageRes(FoodSearchError.Offline))
        assertEquals(R.string.fuel_search_error_network, FoodSearchError.lookupMessageRes(FoodSearchError.Unreachable))
        assertEquals(R.string.fuel_search_error_network, FoodSearchError.lookupMessageRes(IllegalStateException("x")))
    }

    // MARK: - Request window

    @Test
    fun `the request window paces searches`() {
        val window = RequestWindow(limit = 2, windowMs = 60_000)
        val t0 = 1_000_000L
        assertEquals(0L, window.reserve(t0, maxWaitMs = 6_000))
        assertEquals(0L, window.reserve(t0 + 1_000, maxWaitMs = 6_000))
        assertNull("the next slot is 58 s away", window.reserve(t0 + 2_000, maxWaitMs = 6_000))
        assertEquals("a refused request books nothing", 2, window.starts.size)
        assertEquals(5_000L, window.reserve(t0 + 55_000, maxWaitMs = 6_000))
        assertEquals(5_000L, window.reserve(t0 + 56_000, maxWaitMs = 6_000))
        assertNull(window.reserve(t0 + 57_000, maxWaitMs = 6_000))
        assertEquals("old starts expire", 0L, window.reserve(t0 + 130_000, maxWaitMs = 6_000))
    }

    // MARK: - Local matching

    @Test
    fun `local matching ignores case and Polish diacritics`() {
        assertEquals("zolty ser", FoodMatch.fold("Żółty SER"))
        assertEquals("losos", FoodMatch.fold("Łosoś"))
        assertEquals("full width", FoodMatch.fold("ｆｕｌｌ width"))
        assertTrue(FoodMatch.matches("zolty ser", name = "Żółty ser Gouda", brand = null))
        assertTrue(FoodMatch.matches("losos", name = "Łosoś świeży", brand = "MOWI"))
        assertTrue(FoodMatch.matches("mlekovita serek", name = "Serek wiejski", brand = "Mlekovita"))
        assertTrue(FoodMatch.matches("  ", name = "Anything", brand = null))
        assertFalse(FoodMatch.matches("serek piatnica", name = "Serek wiejski", brand = "Mlekovita"))
        assertFalse(FoodMatch.matches("jogurt", name = "Serek wiejski", brand = null))
    }
}
