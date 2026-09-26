package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.notomorrow.data.entity.BodyMeasurementEntity
import kotlinx.coroutines.flow.Flow

/** Tape measurements (`BodyMeasurement`), oldest first — the Progress › Body section reads them that way. */
@Dao
interface BodyMeasurementDao {

    @Query("SELECT * FROM body_measurement ORDER BY day")
    fun observeAll(): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurement WHERE day = :day")
    suspend fun forDay(day: Long): List<BodyMeasurementEntity>

    @Insert
    suspend fun insert(row: BodyMeasurementEntity)

    @Update
    suspend fun update(row: BodyMeasurementEntity)

    @Query("DELETE FROM body_measurement")
    suspend fun deleteAll()
}
