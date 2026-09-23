package app.notomorrow.feature.dashboard

import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.feature.fuelhome.FakeFoodDao
import app.notomorrow.feature.fuelhome.FakeMealDao
import app.notomorrow.model.MealSlot
import app.notomorrow.service.Days
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Dashboard's Fuel row reads today the way the Fuel tab does: every entry whose stored midnight
 * falls on today ([FuelCalendar.dayKey]), including one logged while the phone was in another zone.
 */
class DashboardTodayMealsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val today: LocalDate = LocalDate.of(2026, 9, 22)

    private var order = 0L

    private fun entry(name: String, day: Long) = MealEntryEntity(
        id = name,
        day = day,
        slot = MealSlot.Lunch,
        customName = name,
        grams = 0.0,
        kcal = 100.0,
        proteinG = 5.0,
        carbsG = 10.0,
        fatG = 3.0,
        loggedAt = ++order,
    )

    @Test
    fun `the Fuel row counts what Fuel lists on today`() = runTest {
        val meals = FakeMealDao(FakeFoodDao())
        meals.rows.value = listOf(
            entry("here", Days.millis(today, zone)),
            entry("London", Days.millis(today, ZoneId.of("Europe/London"))),
            entry("New York", Days.millis(today, ZoneId.of("America/New_York"))),
            entry("Tokyo", Days.millis(today, ZoneId.of("Asia/Tokyo"))),
            entry("yesterday", Days.millis(today.minusDays(1), zone)),
            entry("Tokyo tomorrow", Days.millis(today.plusDays(1), ZoneId.of("Asia/Tokyo"))),
        )

        val shown = DashboardViewModel.todayMeals(meals, today, zone).first()

        assertEquals(listOf("here", "London", "New York", "Tokyo"), shown.map { it.customName })
        assertEquals(
            "the same rows the Fuel tab lists on today",
            meals.rows.value.filter { FuelCalendar.dayKey(it.day, zone) == today }.map { it.id },
            shown.map { it.id },
        )
    }
}
