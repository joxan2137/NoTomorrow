package app.notomorrow.service

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import app.notomorrow.R
import app.notomorrow.model.MealSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

/**
 * Body weight in, meals and strength workouts out — the port of `HealthKitService`
 * (`NoTomorrow/Services/HealthKitService.swift`) onto Health Connect.
 *
 * Every call is a no-op that throws [HealthError.Unavailable] when the SDK or the provider app is
 * missing, so a device without Health Connect never crashes a feature. iOS wraps the four nutrients
 * in one `HKCorrelation(.food)`; Health Connect's equivalent is a single [NutritionRecord], which
 * additionally carries `name` and `mealType`. `activeEnergyBurned` — nested inside the
 * `HKWorkoutBuilder` on iOS — becomes a sibling [ActiveCaloriesBurnedRecord] over the same range.
 */
class HealthService(context: Context) {

    private val app = context.applicationContext

    private val _isAuthorized = MutableStateFlow(false)
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile private var cachedClient: HealthConnectClient? = null

    // MARK: - Availability

    /** `SDK_UNAVAILABLE` / `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` / `SDK_AVAILABLE`. */
    fun sdkStatus(): Int = runCatching { HealthConnectClient.getSdkStatus(app) }
        .getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)

    val isAvailable: Boolean get() = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    val needsProviderUpdate: Boolean
        get() = sdkStatus() == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    private fun client(): HealthConnectClient? {
        cachedClient?.let { return it }
        if (!isAvailable) return null
        return runCatching { HealthConnectClient.getOrCreate(app) }.getOrNull()?.also { cachedClient = it }
    }

    // MARK: - Authorization

    /** The set to hand [permissionsContract]; the whole set is requested at once, as on iOS. */
    val permissions: Set<String> get() = PERMISSIONS

    suspend fun grantedPermissions(): Set<String> {
        val client = client() ?: return emptySet()
        return runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
    }

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(PERMISSIONS)

    /**
     * `refreshAuthorization()`: iOS can only see *write* status, and mirrors body-mass sharing.
     * Health Connect reports every grant, so the flag is "we may write body weight".
     */
    suspend fun refreshAuthorization(): Boolean {
        val authorized = grantedPermissions().contains(WRITE_WEIGHT)
        _isAuthorized.value = authorized
        return authorized
    }

    // MARK: - Body weight

    /** Most recent weight sample from any source, or `null` when Health Connect has none. */
    suspend fun readLatestBodyWeight(): BodyWeightSample? {
        val client = client() ?: return null
        return runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.before(Instant.now()),
                    ascendingOrder = false,
                    pageSize = 1,
                )
            )
            response.records.firstOrNull()?.let { BodyWeightSample(it.weight.inKilograms, it.time) }
        }.getOrNull()
    }

    suspend fun saveBodyWeight(kg: Double, time: Instant = Instant.now()) {
        val client = client() ?: throw HealthError.Unavailable
        insert(
            client,
            listOf(
                WeightRecord(
                    time = time,
                    zoneOffset = offset(time),
                    weight = Mass.kilograms(kg),
                    metadata = Metadata.manualEntry(),
                )
            ),
        )
    }

    // MARK: - Meals

    /** One [NutritionRecord] so the four nutrients show up as a single meal in the health app. */
    suspend fun saveMeal(
        kcal: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        name: String? = null,
        slot: MealSlot? = null,
        time: Instant = Instant.now(),
    ) {
        val client = client() ?: throw HealthError.Unavailable
        if (kcal <= 0 && protein <= 0 && carbs <= 0 && fat <= 0) return
        insert(
            client,
            listOf(
                NutritionRecord(
                    startTime = time,
                    startZoneOffset = offset(time),
                    endTime = time,
                    endZoneOffset = offset(time),
                    metadata = Metadata.manualEntry(),
                    energy = kcal.takeIf { it > 0 }?.let { Energy.kilocalories(it) },
                    protein = protein.takeIf { it > 0 }?.let { Mass.grams(it) },
                    totalCarbohydrate = carbs.takeIf { it > 0 }?.let { Mass.grams(it) },
                    totalFat = fat.takeIf { it > 0 }?.let { Mass.grams(it) },
                    name = name,
                    mealType = mealType(slot),
                )
            ),
        )
    }

    // MARK: - Workouts

    /**
     * `saveWorkout(start:end:kcal:)`: a strength-training session plus, when the caller knows it,
     * the active energy over the same range.
     */
    suspend fun saveWorkout(
        start: Instant,
        end: Instant,
        kcal: Double? = null,
        title: String? = null,
    ) {
        val client = client() ?: throw HealthError.Unavailable
        if (!end.isAfter(start)) return
        val records = buildList {
            add(
                ExerciseSessionRecord(
                    startTime = start,
                    startZoneOffset = offset(start),
                    endTime = end,
                    endZoneOffset = offset(end),
                    metadata = Metadata.manualEntry(),
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                    title = title,
                )
            )
            if (kcal != null && kcal > 0) {
                add(
                    ActiveCaloriesBurnedRecord(
                        startTime = start,
                        startZoneOffset = offset(start),
                        endTime = end,
                        endZoneOffset = offset(end),
                        energy = Energy.kilocalories(kcal),
                        metadata = Metadata.manualEntry(),
                    )
                )
            }
        }
        insert(client, records)
    }

    // MARK: - Helpers

    private suspend fun insert(client: HealthConnectClient, records: List<androidx.health.connect.client.records.Record>) {
        if (records.isEmpty()) return
        try {
            client.insertRecords(records)
            _lastError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _lastError.value = e.message
            throw map(e)
        }
    }

    private fun offset(instant: Instant) = ZoneId.systemDefault().rules.getOffset(instant)

    companion object {
        val READ_WEIGHT: String = HealthPermission.getReadPermission(WeightRecord::class)
        val WRITE_WEIGHT: String = HealthPermission.getWritePermission(WeightRecord::class)
        val WRITE_NUTRITION: String = HealthPermission.getWritePermission(NutritionRecord::class)
        val WRITE_EXERCISE: String = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val WRITE_ACTIVE_CALORIES: String =
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)

        /** Exactly the five `android.permission.health.*` entries declared in the manifest. */
        val PERMISSIONS: Set<String> = setOf(
            READ_WEIGHT,
            WRITE_WEIGHT,
            WRITE_NUTRITION,
            WRITE_EXERCISE,
            WRITE_ACTIVE_CALORIES,
        )

        /** For `rememberLauncherForActivityResult` in the health feature. */
        fun permissionsContract(): ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()

        fun mealType(slot: MealSlot?): Int = when (slot) {
            MealSlot.Breakfast -> MealType.MEAL_TYPE_BREAKFAST
            MealSlot.Lunch -> MealType.MEAL_TYPE_LUNCH
            MealSlot.Snack -> MealType.MEAL_TYPE_SNACK
            MealSlot.Dinner -> MealType.MEAL_TYPE_DINNER
            null -> MealType.MEAL_TYPE_UNKNOWN
        }

        /** `HealthKitService.map(_:)`: denied → `notAuthorized`, provider gone → `unavailable`. */
        fun map(error: Throwable): HealthError = when (error) {
            is HealthError -> error
            is SecurityException -> HealthError.NotAuthorized
            is IllegalStateException -> HealthError.Unavailable
            else -> HealthError.SaveFailed
        }
    }
}

/** `readLatestBodyWeight()`'s `(kg: Double, date: Date)` tuple. */
data class BodyWeightSample(val kg: Double, val time: Instant)

/** `HealthKitError` (`HealthKitService.swift:4`). */
sealed class HealthError(message: String? = null) : Exception(message) {

    data object Unavailable : HealthError()

    data object NotAuthorized : HealthError()

    data object SaveFailed : HealthError()

    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            Unavailable -> R.string.health_error_unavailable
            NotAuthorized -> R.string.health_error_notAuthorized
            SaveFailed -> R.string.health_error_saveFailed
        }

    fun localizedMessage(context: Context): String = context.getString(messageRes)
}
