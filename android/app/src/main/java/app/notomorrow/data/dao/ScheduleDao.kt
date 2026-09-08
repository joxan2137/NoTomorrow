package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.notomorrow.data.entity.GymScheduleEntity
import kotlinx.coroutines.flow.Flow

/** The singleton `gym_schedule` row. Weekdays are always persisted sorted. */
@Dao
interface ScheduleDao {

    @Query("SELECT * FROM gym_schedule LIMIT 1")
    fun observeSchedule(): Flow<GymScheduleEntity?>

    @Query("SELECT * FROM gym_schedule LIMIT 1")
    suspend fun schedule(): GymScheduleEntity?

    @Upsert
    suspend fun upsertRaw(schedule: GymScheduleEntity)

    /** Normalises the row (singleton id, sorted weekdays) before writing. */
    suspend fun save(schedule: GymScheduleEntity) = upsertRaw(schedule.normalized())

    @Query("DELETE FROM gym_schedule")
    suspend fun deleteAll()
}
