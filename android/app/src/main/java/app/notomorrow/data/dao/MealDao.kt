package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.relation.DayKcal
import app.notomorrow.data.relation.DayProtein
import app.notomorrow.data.relation.MealEntryWithFood
import kotlinx.coroutines.flow.Flow

/**
 * Meal entries. Every `day` argument is **local midnight epoch millis** in the device
 * zone — the same value the entity stores.
 */
@Dao
interface MealDao {

    @Transaction
    @Query("SELECT * FROM meal_entry WHERE day = :day ORDER BY loggedAt")
    fun observeDay(day: Long): Flow<List<MealEntryWithFood>>

    @Transaction
    @Query("SELECT * FROM meal_entry WHERE day = :day ORDER BY loggedAt")
    suspend fun entriesForDay(day: Long): List<MealEntryWithFood>

    @Query("SELECT * FROM meal_entry WHERE day = :day ORDER BY loggedAt")
    fun observeDayEntries(day: Long): Flow<List<MealEntryEntity>>

    /** `FuelModel.computeStreak` scans 120 days; the aggregate keeps it one row per day. */
    @Query("SELECT day AS day, SUM(proteinG) AS proteinG FROM meal_entry WHERE day >= :from GROUP BY day")
    suspend fun proteinByDaySince(from: Long): List<DayProtein>

    @Query("SELECT day AS day, SUM(proteinG) AS proteinG FROM meal_entry WHERE day >= :from GROUP BY day")
    fun observeProteinByDaySince(from: Long): Flow<List<DayProtein>>

    @Query("SELECT day AS day, SUM(kcal) AS kcal FROM meal_entry WHERE day >= :from GROUP BY day")
    fun observeKcalByDaySince(from: Long): Flow<List<DayKcal>>

    @Query("SELECT * FROM meal_entry WHERE day >= :from ORDER BY day, loggedAt")
    suspend fun entriesSince(from: Long): List<MealEntryEntity>

    /** Export: every entry, oldest first (`day`, then `loggedAt`). */
    @Transaction
    @Query("SELECT * FROM meal_entry ORDER BY day, loggedAt")
    suspend fun allEntriesWithFood(): List<MealEntryWithFood>

    @Query("SELECT * FROM meal_entry WHERE id = :id")
    suspend fun byId(id: String): MealEntryEntity?

    @Insert
    suspend fun insert(entry: MealEntryEntity)

    @Insert
    suspend fun insertAll(entries: List<MealEntryEntity>)

    @Update
    suspend fun update(entry: MealEntryEntity)

    @Delete
    suspend fun delete(entry: MealEntryEntity)

    @Query("DELETE FROM meal_entry WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM meal_entry")
    suspend fun deleteAll()
}
