package app.notomorrow.feature.progress

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.model.SetKind
import kotlin.test.assertEquals
import org.junit.Test

/** Rep-max table (`RepMaxTests.swift`): best set for at least N reps, and the Epley estimate from the best e1RM. */
class RepMaxTest {

    private fun set(kg: Double, reps: Int) = CompletedSetRow(
        setId = 0, setOrder = 0, kind = SetKind.Normal, weightKg = kg, reps = reps, completedAt = 1,
        isPR = false, isSetRecord = false, rpe = null, workoutExerciseId = 1, workoutExerciseOrder = 0,
        exerciseId = "bench", workoutId = "w", workoutName = "W", workoutStartedAt = 0, workoutEndedAt = 1,
    )

    @Test
    fun `best set needs at least that many reps`() {
        val rows = RepMax.rows(listOf(set(100.0, 3), set(90.0, 5), set(95.0, 5), set(60.0, 12)), e1RM = 110.0)
        assertEquals(RepMax.repCounts, rows.map { it.reps })
        assertEquals(100.0, rows[0].best?.weightKg) // 1 rep: 100 × 3 counts
        assertEquals(100.0, rows[1].best?.weightKg) // 3 reps
        assertEquals(95.0, rows[2].best?.weightKg) // 5 reps
        assertEquals(60.0, rows[3].best?.weightKg) // 8 reps: only the 12-rep set qualifies
        assertEquals(60.0, rows[5].best?.weightKg)
    }

    @Test
    fun `estimate inverts Epley`() {
        assertEquals(120.0, RepMax.estimate(e1RM = 120.0, reps = 1))
        assertEquals(90.0, RepMax.estimate(e1RM = 120.0, reps = 10), 0.001)
        assertEquals(0.0, RepMax.estimate(e1RM = 0.0, reps = 5))
        val e1RM = set(100.0, 5).estimatedOneRepMax
        assertEquals(100.0, RepMax.estimate(e1RM = e1RM, reps = 5), 0.001, "round-trips a set's own e1RM")
    }
}
