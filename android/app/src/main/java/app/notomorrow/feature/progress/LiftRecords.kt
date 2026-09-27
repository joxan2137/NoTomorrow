package app.notomorrow.feature.progress

import androidx.compose.runtime.Immutable
import app.notomorrow.data.relation.CompletedSetRow

/**
 * A lift's all-time records — port of `LiftRecords.swift`, read off the same working sets
 * [LiftSummary] holds (so the numbers match the lift's progress page): the best Epley e1RM
 * (`RecordService.bestSet`'s rule), the heaviest set ([LiftSummary.heaviest]) and the set with
 * the most weight × reps.
 */
@Immutable
data class LiftRecords(
    /** The set behind the best e1RM; its e1RM is [bestE1RMKg], the lift's `current`. */
    val bestE1RM: CompletedSetRow,
    val bestE1RMKg: Double,
    val heaviest: CompletedSetRow,
    val bestVolume: CompletedSetRow,
) {
    companion object {
        /** `null` without a set that counts (weight and reps above zero). */
        fun of(sets: List<CompletedSetRow>): LiftRecords? {
            val counted = sets.filter { it.weightKg > 0 && it.reps > 0 }
            val e1RM = bestE1RM(counted) ?: return null
            return LiftRecords(
                bestE1RM = e1RM,
                bestE1RMKg = e1RM.estimatedOneRepMax,
                heaviest = heaviest(counted) ?: return null,
                bestVolume = bestVolume(counted) ?: return null,
            )
        }

        /** Highest e1RM (ties → heavier). */
        fun bestE1RM(sets: List<CompletedSetRow>): CompletedSetRow? =
            sets.maxWithOrNull(compareBy({ it.estimatedOneRepMax }, { it.weightKg }))

        /** Heaviest weight (ties → more reps). */
        fun heaviest(sets: List<CompletedSetRow>): CompletedSetRow? =
            sets.maxWithOrNull(compareBy({ it.weightKg }, { it.reps }))

        /** Most weight × reps (ties → heavier). */
        fun bestVolume(sets: List<CompletedSetRow>): CompletedSetRow? =
            sets.maxWithOrNull(compareBy({ it.weightKg * it.reps }, { it.weightKg }))

        /**
         * The lifts with a PR, in the order the Records screen lists them: most recent PR first,
         * the order [ProgressDerivations.buildLifts] already sorts them in.
         */
        fun withPRs(lifts: List<LiftSummary>): List<Pair<LiftSummary, LiftRecords>> =
            lifts.mapNotNull { lift ->
                if (lift.lastPR == null) return@mapNotNull null
                of(lift.sets)?.let { lift to it }
            }
    }
}
