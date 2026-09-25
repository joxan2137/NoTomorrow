package app.notomorrow.widget

import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.feature.fuelhome.FakeFoodDao
import app.notomorrow.feature.fuelhome.FakeMealDao
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import app.notomorrow.service.Days
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `QuickFoods.pick` (`docs/widgets.md`, "Quick foods") — the same rule as iOS's `QuickFood.pick`:
 * the last 60 days, grouped by food id or folded name, ranked by count then newest, top 4, the
 * newest entry's figures. Plus the Break timer's preset row and the Quick log write.
 */
class QuickFoodsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val today: LocalDate = LocalDate.of(2026, 9, 25)
    private val now: Long = LocalDateTime.of(2026, 9, 25, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun candidate(
        name: String,
        daysAgo: Long,
        minute: Int = 0,
        foodId: String? = null,
        kcal: Double = 100.0,
        grams: Double = 50.0,
        ai: Boolean = false,
    ): QuickFoods.Candidate {
        val day = today.minusDays(daysAgo)
        return QuickFoods.Candidate(
            foodId = foodId,
            name = name,
            grams = grams,
            kcal = kcal,
            protein = 1.0,
            carbs = 2.0,
            fat = 3.0,
            isAIEstimate = ai,
            day = Days.millis(day, zone),
            loggedAt = day.atTime(8, 0).plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli(),
        )
    }

    @Test
    fun ranksByCountThenNewest() {
        val picked = QuickFoods.pick(
            listOf(
                candidate("Oats", 3, foodId = "off:1"),
                candidate("Oats", 2, foodId = "off:1"),
                candidate("Skyr", 1, foodId = "off:2"),
                candidate("Banana", 5),
                candidate("Banana", 4),
                candidate("Apple", 0),
            ),
            now, zone,
        )
        // Oats and Banana both twice: Oats is the newer; then Apple (today) before Skyr (yesterday).
        assertEquals(listOf("food:off:1", "name:banana", "name:apple", "food:off:2"), picked.map { it.key })
    }

    @Test
    fun foldsCustomNamesAcrossCaseDiacriticsAndWhitespace() {
        val picked = QuickFoods.pick(
            listOf(
                candidate("Żółty ser", 3),
                candidate("zolty  SER ", 2),
                candidate("ŻÓŁTY ser", 1, kcal = 321.0),
            ),
            now, zone,
        )
        assertEquals(1, picked.size)
        assertEquals("name:zolty ser", picked.single().key)
        // The newest entry's name and figures, trimmed.
        assertEquals("ŻÓŁTY ser", picked.single().name)
        assertEquals(321.0, picked.single().kcal)
        assertEquals("owsianka", QuickFoods.fold("  Owsianka "))
    }

    @Test
    fun groupsByFoodIdWhateverTheName() {
        val picked = QuickFoods.pick(
            listOf(
                candidate("Old name", 2, foodId = "custom:1", grams = 40.0),
                candidate("New name", 1, foodId = "custom:1", grams = 80.0, ai = true),
                candidate("New name", 1, minute = 5),
            ),
            now, zone,
        )
        val food = picked.first()
        assertEquals("food:custom:1", food.key)
        assertEquals("custom:1", food.foodId)
        assertEquals("New name", food.name)
        assertEquals(80.0, food.grams)
        assertTrue(food.isAIEstimate)
        // The same name without a food is its own group.
        assertEquals("name:new name", picked[1].key)
        assertNull(picked[1].foodId)
    }

    @Test
    fun keepsTheLastSixtyDaysOnly() {
        val picked = QuickFoods.pick(
            listOf(
                candidate("Ancient", 61),
                candidate("Ancient", 61, minute = 1),
                candidate("Edge", 60),
            ),
            now, zone,
        )
        assertEquals(listOf("name:edge"), picked.map { it.key })
        // The window is the Fuel day key's: a day stored at another zone's midnight still counts.
        val bounds = FuelCalendar.storedDayBounds(today.minusDays(60), zone)
        val shifted = candidate("Shifted", 60).copy(day = bounds.lower)
        assertEquals(1, QuickFoods.pick(listOf(shifted), now, zone).size)
        assertEquals(0, QuickFoods.pick(listOf(shifted.copy(day = bounds.lower - 1)), now, zone).size)
    }

    @Test
    fun takesTheTopFourAndSkipsNamelessEntries() {
        val many = (1..6).flatMap { i -> List(i) { candidate("Food $i", i.toLong(), minute = it) } }
        val picked = QuickFoods.pick(many + candidate("   ", 0) + candidate("", 0), now, zone)
        assertEquals(QuickFoods.LIMIT, picked.size)
        assertEquals(listOf("name:food 6", "name:food 5", "name:food 4", "name:food 3"), picked.map { it.key })
    }

    @Test
    fun tiesBreakOnTheKey() {
        val a = candidate("Beta", 1)
        val b = candidate("Alpha", 1)
        assertEquals(listOf("name:alpha", "name:beta"), QuickFoods.pick(listOf(a, b), now, zone).map { it.key })
    }

    @Test
    fun restPresetsKeepTheDefaultInTheMiddle() {
        assertEquals(RestPresets(listOf(60, 90, 120), 90), RestPresets.of(90))
        assertEquals(RestPresets(listOf(60, 90, 120), 60), RestPresets.of(60))
        assertEquals(RestPresets(listOf(60, 90, 120), 120), RestPresets.of(120))
        assertEquals(RestPresets(listOf(60, 75, 120), 75), RestPresets.of(75))
        assertEquals(RestPresets(listOf(60, 120, 150), 150), RestPresets.of(150))
        assertEquals(RestPresets(listOf(30, 60, 120), 30), RestPresets.of(30))
    }

    @Test
    fun loggingWritesTodayInTheSuggestedSlotAndBumpsTheFood() = runTest {
        val food = FoodItemEntity(
            id = "off:1", name = "Oats", source = FoodSource.OpenFoodFacts,
            kcalPer100 = 380.0, proteinPer100 = 13.0, carbsPer100 = 60.0, fatPer100 = 7.0,
        )
        val foods = FakeFoodDao(listOf(food))
        val meals = FakeMealDao(foods)
        val quick = QuickFood("food:off:1", "off:1", "Oats", 60.0, 228.0, 7.8, 36.0, 4.2, isAIEstimate = false)
        val at = LocalDateTime.of(2026, 9, 25, 16, 30).atZone(zone).toInstant().toEpochMilli()

        val entry = QuickLogger.log(meals, foods, quick, at, zone)

        assertEquals(Days.millis(today, zone), entry.day)
        assertEquals(MealSlot.Snack, entry.slot)
        assertEquals("off:1", entry.foodId)
        assertNull(entry.customName)
        assertEquals(at, entry.loggedAt)
        assertEquals(228.0, entry.kcal)
        assertEquals(listOf(entry), meals.rows.value)
        assertEquals(1, foods.rows.value.single().useCount)
        assertEquals(at, foods.rows.value.single().lastUsedAt)

        // A food deleted since the widget drew it logs by name.
        val gone = QuickLogger.log(meals, foods, quick.copy(foodId = "off:gone"), at + 1, zone)
        assertNull(gone.foodId)
        assertEquals("Oats", gone.customName)
    }
}
