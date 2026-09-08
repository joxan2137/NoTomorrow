package app.notomorrow.service

import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.model.SetKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The PR rules of `Services/RecordService.swift`, which iOS has no tests for: the first
 * working set is a record, warm-ups never count either way, a set record needs the exact
 * same weight and only when the set is not already a PR, and an exact timestamp tie falls
 * back to the row order inside the same `WorkoutExercise`.
 */
class RecordServiceTest {

    private var nextId = 1L

    private fun row(
        weightKg: Double,
        reps: Int,
        completedAt: Long,
        kind: SetKind = SetKind.Normal,
        workoutExerciseId: Long = 1,
        setOrder: Int = 0,
        isPR: Boolean = false,
        workoutId: String = "w1",
        workoutStartedAt: Long = completedAt,
    ) = CompletedSetRow(
        setId = nextId++,
        setOrder = setOrder,
        kind = kind,
        weightKg = weightKg,
        reps = reps,
        completedAt = completedAt,
        isPR = isPR,
        isSetRecord = false,
        rpe = null,
        workoutExerciseId = workoutExerciseId,
        workoutExerciseOrder = 0,
        exerciseId = "Barbell_Squat",
        workoutId = workoutId,
        workoutName = "Legs",
        workoutStartedAt = workoutStartedAt,
        workoutEndedAt = null,
    )

    private fun candidate(
        weightKg: Double,
        reps: Int,
        completedAt: Long? = 1_000,
        kind: SetKind = SetKind.Normal,
        workoutExerciseId: Long = 1,
        order: Int = 5,
        id: Long = 999,
    ) = SetEntryEntity(
        id = id,
        workoutExerciseId = workoutExerciseId,
        order = order,
        kind = kind,
        weightKg = weightKg,
        reps = reps,
        completedAt = completedAt,
    )

    // MARK: Epley

    @Test
    fun `epley matches the iOS formula`() {
        assertEquals(102.0, RecordService.epley(85.0, 6), 0.01)
        assertEquals(100.0, RecordService.epley(100.0, 1), 0.0)
        assertEquals(0.0, RecordService.epley(100.0, 0), 0.0)
        assertEquals(0.0, RecordService.epley(0.0, 5), 0.0)
    }

    // MARK: First set

    @Test
    fun `the first working set of an exercise is a PR and not a set record`() {
        val result = RecordService.evaluate(candidate(60.0, 8), previous = emptyList())
        assertTrue(result.isPR)
        assertFalse(result.isSetRecord)
        assertNull(result.bestBefore)
    }

    @Test
    fun `a warm-up is never a record`() {
        val result = RecordService.evaluate(candidate(200.0, 10, kind = SetKind.Warmup), previous = emptyList())
        assertFalse(result.isPR)
        assertFalse(result.isSetRecord)
    }

    @Test
    fun `a zero-rep set is never a record`() {
        val result = RecordService.evaluate(candidate(200.0, 0), previous = emptyList())
        assertFalse(result.isPR)
        assertFalse(result.isSetRecord)
    }

    // MARK: PR rules

    @Test
    fun `a higher e1RM is a PR`() {
        val previous = listOf(row(100.0, 5, 1))          // e1RM 116.67
        val result = RecordService.evaluate(candidate(100.0, 6), previous)  // e1RM 120
        assertTrue(result.isPR)
        assertFalse(result.isSetRecord)
    }

    @Test
    fun `a heavier weight is a PR even at a lower e1RM`() {
        val previous = listOf(row(100.0, 10, 1))         // e1RM 133.3
        val result = RecordService.evaluate(candidate(110.0, 1), previous)  // e1RM 110
        assertTrue(result.isPR)
    }

    @Test
    fun `matching the best is not a PR`() {
        val previous = listOf(row(100.0, 5, 1))
        val result = RecordService.evaluate(candidate(100.0, 5), previous)
        assertFalse(result.isPR)
        assertFalse(result.isSetRecord)
    }

    // MARK: Set records

    @Test
    fun `more reps at the same weight is a set record`() {
        val previous = listOf(row(100.0, 5, 1), row(120.0, 5, 2))
        val result = RecordService.evaluate(candidate(100.0, 6), previous)
        assertFalse(result.isPR)
        assertTrue(result.isSetRecord)
    }

    @Test
    fun `a set record needs exactly the same weight`() {
        val previous = listOf(row(100.0, 5, 1), row(120.0, 8, 2))
        // 99 kg × 6 beats nothing at 99 kg, and 99 kg is neither the heaviest nor the best e1RM.
        val result = RecordService.evaluate(candidate(99.0, 6), previous)
        assertFalse(result.isPR)
        assertFalse(result.isSetRecord)
    }

    @Test
    fun `a PR is never also flagged as a set record`() {
        val previous = listOf(row(100.0, 5, 1))
        val result = RecordService.evaluate(candidate(100.0, 9), previous)
        assertTrue(result.isPR)
        assertFalse(result.isSetRecord)
    }

    @Test
    fun `bestBefore is the highest e1RM, ties going to the heavier set`() {
        // 120 × 1 and 60 × 30 both estimate 120 kg; the heavier set wins the tie.
        val previous = listOf(row(60.0, 30, 1), row(120.0, 1, 2), row(80.0, 5, 3))
        val result = RecordService.evaluate(candidate(50.0, 5), previous)
        assertEquals(120.0, result.bestBefore?.weightKg)
    }

    // MARK: previousSets

    @Test
    fun `previous sets exclude the set itself and everything later`() {
        val rows = listOf(
            row(100.0, 5, 1_000),                    // earlier
            row(110.0, 5, 3_000),                    // later
        ) + row(120.0, 5, 2_000).copy(setId = 999)   // the candidate itself
        val previous = RecordService.previousSets(rows, candidate(120.0, 5, completedAt = 2_000))
        assertEquals(listOf(100.0), previous.map { it.weightKg })
    }

    @Test
    fun `previous sets drop warm-ups and zero-rep rows`() {
        val rows = listOf(
            row(200.0, 10, 500, kind = SetKind.Warmup),
            row(200.0, 0, 600),
            row(100.0, 5, 700),
        )
        val previous = RecordService.previousSets(rows, candidate(100.0, 6, completedAt = 1_000))
        assertEquals(listOf(100.0), previous.map { it.weightKg })
    }

    @Test
    fun `an exact timestamp tie falls back to the row order in the same workout exercise`() {
        val sameExercise = row(100.0, 5, 1_000, workoutExerciseId = 7, setOrder = 0)
        val laterRowSameExercise = row(140.0, 5, 1_000, workoutExerciseId = 7, setOrder = 9)
        val otherExercise = row(200.0, 5, 1_000, workoutExerciseId = 8, setOrder = 0)
        val previous = RecordService.previousSets(
            listOf(sameExercise, laterRowSameExercise, otherExercise),
            candidate(120.0, 5, completedAt = 1_000, workoutExerciseId = 7, order = 5),
        )
        assertEquals(listOf(100.0), previous.map { it.weightKg })
    }

    @Test
    fun `an uncompleted candidate is compared against everything done so far`() {
        val rows = listOf(row(100.0, 5, 1_000))
        val previous = RecordService.previousSets(
            rows,
            candidate(110.0, 5, completedAt = null),
            now = 5_000,
        )
        assertEquals(1, previous.size)
    }

    // MARK: Lookups

    @Test
    fun `heaviest and most-reps sets break ties as iOS does`() {
        val rows = listOf(
            row(100.0, 5, 1),
            row(100.0, 8, 2),
            row(120.0, 1, 3),
            row(90.0, 12, 4),
        )
        assertEquals(120.0, RecordService.heaviestSet(rows)?.weightKg)
        assertEquals(12, RecordService.mostRepsSet(rows)?.reps)
        assertEquals(100.0, RecordService.bestSet(rows)?.weightKg) // 100 × 8 → e1RM 126.7
    }

    @Test
    fun `lastSet ignores the current workout`() {
        val rows = listOf(
            row(100.0, 5, 1_000, workoutId = "old"),
            row(110.0, 5, 2_000, workoutId = "current"),
        )
        assertEquals(110.0, RecordService.lastSet(rows)?.weightKg)
        assertEquals(100.0, RecordService.lastSet(rows, excludingWorkoutId = "current")?.weightKg)
    }

    @Test
    fun `e1RM history keeps the best per workout, oldest first`() {
        val rows = listOf(
            row(100.0, 5, 1_000, workoutId = "b", workoutStartedAt = 900),
            row(120.0, 5, 1_100, workoutId = "b", workoutStartedAt = 900),
            row(90.0, 5, 100, workoutId = "a", workoutStartedAt = 50),
        )
        val history = RecordService.e1RMHistory(rows)
        assertEquals(listOf("a", "b"), history.map { it.workoutId })
        assertEquals(140.0, history[1].e1RM, 0.01)
    }

    @Test
    fun `lastPRDate is the newest PR timestamp`() {
        val rows = listOf(
            row(100.0, 5, 1_000, isPR = true),
            row(120.0, 5, 5_000, isPR = true),
            row(130.0, 5, 9_000),
        )
        assertEquals(5_000L, RecordService.lastPRDate(rows))
        assertNull(RecordService.lastPRDate(listOf(row(100.0, 5, 1_000))))
    }
}
