package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import app.notomorrow.data.entity.FoodItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * The local food cache. Rows arrive from Open Food Facts (`off:<code>`), quick-add and
 * custom entries; `useCount` / `lastUsedAt` drive the "Recent" list in the search sheet
 * (`FoodSearchView.swift:18` — last 10 by `lastUsedAt`).
 */
@Dao
interface FoodDao {

    @Query("SELECT * FROM food_item WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 10): Flow<List<FoodItemEntity>>

    @Query("SELECT * FROM food_item WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 10): List<FoodItemEntity>

    @Query("SELECT * FROM food_item WHERE isFavorite = 1 ORDER BY name COLLATE NOCASE")
    fun observeFavorites(): Flow<List<FoodItemEntity>>

    @Query("SELECT * FROM food_item WHERE id = :id")
    suspend fun byId(id: String): FoodItemEntity?

    @Query("SELECT * FROM food_item WHERE barcode = :barcode LIMIT 1")
    suspend fun byBarcode(barcode: String): FoodItemEntity?

    @Upsert
    suspend fun upsert(food: FoodItemEntity)

    @Upsert
    suspend fun upsertAll(foods: List<FoodItemEntity>)

    /** `FoodSupport.resolveItem` — one use, one bump. */
    @Query("UPDATE food_item SET useCount = useCount + 1, lastUsedAt = :at WHERE id = :id")
    suspend fun bumpUsage(id: String, at: Long)

    @Query("UPDATE food_item SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Delete
    suspend fun delete(food: FoodItemEntity)

    @Query("DELETE FROM food_item")
    suspend fun deleteAll()
}
