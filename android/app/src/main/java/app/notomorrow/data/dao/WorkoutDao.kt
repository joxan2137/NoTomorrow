package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.WorkoutWithExercises
import kotlinx.coroutines.flow.Flow

/**
 * Workouts, their exercises and their sets — one aggregate, because every read that
 * matters (history rows, the done screen, the export, the PR evaluation) crosses all
 * three tables. Writes that touch more than one table go through `db.withTransaction`
 * or one of the `@Transaction` helpers here.
 */
@Dao
interface WorkoutDao {

    // MARK: - Active workout (`WorkoutSessionController`)

    /** `endedAt == nil`, newest first — the fallback adopt path. */
    @Query("SELECT * FROM workout WHERE endedAt IS NULL ORDER BY startedAt DESC")
    fun observeActiveWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workout WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun newestActiveWorkout(): WorkoutEntity?

    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun workout(id: String): WorkoutEntity?

    @Query("SELECT * FROM workout WHERE id = :id")
    fun observeWorkout(id: String): Flow<WorkoutEntity?>

    // MARK: - History

    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT COUNT(*) FROM workout WHERE endedAt IS NOT NULL")
    fun observeFinishedCount(): Flow<Int>

    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC LIMIT :limit")
    fun observeFinishedWorkoutsWithExercises(limit: Int = 100): Flow<List<WorkoutWithExercises>>

    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun lastFinishedWorkoutWithExercises(): WorkoutWithExercises?

    /**
     * `WorkoutDoneView.swift:172` — the previous workout with the same name, for the
     * volume delta.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM workout
        WHERE endedAt IS NOT NULL AND name = :name AND startedAt < :startedBefore AND id != :excludingId
        ORDER BY startedAt DESC LIMIT 1
        """
    )
    suspend fun previousWorkoutWithSameName(
        name: String,
        startedBefore: Long,
        excludingId: String,
    ): WorkoutWithExercises?

    /** Export: every finished workout, oldest first, with the full graph. */
    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt")
    suspend fun finishedWorkoutsWithExercisesAsc(): List<WorkoutWithExercises>

    @Transaction
    @Query("SELECT * FROM workout WHERE id = :id")
    fun observeWorkoutWithExercises(id: String): Flow<WorkoutWithExercises?>

    @Transaction
    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun workoutWithExercises(id: String): WorkoutWithExercises?

    // MARK: - Completed-set projections (`RecordService`, `ProgressModel`)

    @Query(COMPLETED_SETS_SELECT)
    suspend fun completedSets(): List<CompletedSetRow>

    @Query(COMPLETED_SETS_SELECT)
    fun observeCompletedSets(): Flow<List<CompletedSetRow>>

    @Query(COMPLETED_SETS_SELECT + " AND we.exerciseId = :exerciseId")
    suspend fun completedSetsForExercise(exerciseId: String): List<CompletedSetRow>

    @Query(COMPLETED_SETS_SELECT + " AND we.exerciseId = :exerciseId")
    fun observeCompletedSetsForExercise(exerciseId: String): Flow<List<CompletedSetRow>>

    // MARK: - Writes

    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity)

    @Update
    suspend fun updateWorkout(workout: WorkoutEntity)

    @Delete
    suspend fun deleteWorkout(workout: WorkoutEntity)

    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkoutById(id: String)

    @Query("UPDATE workout SET endedAt = :endedAt WHERE id = :id")
    suspend fun finishWorkout(id: String, endedAt: Long)

    @Query("UPDATE workout SET notes = :notes WHERE id = :id")
    suspend fun updateWorkoutNotes(id: String, notes: String)

    @Insert
    suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long

    @Insert
    suspend fun insertWorkoutExercises(workoutExercises: List<WorkoutExerciseEntity>): List<Long>

    @Update
    suspend fun updateWorkoutExercise(workoutExercise: WorkoutExerciseEntity)

    @Delete
    suspend fun deleteWorkoutExercise(workoutExercise: WorkoutExerciseEntity)

    @Query("SELECT * FROM workout_exercise WHERE workoutId = :workoutId ORDER BY orderIndex")
    suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseEntity>

    @Insert
    suspend fun insertSet(set: SetEntryEntity): Long

    @Insert
    suspend fun insertSets(sets: List<SetEntryEntity>): List<Long>

    @Update
    suspend fun updateSet(set: SetEntryEntity)

    @Delete
    suspend fun deleteSet(set: SetEntryEntity)

    @Query("SELECT * FROM set_entry WHERE id = :id")
    suspend fun set(id: Long): SetEntryEntity?

    @Query("SELECT * FROM set_entry WHERE workoutExerciseId = :workoutExerciseId ORDER BY orderIndex")
    suspend fun sets(workoutExerciseId: Long): List<SetEntryEntity>

    /** `RecordService.mark` writes only these two flags back. */
    @Query("UPDATE set_entry SET isPR = :isPR, isSetRecord = :isSetRecord WHERE id = :id")
    suspend fun updateSetRecords(id: Long, isPR: Boolean, isSetRecord: Boolean)

    @Query("UPDATE set_entry SET completedAt = :completedAt WHERE id = :id")
    suspend fun updateSetCompletion(id: Long, completedAt: Long?)

    /** Inserts an exercise and its sets under one transaction (FK order matters). */
    @Transaction
    suspend fun insertWorkoutExerciseWithSets(
        workoutExercise: WorkoutExerciseEntity,
        sets: List<SetEntryEntity>,
    ): Long {
        val id = insertWorkoutExercise(workoutExercise)
        if (sets.isNotEmpty()) insertSets(sets.map { it.copy(workoutExerciseId = id) })
        return id
    }

    // MARK: - Wipe

    @Query("DELETE FROM set_entry")
    suspend fun deleteAllSets()

    @Query("DELETE FROM workout_exercise")
    suspend fun deleteAllWorkoutExercises()

    @Query("DELETE FROM workout")
    suspend fun deleteAllWorkouts()

    companion object {
        /**
         * Every completed set with its exercise and workout context, newest last.
         * Callers append `AND …` to filter; the `WHERE` clause is already open.
         */
        const val COMPLETED_SETS_SELECT = """
            SELECT s.id AS setId, s.orderIndex AS setOrder, s.kind AS kind, s.weightKg AS weightKg,
                   s.reps AS reps, s.completedAt AS completedAt, s.isPR AS isPR,
                   s.isSetRecord AS isSetRecord, s.rpe AS rpe,
                   we.id AS workoutExerciseId, we.orderIndex AS workoutExerciseOrder,
                   we.exerciseId AS exerciseId,
                   w.id AS workoutId, w.name AS workoutName, w.startedAt AS workoutStartedAt,
                   w.endedAt AS workoutEndedAt
            FROM set_entry s
            JOIN workout_exercise we ON we.id = s.workoutExerciseId
            JOIN workout w ON w.id = we.workoutId
            WHERE s.completedAt IS NOT NULL
        """
    }
}
