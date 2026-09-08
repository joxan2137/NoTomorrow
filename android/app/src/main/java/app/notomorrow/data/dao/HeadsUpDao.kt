package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import app.notomorrow.data.entity.HeadsUpEntity
import kotlinx.coroutines.flow.Flow

/** Heads-ups, newest first. `BroService.sync` de-dupes incoming rows by (±1 s, text). */
@Dao
interface HeadsUpDao {

    @Query("SELECT * FROM heads_up ORDER BY sentAt DESC")
    fun observeAllDesc(): Flow<List<HeadsUpEntity>>

    @Query("SELECT * FROM heads_up ORDER BY sentAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HeadsUpEntity>>

    @Query("SELECT * FROM heads_up ORDER BY sentAt DESC")
    suspend fun allDesc(): List<HeadsUpEntity>

    /** Sync window: everything at or after the earliest incoming `sentAt`. */
    @Query("SELECT * FROM heads_up WHERE sentAt >= :earliest")
    suspend fun since(earliest: Long): List<HeadsUpEntity>

    @Query("SELECT * FROM heads_up WHERE fromMe = 0 AND readAt IS NULL ORDER BY sentAt DESC")
    fun observeUnread(): Flow<List<HeadsUpEntity>>

    @Insert
    suspend fun insert(headsUp: HeadsUpEntity)

    @Insert
    suspend fun insertAll(headsUps: List<HeadsUpEntity>)

    @Query("UPDATE heads_up SET readAt = :at WHERE id = :id")
    suspend fun markRead(id: String, at: Long)

    @Query("UPDATE heads_up SET readAt = :at WHERE readAt IS NULL AND fromMe = 0")
    suspend fun markAllRead(at: Long)

    @Delete
    suspend fun delete(headsUp: HeadsUpEntity)

    @Query("DELETE FROM heads_up")
    suspend fun deleteAll()
}
