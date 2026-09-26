package app.notomorrow.feature.workoutactive

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.feature.workout.WorkoutShareLine
import app.notomorrow.feature.workout.workoutShareLines
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Test

/** The finish screen's share card lines (`WorkoutShareCard.lines(for:unit:)`). */
class WorkoutShareCardTest {

    private var nextSet = 1L

    private fun set(order: Int, kg: Double, reps: Int, kind: SetKind = SetKind.Normal, done: Boolean = true) =
        SetEntryEntity(
            id = nextSet++, workoutExerciseId = 0, order = order, kind = kind, weightKg = kg, reps = reps,
            completedAt = if (done) 1L else null,
        )

    private fun entry(order: Int, exercise: ExerciseEntity?, sets: List<SetEntryEntity>) = WorkoutExerciseWithSets(
        WorkoutExerciseEntity(id = order.toLong() + 1, workoutId = "w", exerciseId = exercise?.id, order = order),
        exercise,
        sets,
    )

    @Test
    fun `each exercise with working sets and its heaviest set, in workout order`() {
        val bench = ExerciseEntity(id = "bench", name = "Bench Press", namePL = "Wyciskanie")
        val pullUp = ExerciseEntity(id = "pull", name = "Pull-Up")
        val curl = ExerciseEntity(id = "curl", name = "Curl")
        val workout = WorkoutWithExercises(
            WorkoutEntity(id = "w", name = "Push", startedAt = 0),
            listOf(
                entry(
                    1, pullUp,
                    listOf(set(0, 0.0, 8), set(1, 0.0, 12)),
                ),
                entry(
                    0, bench,
                    listOf(
                        set(0, 120.0, 5, kind = SetKind.Warmup),
                        set(1, 100.0, 5),
                        set(2, 100.0, 6),
                        set(3, 110.0, 2, done = false),
                    ),
                ),
                entry(2, curl, listOf(set(0, 20.0, 10, done = false))),
                entry(3, null, listOf(set(0, 20.0, 10))),
            ),
        )
        assertEquals(
            listOf(
                WorkoutShareLine("Bench Press", 2, "100 kg × 6"),
                WorkoutShareLine("Pull-Up", 2, "× 12"),
            ),
            workoutShareLines(workout, WeightUnit.Kg, Locale.US),
        )
        assertEquals("Wyciskanie", workoutShareLines(workout, WeightUnit.Lb, Locale.forLanguageTag("pl-PL")).first().name)
    }
}
