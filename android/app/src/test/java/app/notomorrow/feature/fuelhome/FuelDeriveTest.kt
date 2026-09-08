package app.notomorrow.feature.fuelhome

import app.notomorrow.feature.fuel.FuelDerive
import app.notomorrow.feature.fuel.MealSlotOrdered
import app.notomorrow.model.MealSlot
import app.notomorrow.feature.fuel.suggestedMealSlot
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The pure half of the Fuel feature — `FuelModel`'s computed properties, `MealSlot.suggested`
 * and `PortionSheet`'s two static helpers, against the same vectors as the Swift source.
 */
class FuelDeriveTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 5)

    // MARK: - Totals

    @Test
    fun `kcal left never goes negative`() {
        assertEquals(600.0, FuelDerive.kcalLeft(eaten = 2000.0, goal = 2600.0))
        assertEquals(0.0, FuelDerive.kcalLeft(eaten = 3000.0, goal = 2600.0))
    }

    @Test
    fun `ring progress clamps at one and is zero without a goal`() {
        assertEquals(0.5, FuelDerive.ringProgress(eaten = 1300.0, goal = 2600.0))
        assertEquals(1.0, FuelDerive.ringProgress(eaten = 5200.0, goal = 2600.0))
        assertEquals(0.0, FuelDerive.ringProgress(eaten = 1300.0, goal = 0.0))
    }

    @Test
    fun `protein remaining never goes negative`() {
        assertEquals(58.0, FuelDerive.proteinRemaining(eaten = 122.0, goal = 180.0))
        assertEquals(0.0, FuelDerive.proteinRemaining(eaten = 200.0, goal = 180.0))
    }

    @Test
    fun `forward navigation stops at today`() {
        assertTrue(FuelDerive.canGoForward(today.minusDays(1), today))
        assertFalse(FuelDerive.canGoForward(today, today))
        assertFalse(FuelDerive.canGoForward(today.plusDays(1), today))
    }

    // MARK: - Streak

    @Test
    fun `streak counts back from today when today already hit the goal`() {
        val protein = mapOf(
            today to 190.0,
            today.minusDays(1) to 181.0,
            today.minusDays(2) to 200.0,
            today.minusDays(3) to 100.0,
        )
        assertEquals(3, FuelDerive.proteinStreak(protein, goal = 180.0, today = today))
    }

    @Test
    fun `streak starts at yesterday while today is still short`() {
        val protein = mapOf(
            today to 40.0,
            today.minusDays(1) to 181.0,
            today.minusDays(2) to 185.0,
        )
        assertEquals(2, FuelDerive.proteinStreak(protein, goal = 180.0, today = today))
    }

    @Test
    fun `a missed yesterday with nothing today is no streak`() {
        val protein = mapOf(today.minusDays(1) to 10.0, today.minusDays(2) to 200.0)
        assertEquals(0, FuelDerive.proteinStreak(protein, goal = 180.0, today = today))
    }

    @Test
    fun `exactly the goal counts`() {
        val protein = mapOf(today to 180.0)
        assertEquals(1, FuelDerive.proteinStreak(protein, goal = 180.0, today = today))
    }

    @Test
    fun `a non-positive goal has no streak`() {
        val protein = mapOf(today to 200.0)
        assertEquals(0, FuelDerive.proteinStreak(protein, goal = 0.0, today = today))
    }

    @Test
    fun `an empty history is no streak`() {
        assertEquals(0, FuelDerive.proteinStreak(emptyMap(), goal = 180.0, today = today))
    }

    // MARK: - Suggested slot

    @Test
    fun `suggested slot follows the clock`() {
        assertEquals(MealSlot.Breakfast, suggestedMealSlot(LocalTime.of(0, 0)))
        assertEquals(MealSlot.Breakfast, suggestedMealSlot(LocalTime.of(10, 59)))
        assertEquals(MealSlot.Lunch, suggestedMealSlot(LocalTime.of(11, 0)))
        assertEquals(MealSlot.Lunch, suggestedMealSlot(LocalTime.of(14, 59)))
        assertEquals(MealSlot.Snack, suggestedMealSlot(LocalTime.of(15, 0)))
        assertEquals(MealSlot.Snack, suggestedMealSlot(LocalTime.of(17, 59)))
        assertEquals(MealSlot.Dinner, suggestedMealSlot(LocalTime.of(18, 0)))
        assertEquals(MealSlot.Dinner, suggestedMealSlot(LocalTime.of(23, 59)))
    }

    @Test
    fun `slot order matches the iOS list`() {
        assertEquals(
            listOf(MealSlot.Breakfast, MealSlot.Lunch, MealSlot.Snack, MealSlot.Dinner),
            MealSlotOrdered,
        )
    }

    // MARK: - Portion helpers

    @Test
    fun `portion text prints whole numbers without grouping`() {
        assertEquals("100", FuelDerive.portionText(100.0, Locale.ENGLISH))
        assertEquals("1250", FuelDerive.portionText(1250.0, Locale.ENGLISH))
        assertEquals("12.5", FuelDerive.portionText(12.5, Locale.ENGLISH))
    }

    @Test
    fun `portion text uses the locale decimal separator`() {
        assertEquals("12,5", FuelDerive.portionText(12.5, Locale.forLanguageTag("pl")))
    }

    @Test
    fun `the stepper clamps at five grams`() {
        assertEquals(90.0, FuelDerive.clampPortion(90.0))
        assertEquals(5.0, FuelDerive.clampPortion(0.0))
        assertEquals(5.0, FuelDerive.clampPortion(-30.0))
    }

    @Test
    fun `recent rows are priced at the serving, else 100 g`() {
        assertEquals(40.0, FuelDerive.recentAmount(40.0))
        assertEquals(100.0, FuelDerive.recentAmount(null))
    }

    @Test
    fun `the serving chip only appears for a positive serving that is not 100 g`() {
        assertFalse(FuelDerive.showsServingChip(null))
        assertFalse(FuelDerive.showsServingChip(0.0))
        assertFalse(FuelDerive.showsServingChip(100.0))
        assertFalse(FuelDerive.showsServingChip(100.4))
        assertTrue(FuelDerive.showsServingChip(40.0))
        assertTrue(FuelDerive.showsServingChip(250.0))
    }

    @Test
    fun `chip selection has a half-gram tolerance`() {
        assertTrue(FuelDerive.isPortionSelected(100.0, 100.0))
        assertTrue(FuelDerive.isPortionSelected(100.3, 100.0))
        assertFalse(FuelDerive.isPortionSelected(101.0, 100.0))
    }

    // MARK: - Manual barcode entry

    @Test
    fun `manual barcode entry keeps digits and validates the length`() {
        assertEquals("5901234123457", FuelDerive.barcodeDigits("590-1234 123457"))
        assertTrue(FuelDerive.isValidBarcode("59012341"))
        assertTrue(FuelDerive.isValidBarcode("5901234123457"))
        assertFalse(FuelDerive.isValidBarcode("5901234"))
        assertFalse(FuelDerive.isValidBarcode("590123412345678"))
    }
}
