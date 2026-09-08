package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import app.notomorrow.data.entity.BodyWeightEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Body weight, one row per day (the `day` primary key makes a second log an upsert —
 * `LogWeightSheet.swift:76`). Ordered oldest first: the charts read it that way.
 */
@Dao
interface BodyWeightDao {

    @Query("SELECT * FROM body_weight_entry ORDER BY day")
    fun observeAll(): Flow<List<BodyWeightEntryEntity>>

    @Query("SELECT * FROM body_weight_entry ORDER BY day")
    suspend fun all(): List<BodyWeightEntryEntity>

    @Query("SELECT * FROM body_weight_entry WHERE day = :day")
    suspend fun forDay(day: Long): BodyWeightEntryEntity?

    @Query("SELECT * FROM body_weight_entry ORDER BY day DESC LIMIT 1")
    suspend fun latest(): BodyWeightEntryEntity?

    @Query("SELECT * FROM body_weight_entry ORDER BY day DESC LIMIT 1")
    fun observeLatest(): Flow<BodyWeightEntryEntity?>

    @Upsert
    suspend fun upsert(entry: BodyWeightEntryEntity)

    @Delete
    suspend fun delete(entry: BodyWeightEntryEntity)

    @Query("DELETE FROM body_weight_entry")
    suspend fun deleteAll()
}
