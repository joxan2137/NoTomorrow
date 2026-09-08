package app.notomorrow.data.db

import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.BodyWeightSource
import app.notomorrow.model.FoodSource
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.MealSlot
import app.notomorrow.model.Participant
import app.notomorrow.model.SetKind
import app.notomorrow.model.TrainingGoal
import app.notomorrow.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Pure-JVM: the converters must round-trip and must never throw on junk. */
class ConvertersTest {

    @Test
    fun `string list round trips`() {
        val value = listOf("chest", "triceps", "front delts")
        assertEquals(value, Converters.jsonToStringList(Converters.stringListToJson(value)))
    }

    @Test
    fun `int list round trips`() {
        val value = listOf(1, 3, 5)
        assertEquals(value, Converters.jsonToIntList(Converters.intListToJson(value)))
    }

    @Test
    fun `int map is stored with string keys and round trips`() {
        val value = mapOf(1 to 1080, 5 to 1140)
        val encoded = Converters.intMapToJson(value)!!
        assertEquals("""{"1":1080,"5":1140}""", encoded)
        assertEquals(value, Converters.jsonToIntMap(encoded))
    }

    @Test
    fun `collections decode to empty on junk or null`() {
        assertEquals(emptyList<String>(), Converters.jsonToStringList("not json"))
        assertEquals(emptyList<Int>(), Converters.jsonToIntList(null))
        assertEquals(emptyMap<Int, Int>(), Converters.jsonToIntMap("["))
        assertEquals(emptyMap<Int, Int>(), Converters.jsonToIntMap("""{"x":3}"""))
    }

    @Test
    fun `time converters round trip`() {
        val instant = Instant.ofEpochMilli(1_772_000_000_000)
        assertEquals(instant, Converters.millisToInstant(Converters.instantToMillis(instant)))
        val date = LocalDate.of(2026, 9, 5)
        assertEquals(date, Converters.epochDayToLocalDate(Converters.localDateToEpochDay(date)))
        assertNull(Converters.millisToInstant(null))
        assertNull(Converters.epochDayToLocalDate(null))
    }

    @Test
    fun `enums are stored as the iOS raw strings`() {
        assertEquals("buildMuscle", Converters.trainingGoalToRaw(TrainingGoal.BuildMuscle))
        assertEquals("loseFat", Converters.trainingGoalToRaw(TrainingGoal.LoseFat))
        assertEquals("kg", Converters.weightUnitToRaw(WeightUnit.Kg))
        assertEquals("warmup", Converters.setKindToRaw(SetKind.Warmup))
        assertEquals("breakfast", Converters.mealSlotToRaw(MealSlot.Breakfast))
        assertEquals("openFoodFacts", Converters.foodSourceToRaw(FoodSource.OpenFoodFacts))
        assertEquals("aiEstimate", Converters.foodSourceToRaw(FoodSource.AiEstimate))
        assertEquals("cancelled", Converters.attendanceStatusToRaw(AttendanceStatus.Cancelled))
        assertEquals("cantMakeIt", Converters.headsUpKindToRaw(HeadsUpKind.CantMakeIt))
        assertEquals("makeUpProposal", Converters.headsUpKindToRaw(HeadsUpKind.MakeUpProposal))
        assertEquals("partner", Converters.participantToRaw(Participant.Partner))
        assertEquals("healthKit", Converters.bodyWeightSourceToRaw(BodyWeightSource.HealthKit))
    }

    @Test
    fun `enums round trip through their raw strings`() {
        TrainingGoal.entries.forEach { assertEquals(it, Converters.rawToTrainingGoal(it.raw)) }
        WeightUnit.entries.forEach { assertEquals(it, Converters.rawToWeightUnit(it.raw)) }
        SetKind.entries.forEach { assertEquals(it, Converters.rawToSetKind(it.raw)) }
        MealSlot.entries.forEach { assertEquals(it, Converters.rawToMealSlot(it.raw)) }
        FoodSource.entries.forEach { assertEquals(it, Converters.rawToFoodSource(it.raw)) }
        AttendanceStatus.entries.forEach { assertEquals(it, Converters.rawToAttendanceStatus(it.raw)) }
        HeadsUpKind.entries.forEach { assertEquals(it, Converters.rawToHeadsUpKind(it.raw)) }
        Participant.entries.forEach { assertEquals(it, Converters.rawToParticipant(it.raw)) }
        BodyWeightSource.entries.forEach { assertEquals(it, Converters.rawToBodyWeightSource(it.raw)) }
    }

    @Test
    fun `unknown raw falls back to a default instead of throwing`() {
        assertEquals(TrainingGoal.BuildMuscle, Converters.rawToTrainingGoal("nonsense"))
        assertEquals(WeightUnit.Kg, Converters.rawToWeightUnit(null))
        assertEquals(SetKind.Normal, Converters.rawToSetKind(""))
        assertEquals(AttendanceStatus.Planned, Converters.rawToAttendanceStatus("?"))
        assertEquals(Participant.Me, Converters.rawToParticipant("nobody"))
    }
}
