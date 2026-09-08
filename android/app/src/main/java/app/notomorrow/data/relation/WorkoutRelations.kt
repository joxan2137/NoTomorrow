package app.notomorrow.data.relation

import androidx.room.Embedded
import androidx.room.Relation
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.model.SetKind

/**
 * Read DTOs for the workout graph. `@Relation` reads only — deletion relies on the
 * declared foreign keys (`Workout` → `WorkoutExercise` → `SetEntry`, all CASCADE).
 *
 * Every query that returns one of these is `@Transaction`, so the graph is consistent.
 */

/** `WorkoutExercise` with its exercise (nullable after a library delete) and its sets. */
data class WorkoutExerciseWithSets(
    @Embedded val workoutExercise: WorkoutExerciseEntity,
    @Relation(parentColumn = "exerciseId", entityColumn = "id")
    val exercise: ExerciseEntity?,
    @Relation(parentColumn = "id", entityColumn = "workoutExerciseId")
    val sets: List<SetEntryEntity>,
) {
    /** `WorkoutExercise.sortedSets`. */
    val sortedSets: List<SetEntryEntity> get() = sets.sortedBy { it.order }

    /** `WorkoutExercise.isDone`. */
    val isDone: Boolean get() = sets.isNotEmpty() && sets.all { it.isCompleted }
}

/** `Workout` with its exercises and their sets. */
data class WorkoutWithExercises(
    @Embedded val workout: WorkoutEntity,
    @Relation(
        entity = WorkoutExerciseEntity::class,
        parentColumn = "id",
        entityColumn = "workoutId",
    )
    val exercises: List<WorkoutExerciseWithSets>,
) {
    /** `Workout.sortedExercises`. */
    val sortedExercises: List<WorkoutExerciseWithSets> get() = exercises.sortedBy { it.workoutExercise.order }

    /** `Workout.isActive`. */
    val isActive: Boolean get() = workout.endedAt == null

    private val allSets: List<SetEntryEntity> get() = exercises.flatMap { it.sets }

    /**
     * `Workout.totalVolumeKg` — Σ `weightKg × reps` over completed sets whose kind is not
     * `warmup` (drop and failure sets **are** included).
     */
    val totalVolumeKg: Double
        get() = allSets
            .filter { it.isCompleted && it.kind != SetKind.Warmup }
            .sumOf { it.weightKg * it.reps }

    /** `Workout.completedSetCount` — warm-ups included. */
    val completedSetCount: Int get() = allSets.count { it.isCompleted }

    /** `Workout.prCount`. */
    val prCount: Int get() = allSets.count { it.isPR }
}

/**
 * Flat projection of one completed set with the workout/exercise context around it —
 * the replacement for SwiftData's lazy `set.workoutExercise?.workout` walk.
 *
 * `RecordService` (PR evaluation, tie-break on `workoutExerciseOrder`) and
 * `ProgressModel` (per-workout best e1RM, weekly volume) are both built on this, so
 * neither has to issue an N+1 of graph reads.
 */
data class CompletedSetRow(
    val setId: Long,
    val setOrder: Int,
    val kind: SetKind,
    val weightKg: Double,
    val reps: Int,
    val completedAt: Long,
    val isPR: Boolean,
    val isSetRecord: Boolean,
    val rpe: Double?,
    val workoutExerciseId: Long,
    val workoutExerciseOrder: Int,
    val exerciseId: String?,
    val workoutId: String,
    val workoutName: String,
    val workoutStartedAt: Long,
    val workoutEndedAt: Long?,
) {
    /**
     * Epley estimated one-rep max (`SetEntry.estimatedOneRepMax`): 0 when reps or weight
     * are non-positive, the weight itself for a single, else `weight × (1 + reps / 30)`.
     */
    val estimatedOneRepMax: Double
        get() = when {
            reps <= 0 || weightKg <= 0 -> 0.0
            reps == 1 -> weightKg
            else -> weightKg * (1 + reps / 30.0)
        }
}
