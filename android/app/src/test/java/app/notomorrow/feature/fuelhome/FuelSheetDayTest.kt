package app.notomorrow.feature.fuelhome

import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.feature.fuel.FuelSheet
import app.notomorrow.feature.fuel.FuelViewModel
import app.notomorrow.feature.fuel.PortionFood
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FoodSearchService
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * A logging sheet logs to the day it was opened on: the 30-minute snap-back (and midnight) move
 * the tab's selected day while a sheet is up, and the portion sheet a barcode scan opens must not
 * follow it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelSheetDayTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val today: LocalDate = LocalDate.now(zone)

    /** A valid EAN-13 already in the library, so the scan resolves without Open Food Facts. */
    private val code = "5901234123457"

    private val foods = FakeFoodDao(
        listOf(
            FoodItemEntity(
                id = "off:$code", name = "Żurek", source = FoodSource.OpenFoodFacts, barcode = code,
                kcalPer100 = 60.0, proteinPer100 = 2.0, carbsPer100 = 6.0, fatPer100 = 3.0, lastUsedAt = 1L,
            ),
        ),
    )

    /** Every request fails: nothing in these tests may reach the network. */
    private val service = FoodSearchService(
        engine = MockEngine { respond(content = "", status = HttpStatusCode.fromValue(599)) },
        retryDelayMs = 1,
    )

    private var now = 1_000_000L

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model() = FuelViewModel(
        profileDao = FakeProfileDao(),
        foodDao = foods,
        mealDao = FakeMealDao(foods),
        foodSearch = service,
        zone = { zone },
        clock = { now },
    )

    @Test
    fun `a snap-back while the scanned portion sheet is up keeps the day the scanner opened on`() = runTest {
        val model = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        val yesterday = today.minusDays(1)
        assertTrue(model.goPreviousDay())

        // The scanner opened on yesterday; its code arrives and the saved food opens the portion sheet.
        val scan = FuelSheet.Barcode(MealSlot.Lunch, model.state.value.day)
        model.lookupBarcode(code, scan.meal, scan.day)
        assertEquals("off:$code", (model.state.value.portionFood as PortionFood.Item).item.id)

        // Away for 31 minutes: Fuel returns to today with the sheet still up.
        model.appDidEnterBackground()
        now += 31 * 60_000L
        model.appWillEnterForeground()

        val state = model.state.value
        assertEquals("the tab snapped back", today, state.day)
        assertEquals("the sheet still logs to the day it was opened on", yesterday, state.lookupDay)
        assertEquals(MealSlot.Lunch, state.lookupMeal)
    }

    @Test
    fun `before any scan the lookup day follows the selected day`() = runTest {
        val model = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        assertEquals(today, model.state.value.lookupDay)
        model.goPreviousDay()
        assertEquals(today.minusDays(1), model.state.value.lookupDay)
    }
}
