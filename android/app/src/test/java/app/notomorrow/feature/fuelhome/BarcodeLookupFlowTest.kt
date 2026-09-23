package app.notomorrow.feature.fuelhome

import app.notomorrow.R
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.feature.fuel.BarcodeLookupFlow
import app.notomorrow.feature.fuel.FoodSearchViewModel
import app.notomorrow.feature.fuel.FuelDerive
import app.notomorrow.feature.fuel.LabelValues
import app.notomorrow.feature.fuel.PortionFood
import app.notomorrow.feature.fuel.ProductLabel
import app.notomorrow.model.FoodSource
import app.notomorrow.service.BarcodeKey
import app.notomorrow.service.FoodSearchService
import app.notomorrow.service.ProductStub
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The scan flow shared by the Fuel tab and the search sheet (`BarcodeLookupFlow.swift`, plus the
 * saved-food and label halves of `BarcodeKeyTests.swift`): saved foods first under every key,
 * then Open Food Facts, and the prompt each outcome raises; the label form's rules and upsert; the
 * manual-entry check; and the search sheet's saved-food matching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeLookupFlowTest {

    private var requests = 0

    /** Serves [bodies] in order as `(status, body)`, then 599. */
    private fun service(vararg bodies: Pair<Int, String>): FoodSearchService {
        val queue = ArrayDeque(bodies.toList())
        val engine = MockEngine {
            requests++
            val (status, body) = queue.removeFirstOrNull() ?: (599 to "")
            respond(content = body, status = HttpStatusCode.fromValue(status))
        }
        return FoodSearchService(engine = engine, retryDelayMs = 1)
    }

    private fun TestScope.flow(dao: FakeFoodDao, service: FoodSearchService) =
        BarcodeLookupFlow(dao, service, this, locale = { Locale.forLanguageTag("pl-PL") }, retryPauseMs = 1)

    private fun food(id: String, source: FoodSource, barcode: String?, lastUsed: Long?) = FoodItemEntity(
        id = id, name = id, source = source, barcode = barcode,
        kcalPer100 = 100.0, proteinPer100 = 1.0, carbsPer100 = 1.0, fatPer100 = 1.0, lastUsedAt = lastUsed,
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/off/$name")).bufferedReader().use { it.readText() }

    // MARK: - Saved foods

    @Test
    fun `a scan finds saved foods under every key`() = runTest {
        val dao = FakeFoodDao(
            listOf(
                food("label:2412345", FoodSource.Custom, "2412345", null),
                food("off:049000028911", FoodSource.OpenFoodFacts, "049000028911", 10),
                food("off:0049000028911", FoodSource.OpenFoodFacts, "0049000028911", 20),
            ),
        )
        assertEquals(
            "another package of the same deli item",
            "label:2412345",
            BarcodeLookupFlow.savedFood("2412345009995", dao)?.id,
        )
        assertEquals(
            "the UPC-A and EAN-13 forms both match; the most recent wins",
            "off:0049000028911",
            BarcodeLookupFlow.savedFood("049000028911", dao)?.id,
        )
        assertNull(BarcodeLookupFlow.savedFood("5901234123457", dao))
    }

    @Test
    fun `a saved food opens without the network`() = runTest {
        val dao = FakeFoodDao(listOf(food("label:2412345", FoodSource.Custom, "2412345", null)))
        val flow = flow(dao, service())
        var opened: PortionFood? = null
        flow.run("2412345011111") { opened = it }
        assertEquals("label:2412345", (opened as PortionFood.Item).item.id)
        assertEquals(0, requests)
        assertFalse(flow.state.value.isLookingUp)
    }

    // MARK: - Outcomes

    @Test
    fun `a product with nutrition opens the portion sheet`() = runTest {
        val flow = flow(FakeFoodDao(), service(200 to fixture("off_5900056007181_name_only_en.json")))
        var opened: PortionFood? = null
        flow.run("5900056007181") { opened = it }
        assertEquals("Bagatelka", (opened as PortionFood.Candidate).candidate.name)
        assertNull(flow.state.value.prompt)
    }

    @Test
    fun `a name without nutrition raises the partial prompt`() = runTest {
        val flow = flow(FakeFoodDao(), service(200 to fixture("off_2050401935713_name_no_nutrition.json")))
        flow.run("2050401935713") { error("nothing to size") }
        val prompt = flow.state.value.prompt as BarcodeLookupFlow.Prompt.Partial
        assertEquals("2050401935713", prompt.code)
        assertEquals("Łosoś świeży · MOWI", BarcodeLookupFlow.partialTitle(prompt.stub))
        assertEquals("Łosoś świeży", BarcodeLookupFlow.partialTitle(prompt.stub.copy(brand = null)))
    }

    @Test
    fun `an unknown code raises the not-found prompt`() = runTest {
        val flow = flow(FakeFoodDao(), service(404 to fixture("off_404.json")))
        flow.run("6903082830807") { error("nothing to size") }
        assertEquals(BarcodeLookupFlow.Prompt.NotFound("6903082830807"), flow.state.value.prompt)
    }

    @Test
    fun `a busy database raises a failed prompt with the busy message`() = runTest {
        val flow = flow(FakeFoodDao(), service(503 to "", 503 to ""))
        flow.run("6903082830807") { error("nothing to size") }
        assertEquals(
            BarcodeLookupFlow.Prompt.Failed(R.string.fuel_search_error_busy, "6903082830807"),
            flow.state.value.prompt,
        )
        assertEquals("one retry", 2, requests)
    }

    @Test
    fun `a rate-limited scan raises the busy message, not too many searches`() = runTest {
        val flow = flow(FakeFoodDao(), service(429 to "", 429 to ""))
        flow.run("6903082830807") { error("nothing to size") }
        assertEquals(
            BarcodeLookupFlow.Prompt.Failed(R.string.fuel_search_error_busy, "6903082830807"),
            flow.state.value.prompt,
        )
        assertEquals("one retry", 2, requests)
    }

    @Test
    fun `a name made from the brand does not repeat it in the partial title`() {
        val stub = ProductStub(code = "5900783000000", name = "Pudliszki 200 g", brand = "Pudliszki")
        assertEquals("Pudliszki 200 g", BarcodeLookupFlow.partialTitle(stub))
        assertEquals("the brand alone, any case", "pudliszki", BarcodeLookupFlow.partialTitle(stub.copy(name = "pudliszki")))
        assertEquals(
            "a longer word that merely starts with the brand keeps it",
            "Mlekovita jogurt · Mleko",
            BarcodeLookupFlow.partialTitle(stub.copy(name = "Mlekovita jogurt", brand = "Mleko")),
        )
        assertEquals("Łosoś świeży · MOWI", BarcodeLookupFlow.partialTitle(stub.copy(name = "Łosoś świeży", brand = "MOWI")))
    }

    @Test
    fun `try again repeats the lookup`() = runTest {
        val flow = flow(FakeFoodDao(), service(503 to "", 503 to "", 200 to fixture("off_5900056007181_name_only_en.json")))
        flow.run("5900056007181") { error("the first try fails") }
        flow.dismissPrompt()
        var opened: PortionFood? = null
        flow.retry("5900056007181") { opened = it }
        coroutineContext.job.children.toList().joinAll()
        assertNotNull(opened)
        assertNull(flow.state.value.prompt)
    }

    @Test
    fun `a superseded lookup never hides the pill of the one that replaced it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        val secondRead = CompletableDeferred<Unit>()
        val saved = food("label:2412345", FoodSource.Custom, "2412345", null)
        var reads = 0
        val dao = object : FoodDao by FakeFoodDao() {
            override suspend fun preferredByBarcode(keys: List<String>): FoodItemEntity? {
                val first = reads++ == 0
                // Like a Room query: the read runs to the end on its own executor even after a
                // cancel, and the caller sees the cancel only when it resumes.
                withContext(NonCancellable) { (if (first) firstRead else secondRead).await() }
                currentCoroutineContext().ensureActive()
                return if (first) null else saved
            }
        }
        val flow = BarcodeLookupFlow(dao, service(), this, locale = { Locale.forLanguageTag("pl-PL") }, retryPauseMs = 1)

        var opened: PortionFood? = null
        try {
            flow.start("5900056007181") { error("superseded") }
            runCurrent()
            assertTrue(flow.state.value.isLookingUp)

            flow.start("2412345011111") { opened = it }
            runCurrent()
            firstRead.complete(Unit)
            runCurrent()
            assertTrue("the second lookup is still running", flow.state.value.isLookingUp)

            secondRead.complete(Unit)
            runCurrent()
        } finally {
            // Never leave a read waiting: a failed assertion must fail the test, not hang it.
            firstRead.complete(Unit)
            secondRead.complete(Unit)
        }
        assertEquals("label:2412345", (opened as PortionFood.Item).item.id)
        assertFalse(flow.state.value.isLookingUp)
        assertEquals("the superseded lookup never reached the network", 0, requests)
    }

    // MARK: - Label form

    @Test
    fun `the hint under a disabled save names the empty required fields`() {
        assertEquals(
            "name and kcal typed, the macros still empty",
            listOf(LabelValues.Field.Protein, LabelValues.Field.Carbs, LabelValues.Field.Fat),
            LabelValues.missingRequired(name = "Szynka", kcal = "110", protein = "", carbs = " ", fat = ""),
        )
        assertEquals(
            listOf(LabelValues.Field.Name, LabelValues.Field.Kcal),
            LabelValues.missingRequired(name = "", kcal = "", protein = "20", carbs = "1", fat = "3"),
        )
        assertEquals(
            "fiber and serving are optional",
            emptyList<LabelValues.Field>(),
            LabelValues.missingRequired(name = "Szynka", kcal = "110", protein = "20", carbs = "1", fat = "0"),
        )
    }

    @Test
    fun `saving a label closes the form and the next package matches`() = runTest {
        val dao = FakeFoodDao()
        val flow = flow(dao, service())
        flow.openLabel("2412345004526", null)
        val request = requireNotNull(flow.state.value.labelRequest)
        val item = flow.saveLabel(request, "Szynka", LabelValues(kcal = 110.0, protein = 20.0, carbs = 1.0, fat = 3.0))
        assertEquals("label:2412345", item.id)
        assertEquals("2412345", item.barcode)
        assertNull(flow.state.value.labelRequest)
        assertEquals("label:2412345", BarcodeLookupFlow.savedFood("2412345011111", dao)?.id)
    }

    @Test
    fun `saving a label files it under the key and updates in place`() = runTest {
        val dao = FakeFoodDao()
        val stub = ProductStub(
            code = "2050401935713", name = "Łosoś świeży", brand = "MOWI", quantity = "150 g",
            servingSizeG = 150.0, imageURL = "https://example.org/front.jpg",
        )
        val values = LabelValues(kcal = 208.0, protein = 20.0, carbs = 0.0, fat = 13.0, servingG = 150.0)
        val saved = ProductLabel.save("2050401935713", "Łosoś świeży", stub, values, dao)
        assertEquals("label:2050401935713", saved.id)
        assertEquals(FoodSource.Custom, saved.source)
        assertEquals("2050401935713", saved.barcode)
        assertEquals("MOWI", saved.brand)
        assertEquals("https://example.org/front.jpg", saved.imageURL)
        assertEquals(150.0, saved.servingSizeG!!, 0.0)

        dao.bumpUsage(saved.id, at = 500)
        val corrected = LabelValues(kcal = 200.0, protein = 20.5, carbs = 0.0, fat = 12.0, fiber = 0.0)
        val again = ProductLabel.save("2050401935713", "Łosoś", null, corrected, dao)
        assertEquals(1, dao.rows.value.size)
        assertEquals("Łosoś", again.name)
        assertEquals(200.0, again.kcalPer100, 0.0)
        assertEquals(0.0, again.fiberPer100!!, 0.0)
        assertNull(again.servingSizeG)
        assertEquals("a later save without a stub keeps the brand", "MOWI", again.brand)
        assertEquals("the row keeps its usage stats", 500L, again.lastUsedAt)

        val deli = ProductLabel.save(BarcodeKey.storageKey("2412345004526"), "Szynka", null, values, dao)
        assertEquals("2412345", deli.barcode)
    }

    @Test
    fun `label values`() {
        assertEquals(
            LabelValues(kcal = 250.0, protein = 12.5, carbs = 30.0, fat = 8.0, fiber = 3.0, servingG = 30.0),
            LabelValues.parse(kcal = "250", protein = "12,5", carbs = "30", fat = "8", fiber = "3", serving = "30"),
        )
        assertEquals(
            "water; fiber and serving are optional",
            LabelValues(kcal = 0.0, protein = 0.0, carbs = 0.0, fat = 0.0),
            LabelValues.parse(kcal = "0", protein = "0", carbs = "0", fat = "0", fiber = " ", serving = ""),
        )
        assertNotNull(
            "rounded oil label",
            LabelValues.parse(kcal = "900", protein = "0,5", carbs = "0,5", fat = "100", fiber = "", serving = ""),
        )
        assertNull(LabelValues.parse(kcal = "", protein = "1", carbs = "1", fat = "1", fiber = "", serving = ""))
        assertNull(LabelValues.parse(kcal = "951", protein = "1", carbs = "1", fat = "1", fiber = "", serving = ""))
        assertNull(LabelValues.parse(kcal = "500", protein = "40", carbs = "40", fat = "30", fiber = "", serving = ""))
        assertNull(LabelValues.parse(kcal = "100", protein = "1", carbs = "1", fat = "1", fiber = "abc", serving = ""))
        assertNull(LabelValues.parse(kcal = "100", protein = "1", carbs = "1", fat = "1", fiber = "", serving = "0"))
        assertNull(LabelValues.parse(kcal = "100", protein = "-1", carbs = "1", fat = "1", fiber = "", serving = ""))
    }

    // MARK: - Manual entry

    @Test
    fun `manual entry checks the digits`() {
        assertEquals("5901234123457", FuelDerive.barcodeDigits("590-1234 123457"))
        assertEquals("ASCII digits only", "", FuelDerive.barcodeDigits("٥٩٠"))
        assertFalse("still typing", FuelDerive.showsCheckDigits("5901234"))
        assertTrue("typo in the last digit", FuelDerive.showsCheckDigits("5901234123458"))
        assertNull(FuelDerive.manualBarcode("5901234123458"))
        assertFalse(FuelDerive.showsCheckDigits("5901234123457"))
        assertEquals("0042100005264", FuelDerive.manualBarcode("04252614"))
        assertFalse("a UPC-E is accepted", FuelDerive.showsCheckDigits("04252614"))
    }

    // MARK: - Search sheet

    @Test
    fun `the search sheet shows recent foods, or every saved food matching the query`() {
        val library = listOf(
            food("a", FoodSource.OpenFoodFacts, null, 300).copy(name = "Żółty ser Gouda"),
            food("b", FoodSource.OpenFoodFacts, null, 200).copy(name = "Serek wiejski", brand = "Mlekovita"),
            food("c", FoodSource.Custom, "2412345", null).copy(name = "Łosoś świeży", brand = "MOWI"),
        )
        assertEquals("no query: used foods only", listOf("a", "b"), FoodSearchViewModel.savedFoods(library, "").map { it.id })
        assertEquals(listOf("a"), FoodSearchViewModel.savedFoods(library, "zolty ser").map { it.id })
        assertEquals(listOf("b"), FoodSearchViewModel.savedFoods(library, " mlekovita serek ").map { it.id })
        assertEquals("never-used labels match too", listOf("c"), FoodSearchViewModel.savedFoods(library, "losos").map { it.id })
        val many = (1..15).map { food("f$it", FoodSource.OpenFoodFacts, null, it.toLong()).copy(name = "Jogurt $it") }
        assertEquals(10, FoodSearchViewModel.savedFoods(many, "jogurt").size)
        assertEquals(10, FoodSearchViewModel.savedFoods(many, "").size)
    }
}
