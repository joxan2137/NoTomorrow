package app.notomorrow.feature.fuelaiscan

import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.feature.fuel.AIScanCorrections
import app.notomorrow.feature.fuel.AIScanEdits
import app.notomorrow.feature.fuel.AIScanFormat
import app.notomorrow.feature.fuel.AIScanLog
import app.notomorrow.feature.fuel.LabelFill
import app.notomorrow.feature.fuel.PortionFood
import app.notomorrow.feature.fuel.replacedBy
import app.notomorrow.feature.fuelhome.FakeFoodDao
import app.notomorrow.model.FoodCandidate
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIDatabaseFood
import app.notomorrow.net.dto.AIFood
import app.notomorrow.net.dto.AIPer100
import app.notomorrow.net.dto.LabelReading
import app.notomorrow.service.FoodSearchService
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Correcting an AI estimate before logging (`AIScanCorrectionTests.swift`): the count stepper, the
 * portion line, remove / rename / replace / add, the corrections line a refine sends, the merge that
 * keeps the user's fixes, logging database picks as food entries, and the label fill.
 */
class AIScanCorrectionsTest {

    private val pl: Locale = Locale.forLanguageTag("pl-PL")

    private fun pierogi() = AIFood.mock(
        "Pierogi ruskie", AIPer100(200.0, 6.5, 30.0, 6.0), count = 6.0, unit = "szt.", perUnit = 35.0, confidence = 0.65,
    )

    private fun oil() = AIFood.mock(
        "Olej", AIPer100(884.0, 0.0, 0.0, 100.0), count = 1.0, unit = "łyżka", perUnit = 10.0, confidence = 0.3,
        isGuess = true,
    )

    private fun kompot(perUnit: Double = 250.0) = AIFood.mock(
        "Kompot", AIPer100(60.0, 0.0, 15.0, 0.0), count = 1.0, unit = "", perUnit = perUnit, confidence = 1.0,
    )

    // MARK: - Count and portion line

    @Test
    fun `count steps are whole units with half a unit as the floor`() {
        assertEquals(7.0, AIScanCorrections.steppedCount(6.0, up = true), 0.0)
        assertEquals(0.5, AIScanCorrections.steppedCount(1.0, up = false), 0.0)
        assertEquals(0.5, AIScanCorrections.steppedCount(0.5, up = false), 0.0)
        assertEquals(1.0, AIScanCorrections.steppedCount(0.5, up = true), 0.0)
        assertEquals(1.5, AIScanCorrections.steppedCount(2.5, up = false), 0.0)
        assertEquals(99.0, AIScanCorrections.steppedCount(99.0, up = true), 0.0)
    }

    @Test
    fun `stepping the count keeps the unit weight`() {
        val food = pierogi()
        val edits = AIScanEdits(listOf(food)).stepCount(food.id, up = true)
        assertEquals(7.0, edits.foods[0].portionCount!!, 0.0)
        assertEquals(245.0, edits.foods[0].grams, 0.0)
        assertEquals(490.0, edits.foods[0].kcal, 0.0)
    }

    @Test
    fun `the portion line shows a counted item only`() {
        assertEquals("6 szt." to "35", AIScanFormat.portionBasis(pierogi(), pl))
        assertEquals("1,5 szt." to "35", AIScanFormat.portionBasis(pierogi().withCount(1.5), pl))
        assertNull("a single unit needs no basis line", AIScanFormat.portionBasis(oil(), pl))
        assertNull("no unit, no line", AIScanFormat.portionBasis(kompot().withCount(2.0), pl))
    }

    // MARK: - Corrections line and notes

    @Test
    fun `the corrections line lists kept items and removals`() {
        val line = AIScanCorrections.line(listOf(pierogi().withCount(8.0), kompot(perUnit = 250.5)), listOf("Olej"))
        assertEquals(
            "User corrections (authoritative): Pierogi ruskie = 280 g (8 szt.); Kompot = 250.5 g; removed: Olej",
            line,
        )
        assertNull(AIScanCorrections.line(emptyList(), emptyList()))
    }

    @Test
    fun `notes stay within the backend limit and cut the typed part first`() {
        val line = "User corrections (authoritative): Pierogi = 280 g"
        assertEquals("z okrasą\n$line", AIScanCorrections.notes("  z okrasą ", line))
        assertEquals(line, AIScanCorrections.notes("", line))
        assertEquals("150 g", AIScanCorrections.notes(" 150 g ", null))

        val long = "🍕".repeat(1000) // 2000 UTF-16 units
        val notes = AIScanCorrections.notes(long, line)
        assertTrue(notes.length <= 1500)
        assertTrue("the corrections line is kept whole", notes.endsWith(line))
        assertFalse("a pizza is never cut in half", notes.first().isLowSurrogate() || notes.substringBefore('\n').last().isHighSurrogate())
        assertTrue(AIScanCorrections.notes(long, null).length <= 1500)
    }

    @Test
    fun `numbers are written the machine way`() {
        assertEquals("280", AIScanCorrections.number(280.0))
        assertEquals("250.5", AIScanCorrections.number(250.54))
        assertEquals("0.5", AIScanCorrections.number(0.5))
    }

    @Test
    fun `the merge keeps corrections and removals`() {
        val fixed = pierogi().withCount(8.0).copy(name = "Pierogi z mięsem")
        val refined = listOf(
            AIFood.mock("pierogi RUSKIE", AIPer100(210.0, 7.0, 30.0, 7.0), count = 6.0, unit = "szt.", perUnit = 36.0, confidence = 0.6),
            AIFood.mock("Olej", AIPer100(884.0, 0.0, 0.0, 100.0), count = 1.0, unit = "łyżka", perUnit = 10.0, confidence = 0.3),
            AIFood.mock("Surówka", AIPer100(50.0, 1.0, 10.0, 0.5), count = 1.0, unit = "porcja", perUnit = 120.0, confidence = 0.6),
        )
        val kompot = kompot()
        val merged = AIScanCorrections.merge(
            refined,
            listOf(
                AIScanCorrections.Kept(fixed, listOf("Pierogi ruskie", "Pierogi z mięsem")),
                AIScanCorrections.Kept(kompot, listOf("Kompot")),
            ),
            listOf("olej"),
        )
        assertEquals(listOf("Pierogi z mięsem", "Surówka", "Kompot"), merged.map { it.name })
        assertEquals("the user's item replaces the model's, figures included", fixed, merged[0])
    }

    private fun food(name: String, kcal: Double = 150.0, grams: Double = 100.0, key: String = "none") =
        AIFood.mock(name, AIPer100(kcal, 5.0, 20.0, 5.0), count = 1.0, unit = "porcja", perUnit = grams, confidence = 0.6)
            .copy(genericKey = key)

    @Test
    fun `the merge finds a renamed item`() {
        val fixed = pierogi().withCount(8.0)
        val refined = listOf(food("Pierogi ruskie z cebulką", kcal = 210.0, grams = 240.0), food("Surówka"))

        val merged = AIScanCorrections.merge(refined, listOf(AIScanCorrections.Kept(fixed, listOf("Pierogi ruskie"))), emptyList())

        assertEquals("the renamed item is replaced, not counted next to the user's", listOf(fixed, refined[1]), merged)
    }

    @Test
    fun `the merge drops the model's split of a kept item`() {
        val fixed = food("Ziemniaki z masłem", kcal = 120.0, grams = 250.0)
        val refined = listOf(food("Ziemniaki", grams = 220.0), food("Masło", kcal = 740.0, grams = 10.0), food("Kotlet schabowy"))

        val merged = AIScanCorrections.merge(refined, listOf(AIScanCorrections.Kept(fixed, listOf("Ziemniaki z masłem"))), emptyList())

        assertEquals(listOf("Ziemniaki z masłem", "Kotlet schabowy"), merged.map { it.name })
        assertEquals(fixed.kcal + refined[2].kcal, merged.sumOf { it.kcal }, 0.001)
    }

    @Test
    fun `the merge matches by generic key`() {
        val fixed = food("Schabowy", grams = 180.0, key = "pork_chop_breaded")
        val refined = listOf(food("Kotlet panierowany", grams = 150.0, key = "pork_chop_breaded"), food("Kapusta zasmażana"))

        val merged = AIScanCorrections.merge(refined, listOf(AIScanCorrections.Kept(fixed, listOf("Schabowy"))), emptyList())

        assertEquals(listOf("Schabowy", "Kapusta zasmażana"), merged.map { it.name })
    }

    @Test
    fun `a renamed removed item stays removed`() {
        val refined = listOf(
            food("Pierogi ruskie"),
            food("Olej rzepakowy", kcal = 884.0, grams = 10.0),
            food("Sałatka z olejem"),
            food("Oliwa z oliwek", kcal = 884.0, grams = 10.0, key = "olive_oil"),
        )

        val merged = AIScanCorrections.merge(refined, emptyList(), listOf("Olej", "Oliwa"), removedKeys = listOf("olive_oil"))

        assertEquals(
            "a dish that only mentions the removed food is a different item",
            listOf("Pierogi ruskie", "Sałatka z olejem"),
            merged.map { it.name },
        )
    }

    @Test
    fun `a removed item back under a new name and the same key stays removed`() {
        val refined = listOf(food("Pierogi ruskie"), food("Tłuszcz roślinny", kcal = 884.0, grams = 10.0, key = "olive_oil"))

        val merged = AIScanCorrections.merge(refined, emptyList(), listOf("Oliwa"), removedKeys = listOf("olive_oil", "none", " "))

        assertEquals(listOf("Pierogi ruskie"), merged.map { it.name })
    }

    @Test
    fun `the merge keeps different foods apart`() {
        val fixed = food("Kurczak", grams = 150.0)
        val refined = listOf(food("Kurczak", grams = 120.0), food("Sos do kurczaka"), food("Pierogi z mięsem"), food("Ser żółty"))

        val merged = AIScanCorrections.merge(
            refined,
            listOf(
                AIScanCorrections.Kept(fixed, listOf("Kurczak")),
                AIScanCorrections.Kept(food("Serek wiejski"), listOf("Serek wiejski")),
            ),
            listOf("Pierogi ruskie"),
        )

        assertEquals(
            listOf("Kurczak", "Sos do kurczaka", "Pierogi z mięsem", "Ser żółty", "Serek wiejski"),
            merged.map { it.name },
        )
    }

    @Test
    fun `name matching rules`() {
        assertTrue(AIScanCorrections.sameFood("Pierogi ruskie", "pierogi RUSKIE z cebulką"))
        assertTrue(AIScanCorrections.sameFood("Olej", "Olej rzepakowy"))
        assertFalse(AIScanCorrections.sameFood("Olej", "Sałatka z olejem"))
        assertFalse(AIScanCorrections.sameFood("Pierogi ruskie", "Pierogi z mięsem"))
        assertTrue(AIScanCorrections.isPart("Masło", of = "Ziemniaki z masłem"))
        assertFalse(AIScanCorrections.isPart("Sos do kurczaka", of = "Kurczak"))
        assertTrue(AIScanCorrections.sameWord("ziemniaki", "ziemniakami"))
        assertFalse(AIScanCorrections.sameWord("serek", "sernik"))
        assertFalse(AIScanCorrections.sameWord("ser", "sery"))
        assertEquals(listOf("ziemniaki", "maslem", "lyzki"), AIScanCorrections.words("Ziemniaki z masłem, 2 łyżki"))
    }

    @Test
    fun `removing a model item remembers its generic key`() {
        val o = food("Oliwa", kcal = 884.0, grams = 10.0, key = "olive_oil")
        val edits = AIScanEdits(listOf(pierogi(), o)).remove(o.id)
        assertEquals(listOf("Oliwa"), edits.removedNames)
        assertEquals(listOf("olive_oil"), edits.removedKeys)

        val added = food("Oliwa z oliwek", key = "olive_oil")
        val withAdded = AIScanEdits(listOf(pierogi())).append(added).remove(added.id)
        assertEquals("an added item tells the model nothing", emptyList<String>(), withAdded.removedKeys)
    }

    @Test
    fun `a refine that renames and splits the user's items counts each food once`() {
        val p = pierogi()
        val potatoes = food("Ziemniaki z masłem", kcal = 120.0, grams = 250.0)
        val o = food("Oliwa", kcal = 884.0, grams = 10.0, key = "olive_oil")
        val edits = AIScanEdits(listOf(p, potatoes, o))
            .stepCount(p.id, up = true)
            .setGrams(potatoes.id, 300.0)
            .remove(o.id)
        val refined = listOf(
            food("Pierogi ruskie z cebulką", kcal = 210.0, grams = 240.0),
            food("Ziemniaki", grams = 220.0),
            food("Masło", kcal = 740.0, grams = 10.0),
            food("Oliwa z oliwek extra virgin", kcal = 884.0, grams = 10.0, key = "olive_oil"),
        )

        val merged = edits.answered(refined, refining = true)

        assertEquals(listOf("Pierogi ruskie", "Ziemniaki z masłem"), merged.map { it.name })
        assertEquals(listOf(p.id, potatoes.id), merged.map { it.id })
        assertEquals(245.0, merged[0].grams, 0.0)
        assertEquals(300.0, merged[1].grams, 0.0)
    }

    // MARK: - Edits

    @Test
    fun `removals and edits are reported on refine`() {
        val p = pierogi()
        val o = oil()
        val edits = AIScanEdits(listOf(p, o)).stepCount(p.id, up = true).remove(o.id)
        assertEquals(1, edits.foods.size)
        assertEquals(
            "User corrections (authoritative): Pierogi ruskie = 245 g (7 szt.); removed: Olej",
            edits.correctionsLine,
        )
        assertEquals("z okrasą\n" + edits.correctionsLine, edits.outgoingNotes("z okrasą", refining = true))
        assertEquals("z okrasą", edits.outgoingNotes("z okrasą", refining = false))
    }

    @Test
    fun `opening the editor without changes is no correction`() {
        val p = pierogi()
        val edits = AIScanEdits(listOf(p))
        assertEquals(edits, edits.update(p))
        assertNull(edits.update(p).correctionsLine)
    }

    @Test
    fun `removing an added item tells the model nothing`() {
        val added = kompot()
        val edits = AIScanEdits(listOf(pierogi())).append(added)
        assertEquals("User corrections (authoritative): Kompot = 250 g", edits.correctionsLine)
        assertNull(edits.remove(added.id).correctionsLine)
    }

    @Test
    fun `a rename keeps the model's name for matching`() {
        val p = pierogi()
        val edits = AIScanEdits(listOf(p)).update(p.copy(name = "Pierogi z mięsem"))
        assertEquals(listOf("Pierogi ruskie", "Pierogi z mięsem"), edits.kept[p.id])
        assertEquals(
            "a removed renamed item is reported under the model's name",
            listOf("Pierogi ruskie"),
            edits.remove(p.id).removedNames,
        )
    }

    @Test
    fun `a refine answer is merged, a first answer is taken as it is`() {
        val p = pierogi()
        val o = oil()
        val edits = AIScanEdits(listOf(p, o)).setGrams(p.id, 280.0).remove(o.id)
        val refined = listOf(
            AIFood.mock("Pierogi ruskie", AIPer100(210.0, 7.0, 30.0, 7.0), count = 6.0, unit = "szt.", perUnit = 36.0, confidence = 0.6),
            AIFood.mock("Śmietana", AIPer100(185.0, 2.5, 3.6, 18.0), count = 1.0, unit = "łyżka", perUnit = 15.0, confidence = 0.4),
        )
        val merged = edits.answered(refined, refining = true)
        assertEquals(listOf("Pierogi ruskie", "Śmietana"), merged.map { it.name })
        assertEquals(280.0, merged[0].grams, 0.0)
        assertEquals(p.id, merged[0].id)
        assertEquals(refined, edits.answered(refined, refining = false))
    }

    // MARK: - Database picks

    private val candidate = FoodCandidate(
        id = "off:5900000000001", code = "5900000000001", name = "Pierogi ruskie Biedronka", brand = "Biedronka",
        kcalPer100 = 190.0, proteinPer100 = 6.0, carbsPer100 = 29.0, fatPer100 = 5.5,
    )

    @Test
    fun `replace takes the product and keeps the amount`() {
        val original = pierogi()
        val replaced = original.replacedBy(PortionFood.Candidate(candidate))
        assertEquals(original.id, replaced.id)
        assertEquals("Pierogi ruskie Biedronka", replaced.name)
        assertEquals(210.0, replaced.grams, 0.0)
        assertEquals(6.0, replaced.portionCount!!, 0.0)
        assertEquals("szt.", replaced.portionUnit)
        assertEquals(35.0, replaced.gramsPerUnit!!, 0.0)
        assertEquals(399.0, replaced.kcal, 0.0)
        assertEquals(1.0, replaced.confidence, 0.0)
        assertEquals("database", replaced.nutritionSource)
        assertEquals(AIDatabaseFood.Candidate(candidate), replaced.databaseFood)
    }

    @Test
    fun `an added product is one unit of its grams at full confidence`() {
        val saved = FoodItemEntity(
            id = "custom:kompot", name = "Kompot", source = FoodSource.Custom,
            kcalPer100 = 60.0, proteinPer100 = 0.0, carbsPer100 = 15.0, fatPer100 = 0.0,
        )
        val item = AIScanCorrections.fromDatabase(PortionFood.Item(saved), 250.04)
        assertEquals(250.0, item.grams, 0.0)
        assertEquals(1.0, item.units, 0.0)
        assertNull("one unit: no unit name, no portion line", item.unitName)
        assertEquals(150.0, item.kcal, 0.0)
        assertEquals(37.5, item.carbs, 0.0)
        assertEquals(AIDatabaseFood.Item("custom:kompot"), item.databaseFood)
        assertFalse(item.isGuess)
    }

    @Test
    fun `logging writes database picks as food entries`() = runTest {
        val saved = FoodItemEntity(
            id = "custom:kompot", name = "Kompot", source = FoodSource.Custom,
            kcalPer100 = 60.0, proteinPer100 = 0.0, carbsPer100 = 15.0, fatPer100 = 0.0,
        )
        val dao = FakeFoodDao(listOf(saved))
        val search = FoodSearchService(engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }, retryDelayMs = 1)
        val foods = listOf(
            pierogi(),
            AIScanCorrections.fromDatabase(PortionFood.Item(saved), 250.0),
            AIScanCorrections.fromDatabase(PortionFood.Candidate(candidate), 100.0),
            AIScanCorrections.fromDatabase(PortionFood.Item(saved.copy(id = "custom:gone")), 50.0),
        )

        val entries = AIScanLog.entries(foods, MealSlot.Dinner, day = 0L, foodDao = dao, foodSearch = search, now = 42L)

        assertEquals(4, entries.size)
        val ai = entries[0]
        assertTrue(ai.isAIEstimate)
        assertNull(ai.foodId)
        assertEquals(420.0, ai.kcal, 0.0)
        val picked = entries[1]
        assertFalse(picked.isAIEstimate)
        assertEquals("custom:kompot", picked.foodId)
        assertEquals(150.0, picked.kcal, 1e-9)
        assertEquals(1, dao.rows.value.first { it.id == "custom:kompot" }.useCount)
        val off = entries[2]
        assertEquals("off:5900000000001", off.foodId)
        assertNotNull("an Open Food Facts pick is saved to the library", dao.rows.value.firstOrNull { it.id == off.foodId })
        val gone = entries[3]
        assertNull("a saved food deleted meanwhile logs as a custom row", gone.foodId)
        assertEquals("Kompot", gone.customName)
        assertEquals(30.0, gone.kcal, 1e-9)
    }

    // MARK: - Label fill

    @Test
    fun `a label fills the figures and only an empty name`() {
        val reading = LabelReading(
            legible = true, energyFrom = "kcal", name = "Baton zbożowy", brand = "Sante",
            per100 = LabelReading.Per100(kcal = 400.0, protein = 10.0, carbs = 60.0, fat = 13.0, fiber = 5.0, sugar = 20.0, salt = 0.5),
            servingSizeG = 30.0, confidence = 0.85,
        )
        assertEquals(
            LabelFill(name = "Baton zbożowy", brand = "Sante", kcal = "400", protein = "10", carbs = "60", fat = "13", fiber = "5", serving = "30"),
            LabelFill.from(reading, currentName = "", locale = pl),
        )
        assertNull(LabelFill.from(reading, currentName = "Mój baton", locale = pl).name)

        val decimals = reading.copy(per100 = LabelReading.Per100(kcal = 97.5, protein = 11.0, carbs = 2.5, fat = 5.0))
        assertEquals("97,5", LabelFill.from(decimals, currentName = "x", locale = pl).kcal)
        assertNull("not printed: the field is left alone", LabelFill.from(decimals, currentName = "x", locale = pl).fiber)
    }

    @Test
    fun `an illegible label only fills the name`() {
        val reading = LabelReading(legible = false, unreadableReason = "illegible", name = "Serek", brand = "")
        assertEquals(LabelFill(name = "Serek"), LabelFill.from(reading, currentName = "", locale = pl))
    }
}
