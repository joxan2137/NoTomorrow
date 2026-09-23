package app.notomorrow.feature.fuelhome

import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.feature.fuel.EntryEditTexts
import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.feature.fuel.FuelDerive
import app.notomorrow.feature.fuel.QuickAddUiState
import app.notomorrow.feature.fuel.QuickAddViewModel
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import app.notomorrow.service.Days
import app.notomorrow.util.Parsing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Editing a logged meal — the iOS `MealEntryEditTests` / `FuelInputTests` cases: moving an entry to
 * another day, the AI rounding bug (untouched prefills keep the exact figures), ungrouped prefills
 * that parse back (l10n-a11y-11), the day stepper, copies and restores, and the swipe rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelMealEditTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val today: LocalDate = LocalDate.of(2026, 9, 22)
    private val pl: Locale = Locale.forLanguageTag("pl-PL")
    private val enUS: Locale = Locale.US

    private val chilli = MealEntryEntity(
        id = "entry-ai",
        day = Days.millis(today, zone),
        slot = MealSlot.Dinner,
        customName = "Kotlet schabowy",
        grams = 187.36,
        kcal = 1234.4567,
        proteinG = 12.345,
        carbsG = 30.06,
        fatG = 7.77,
        isAIEstimate = true,
        confidence = 0.62,
        loggedAt = 1_790_000_000_000,
    )

    private val skyr = FoodItemEntity(
        id = "off:123",
        name = "Skyr",
        source = FoodSource.OpenFoodFacts,
        kcalPer100 = 63.0,
        proteinPer100 = 11.0,
        carbsPer100 = 4.0,
        fatPer100 = 0.2,
    )

    private val skyrEntry = MealEntryEntity(
        id = "entry-food",
        day = Days.millis(today, zone),
        slot = MealSlot.Breakfast,
        foodId = skyr.id,
        grams = 150.0,
        kcal = 90.0, // logged before the food's figures were corrected (would derive 94.5 now)
        proteinG = 16.0,
        carbsG = 6.0,
        fatG = 0.3,
        loggedAt = 1_790_000_100_000,
    )

    // MARK: - Prefills (l10n-a11y-11)

    @Test
    fun `field text never groups`() {
        assertEquals("1234.5", FuelDerive.portionText(1234.5, enUS))
        assertEquals("1234,5", FuelDerive.portionText(1234.5, pl))
        assertEquals("12345,5", FuelDerive.portionText(12345.46, pl))
        assertEquals("1234,5", FuelDerive.portionText(1234.5, Locale.GERMANY))
        assertEquals("1234,5", FuelDerive.portionText(1234.5, Locale.FRANCE))
        assertEquals("250000", FuelDerive.portionText(250_000.0, pl))
        assertEquals("150", FuelDerive.portionText(150.0, pl))
        assertEquals("72", FuelDerive.portionText(72.04, pl), "at most one decimal")
        assertEquals("0,2", FuelDerive.portionText(0.25, pl), "half-even, like Foundation")
        assertEquals("0", FuelDerive.portionText(0.0, enUS))
    }

    @Test
    fun `field text parses back in every locale`() {
        val locales = listOf("en-US", "en-GB", "en-PL", "pl-PL", "de-DE", "fr-FR").map(Locale::forLanguageTag)
        for (locale in locales) {
            for (value in listOf(0.0, 5.0, 72.5, 999.9, 1234.5, 12345.5, 250_000.0)) {
                val text = FuelDerive.portionText(value, locale)
                assertEquals(value, Parsing.decimal(text), "$locale: $text")
            }
        }
    }

    @Test
    fun `edit texts prefill every figure and grams only for a weighed row`() {
        assertEquals(
            EntryEditTexts(grams = "187,4", kcal = "1234,5", protein = "12,3", carbs = "30,1", fat = "7,8"),
            EntryEditTexts.of(chilli, pl),
        )
        val quickAdd = chilli.copy(grams = 0.0, kcal = 230.0, proteinG = 4.0, carbsG = 30.0, fatG = 10.0)
        assertEquals(
            EntryEditTexts(grams = "", kcal = "230", protein = "4", carbs = "30", fat = "10"),
            EntryEditTexts.of(quickAdd, enUS),
        )
    }

    // MARK: - The AI rounding bug

    @Test
    fun `untouched prefills keep an AI row's exact figures and badge`() {
        val prefill = EntryEditTexts.of(chilli, pl)

        val saved = FuelDerive.editedCustomEntry(
            entry = chilli,
            name = "Schabowy",
            texts = prefill,
            prefill = prefill,
            showsGrams = true,
            slot = MealSlot.Lunch,
            day = today,
            zone = zone,
        )!!

        assertEquals("Schabowy", saved.customName)
        assertEquals(MealSlot.Lunch, saved.slot)
        assertTrue(saved.isAIEstimate)
        assertEquals(0.62, saved.confidence)
        assertEquals(1234.4567, saved.kcal)
        assertEquals(187.36, saved.grams)
        assertEquals(12.345, saved.proteinG)
        assertEquals(30.06, saved.carbsG)
        assertEquals(7.77, saved.fatG)
    }

    @Test
    fun `a typed figure replaces the original and drops the badge`() {
        val prefill = EntryEditTexts.of(chilli, pl)

        val saved = FuelDerive.editedCustomEntry(
            entry = chilli,
            name = chilli.customName.orEmpty(),
            texts = prefill.copy(kcal = "1250"),
            prefill = prefill,
            showsGrams = true,
            slot = chilli.slot,
            day = today,
            zone = zone,
        )!!

        assertEquals(1250.0, saved.kcal)
        assertEquals(12.345, saved.proteinG, "untouched fields stay exact")
        assertFalse(saved.isAIEstimate)
        assertNull(saved.confidence)
    }

    @Test
    fun `edited figure vectors`() {
        assertEquals(1250.0, FuelDerive.editedFigure("1250", prefill = "1234,5", original = 1234.4567))
        assertEquals(12.5, FuelDerive.editedFigure("12,5", prefill = "12,3", original = 12.345))
        assertEquals(
            1234.5,
            FuelDerive.editedFigure("1234.5", prefill = "1234,5", original = 1234.4567),
            "retyping the same number with a dot is an edit",
        )
        assertEquals(7.77, FuelDerive.editedFigure("7,8", prefill = "7,8", original = 7.77))
        assertNull(FuelDerive.editedFigure("", prefill = "7,8", original = 7.77))
        assertNull(FuelDerive.editedFigure("-5", prefill = "7,8", original = 7.77))
    }

    @Test
    fun `an unparseable kcal cannot be saved`() {
        val prefill = EntryEditTexts.of(chilli, enUS)
        assertNull(
            FuelDerive.editedCustomEntry(
                chilli, "x", prefill.copy(kcal = "1,234.5"), prefill, true, chilli.slot, today, zone,
            ),
        )
    }

    @Test
    fun `an unparseable grams text keeps the logged grams`() {
        val prefill = EntryEditTexts.of(chilli, pl)
        val saved = FuelDerive.editedCustomEntry(
            chilli, chilli.customName.orEmpty(), prefill.copy(grams = "abc"), prefill, true, chilli.slot, today, zone,
        )!!
        assertEquals(187.36, saved.grams)
        assertTrue(saved.isAIEstimate)
    }

    @Test
    fun `an edit saves a 0 kcal row, an add needs calories`() {
        val water = QuickAddUiState(name = "Woda", kcalText = "0", isEditing = true)
        assertTrue(water.canAdd, "a 0 kcal AI row can still be renamed, moved or re-slotted")
        assertFalse(water.copy(isEditing = false).canAdd, "a new quick add needs calories")
        assertFalse(water.copy(kcalText = "").canAdd)
        assertFalse(water.copy(kcalText = "-5").canAdd)
        assertFalse(water.copy(kcalText = "abc").canAdd)
        assertFalse(water.copy(name = "  ").canAdd)
        assertTrue(QuickAddUiState(name = "Kanapka", kcalText = "250,5").canAdd)
    }

    @Test
    fun `a 0 kcal AI row moves to another day from the edit sheet`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val water = chilli.copy(id = "entry-water", customName = "Woda", grams = 250.0, kcal = 0.0, proteinG = 0.0, carbsG = 0.0, fatG = 0.0)
            val meals = FakeMealDao(FakeFoodDao(), listOf(water))
            val model = QuickAddViewModel(meals, zone, locale = { pl })
            model.bindEntry(water.id)
            assertTrue(model.state.value.canAdd)

            model.setDay(today.minusDays(1))
            model.setSlot(MealSlot.Breakfast)
            var saved = false
            model.save { saved = true }

            assertTrue(saved)
            val moved = meals.rows.value.single()
            assertEquals(Days.millis(today.minusDays(1), zone), moved.day)
            assertEquals(MealSlot.Breakfast, moved.slot)
            assertEquals(0.0, moved.kcal)
            assertTrue(moved.isAIEstimate, "untouched figures keep the estimate's badge")
        } finally {
            Dispatchers.resetMain()
        }
    }

    // MARK: - Moving to another day

    @Test
    fun `moving a custom entry keeps its slot, figures, AI flags and loggedAt`() {
        val prefill = EntryEditTexts.of(chilli, pl)
        val twoDaysAgo = today.minusDays(2)

        val saved = FuelDerive.editedCustomEntry(
            chilli, chilli.customName.orEmpty(), prefill, prefill, true, chilli.slot, twoDaysAgo, zone,
        )!!

        assertEquals(Days.millis(twoDaysAgo, zone), saved.day)
        assertEquals(twoDaysAgo, FuelCalendar.dayKey(saved.day, zone), "found by the new day's query")
        assertEquals(chilli.loggedAt, saved.loggedAt, "the clock time is not shown anywhere; the row keeps its order")
        assertEquals(chilli.id, saved.id)
        assertEquals(MealSlot.Dinner, saved.slot)
        assertEquals(1234.4567, saved.kcal)
        assertTrue(saved.isAIEstimate, "a move is not an edit of the figures")
    }

    @Test
    fun `moving to the day it is already listed under keeps a zone-shifted midnight`() {
        val shifted = chilli.copy(day = Days.millis(today, zone) - 3_600_000)
        assertSame(shifted, FuelDerive.moved(shifted, today, zone))
        assertEquals(Days.millis(today.minusDays(1), zone), FuelDerive.moved(shifted, today.minusDays(1), zone).day)
    }

    @Test
    fun `moving across the DST change lands on local midnight`() {
        val sunday = LocalDate.of(2026, 10, 25) // the 25-hour day in Warsaw
        val moved = FuelDerive.moved(chilli, sunday, zone)
        assertEquals(sunday.atStartOfDay(zone).toInstant().toEpochMilli(), moved.day)
        assertEquals(sunday, FuelCalendar.dayKey(moved.day, zone))
    }

    // MARK: - Portion sheet save

    @Test
    fun `a portion save without new grams keeps the logged figures`() {
        val saved = FuelDerive.editedPortion(skyrEntry, skyr, 150.0, MealSlot.Snack, today.minusDays(1), zone)
        assertEquals(90.0, saved.kcal, "not re-derived from the food's current per-100 g")
        assertEquals(16.0, saved.proteinG)
        assertEquals(MealSlot.Snack, saved.slot)
        assertEquals(Days.millis(today.minusDays(1), zone), saved.day)
        assertEquals(skyrEntry.loggedAt, saved.loggedAt)
        assertEquals(skyrEntry.foodId, saved.foodId)
    }

    @Test
    fun `a portion save with new grams re-derives the figures`() {
        val saved = FuelDerive.editedPortion(skyrEntry, skyr, 200.0, skyrEntry.slot, today, zone)
        assertEquals(200.0, saved.grams)
        assertEquals(126.0, saved.kcal)
        assertEquals(22.0, saved.proteinG)
        assertEquals(skyrEntry.day, saved.day, "same day: the stored value is untouched")
    }

    // MARK: - Day stepper

    @Test
    fun `the day stepper stays in the past`() {
        assertEquals(today.minusDays(1), FuelDerive.stepDay(today, -1, today))
        assertEquals(today, FuelDerive.stepDay(today.minusDays(1), 1, today))
        assertEquals(today, FuelDerive.stepDay(today, 1, today), "never past today")
        assertEquals(today, FuelDerive.stepDay(today.plusDays(5), 0, today))
    }

    @Test
    fun `the day stepper crosses months and the DST change`() {
        val firstOfNovember = LocalDate.of(2026, 11, 1)
        assertEquals(LocalDate.of(2026, 10, 31), FuelDerive.stepDay(firstOfNovember, -1, firstOfNovember))
        assertEquals(LocalDate.of(2026, 10, 26), FuelDerive.stepDay(LocalDate.of(2026, 10, 25), 1, firstOfNovember))
    }

    // MARK: - Copies and restores

    @Test
    fun `a copy is a new entry today with the same figures, slot and AI flags`() {
        val copy = FuelDerive.copied(chilli, id = "copy-1", day = today.plusDays(3), loggedAt = 42, zone = zone)
        assertEquals("copy-1", copy.id)
        assertEquals(Days.millis(today.plusDays(3), zone), copy.day)
        assertEquals(42, copy.loggedAt)
        assertEquals(chilli.slot, copy.slot)
        assertEquals(chilli.kcal, copy.kcal)
        assertEquals(chilli.customName, copy.customName)
        assertTrue(copy.isAIEstimate)
        assertEquals(0.62, copy.confidence)
    }

    @Test
    fun `a restore keeps the row unless its food is gone`() {
        assertSame(skyrEntry, FuelDerive.restored(skyrEntry, "Skyr", foodExists = true))
        assertSame(chilli, FuelDerive.restored(chilli, "Kotlet schabowy", foodExists = false))
        val orphan = FuelDerive.restored(skyrEntry, "Skyr", foodExists = false)
        assertNull(orphan.foodId)
        assertEquals("Skyr", orphan.customName)
        assertEquals(skyrEntry.kcal, orphan.kcal)
        assertEquals(skyrEntry.id, orphan.id)
    }

    // MARK: - Swipes

    @Test
    fun `a swipe commits only past half the row, whatever the speed`() {
        val width = 1080f
        assertFalse(FuelDerive.swipeCommits(-249f, width), "the 83 dp flick that used to delete")
        assertFalse(FuelDerive.swipeCommits(-539f, width))
        assertTrue(FuelDerive.swipeCommits(-540f, width))
        assertTrue(FuelDerive.swipeCommits(700f, width), "a leading edit swipe")
        assertFalse(FuelDerive.swipeCommits(-900f, 0f), "not measured yet")
        assertNotEquals(0f, FuelDerive.SWIPE_COMMIT_FRACTION)
    }
}
