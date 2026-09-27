package app.notomorrow.feature.workout

import androidx.annotation.StringRes
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.util.S

/**
 * `ExerciseLibrary.Equipment` — the exercise picker's equipment chips, mapped onto
 * free-exercise-db `equipment` values: the EZ bar counts as a barbell, and medicine / exercise
 * balls, foam rollers, "other" and no equipment at all are [Other].
 */
enum class ExerciseEquipment(val raw: String) {
    All("all"),
    Barbell("barbell"),
    Dumbbell("dumbbell"),
    Machine("machine"),
    Cable("cable"),
    Bodyweight("bodyweight"),
    Kettlebell("kettlebell"),
    Band("band"),
    Other("other");

    /** The chip title — the equipment names the exercise detail already uses. */
    @get:StringRes
    val titleRes: Int
        get() = when (this) {
            All -> S.equipment_all
            Barbell -> S.equipment_barbell
            Dumbbell -> S.equipment_dumbbell
            Machine -> S.equipment_machine
            Cable -> S.equipment_cable
            Bodyweight -> S.equipment_body_only
            Kettlebell -> S.equipment_kettlebells
            Band -> S.equipment_bands
            Other -> S.equipment_other
        }

    /** The raw value a custom exercise created under this chip gets, so it stays in the filtered list. */
    val representative: String?
        get() = when (this) {
            All -> null
            Barbell -> "barbell"
            Dumbbell -> "dumbbell"
            Machine -> "machine"
            Cable -> "cable"
            Bodyweight -> "body only"
            Kettlebell -> "kettlebells"
            Band -> "bands"
            Other -> "other"
        }

    fun matches(equipment: String?): Boolean = this == All || of(equipment) == this

    companion object {
        /** The chip an exercise's raw `equipment` value falls under (never [All]). */
        fun of(equipment: String?): ExerciseEquipment = when (equipment?.lowercase()) {
            "barbell", "e-z curl bar" -> Barbell
            "dumbbell" -> Dumbbell
            "machine" -> Machine
            "cable" -> Cable
            "body only" -> Bodyweight
            "kettlebells" -> Kettlebell
            "bands" -> Band
            else -> Other
        }

        /** The picker's equipment filter, applied after the search and muscle chip. */
        fun filter(exercises: List<ExerciseEntity>, equipment: ExerciseEquipment): List<ExerciseEntity> =
            if (equipment == All) exercises else exercises.filter { equipment.matches(it.equipment) }
    }
}
