package app.notomorrow.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.notomorrow.model.SetKind

/**
 * `Workout` (`Models.swift:169`). [endedAt] `null` means the workout is still running —
 * that is the only definition of "active" (`WorkoutSessionController`).
 */
@Entity(
    tableName = "workout",
    indices = [Index("endedAt"), Index("startedAt")],
)
data class WorkoutEntity(
    @PrimaryKey val id: String,
    /** Routine name at the time, or "Workout". */
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val notes: String = "",
)

/** `WorkoutExercise` (`Models.swift:199`). Sets cascade; a deleted exercise nulls the link. */
@Entity(
    tableName = "workout_exercise",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class WorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: String,
    val exerciseId: String?,
    @ColumnInfo(name = "orderIndex") val order: Int,
    val restSeconds: Int = 90,
    val notes: String = "",
    /** Neighbouring exercises with the same id form a superset (`Superset`); `null` = on its own. Schema 2. */
    val supersetGroup: Int? = null,
)

/**
 * `SetEntry` (`Models.swift:216`). A set is completed exactly when [completedAt] is not
 * null. Epley e1RM lives on the service side; the entity only carries the numbers.
 */
@Entity(
    tableName = "set_entry",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutExerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutExerciseId")],
)
data class SetEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    @ColumnInfo(name = "orderIndex") val order: Int,
    val kind: SetKind = SetKind.Normal,
    val weightKg: Double = 0.0,
    val reps: Int = 0,
    val completedAt: Long? = null,
    val isPR: Boolean = false,
    val isSetRecord: Boolean = false,
    val rpe: Double? = null,
) {
    val isCompleted: Boolean get() = completedAt != null
}
