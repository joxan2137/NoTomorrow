package app.notomorrow.feature.progress

import app.notomorrow.data.relation.CompletedSetRow

/**
 * The rep-max table on a lift's progress page — 1:1 port of `RepMax.swift`: for 1, 3, 5, 8, 10
 * and 12 reps, the heaviest weight actually lifted for at least that many reps, and what the best
 * e1RM (Epley) predicts for that many.
 */
object RepMax {
    val repCounts = listOf(1, 3, 5, 8, 10, 12)

    data class Row(
        val reps: Int,
        /** Heaviest set with at least [reps] reps (the most reps on a tie), `null` when none. */
        val best: CompletedSetRow?,
        /** Epley inverted: e1RM / (1 + reps / 30); the e1RM itself for a single. */
        val estimatedKg: Double,
    )

    fun estimate(e1RM: Double, reps: Int): Double {
        if (e1RM <= 0 || reps <= 0) return 0.0
        return if (reps == 1) e1RM else e1RM / (1 + reps / 30.0)
    }

    fun rows(sets: List<CompletedSetRow>, e1RM: Double): List<Row> = repCounts.map { reps ->
        val best = sets.filter { it.reps >= reps && it.weightKg > 0 }
            .maxWithOrNull(compareBy<CompletedSetRow> { it.weightKg }.thenBy { it.reps })
        Row(reps = reps, best = best, estimatedKg = estimate(e1RM, reps))
    }
}
