package app.notomorrow.feature.progress

import app.notomorrow.util.Fmt
import kotlin.math.max

/**
 * Percentages of a one-rep max (the lift page's "Percentages" and the 1RM calculator) — port of
 * `OneRepMax.swift`: for 100, 95 … 50 % of the e1RM, the weight rounded to what plates make
 * (2.5 kg / 5 lb, `WarmupPlan.increment`) and about how many reps it allows. Everything is Epley,
 * the same model as [Fmt.epley] and the rep-max table ([RepMax]). Weights are in the user's unit,
 * so the rounding lands on real plates.
 */
object OneRepMax {
    val percentages = listOf(100, 95, 90, 85, 80, 75, 70, 65, 60, 50)

    data class Row(
        val percent: Int,
        /** [percent] of the e1RM, rounded to the nearest step. */
        val weight: Double,
        /** The most reps [RepMax.estimate] predicts at this percentage (1 at 100 %). */
        val reps: Int,
    )

    /** Epley, as `SetEntry.estimatedOneRepMax`: the weight itself for a single, 0 for no lift. */
    fun estimate(weight: Double, reps: Int): Double = Fmt.epley(weight, reps)

    /**
     * The largest n with `RepMax.estimate(e1RM, n) >= percent % of e1RM`: n ≤ 30 × (100 − p) / p,
     * at least 1. Integer arithmetic, so 75 % gives exactly 10.
     */
    fun reps(atPercent: Int): Int {
        if (atPercent <= 0 || atPercent >= 100) return 1
        return max(1, 30 * (100 - atPercent) / atPercent)
    }

    /** Nearest multiple of [step] (halves round up, as Swift's `.toNearestOrAwayFromZero`). */
    fun round(value: Double, step: Double): Double {
        if (step <= 0) return value
        return Fmt.roundHalfAwayFromZero(value / step) * step
    }

    /** The table for [e1RM] (in the user's unit); empty without one. */
    fun rows(e1RM: Double, step: Double): List<Row> {
        if (e1RM <= 0) return emptyList()
        return percentages.map { percent ->
            Row(percent = percent, weight = round(e1RM * percent / 100.0, step), reps = reps(percent))
        }
    }
}
