package app.notomorrow.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.notomorrow.model.BodyWeightSource
import app.notomorrow.model.FoodCandidate
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot

/**
 * `FoodItem` (`Models.swift:253`). [id] is `"off:<barcode>"`, `"usda:<fdcId>"` or
 * `"custom:<uuid>"`; every macro is per 100 g.
 */
@Entity(tableName = "food_item")
data class FoodItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String? = null,
    val source: FoodSource,
    val barcode: String? = null,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val carbsPer100: Double,
    val fatPer100: Double,
    val fiberPer100: Double? = null,
    val servingSizeG: Double? = null,
    val servingLabel: String? = null,
    val imageURL: String? = null,
    val isFavorite: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
)

/** `FoodCandidate.makeFoodItem()` (`FoodSearchService.swift:19`). */
fun FoodCandidate.makeFoodItem(): FoodItemEntity = FoodItemEntity(
    id = id,
    name = name,
    brand = brand,
    source = FoodSource.OpenFoodFacts,
    barcode = code,
    kcalPer100 = kcalPer100,
    proteinPer100 = proteinPer100,
    carbsPer100 = carbsPer100,
    fatPer100 = fatPer100,
    fiberPer100 = fiberPer100,
    servingSizeG = servingSizeG,
    servingLabel = servingLabel,
    imageURL = imageURL,
)

/**
 * `MealEntry` (`Models.swift:295`). [day] is local midnight in the device zone;
 * [foodId] nulls when the food row is deleted, and [customName] carries the label then.
 */
@Entity(
    tableName = "meal_entry",
    foreignKeys = [
        ForeignKey(
            entity = FoodItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["foodId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["day", "slot"]), Index("foodId")],
)
data class MealEntryEntity(
    @PrimaryKey val id: String,
    val day: Long,
    val slot: MealSlot,
    val foodId: String? = null,
    val customName: String? = null,
    val grams: Double,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val isAIEstimate: Boolean = false,
    val confidence: Double? = null,
    val loggedAt: Long = System.currentTimeMillis(),
)

/**
 * `BodyWeightEntry` (`Models.swift:334`). One row per day: [day] (local midnight) is the
 * primary key, so logging twice on the same day upserts.
 */
@Entity(tableName = "body_weight_entry")
data class BodyWeightEntryEntity(
    @PrimaryKey val day: Long,
    val kg: Double,
    val source: BodyWeightSource = BodyWeightSource.Manual,
)

/**
 * `BodyMeasurement` (`Models.swift:350`) — one tape reading. [day] is local midnight; [kind] the
 * `MeasurementKind` raw value (`waist`, `bodyFat`…); [value] centimetres, or percent for body fat.
 * Logging a kind twice on a day replaces that day's row (`Measurements.save`). Schema 3.
 */
@Entity(tableName = "body_measurement", indices = [Index("day")])
data class BodyMeasurementEntity(
    @PrimaryKey val id: String,
    val day: Long,
    val kind: String,
    val value: Double,
)
