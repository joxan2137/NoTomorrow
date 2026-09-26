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
import app.notomorrow.data.relation.ExerciseNoteRow
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

    /** Every unfinished workout, newest first — adoption and the launch-time orphan repair. */
    @Query("SELECT * FROM workout WHERE endedAt IS NULL ORDER BY startedAt DESC")
    suspend fun activeWorkouts(): List<WorkoutEntity>

    /** `Workout.completedSetCount` without loading the graph — gates Discard. Warm-ups count. */
    @Query(
        """
        SELECT COUNT(*) FROM set_entry s
        JOIN workout_exercise we ON we.id = s.workoutExerciseId
        WHERE we.workoutId = :workoutId AND s.completedAt IS NOT NULL
        """
    )
    suspend fun completedSetCount(workoutId: String): Int

    /** The last `completedAt` in a workout — where the orphan repair ends an abandoned one. */
    @Query(
        """
        SELECT MAX(s.completedAt) FROM set_entry s
        JOIN workout_exercise we ON we.id = s.workoutExerciseId
        WHERE we.workoutId = :workoutId AND s.completedAt IS NOT NULL
        """
    )
    suspend fun lastCompletedAt(workoutId: String): Long?

    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun workout(id: String): WorkoutEntity?

    /**
     * Finished workouts other than [excludingId] that started in `[from, to)` and have a completed
     * set — the ones that keep a day attended when an edited or deleted workout leaves it.
     */
    @Query(
        """
        SELECT COUNT(*) FROM workout w
        WHERE w.endedAt IS NOT NULL AND w.startedAt >= :from AND w.startedAt < :to AND w.id != :excludingId
          AND EXISTS (
            SELECT 1 FROM set_entry s JOIN workout_exercise we ON we.id = s.workoutExerciseId
            WHERE we.workoutId = w.id AND s.completedAt IS NOT NULL
          )
        """
    )
    suspend fun countedWorkoutsBetween(from: Long, to: Long, excludingId: String): Int

    /**
     * Finished workouts with a completed set that started in `[from, to)` — the Today card's
     * "trained today" (`DashboardView.trainedToday`), which hides "Can't make it".
     */
    @Query(
        """
        SELECT COUNT(*) FROM workout w
        WHERE w.endedAt IS NOT NULL AND w.startedAt >= :from AND w.startedAt < :to
          AND EXISTS (
            SELECT 1 FROM set_entry s JOIN workout_exercise we ON we.id = s.workoutExerciseId
            WHERE we.workoutId = w.id AND s.completedAt IS NOT NULL
          )
        """
    )
    fun observeCountedWorkoutsBetween(from: Long, to: Long): Flow<Int>

    /**
     * Start times of the finished workouts with a completed set that started at or after [from] —
     * the Fuel calendar widget's trained days and its 30-day session count (`docs/widgets.md`).
     */
    @Query(
        """
        SELECT w.startedAt FROM workout w
        WHERE w.endedAt IS NOT NULL AND w.startedAt >= :from
          AND EXISTS (
            SELECT 1 FROM set_entry s JOIN workout_exercise we ON we.id = s.workoutExerciseId
            WHERE we.workoutId = w.id AND s.completedAt IS NOT NULL
          )
        ORDER BY w.startedAt
        """
    )
    suspend fun countedWorkoutStartsSince(from: Long): List<Long>

    @Query("SELECT * FROM workout WHERE id = :id")
    fun observeWorkout(id: String): Flow<WorkoutEntity?>

    // MARK: - History

    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT COUNT(*) FROM workout WHERE endedAt IS NOT NULL")
    fun observeFinishedCount(): Flow<Int>

    /**
     * Name of the most recent finished workout that came from a routine (its name matches one) —
     * the Dashboard's suggestion rotates from it, so ad-hoc workouts do not shift the rotation.
     */
    @Query(
        """
        SELECT name FROM workout
        WHERE endedAt IS NOT NULL AND name IN (SELECT name FROM routine)
        ORDER BY startedAt DESC LIMIT 1
        """
    )
    fun observeLastRoutineWorkoutName(): Flow<String?>

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

    /** Every entry of [exerciseId] with a note, and its workout (`ActiveWorkoutModel.previousNote(for:)`). */
    @Query(
        """
        SELECT we.notes AS notes, w.id AS workoutId, w.startedAt AS workoutStartedAt, w.endedAt AS workoutEndedAt,
               we.id AS workoutExerciseId
        FROM workout_exercise we JOIN workout w ON w.id = we.workoutId
        WHERE we.exerciseId = :exerciseId AND we.notes != ''
        """,
    )
    suspend fun exerciseNotes(exerciseId: String): List<ExerciseNoteRow>

    /**
     * Exercises with at least one workout entry, finished or in progress — a custom exercise in
     * none of them may be deleted from the picker (`exercise.usages.isEmpty`).
     */
    @Query("SELECT DISTINCT exerciseId FROM workout_exercise WHERE exerciseId IS NOT NULL")
    fun observeUsedExerciseIds(): Flow<List<String>>

    /** Workout entries of [exerciseId] — the delete's last check. */
    @Query("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = :exerciseId")
    suspend fun usageCount(exerciseId: String): Int

    @Query("UPDATE workout_exercise SET notes = :notes WHERE id = :id")
    suspend fun updateWorkoutExerciseNotes(id: Long, notes: String)

    /** The exercise menu's Rest timer: the rest after this workout exercise's sets. */
    @Query("UPDATE workout_exercise SET restSeconds = :seconds WHERE id = :id")
    suspend fun updateWorkoutExerciseRest(id: Long, seconds: Int)

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

    /** "Edit sets" from the summary: the workout is back in progress. */
    @Query("UPDATE workout SET endedAt = NULL WHERE id = :id")
    suspend fun reopenWorkout(id: String)

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

    /**
     * `RecordService.rebuild`'s open-set pass: an open set of these exercises that still carries a
     * PR / set-record flag loses it. Returns the rows changed.
     */
    @Query(
        """
        UPDATE set_entry SET isPR = 0, isSetRecord = 0
        WHERE completedAt IS NULL AND (isPR = 1 OR isSetRecord = 1)
          AND workoutExerciseId IN (SELECT id FROM workout_exercise WHERE exerciseId IN (:exerciseIds))
        """
    )
    suspend fun clearOpenSetRecords(exerciseIds: List<String>): Int

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
