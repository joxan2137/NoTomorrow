package app.notomorrow.feature.progress

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.model.SetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** All-time records of a lift (`LiftRecordsTests.swift`): best e1RM, heaviest weight, best volume set. */
class LiftRecordsTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private var setId = 1L

    private fun set(
        kg: Double,
        reps: Int,
        day: Int = 1,
        exerciseId: String = "bench",
        isPR: Boolean = false,
    ): CompletedSetRow {
        val at = LocalDate.of(2026, 9, day).atStartOfDay(zone).plusHours(18).toInstant().toEpochMilli()
        return CompletedSetRow(
            setId = setId++,
            setOrder = 0,
            kind = SetKind.Normal,
            weightKg = kg,
            reps = reps,
            completedAt = at,
            isPR = isPR,
            isSetRecord = false,
            rpe = null,
            workoutExerciseId = 1,
            workoutExerciseOrder = 0,
            exerciseId = exerciseId,
            workoutId = "w$day",
            workoutName = "Push A",
            workoutStartedAt = at,
            workoutEndedAt = at + 3_600_000,
        )
    }

    @Test
    fun `each record picks its own set`() {
        val e1RM = set(120.0, 1, day = 2)
        val volume = set(80.0, 12, day = 3)
        val records = LiftRecords.of(listOf(set(100.0, 5, day = 1), e1RM, volume))!!
        // e1RM: 100 × 5 → 116.7, 120 × 1 → 120, 80 × 12 → 112.
        assertEquals(e1RM, records.bestE1RM)
        assertEquals(120.0, records.bestE1RMKg, 1e-9)
        assertEquals(e1RM, records.heaviest)
        // Volume: 500, 120, 960.
        assertEquals(volume, records.bestVolume)
    }

    @Test
    fun ties() {
        // Same e1RM (60 × 15 and 90 × 1 are both 90): the heavier wins.
        assertEquals(90.0, LiftRecords.of(listOf(set(60.0, 15), set(90.0, 1)))!!.bestE1RM.weightKg, 0.0)
        // Same weight: more reps wins.
        assertEquals(5, LiftRecords.of(listOf(set(100.0, 3), set(100.0, 5), set(100.0, 4)))!!.heaviest.reps)
        // Same volume: the heavier set.
        assertEquals(100.0, LiftRecords.of(listOf(set(50.0, 20), set(100.0, 10)))!!.bestVolume.weightKg, 0.0)
    }

    @Test
    fun `no counting sets, no records`() {
        assertNull(LiftRecords.of(emptyList()))
        assertNull(LiftRecords.of(listOf(set(0.0, 10), set(60.0, 0))))
    }

    @Test
    fun `records match the lift page and list lifts with a PR, most recent first`() {
        val lifts = ProgressDerivations.buildLifts(
            listOf(
                set(100.0, 5, day = 1, exerciseId = "bench", isPR = true),
                set(105.0, 5, day = 8, exerciseId = "bench"),
                set(140.0, 3, day = 5, exerciseId = "squat", isPR = true),
                set(60.0, 8, day = 9, exerciseId = "row"),   // never PR'd
            ),
        ) { it.replaceFirstChar { c -> c.uppercase() } }
        val listed = LiftRecords.withPRs(lifts)
        assertEquals(listOf("squat", "bench"), listed.map { it.first.exerciseId })
        val (bench, benchRecords) = listed.last()
        assertEquals(bench.current, benchRecords.bestE1RMKg, 1e-9)
        assertNotNull(bench.heaviest)
        assertEquals(bench.heaviest, benchRecords.heaviest)
        assertEquals(105.0, benchRecords.bestVolume.weightKg, 0.0)
    }
}
