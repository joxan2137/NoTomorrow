package app.notomorrow.feature.fuel

import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.model.FoodSource
import app.notomorrow.service.FoodMatch
import java.util.UUID

/**
 * `CustomFoodLibrary` (`Features/Fuel/FuelSupport.swift`): quick adds and AI estimates kept in
 * the food library, so they show under Recent / "Your foods" in the search sheet and can be
 * logged again later like any saved product.
 */
object CustomFoodLibrary {

    /**
     * Saves the food for [name] with one portion's figures and marks it used at [now]. A food
     * saved before from a quick add or an estimate under the same name (ignoring case and
     * diacritics) is refreshed instead of duplicated, except that an estimate never overwrites
     * figures the user typed. With a weight the figures become per-100 g values and the weight its
     * serving; without one the portion itself counts as 100 g. Null (nothing saved) for a blank name.
     */
    suspend fun save(
        name: String,
        grams: Double,
        kcal: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        source: FoodSource,
        foodDao: FoodDao,
        now: Long = System.currentTimeMillis(),
    ): FoodItemEntity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val existing = existing(trimmed, foodDao)
        val base = existing ?: FoodItemEntity(
            id = "custom:${UUID.randomUUID()}",
            name = trimmed,
            source = source,
            kcalPer100 = 0.0,
            proteinPer100 = 0.0,
            carbsPer100 = 0.0,
            fatPer100 = 0.0,
        )
        val overwrite = existing == null || source == FoodSource.QuickAdd || existing.source == FoodSource.AiEstimate
        val scale = if (grams > 0) 100.0 / grams else 1.0
        val figures = if (!overwrite) base else base.copy(
            name = trimmed,
            source = source,
            kcalPer100 = kcal * scale,
            proteinPer100 = protein * scale,
            carbsPer100 = carbs * scale,
            fatPer100 = fat * scale,
            servingSizeG = grams.takeIf { it > 0 },
        )
        val item = figures.copy(useCount = figures.useCount + 1, lastUsedAt = now)
        foodDao.upsert(item)
        return item
    }

    /** The quick-add or estimated food already saved under [name], if any. */
    suspend fun existing(name: String, foodDao: FoodDao): FoodItemEntity? {
        val key = FoodMatch.fold(name.trim())
        return foodDao.customFoods().firstOrNull { FoodMatch.fold(it.name) == key }
    }
}
