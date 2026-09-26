package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.relation.RoutineWithItems
import kotlinx.coroutines.flow.Flow

/**
 * Routines and their items. Items cascade with the routine, so deleting a
 * [RoutineEntity] is enough; `RoutineSeeder` writes both in one transaction and only
 * when [routineCount] is 0. The routine editor (`RoutineStore`) writes through
 * [saveRoutineWithItems] and [insertRoutineInOrder], each one transaction.
 */
@Dao
interface RoutineDao {

    @Transaction
    @Query("SELECT * FROM routine ORDER BY orderIndex")
    fun observeRoutinesWithItems(): Flow<List<RoutineWithItems>>

    @Transaction
    @Query("SELECT * FROM routine ORDER BY orderIndex")
    suspend fun routinesWithItems(): List<RoutineWithItems>

    @Transaction
    @Query("SELECT * FROM routine WHERE id = :id")
    suspend fun routineWithItems(id: String): RoutineWithItems?

    @Transaction
    @Query("SELECT * FROM routine WHERE id = :id")
    fun observeRoutineWithItems(id: String): Flow<RoutineWithItems?>

    @Query("SELECT * FROM routine ORDER BY orderIndex")
    fun observeRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT COUNT(*) FROM routine")
    suspend fun routineCount(): Int

    @Query("SELECT * FROM routine_item WHERE routineId = :routineId ORDER BY orderIndex")
    suspend fun items(routineId: String): List<RoutineItemEntity>

    @Insert
    suspend fun insertRoutine(routine: RoutineEntity)

    @Insert
    suspend fun insertRoutines(routines: List<RoutineEntity>)

    @Insert
    suspend fun insertItems(items: List<RoutineItemEntity>): List<Long>

    @Insert
    suspend fun insertItem(item: RoutineItemEntity): Long

    @Upsert
    suspend fun upsertRoutine(routine: RoutineEntity)

    @Update
    suspend fun updateItem(item: RoutineItemEntity)

    @Update
    suspend fun updateRoutines(routines: List<RoutineEntity>)

    @Query("DELETE FROM routine_item WHERE routineId = :routineId")
    suspend fun deleteItems(routineId: String)

    @Query("DELETE FROM routine WHERE id = :id")
    suspend fun deleteRoutineById(id: String)

    @Delete
    suspend fun deleteRoutine(routine: RoutineEntity)

    @Delete
    suspend fun deleteItem(item: RoutineItemEntity)

    /** Seeds a routine and its items together; foreign keys require the parent first. */
    @Transaction
    suspend fun insertRoutineWithItems(routine: RoutineEntity, items: List<RoutineItemEntity>) {
        insertRoutine(routine)
        if (items.isNotEmpty()) insertItems(items)
    }

    /**
     * The editor's Save: the routine row written (inserted, or renamed in place, so its items do
     * not cascade away mid-write) and its lines replaced by [items].
     */
    @Transaction
    suspend fun saveRoutineWithItems(routine: RoutineEntity, items: List<RoutineItemEntity>) {
        upsertRoutine(routine)
        deleteItems(routine.id)
        if (items.isNotEmpty()) insertItems(items)
    }

    /**
     * Duplicate: the copy and its items inserted, and the other routines renumbered around it
     * ([reordered] holds only the rows whose order changed).
     */
    @Transaction
    suspend fun insertRoutineInOrder(
        routine: RoutineEntity,
        items: List<RoutineItemEntity>,
        reordered: List<RoutineEntity>,
    ) {
        if (reordered.isNotEmpty()) updateRoutines(reordered)
        insertRoutineWithItems(routine, items)
    }

    @Query("DELETE FROM routine_item")
    suspend fun deleteAllItems()

    @Query("DELETE FROM routine")
    suspend fun deleteAllRoutines()
}
