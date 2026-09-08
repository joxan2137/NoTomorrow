package app.notomorrow.service

import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.model.SetKind

/**
 * Personal records — 1:1 port of `NoTomorrow/Services/RecordService.swift`.
 *
 * A PR is a new best Epley e1RM **or** a new heaviest weight for the exercise; a set
 * record is the most reps ever done at that exact weight. Warm-ups never count, in
 * either direction, and neither do sets with `reps <= 0`.
 *
 * SwiftData walks `exercise.usages.flatMap(\.sets)` lazily; Room cannot, so every lookup
 * runs on the flat [CompletedSetRow] projection
 * (`WorkoutDao.completedSetsForExercise`) — one query, no N+1. The rules themselves live
 * in [Companion] as pure functions so they can be tested without a database.
 */
class RecordService(private val workoutDao: WorkoutDao) {

    /** `RecordService.Evaluation`. */
    data class Evaluation(
        val isPR: Boolean,
        val isSetRecord: Boolean,
        val bestBefore: CompletedSetRow?,
    )

    /** One point of `e1RMHistory`: the best e1RM of a workout, dated at its start. */
    data class E1RMSample(val workoutId: String, val startedAt: Long, val e1RM: Double)

    // MARK: - Evaluation

    /**
     * Compares [set] against every completed working set of the same exercise done
     * before it (earlier sets of the current workout included, later ones excluded).
     * Does not write anything.
     */
    suspend fun evaluate(set: SetEntryEntity, exerciseId: String?): Evaluation {
        if (exerciseId == null || set.kind == SetKind.Warmup || set.reps <= 0) {
            return Evaluation(isPR = false, isSetRecord = false, bestBefore = null)
        }
        val previous = previousSets(workoutDao.completedSetsForExercise(exerciseId), set)
        return evaluate(set, previous)
    }

    /**
     * Evaluates and writes `isPR` / `isSetRecord` onto the row — the tick path of the
     * active workout. Returns the evaluation so the caller can show the "previous best"
     * hint.
     */
    suspend fun mark(set: SetEntryEntity, exerciseId: String?): Evaluation {
        val result = evaluate(set, exerciseId)
        workoutDao.updateSetRecords(set.id, result.isPR, result.isSetRecord)
        return result
    }

    // MARK: - Lookups

    /** Every completed working set of the exercise, across all workouts. */
    suspend fun completedSets(exerciseId: String): List<CompletedSetRow> =
        completedSets(workoutDao.completedSetsForExercise(exerciseId))

    /** Highest-e1RM completed set ever (ties → heavier weight). */
    suspend fun bestSet(exerciseId: String): CompletedSetRow? =
        bestSet(workoutDao.completedSetsForExercise(exerciseId))

    /** Heaviest completed set ever (ties → more reps). */
    suspend fun heaviestSet(exerciseId: String): CompletedSetRow? =
        heaviestSet(workoutDao.completedSetsForExercise(exerciseId))

    /** Completed set with the most reps ever (ties → heavier). */
    suspend fun mostRepsSet(exerciseId: String): CompletedSetRow? =
        mostRepsSet(workoutDao.completedSetsForExercise(exerciseId))

    /**
     * Most recently completed set of the exercise outside [excludingWorkoutId] — the
     * "Last: 80 × 8" line and the set prefill.
     */
    suspend fun lastSet(exerciseId: String, excludingWorkoutId: String? = null): CompletedSetRow? =
        lastSet(workoutDao.completedSetsForExercise(exerciseId), excludingWorkoutId)

    /** Best e1RM per workout, oldest first. Date = workout start. */
    suspend fun e1RMHistory(exerciseId: String): List<E1RMSample> =
        e1RMHistory(workoutDao.completedSetsForExercise(exerciseId))

    /** Timestamp of the most recent PR set, if any. */
    suspend fun lastPRDate(exerciseId: String): Long? =
        lastPRDate(workoutDao.completedSetsForExercise(exerciseId))

    companion object {

        /**
         * Epley estimated one-rep max — `SetEntry.estimatedOneRepMax`. 0 when reps or
         * weight are non-positive, the weight itself for a single.
         */
        fun epley(weightKg: Double, reps: Int): Double = when {
            reps <= 0 || weightKg <= 0 -> 0.0
            reps == 1 -> weightKg
            else -> weightKg * (1 + reps / 30.0)
        }

        /** Working sets only: completed, `kind != warmup`, `reps > 0`. */
        fun completedSets(rows: List<CompletedSetRow>): List<CompletedSetRow> =
            rows.filter { it.kind != SetKind.Warmup && it.reps > 0 }

        /**
         * Completed working sets of the same exercise finished before [set] (the set
         * itself and anything later excluded).
         *
         * On an **exact timestamp tie** — the same workout, batch-ticked — only rows in
         * the same `WorkoutExercise` with a lower `order` count, exactly as iOS.
         */
        fun previousSets(
            rows: List<CompletedSetRow>,
            set: SetEntryEntity,
            now: Long = System.currentTimeMillis(),
        ): List<CompletedSetRow> {
            val cutoff = set.completedAt ?: now
            return completedSets(rows).filter { other ->
                if (other.setId == set.id) return@filter false
                if (other.completedAt != cutoff) return@filter other.completedAt < cutoff
                other.workoutExerciseId == set.workoutExerciseId && other.setOrder < set.order
            }
        }

        /**
         * The rule itself. No previous working set ⇒ the set is the exercise's first
         * record (`isPR = true`, `isSetRecord = false`). Otherwise `isPR` when the e1RM
         * or the weight beats every previous one, and `isSetRecord` **only when it is not
         * a PR** and it beats the rep count of every previous set at exactly that weight.
         */
        fun evaluate(set: SetEntryEntity, previous: List<CompletedSetRow>): Evaluation {
            if (set.kind == SetKind.Warmup || set.reps <= 0) {
                return Evaluation(isPR = false, isSetRecord = false, bestBefore = null)
            }
            val best = bestOf(previous)
            if (previous.isEmpty()) {
                return Evaluation(isPR = true, isSetRecord = false, bestBefore = null)
            }

            val maxE1RM = previous.maxOfOrNull { it.estimatedOneRepMax } ?: 0.0
            val maxWeight = previous.maxOfOrNull { it.weightKg } ?: 0.0
            val e1RM = epley(set.weightKg, set.reps)
            val isPR = e1RM > maxE1RM || set.weightKg > maxWeight

            var isSetRecord = false
            if (!isPR) {
                val maxReps = previous.filter { it.weightKg == set.weightKg }.maxOfOrNull { it.reps }
                if (maxReps != null) isSetRecord = set.reps > maxReps
            }
            return Evaluation(isPR = isPR, isSetRecord = isSetRecord, bestBefore = best)
        }

        /** Highest-e1RM row of an already-filtered list (ties → heavier weight). */
        fun bestSet(rows: List<CompletedSetRow>): CompletedSetRow? = bestOf(completedSets(rows))

        /** Heaviest row (ties → more reps). */
        fun heaviestSet(rows: List<CompletedSetRow>): CompletedSetRow? =
            completedSets(rows).maxWithOrNull(
                compareBy<CompletedSetRow> { it.weightKg }.thenBy { it.reps },
            )

        /** Most reps (ties → heavier). */
        fun mostRepsSet(rows: List<CompletedSetRow>): CompletedSetRow? =
            completedSets(rows).maxWithOrNull(
                compareBy<CompletedSetRow> { it.reps }.thenBy { it.weightKg },
            )

        /** Most recently completed working set, optionally ignoring one workout. */
        fun lastSet(rows: List<CompletedSetRow>, excludingWorkoutId: String? = null): CompletedSetRow? =
            completedSets(rows)
                .filter { excludingWorkoutId == null || it.workoutId != excludingWorkoutId }
                .maxByOrNull { it.completedAt }

        /** Best e1RM per workout, oldest first, dated at the workout start. */
        fun e1RMHistory(rows: List<CompletedSetRow>): List<E1RMSample> {
            val best = LinkedHashMap<String, E1RMSample>()
            for (row in completedSets(rows)) {
                val e1RM = row.estimatedOneRepMax
                val current = best[row.workoutId]
                if (current == null || e1RM > current.e1RM) {
                    best[row.workoutId] = E1RMSample(row.workoutId, row.workoutStartedAt, e1RM)
                }
            }
            return best.values.sortedBy { it.startedAt }
        }

        /** Timestamp of the most recent PR set. */
        fun lastPRDate(rows: List<CompletedSetRow>): Long? =
            completedSets(rows).filter { it.isPR }.maxOfOrNull { it.completedAt }

        /**
         * Every PR / set-record set of a workout, PRs first, each group in row order —
         * the "Records" list of the done screen.
         */
        fun records(workout: WorkoutWithExercises): List<SetEntryEntity> {
            val all = workout.sortedExercises
                .flatMap { it.sortedSets }
                .filter { it.isCompleted && (it.isPR || it.isSetRecord) }
            return all.filter { it.isPR } + all.filter { !it.isPR }
        }

        /** `max { (e1RM, weight) }` over an already-filtered list. */
        private fun bestOf(rows: List<CompletedSetRow>): CompletedSetRow? =
            rows.maxWithOrNull(
                compareBy<CompletedSetRow> { it.estimatedOneRepMax }.thenBy { it.weightKg },
            )
    }
}
