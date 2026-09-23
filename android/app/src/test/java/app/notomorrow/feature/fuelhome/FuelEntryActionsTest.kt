package app.notomorrow.feature.fuelhome

import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.feature.fuel.FuelDerive
import app.notomorrow.feature.fuel.FuelEntryActions
import app.notomorrow.feature.fuel.FuelUndo
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import app.notomorrow.service.Days
import app.notomorrow.util.S
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The Fuel home's undoable actions — the iOS `FuelUndoCopyTests` cases: delete (restored with the
 * same id, day and order), "Log again today" on an entry and "Copy to today" on a past day's meal
 * slot (new entries today, same slot, removable with Undo).
 */
class FuelEntryActionsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val today: LocalDate = LocalDate.of(2026, 9, 22)
    private val nowMillis: Long = ZonedDateTime.of(2026, 9, 22, 12, 30, 0, 0, zone).toInstant().toEpochMilli()

    private val foods = FakeFoodDao()
    private val meals = FakeMealDao(foods)
    private val actions = FuelEntryActions(meals, foods, zone)

    private fun day(offset: Long): LocalDate = today.plusDays(offset)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun log(
        id: String,
        date: LocalDate,
        slot: MealSlot,
        kcal: Double,
        loggedAt: Long,
        food: FoodItemEntity? = null,
        ai: Boolean = false,
    ): MealEntryEntity {
        val entry = MealEntryEntity(
            id = id,
            day = Days.millis(date, zone),
            slot = slot,
            foodId = food?.id,
            customName = if (food == null) id else null,
            grams = 100.0,
            kcal = kcal,
            proteinG = 10.0,
            carbsG = 20.0,
            fatG = 5.0,
            isAIEstimate = ai,
            confidence = if (ai) 0.6 else null,
            loggedAt = loggedAt,
        )
        meals.rows.value = meals.rows.value + entry
        return entry
    }

    /** What the Fuel list shows for [date] and [slot]: the day's key range, by `loggedAt`. */
    private fun shown(date: LocalDate, slot: MealSlot): List<MealEntryEntity> {
        val bounds = FuelCalendar.storedDayBounds(date, zone)
        return meals.rows.value
            .filter { it.day >= bounds.lower && it.day < bounds.upper && it.slot == slot }
            .sortedBy { it.loggedAt }
    }

    private val serek = FoodItemEntity(
        id = "off:5900259000002",
        name = "Serek wiejski",
        source = FoodSource.OpenFoodFacts,
        kcalPer100 = 97.0,
        proteinPer100 = 11.0,
        carbsPer100 = 2.0,
        fatPer100 = 5.0,
    )

    // MARK: - Delete + undo

    @Test
    fun `delete then undo restores the same entry in its place`() = runTest {
        foods.rows.value = listOf(serek)
        val first = log("serek", day(-2), MealSlot.Breakfast, 97.0, at(day(-2), 8), food = serek)
        val second = log("Kawa", day(-2), MealSlot.Breakfast, 20.0, at(day(-2), 9))

        actions.delete(first.id)

        assertEquals(listOf(second.id), shown(day(-2), MealSlot.Breakfast).map { it.id })
        val undo = assertNotNull(actions.pending.value)
        assertIs<FuelUndo.Kind.Deleted>(undo.kind)
        assertEquals(S.fuel_entryDeleted, undo.messageRes)

        actions.undo()

        assertNull(actions.pending.value)
        assertEquals(first, meals.byId(first.id), "every stored column comes back as it was")
        assertEquals(listOf(first.id, second.id), shown(day(-2), MealSlot.Breakfast).map { it.id })
    }

    @Test
    fun `undo restores an AI row with its badge`() = runTest {
        val entry = log("Leczo", day(-1), MealSlot.Dinner, 412.6, at(day(-1), 19), ai = true)

        actions.delete(entry.id)
        actions.undo()

        val restored = assertNotNull(meals.byId(entry.id))
        assertTrue(restored.isAIEstimate)
        assertEquals(0.6, restored.confidence)
        assertEquals(412.6, restored.kcal)
    }

    @Test
    fun `undo keeps a zone-shifted stored midnight exactly`() = runTest {
        // Logged an hour further east: the stored midnight is not this zone's.
        val shifted = log("Jabłko", day(-1), MealSlot.Snack, 80.0, at(day(-1), 15))
            .copy(day = Days.millis(day(-1), zone) - 3_600_000)
        meals.rows.value = listOf(shifted)

        actions.delete(shifted.id)
        actions.undo()

        assertEquals(shifted.day, meals.byId(shifted.id)?.day)
    }

    @Test
    fun `a food deleted meanwhile comes back as a custom row with its name`() = runTest {
        foods.rows.value = listOf(serek)
        val entry = log("serek", day(-1), MealSlot.Lunch, 97.0, at(day(-1), 13), food = serek)

        actions.delete(entry.id)
        foods.delete(serek)
        actions.undo()

        val restored = assertNotNull(meals.byId(entry.id))
        assertNull(restored.foodId)
        assertEquals("Serek wiejski", restored.customName)
        assertEquals(97.0, restored.kcal)
    }

    @Test
    fun `only the last delete is undoable`() = runTest {
        val a = log("A", today, MealSlot.Lunch, 100.0, at(today, 12))
        val b = log("B", today, MealSlot.Lunch, 200.0, at(today, 13))

        actions.delete(a.id)
        val firstUndo = assertNotNull(actions.pending.value)
        actions.delete(b.id)
        assertNotEquals(firstUndo.id, actions.pending.value?.id)

        actions.expire(firstUndo.id)
        assertNotNull(actions.pending.value, "an old toast's timer must not end the newer undo")

        actions.undo()
        assertEquals(listOf("B"), meals.rows.value.map { it.id })
    }

    @Test
    fun `an expired undo does nothing`() = runTest {
        val a = log("A", today, MealSlot.Snack, 100.0, at(today, 10))
        actions.delete(a.id)

        actions.expire(assertNotNull(actions.pending.value).id)
        actions.undo()

        assertNull(actions.pending.value)
        assertTrue(meals.rows.value.isEmpty())
    }

    @Test
    fun `deleting a row that is already gone leaves no undo`() = runTest {
        actions.delete("missing")
        assertNull(actions.pending.value)
    }

    // MARK: - Log again today

    @Test
    fun `log again today copies into the same slot today`() = runTest {
        val owsianka = FoodItemEntity(
            id = "custom:owsianka",
            name = "Owsianka",
            source = FoodSource.Custom,
            kcalPer100 = 380.0,
            proteinPer100 = 13.0,
            carbsPer100 = 66.0,
            fatPer100 = 7.0,
            useCount = 3,
        )
        foods.rows.value = listOf(owsianka)
        val source = log("owsianka", day(-3), MealSlot.Breakfast, 380.0, at(day(-3), 7), food = owsianka)
        val ai = log("Kanapka", day(-3), MealSlot.Snack, 311.7, at(day(-3), 16), ai = true)

        actions.logAgainToday(source.id, nowMillis)
        actions.logAgainToday(ai.id, nowMillis)

        val copy = shown(today, MealSlot.Breakfast).single()
        assertNotEquals(source.id, copy.id)
        assertEquals(Days.millis(today, zone), copy.day)
        assertEquals(nowMillis, copy.loggedAt)
        assertEquals(380.0, copy.kcal)
        assertEquals(owsianka.id, copy.foodId)
        assertEquals(4, foods.byId(owsianka.id)?.useCount)
        assertEquals(nowMillis, foods.byId(owsianka.id)?.lastUsedAt)

        val aiCopy = shown(today, MealSlot.Snack).single()
        assertEquals("Kanapka", aiCopy.customName)
        assertTrue(aiCopy.isAIEstimate, "an unchanged copy is still the same estimate")
        assertEquals(0.6, aiCopy.confidence)

        assertEquals(source, meals.byId(source.id), "the source stays on its day, untouched")
        val undo = assertNotNull(actions.pending.value)
        assertEquals(
            FuelUndo.Kind.Added(listOf(copy.id, aiCopy.id)),
            undo.kind,
            "copies in a row add up: one Undo takes both back",
        )
        assertEquals(S.fuel_addedToToday, undo.messageRes)
    }

    // MARK: - Copy a meal slot to today

    @Test
    fun `copy to today copies the whole slot in order and undo removes it`() = runTest {
        val base = day(-1)
        val slot = listOf(
            log("Jajecznica", base, MealSlot.Breakfast, 320.0, at(base, 8, 0)),
            log("Chleb", base, MealSlot.Breakfast, 160.0, at(base, 8, 1)),
            log("Pomidor", base, MealSlot.Breakfast, 20.0, at(base, 8, 2)),
        )
        log("Pierogi", base, MealSlot.Dinner, 600.0, at(base, 19))
        log("Banan", today, MealSlot.Breakfast, 105.0, at(today, 7))

        actions.copyToToday(slot.map { it.id }, nowMillis)

        assertEquals(
            listOf("Banan", "Jajecznica", "Chleb", "Pomidor"),
            shown(today, MealSlot.Breakfast).map { it.customName },
            "copies land after what today already has, in the source order",
        )
        assertEquals(
            listOf(nowMillis, nowMillis + 1, nowMillis + 2),
            shown(today, MealSlot.Breakfast).drop(1).map { it.loggedAt },
        )
        assertTrue(shown(today, MealSlot.Dinner).isEmpty(), "only the chosen slot is copied")
        assertEquals(4, meals.rows.value.count { it.day == Days.millis(base, zone) }, "yesterday is untouched")

        actions.undo()

        assertEquals(listOf("Banan"), shown(today, MealSlot.Breakfast).map { it.customName })
        assertEquals(5, meals.rows.value.size)
        assertNull(actions.pending.value)
    }

    @Test
    fun `a double tap on copy to today is undone by one undo`() = runTest {
        val base = day(-2)
        val slot = listOf(
            log("Owsianka", base, MealSlot.Breakfast, 380.0, at(base, 7, 0)),
            log("Kawa", base, MealSlot.Breakfast, 40.0, at(base, 7, 5)),
        )
        log("Banan", today, MealSlot.Breakfast, 105.0, at(today, 7))

        actions.copyToToday(slot.map { it.id }, nowMillis)
        val first = assertNotNull(actions.pending.value)
        actions.copyToToday(slot.map { it.id }, nowMillis + 300)
        val second = assertNotNull(actions.pending.value)

        assertEquals(5, shown(today, MealSlot.Breakfast).size, "the slot was copied twice")
        assertNotEquals(first.id, second.id, "a new undo: the toast's timer restarts and is announced again")
        assertEquals(4, (second.kind as FuelUndo.Kind.Added).ids.size)
        actions.expire(first.id)
        assertNotNull(actions.pending.value, "the first toast's timer does not end the combined undo")

        actions.undo()

        assertEquals(listOf("Banan"), shown(today, MealSlot.Breakfast).map { it.customName })
        assertEquals(3, meals.rows.value.size)
        assertNull(actions.pending.value)
    }

    @Test
    fun `a copy after a delete starts a new undo`() = runTest {
        val base = day(-1)
        val source = log("Zupa", base, MealSlot.Lunch, 250.0, at(base, 13))
        val gone = log("Ciastko", today, MealSlot.Snack, 200.0, at(today, 10))
        actions.logAgainToday(source.id, nowMillis)
        actions.delete(gone.id)
        actions.logAgainToday(source.id, nowMillis + 1_000)

        val undo = assertIs<FuelUndo.Kind.Added>(assertNotNull(actions.pending.value).kind)
        assertEquals(1, undo.ids.size, "the delete in between ended the earlier copy's undo")
        actions.undo()
        assertEquals(1, shown(today, MealSlot.Lunch).size, "the first copy stays")
        assertTrue(shown(today, MealSlot.Snack).isEmpty(), "the delete stays")
    }

    @Test
    fun `copies follow a time-zone change`() = runTest {
        var current = zone
        val live = FuelEntryActions(meals, foods) { current }
        val source = log("Zupa", day(-1), MealSlot.Lunch, 250.0, at(day(-1), 13))
        // 23:30 in Warsaw on the 22nd is already the 23rd in Tokyo.
        val late = ZonedDateTime.of(2026, 9, 22, 23, 30, 0, 0, zone).toInstant().toEpochMilli()

        current = ZoneId.of("Asia/Tokyo")
        live.logAgainToday(source.id, late)

        val copy = meals.rows.value.single { it.id != source.id }
        assertEquals(Days.millis(LocalDate.of(2026, 9, 23), current), copy.day)
    }

    @Test
    fun `copying an empty slot does nothing`() = runTest {
        actions.copyToToday(emptyList(), nowMillis)

        assertNull(actions.pending.value)
        assertTrue(meals.rows.value.isEmpty())
    }

    @Test
    fun `the undo toast lasts four seconds, ten with a screen reader`() {
        assertEquals(4_000, FuelDerive.undoDurationMs(screenReader = false))
        assertEquals(10_000, FuelDerive.undoDurationMs(screenReader = true))
        assertFalse(FuelUndo(FuelUndo.Kind.Added(emptyList())).id.isBlank())
    }
}
