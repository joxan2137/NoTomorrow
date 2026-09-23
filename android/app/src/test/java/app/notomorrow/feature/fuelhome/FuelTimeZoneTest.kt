package app.notomorrow.feature.fuelhome

import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.feature.fuel.FuelViewModel
import app.notomorrow.model.MealSlot
import app.notomorrow.service.Days
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
 * `ACTION_TIMEZONE_CHANGED`: the Fuel model reads the zone when it needs "today", not once at
 * construction, so a zone change moves today and the stored-day bounds with it.
 *
 * The two zones are 25 hours apart, so Kiritimati's date is always later than Pago Pago's, whatever
 * the real clock says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelTimeZoneTest {

    private val west: ZoneId = ZoneId.of("Pacific/Pago_Pago") // UTC−11
    private val east: ZoneId = ZoneId.of("Pacific/Kiritimati") // UTC+14

    private var zone: ZoneId = west

    private val foods = FakeFoodDao()
    private val meals = FakeMealDao(foods)

    /** Every request fails: nothing in these tests may reach the network. */
    private val service = FoodSearchService(
        engine = MockEngine { respond(content = "", status = HttpStatusCode.fromValue(599)) },
        retryDelayMs = 1,
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model() = FuelViewModel(
        profileDao = FakeProfileDao(),
        foodDao = foods,
        mealDao = meals,
        foodSearch = service,
        zone = { zone },
    )

    /** An entry logged at local midnight of [date] in [zone], as the app stores it. */
    private fun logged(date: LocalDate, zone: ZoneId, kcal: Double) = MealEntryEntity(
        id = "entry-$date-$zone",
        day = Days.millis(date, zone),
        slot = MealSlot.Lunch,
        customName = "Pierogi",
        grams = 0.0,
        kcal = kcal,
        proteinG = 20.0,
        carbsG = 60.0,
        fatG = 15.0,
        loggedAt = Days.millis(date, zone),
    )

    @Test
    fun `a zone change moves today and lists the day under the new zone's midnight`() = runTest {
        val eastToday = LocalDate.now(east)
        meals.rows.value = listOf(logged(eastToday, east, kcal = 540.0))
        val model = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        assertEquals(LocalDate.now(west), model.state.value.today)

        zone = east
        model.syncToday()

        val state = model.state.value
        assertEquals("today follows the new zone", eastToday, state.today)
        assertEquals("a model on today follows it", eastToday, state.day)
        assertEquals("the entry stored at the new zone's midnight is on today", 540.0, state.kcalEaten, 0.0)
    }

    @Test
    fun `a jump reads the zone at the time of the jump`() = runTest {
        val model = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        val eastToday = LocalDate.now(east)

        model.goTo(eastToday)
        assertTrue("a later date than today clamps to today", model.state.value.day < eastToday)

        zone = east
        model.goTo(eastToday)
        assertEquals("the same date is today in the new zone", eastToday, model.state.value.day)
    }
}
