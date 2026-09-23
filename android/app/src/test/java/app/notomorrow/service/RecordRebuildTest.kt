package app.notomorrow.service

import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.model.SetKind
import app.notomorrow.service.RecordService.RecordFlags
import app.notomorrow.service.RecordService.RecordRow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `RecordService.rebuildFlags` / `rebuild` — the chronological re-derivation an edit, a delete or
 * Finish runs (`WorkoutEditTests.swift`, records section): it must give every set exactly what the
 * tick-time rule would have, ties included.
 */
class RecordRebuildTest {

    private fun row(
        id: Long,
        group: Long = 0,
        order: Int = 0,
        atMinute: Long?,
        kind: SetKind = SetKind.Normal,
        kg: Double,
        reps: Int,
    ) = RecordRow(
        id = id,
        group = group,
        order = order,
        completedAt = atMinute?.let { it * 60_000 },
        kind = kind,
        weightKg = kg,
        reps = reps,
    )

    @Test
    fun `a chain is rebuilt like the tick-time rule, and follows an edit or a delete`() {
        // 80×5, 85×5, 82.5×7 (e1RM 101.75 > 99.17): each one is a PR when ticked in order.
        val rows = listOf(
            row(1, group = 1, atMinute = 1, kg = 80.0, reps = 5),
            row(2, group = 2, atMinute = 2, kg = 85.0, reps = 5),
            row(3, group = 3, atMinute = 3, kg = 82.5, reps = 7),
        )
        assertEquals(listOf(true, true, true), listOf(1L, 2L, 3L).map { RecordService.rebuildFlags(rows)[it]?.isPR })

        // The first one edited to 90×5: it stays the first record, the later two no longer beat it.
        val edited = listOf(rows[0].copy(weightKg = 90.0)) + rows.drop(1)
        assertEquals(listOf(true, false, false), listOf(1L, 2L, 3L).map { RecordService.rebuildFlags(edited)[it]?.isPR })

        // Deleting the first: 85×5 becomes the first record, 82.5×7 still beats it by e1RM.
        val deleted = RecordService.rebuildFlags(rows.drop(1))
        assertEquals(listOf(true, true), listOf(2L, 3L).map { deleted[it]?.isPR })
    }

    @Test
    fun `a set record follows the best reps at that weight`() {
        val rows = listOf(
            row(1, group = 1, atMinute = 1, kg = 100.0, reps = 5),
            row(2, group = 2, atMinute = 2, kg = 60.0, reps = 10),
            row(3, group = 3, atMinute = 3, kg = 60.0, reps = 12),
        )
        assertEquals(RecordFlags(isPR = false, isSetRecord = true), RecordService.rebuildFlags(rows)[3], "12 > 10 at 60 kg")

        val edited = rows.map { if (it.id == 2L) it.copy(reps = 12) else it }
        assertEquals(RecordFlags(isPR = false, isSetRecord = false), RecordService.rebuildFlags(edited)[3], "12 no longer beats 12")
    }

    @Test
    fun `ties only see earlier rows of the same exercise entry`() {
        var result = RecordService.rebuildFlags(
            listOf(
                row(1, group = 1, order = 0, atMinute = 5, kg = 80.0, reps = 5),
                row(2, group = 1, order = 1, atMinute = 5, kg = 80.0, reps = 5),
            ),
        )
        assertEquals(true, result[1]?.isPR)
        assertEquals(RecordFlags(isPR = false, isSetRecord = false), result[2])

        // Same instant in different entries: neither sees the other, both are first records.
        result = RecordService.rebuildFlags(
            listOf(
                row(1, group = 1, order = 0, atMinute = 5, kg = 80.0, reps = 5),
                row(2, group = 2, order = 1, atMinute = 5, kg = 80.0, reps = 5),
            ),
        )
        assertEquals(true, result[1]?.isPR)
        assertEquals(true, result[2]?.isPR)
    }

    @Test
    fun `warm-ups, empty and open sets are never flagged nor a bar to beat`() {
        val result = RecordService.rebuildFlags(
            listOf(
                row(1, atMinute = 1, kind = SetKind.Warmup, kg = 200.0, reps = 5),
                row(2, order = 1, atMinute = 2, kg = 100.0, reps = 0),
                row(3, order = 2, atMinute = null, kg = 300.0, reps = 5),
                row(4, order = 3, atMinute = 3, kg = 60.0, reps = 5),
            ),
        )
        val none = RecordFlags(isPR = false, isSetRecord = false)
        assertEquals(none, result[1])
        assertEquals(none, result[2])
        assertEquals(none, result[3])
        assertEquals(true, result[4]?.isPR)
    }

    @Test
    fun `rebuild writes only what changed and repairs stale warm-up and open flags`() = runBlocking {
        val dao = FakeWorkoutDao()
        dao.insertWorkout(WorkoutEntity(id = "w", name = "Push A", startedAt = 0, endedAt = 3_600_000))
        val entry = dao.insertWorkoutExercise(WorkoutExerciseEntity(workoutId = "w", exerciseId = BENCH, order = 0))
        dao.insertSet(SetEntryEntity(workoutExerciseId = entry, order = 0, kind = SetKind.Warmup, weightKg = 60.0, reps = 5, completedAt = 60_000, isPR = true))
        dao.insertSet(SetEntryEntity(workoutExerciseId = entry, order = 1, weightKg = 80.0, reps = 5, completedAt = 120_000, isPR = true))
        dao.insertSet(SetEntryEntity(workoutExerciseId = entry, order = 2, weightKg = 90.0, reps = 5, isPR = true))

        val changed = RecordService(dao).rebuild(setOf(BENCH))

        assertEquals(2, changed, "the warm-up and the open set; the real PR is left alone")
        assertEquals(listOf(false, true, false), dao.sets.value.sortedBy { it.order }.map { it.isPR })
        assertEquals(0, RecordService(dao).rebuild(emptySet()))
        assertTrue(dao.sets.value.none { it.isSetRecord })
    }

    private companion object {
        const val BENCH = "Barbell_Bench_Press_-_Medium_Grip"
    }
}
