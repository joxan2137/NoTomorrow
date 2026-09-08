package app.notomorrow.data.db

import androidx.room.TypeConverter
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.BodyWeightSource
import app.notomorrow.model.FoodSource
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.MealSlot
import app.notomorrow.model.Participant
import app.notomorrow.model.SetKind
import app.notomorrow.model.TrainingGoal
import app.notomorrow.model.WeightUnit
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/**
 * Room type converters.
 *
 * * Enums are stored as their **iOS raw strings** so the two ports read the same.
 *   Decoding is tolerant: an unknown or missing raw falls back to the documented
 *   default rather than throwing, because a corrupt cell must never crash a query.
 * * Collections are stored as JSON. `Map<Int, Int>` uses **String keys** — the same
 *   wire form `ScheduleDto.overrides` uses.
 * * Instants and dates are epoch millis / epoch days. Entity columns declare `Long`
 *   directly; these two pairs exist for relation POJOs and future columns.
 */
object Converters {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // MARK: - Collections

    @TypeConverter
    fun stringListToJson(value: List<String>?): String? =
        value?.let { json.encodeToString(it) }

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> =
        value?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()

    @TypeConverter
    fun intListToJson(value: List<Int>?): String? =
        value?.let { json.encodeToString(it) }

    @TypeConverter
    fun jsonToIntList(value: String?): List<Int> =
        value?.let { runCatching { json.decodeFromString<List<Int>>(it) }.getOrNull() } ?: emptyList()

    @TypeConverter
    fun intMapToJson(value: Map<Int, Int>?): String? =
        value?.let { map -> json.encodeToString(map.entries.associate { (k, v) -> k.toString() to v }) }

    @TypeConverter
    fun jsonToIntMap(value: String?): Map<Int, Int> {
        val raw = value?.let {
            runCatching { json.decodeFromString<Map<String, Int>>(it) }.getOrNull()
        } ?: return emptyMap()
        return raw.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
    }

    // MARK: - Time

    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    // MARK: - Enums

    @TypeConverter
    fun trainingGoalToRaw(value: TrainingGoal?): String? = value?.raw

    @TypeConverter
    fun rawToTrainingGoal(value: String?): TrainingGoal =
        TrainingGoal.from(value) ?: TrainingGoal.BuildMuscle

    @TypeConverter
    fun weightUnitToRaw(value: WeightUnit?): String? = value?.raw

    @TypeConverter
    fun rawToWeightUnit(value: String?): WeightUnit = WeightUnit.from(value) ?: WeightUnit.Kg

    @TypeConverter
    fun setKindToRaw(value: SetKind?): String? = value?.raw

    @TypeConverter
    fun rawToSetKind(value: String?): SetKind = SetKind.from(value) ?: SetKind.Normal

    @TypeConverter
    fun mealSlotToRaw(value: MealSlot?): String? = value?.raw

    @TypeConverter
    fun rawToMealSlot(value: String?): MealSlot = MealSlot.from(value) ?: MealSlot.Snack

    @TypeConverter
    fun foodSourceToRaw(value: FoodSource?): String? = value?.raw

    @TypeConverter
    fun rawToFoodSource(value: String?): FoodSource = FoodSource.from(value) ?: FoodSource.Custom

    @TypeConverter
    fun attendanceStatusToRaw(value: AttendanceStatus?): String? = value?.raw

    @TypeConverter
    fun rawToAttendanceStatus(value: String?): AttendanceStatus =
        AttendanceStatus.from(value) ?: AttendanceStatus.Planned

    @TypeConverter
    fun headsUpKindToRaw(value: HeadsUpKind?): String? = value?.raw

    @TypeConverter
    fun rawToHeadsUpKind(value: String?): HeadsUpKind = HeadsUpKind.from(value) ?: HeadsUpKind.Custom

    @TypeConverter
    fun participantToRaw(value: Participant?): String? = value?.raw

    @TypeConverter
    fun rawToParticipant(value: String?): Participant = Participant.from(value) ?: Participant.Me

    @TypeConverter
    fun bodyWeightSourceToRaw(value: BodyWeightSource?): String? = value?.raw

    @TypeConverter
    fun rawToBodyWeightSource(value: String?): BodyWeightSource =
        BodyWeightSource.from(value) ?: BodyWeightSource.Manual
}
