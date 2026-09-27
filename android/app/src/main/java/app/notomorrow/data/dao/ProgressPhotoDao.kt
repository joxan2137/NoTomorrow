package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.notomorrow.data.entity.ProgressPhotoEntity
import kotlinx.coroutines.flow.Flow

/** Progress photos (`ProgressPhoto`), newest first — the Progress › Body strip reads them that way. */
@Dao
interface ProgressPhotoDao {

    @Query("SELECT * FROM progress_photo ORDER BY takenAt DESC, id DESC")
    fun observeAll(): Flow<List<ProgressPhotoEntity>>

    @Insert
    suspend fun insert(row: ProgressPhotoEntity)

    @Query("DELETE FROM progress_photo WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM progress_photo")
    suspend fun deleteAll()
}
