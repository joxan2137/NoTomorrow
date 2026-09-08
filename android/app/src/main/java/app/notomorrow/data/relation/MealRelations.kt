package app.notomorrow.data.relation

import androidx.room.Embedded
import androidx.room.Relation
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.data.entity.MealEntryEntity

/** `MealEntry` with the food it was logged from (null for quick-add and AI entries). */
data class MealEntryWithFood(
    @Embedded val entry: MealEntryEntity,
    @Relation(parentColumn = "foodId", entityColumn = "id")
    val food: FoodItemEntity?,
) {
    /** `MealEntry.displayName` — the food's name, else the custom name, else empty. */
    val displayName: String get() = food?.name ?: entry.customName ?: ""
}

/** `SUM(proteinG) GROUP BY day` — the protein-streak scan (`FuelModel.computeStreak`). */
data class DayProtein(
    val day: Long,
    val proteinG: Double,
)

/** `SUM(kcal) GROUP BY day` — day totals without loading every entry. */
data class DayKcal(
    val day: Long,
    val kcal: Double,
)
