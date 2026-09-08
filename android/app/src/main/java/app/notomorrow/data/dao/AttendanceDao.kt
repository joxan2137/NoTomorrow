package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.Participant
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Attendance rows for both participants. `day` arguments are local midnight epoch
 * millis. The `(day, participant)` unique index means every write must go through
 * [upsert] — a plain insert on an existing pair throws.
 */
@Dao
interface AttendanceDao {

    @Query("SELECT * FROM attendance_record ORDER BY day DESC")
    fun observeAllDesc(): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_record ORDER BY day DESC")
    suspend fun allDesc(): List<AttendanceRecordEntity>

    /** `[from, to)` — the current-week strip and the 30-day counts. */
    @Query("SELECT * FROM attendance_record WHERE day >= :from AND day < :to ORDER BY day")
    fun observeRange(from: Long, to: Long): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_record WHERE day >= :from AND day < :to ORDER BY day")
    suspend fun range(from: Long, to: Long): List<AttendanceRecordEntity>

    @Query("SELECT * FROM attendance_record WHERE day >= :from ORDER BY day")
    suspend fun since(from: Long): List<AttendanceRecordEntity>

    @Query("SELECT * FROM attendance_record WHERE day = :day")
    suspend fun forDay(day: Long): List<AttendanceRecordEntity>

    @Query("SELECT * FROM attendance_record WHERE day = :day AND participant = :participant LIMIT 1")
    suspend fun forDay(day: Long, participant: Participant): AttendanceRecordEntity?

    @Query(
        """
        SELECT * FROM attendance_record
        WHERE participant = :participant AND day < :before AND status != 'planned' AND status != 'confirmed'
        ORDER BY day DESC
        """
    )
    suspend fun pastResolved(participant: Participant, before: Long): List<AttendanceRecordEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AttendanceRecordEntity)

    @Update
    suspend fun update(record: AttendanceRecordEntity)

    @Delete
    suspend fun delete(record: AttendanceRecordEntity)

    /**
     * `AttendanceService.upsert` — one row per (day, participant). Keeps the existing
     * row's id (so nothing that holds it goes stale) and stamps `updatedAt`.
     */
    @Transaction
    suspend fun upsert(
        day: Long,
        participant: Participant,
        scheduledMinuteOfDay: Int,
        status: AttendanceStatus,
        reason: String? = null,
        note: String? = null,
        makeUpDay: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): AttendanceRecordEntity {
        val existing = forDay(day, participant)
        val row = existing?.copy(
            scheduledMinuteOfDay = scheduledMinuteOfDay,
            status = status,
            reason = reason,
            note = note,
            makeUpDay = makeUpDay,
            updatedAt = now,
        ) ?: AttendanceRecordEntity(
            id = UUID.randomUUID().toString(),
            day = day,
            participant = participant,
            scheduledMinuteOfDay = scheduledMinuteOfDay,
            status = status,
            reason = reason,
            note = note,
            makeUpDay = makeUpDay,
            updatedAt = now,
        )
        if (existing == null) insert(row) else update(row)
        return row
    }

    @Query("DELETE FROM attendance_record")
    suspend fun deleteAll()
}
