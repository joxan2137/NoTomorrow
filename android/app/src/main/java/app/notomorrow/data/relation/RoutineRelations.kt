package app.notomorrow.data.relation

import androidx.room.Embedded
import androidx.room.Relation
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity

/** `RoutineItem` with its exercise (nullable after a library delete). */
data class RoutineItemWithExercise(
    @Embedded val item: RoutineItemEntity,
    @Relation(parentColumn = "exerciseId", entityColumn = "id")
    val exercise: ExerciseEntity?,
)

/** `Routine` with its items. `Routine.sortedItems` is [sortedItems]. */
data class RoutineWithItems(
    @Embedded val routine: RoutineEntity,
    @Relation(
        entity = RoutineItemEntity::class,
        parentColumn = "id",
        entityColumn = "routineId",
    )
    val items: List<RoutineItemWithExercise>,
) {
    val sortedItems: List<RoutineItemWithExercise> get() = items.sortedBy { it.item.order }
}
