package app.notomorrow.feature.fuelhome

import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.feature.fuel.CustomFoodLibrary
import app.notomorrow.feature.fuel.QuickAddViewModel
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Quick adds and AI estimates saved to the food library (`CustomFoodLibraryTests.swift`). */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomFoodLibraryTest {

    private val pl: Locale = Locale.forLanguageTag("pl-PL")
    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a weighed portion is stored per 100 g`() = runTest {
        val dao = FakeFoodDao()
        val item = CustomFoodLibrary.save(
            "  Owsianka ", grams = 250.0, kcal = 300.0, protein = 10.0, carbs = 50.0, fat = 5.0,
            source = FoodSource.QuickAdd, foodDao = dao, now = 7L,
        )!!
        assertTrue(item.id.startsWith("custom:"))
        assertEquals("Owsianka", item.name)
        assertEquals(120.0, item.kcalPer100, 1e-9)
        assertEquals(4.0, item.proteinPer100, 1e-9)
        assertEquals(20.0, item.carbsPer100, 1e-9)
        assertEquals(2.0, item.fatPer100, 1e-9)
        assertEquals(250.0, item.servingSizeG!!, 0.0)
        assertEquals(1, item.useCount)
        assertEquals(7L, item.lastUsedAt)
        assertEquals(listOf(item), dao.rows.value)
    }

    @Test
    fun `a portion without a weight counts as 100 g`() = runTest {
        val item = CustomFoodLibrary.save(
            "Kebab", grams = 0.0, kcal = 650.0, protein = 30.0, carbs = 60.0, fat = 30.0,
            source = FoodSource.QuickAdd, foodDao = FakeFoodDao(),
        )!!
        assertEquals(650.0, item.kcalPer100, 0.0)
        assertNull(item.servingSizeG)
    }

    @Test
    fun `the same name is refreshed instead of duplicated`() = runTest {
        val dao = FakeFoodDao()
        CustomFoodLibrary.save("Żurek", 0.0, 200.0, 0.0, 0.0, 0.0, FoodSource.QuickAdd, dao)
        val again = CustomFoodLibrary.save("zurek", 0.0, 250.0, 0.0, 0.0, 0.0, FoodSource.QuickAdd, dao)!!
        assertEquals(1, dao.rows.value.size)
        assertEquals(250.0, again.kcalPer100, 0.0)
        assertEquals(2, again.useCount)
    }

    @Test
    fun `an estimate keeps figures the user typed`() = runTest {
        val dao = FakeFoodDao()
        CustomFoodLibrary.save("Pierogi", 100.0, 190.0, 6.0, 29.0, 5.0, FoodSource.QuickAdd, dao)
        val item = CustomFoodLibrary.save("Pierogi", 210.0, 420.0, 14.0, 63.0, 13.0, FoodSource.AiEstimate, dao)!!
        assertEquals(FoodSource.QuickAdd, item.source)
        assertEquals(190.0, item.kcalPer100, 0.0)
        assertEquals(2, item.useCount)
    }

    @Test
    fun `products with the same name are left alone`() = runTest {
        val product = FoodItemEntity(
            id = "off:590", name = "Skyr", source = FoodSource.OpenFoodFacts, barcode = "590",
            kcalPer100 = 60.0, proteinPer100 = 11.0, carbsPer100 = 4.0, fatPer100 = 0.0,
        )
        val dao = FakeFoodDao(listOf(product))
        CustomFoodLibrary.save("Skyr", 150.0, 100.0, 16.0, 6.0, 0.0, FoodSource.AiEstimate, dao)
        assertEquals(2, dao.rows.value.size)
        assertEquals(product, dao.rows.value.first { it.id == "off:590" })
    }

    @Test
    fun `a blank name saves nothing`() = runTest {
        val dao = FakeFoodDao()
        assertNull(CustomFoodLibrary.save("  ", 0.0, 100.0, 0.0, 0.0, 0.0, FoodSource.QuickAdd, dao))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `quick add logs and saves the food by default`() = runTest {
        val foods = FakeFoodDao()
        val meals = FakeMealDao(foods)
        val model = QuickAddViewModel(meals, zone = zone, locale = { pl }, foodDao = foods)
        model.start("Owsianka")
        assertTrue("a new quick add can take a weight", model.state.value.showsGrams)
        assertTrue(model.state.value.saveToLibrary)
        model.setGrams("250")
        model.setKcal("300")

        var added = false
        model.add(MealSlot.Breakfast, LocalDate.now(zone)) { added = true }
        assertTrue(added)
        val entry = meals.rows.value.single()
        assertEquals("Owsianka", entry.customName)
        assertEquals(250.0, entry.grams, 0.0)
        assertNull("the logged row stays a quick add", entry.foodId)
        val saved = foods.rows.value.single()
        assertEquals(FoodSource.QuickAdd, saved.source)
        assertEquals(120.0, saved.kcalPer100, 1e-9)
        assertNotNull(saved.lastUsedAt)
    }

    @Test
    fun `quick add with saving off only logs`() = runTest {
        val foods = FakeFoodDao()
        val meals = FakeMealDao(foods)
        val model = QuickAddViewModel(meals, zone = zone, locale = { pl }, foodDao = foods)
        model.start("Kebab")
        model.setKcal("650")
        model.setSaveToLibrary(false)

        model.add(MealSlot.Dinner, LocalDate.now(zone)) {}
        assertEquals(1, meals.rows.value.size)
        assertTrue(foods.rows.value.isEmpty())
    }

    @Test
    fun `save for later keeps the food without logging it`() = runTest {
        val foods = FakeFoodDao()
        val meals = FakeMealDao(foods)
        val model = QuickAddViewModel(meals, zone = zone, locale = { pl }, foodDao = foods)
        model.start("Kebab")
        model.setKcal("650")

        var saved = false
        model.saveForLater { saved = true }
        assertTrue(saved)
        assertTrue(meals.rows.value.isEmpty())
        assertEquals(listOf("Kebab"), foods.rows.value.map { it.name })
    }
}
