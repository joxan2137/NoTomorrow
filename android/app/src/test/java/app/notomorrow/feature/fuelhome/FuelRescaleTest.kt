package app.notomorrow.feature.fuelhome

import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.feature.fuel.EntryEditTexts
import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.feature.fuel.FuelDerive
import app.notomorrow.feature.fuel.QuickAddViewModel
import app.notomorrow.model.MealSlot
import app.notomorrow.service.Days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Weighing a logged AI or quick-add portion afterwards (`MealEntryEditTests.swift`, "Rescale"):
 * kcal and macros follow the grams proportionally until the user types a figure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelRescaleTest {

    private val pl: Locale = Locale.forLanguageTag("pl-PL")
    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val today: LocalDate = LocalDate.now(zone)

    private fun entry(
        grams: Double,
        kcal: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        name: String = "Kotlet schabowy",
    ) = MealEntryEntity(
        id = "e1", day = Days.millis(today, zone), slot = MealSlot.Dinner, customName = name, grams = grams,
        kcal = kcal, proteinG = protein, carbsG = carbs, fatG = fat, isAIEstimate = true, confidence = 0.6,
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `figures follow the grams proportionally`() {
        val entry = entry(grams = 150.0, kcal = 390.0, protein = 33.0, carbs = 15.0, fat = 22.5)
        val figures = FuelDerive.figuresAt(entry, 120.0)!!
        assertEquals(312.0, figures.kcal, 1e-9)
        assertEquals(26.4, figures.protein, 1e-9)
        assertEquals(12.0, figures.carbs, 1e-9)
        assertEquals(18.0, figures.fat, 1e-9)
        assertNull(FuelDerive.figuresAt(entry, 0.0))
        assertNull(
            "no portion, nothing to scale from",
            FuelDerive.figuresAt(entry(grams = 0.0, kcal = 230.0, protein = 4.0, carbs = 30.0, fat = 10.0), 50.0),
        )
    }

    @Test
    fun `rescaled texts follow the grams field`() {
        val entry = entry(grams = 187.36, kcal = 243.568, protein = 5.06, carbs = 52.84, fat = 0.56, name = "Ryż")
        val prefill = EntryEditTexts.of(entry, pl)
        assertEquals(
            EntryEditTexts(grams = "250", kcal = "325", protein = "6,8", carbs = "70,5", fat = "0,7"),
            FuelDerive.rescaledTexts(entry, "250", prefill, pl),
        )
        assertEquals(
            "back at the original grams: the original texts, so the exact figures are kept",
            prefill,
            FuelDerive.rescaledTexts(entry, prefill.grams, prefill, pl),
        )
        assertNull(FuelDerive.rescaledTexts(entry, "", prefill, pl))
        assertNull(FuelDerive.rescaledTexts(entry, "0", prefill, pl))
    }

    @Test
    fun `typed figures stop the rescale`() {
        val written = EntryEditTexts(grams = "150", kcal = "390", protein = "33", carbs = "15", fat = "22,5")
        val typed = written.copy(grams = "120")
        assertTrue("only the grams changed", typed.sameFigures(written))
        assertFalse(typed.copy(kcal = "350").sameFigures(written))
    }

    @Test
    fun `saving a rescale stores the exact figures and drops the badge`() {
        val entry = entry(grams = 187.36, kcal = 243.568, protein = 5.06, carbs = 52.84, fat = 0.56, name = "Ryż")
        val prefill = EntryEditTexts.of(entry, pl)
        val written = FuelDerive.rescaledTexts(entry, "250", prefill, pl)!!
        val saved = FuelDerive.editedCustomEntry(
            entry, "Ryż", written, prefill, showsGrams = true, slot = entry.slot, day = today, zone = zone,
            written = written,
        )!!
        assertEquals(250.0, saved.grams, 0.0)
        assertEquals(325.0, saved.kcal, 1e-9)
        assertEquals(5.06 * 250 / 187.36, saved.proteinG, 1e-12)
        assertFalse(saved.isAIEstimate)
    }

    @Test
    fun `the sheet rescales until a figure is typed`() = runTest {
        val foods = FakeFoodDao()
        val row = entry(grams = 150.0, kcal = 390.0, protein = 33.0, carbs = 15.0, fat = 22.5)
        val meals = FakeMealDao(foods, listOf(row))
        val model = QuickAddViewModel(meals, zone = zone, locale = { pl })
        model.bindEntry(row.id)
        assertTrue(model.state.value.showsGrams)

        model.setGrams("120")
        assertEquals("312", model.state.value.kcalText)
        assertEquals("26,4", model.state.value.proteinText)
        assertEquals("12", model.state.value.carbsText)
        assertEquals("18", model.state.value.fatText)

        model.setGrams("150")
        assertEquals("back at the prefill", "390", model.state.value.kcalText)

        model.setKcal("350")
        model.setGrams("100")
        assertEquals("a typed figure stays", "350", model.state.value.kcalText)
        assertEquals("33", model.state.value.proteinText)

        var saved = false
        model.save { saved = true }
        assertTrue(saved)
        val stored = meals.rows.value.single()
        assertEquals(100.0, stored.grams, 0.0)
        assertEquals(350.0, stored.kcal, 0.0)
        assertEquals("untouched macros keep their exact figures", 33.0, stored.proteinG, 0.0)
        assertEquals(FuelCalendar.dayKey(row.day, zone), FuelCalendar.dayKey(stored.day, zone))
    }

    @Test
    fun `saving right after a rescale keeps the exact figures`() = runTest {
        val foods = FakeFoodDao()
        val row = entry(grams = 187.36, kcal = 243.568, protein = 5.06, carbs = 52.84, fat = 0.56, name = "Ryż")
        val meals = FakeMealDao(foods, listOf(row))
        val model = QuickAddViewModel(meals, zone = zone, locale = { pl })
        model.bindEntry(row.id)
        model.setGrams("250")
        model.save {}
        val stored = meals.rows.value.single()
        assertNotNull(stored)
        assertEquals(243.568 * 250 / 187.36, stored.kcal, 1e-12)
        assertEquals(52.84 * 250 / 187.36, stored.carbsG, 1e-12)
    }
}
