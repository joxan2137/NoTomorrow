package app.notomorrow.feature.workoutactive

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.ExerciseNoteRow
import app.notomorrow.feature.workout.exerciseHistorySessions
import app.notomorrow.model.SetKind
import kotlin.test.assertEquals
import org.junit.Test

/** The exercise history sheet's sessions (`ExerciseHistorySheet.sessions(of:excluding:)`). */
class ExerciseHistoryTest {

    private fun row(
        entry: Long,
        workout: String,
        startedAt: Long,
        order: Int,
        kg: Double,
        reps: Int,
        kind: SetKind = SetKind.Normal,
        endedAt: Long? = startedAt + 3_600_000,
    ) = CompletedSetRow(
        setId = entry * 100 + order, setOrder = order, kind = kind, weightKg = kg, reps = reps,
        completedAt = startedAt + order, isPR = false, isSetRecord = false, rpe = null,
        workoutExerciseId = entry, workoutExerciseOrder = 0, exerciseId = "bench", workoutId = workout,
        workoutName = "Push $workout", workoutStartedAt = startedAt, workoutEndedAt = endedAt,
    )

    @Test
    fun `sessions are newest first with their sets in order`() {
        val rows = listOf(
            row(1, "a", startedAt = 1_000, order = 1, kg = 80.0, reps = 8),
            row(1, "a", startedAt = 1_000, order = 0, kg = 60.0, reps = 10),
            row(2, "b", startedAt = 5_000, order = 0, kg = 82.5, reps = 6),
        )
        val sessions = exerciseHistorySessions(rows, emptyList(), excludingWorkoutId = null)
        assertEquals(listOf(2L, 1L), sessions.map { it.id })
        assertEquals(listOf(60.0, 80.0), sessions[1].sets.map { it.weightKg })
        assertEquals("Push b", sessions[0].workoutName)
    }

    @Test
    fun `the workout in progress and unfinished ones are left out`() {
        val rows = listOf(
            row(1, "done", startedAt = 1_000, order = 0, kg = 80.0, reps = 8),
            row(2, "current", startedAt = 9_000, order = 0, kg = 85.0, reps = 5),
            row(3, "open", startedAt = 8_000, order = 0, kg = 85.0, reps = 5, endedAt = null),
        )
        val sessions = exerciseHistorySessions(rows, emptyList(), excludingWorkoutId = "current")
        assertEquals(listOf(1L), sessions.map { it.id })
    }

    @Test
    fun `best e1RM skips warm-ups and the note is the entry's own`() {
        val rows = listOf(
            row(1, "a", startedAt = 1_000, order = 0, kg = 200.0, reps = 5, kind = SetKind.Warmup),
            row(1, "a", startedAt = 1_000, order = 1, kg = 100.0, reps = 1),
            row(1, "a", startedAt = 1_000, order = 2, kg = 90.0, reps = 5),
        )
        val notes = listOf(
            ExerciseNoteRow("seat 4", "a", 1_000, 4_000, workoutExerciseId = 1),
            ExerciseNoteRow("other", "b", 2_000, 5_000, workoutExerciseId = 7),
        )
        val session = exerciseHistorySessions(rows, notes, excludingWorkoutId = null).single()
        assertEquals(105.0, session.bestE1RM, 0.001)
        assertEquals("seat 4", session.note)
        assertEquals(3, session.sets.size, "warm-ups are still listed")
    }

    @Test
    fun `no completed sets means no sessions`() {
        assertEquals(emptyList(), exerciseHistorySessions(emptyList(), emptyList(), excludingWorkoutId = null))
    }
}
