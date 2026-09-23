package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import app.notomorrow.data.entity.ExerciseEntity
import kotlinx.coroutines.flow.Flow

/**
 * The exercise library: 989 bundled records plus the user's `custom-<uuid>` rows.
 * `ExerciseLibrary.importIfNeeded()` uses [libraryCount], [insertAllIgnoring] and
 * [missingPolishNames] / [updatePolishName]; the picker uses [observeAllByName].
 */
@Dao
interface ExerciseDao {

    @Query("SELECT * FROM exercise ORDER BY name COLLATE NOCASE")
    fun observeAllByName(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise ORDER BY name COLLATE NOCASE")
    suspend fun allByName(): List<ExerciseEntity>

    @Query("SELECT COUNT(*) FROM exercise")
    suspend fun count(): Int

    /** Bundled (non-custom) rows — the import's "library is empty" check. */
    @Query("SELECT COUNT(*) FROM exercise WHERE isCustom = 0")
    suspend fun libraryCount(): Int

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun byId(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercise WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<ExerciseEntity>

    /** Bundled rows that have not been back-filled with a Polish name yet. */
    @Query("SELECT * FROM exercise WHERE namePL IS NULL AND isCustom = 0 LIMIT :limit")
    suspend fun missingPolishNames(limit: Int = 2000): List<ExerciseEntity>

    @Query("UPDATE exercise SET namePL = :namePL WHERE id = :id")
    suspend fun updatePolishName(id: String, namePL: String)

    @Query("UPDATE exercise SET lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: String, at: Long)

    /** Import path: never clobbers a row the user already has. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoring(exercises: List<ExerciseEntity>)

    @Upsert
    suspend fun upsert(exercise: ExerciseEntity)

    @Update
    suspend fun update(exercise: ExerciseEntity)

    /** Delete-account wipe: the bundled library is not the user's data, custom rows are. */
    @Query("DELETE FROM exercise WHERE isCustom = 1")
    suspend fun deleteCustom()

    /** Delete-account wipe: the library stays, the order the last account used it in does not. */
    @Query("UPDATE exercise SET lastUsedAt = NULL WHERE lastUsedAt IS NOT NULL")
    suspend fun clearLastUsed()

    @Query("DELETE FROM exercise")
    suspend fun deleteAll()
}
