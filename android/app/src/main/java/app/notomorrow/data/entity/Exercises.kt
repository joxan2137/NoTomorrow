package app.notomorrow.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `Exercise` (`Models.swift:94`). [id] is the free-exercise-db id
 * (`"Barbell_Bench_Press_-_Medium_Grip"`) or `"custom-<uuid>"`. The `images` array in
 * `assets/exercises.json` is parsed and discarded.
 */
@Entity(tableName = "exercise")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val namePL: String? = null,
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: String? = null,
    val category: String = "strength",
    val force: String? = null,
    val mechanic: String? = null,
    val level: String? = null,
    val instructions: List<String> = emptyList(),
    val isCustom: Boolean = false,
    val lastUsedAt: Long? = null,
)

/** `Routine` (`Models.swift:132`) — "Push A". Items cascade. */
@Entity(tableName = "routine")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Column is `orderIndex`: `order` is a SQLite keyword. */
    @ColumnInfo(name = "orderIndex") val order: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * `RoutineItem` (`Models.swift:149`). The parent routine cascades; a deleted exercise
 * nulls [exerciseId] (SwiftData's `.nullify`).
 */
@Entity(
    tableName = "routine_item",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("routineId"), Index("exerciseId")],
)
data class RoutineItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: String,
    val exerciseId: String?,
    @ColumnInfo(name = "orderIndex") val order: Int,
    val targetSets: Int = 3,
    val targetReps: Int = 8,
    val restSeconds: Int = 90,
    /** Neighbouring items with the same id form a superset (`Superset`); `null` = on its own. Schema 2. */
    val supersetGroup: Int? = null,
)
