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

    // MARK: - Rebuild

    /**
     * Re-derives the flags of every set of these exercises (all workouts) with [rebuildFlags] and
     * writes the ones that changed; an open set still carrying a flag is cleared. Run after a
     * finished workout is edited or deleted, and on Finish. Returns the rows changed.
     *
     * One query per exercise on the flat completed-set projection, like every other lookup here.
     */
    suspend fun rebuild(exerciseIds: Set<String>): Int {
        if (exerciseIds.isEmpty()) return 0
        var changed = 0
        for (exerciseId in exerciseIds) {
            val sets = workoutDao.completedSetsForExercise(exerciseId)
            val flags = rebuildFlags(
                sets.map { set ->
                    RecordRow(
                        id = set.setId,
                        group = set.workoutExerciseId,
                        order = set.setOrder,
                        completedAt = set.completedAt,
                        kind = set.kind,
                        weightKg = set.weightKg,
                        reps = set.reps,
                    )
                },
            )
            for (set in sets) {
                val f = flags[set.setId] ?: continue
                if (set.isPR == f.isPR && set.isSetRecord == f.isSetRecord) continue
                workoutDao.updateSetRecords(set.setId, f.isPR, f.isSetRecord)
                changed += 1
            }
        }
        changed += workoutDao.clearOpenSetRecords(exerciseIds.toList())
        return changed
    }

    /** One set of one exercise, as the records rule sees it — `RecordService.RecordRow`. */
    data class RecordRow(
        val id: Long,
        /** The `WorkoutExercise` it belongs to: sets ticked at the same instant are ordered by row only inside it. */
        val group: Long,
        val order: Int,
        val completedAt: Long?,
        val kind: SetKind,
        val weightKg: Double,
        val reps: Int,
    ) {
        val estimatedOneRepMax: Double get() = epley(weightKg, reps)
    }

    data class RecordFlags(val isPR: Boolean, val isSetRecord: Boolean)

    companion object {

        /**
         * Flags for every row of ONE exercise, exactly as [evaluate] would have set them had each
         * completed working set been ticked in `completedAt` order — the same tie rule as
         * [previousSets]: equal timestamps only see earlier rows of the same `WorkoutExercise`.
         * Open sets, warm-ups and 0-rep sets get no flag.
         */
        fun rebuildFlags(rows: List<RecordRow>): Map<Long, RecordFlags> {
            val result = HashMap<Long, RecordFlags>()
            for (row in rows) result[row.id] = RecordFlags(isPR = false, isSetRecord = false)

            val byInstant = rows
                .filter { it.completedAt != null && it.kind != SetKind.Warmup && it.reps > 0 }
                .groupBy { it.completedAt!! }
            var seen = 0
            var maxE1RM = 0.0
            var maxWeight = 0.0
            val repsAtWeight = HashMap<Double, Int>()

            for (instant in byInstant.keys.sorted()) {
                val tie = byInstant.getValue(instant)
                for (row in tie) {
                    val local = tie.filter { it.group == row.group && it.order < row.order }
                    // The first logged working set of an exercise is its first record.
                    if (seen + local.size == 0) {
                        result[row.id] = RecordFlags(isPR = true, isSetRecord = false)
                        continue
                    }
                    val priorE1RM = maxOf(maxE1RM, local.maxOfOrNull { it.estimatedOneRepMax } ?: 0.0)
                    val priorWeight = maxOf(maxWeight, local.maxOfOrNull { it.weightKg } ?: 0.0)
                    val isPR = row.estimatedOneRepMax > priorE1RM || row.weightKg > priorWeight
                    var isSetRecord = false
                    if (!isPR) {
                        val localReps = local.filter { it.weightKg == row.weightKg }.maxOfOrNull { it.reps }
                        val best = listOfNotNull(repsAtWeight[row.weightKg], localReps).maxOrNull()
                        if (best != null) isSetRecord = row.reps > best
                    }
                    result[row.id] = RecordFlags(isPR = isPR, isSetRecord = isSetRecord)
                }
                for (row in tie) {
                    seen += 1
                    maxE1RM = maxOf(maxE1RM, row.estimatedOneRepMax)
                    maxWeight = maxOf(maxWeight, row.weightKg)
                    repsAtWeight[row.weightKg] = maxOf(repsAtWeight[row.weightKg] ?: 0, row.reps)
                }
            }
            return result
        }

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

        /**
         * Most recently completed working set, optionally ignoring one workout. Sets sharing one
         * `completedAt` (batch-ticked, or added in the workout editor, which gives a new row its
         * neighbour's time) resolve by position ([COMPLETED_EARLIER]): the set done last, not
         * whichever the query returned first.
         */
        fun lastSet(rows: List<CompletedSetRow>, excludingWorkoutId: String? = null): CompletedSetRow? =
            completedSets(rows)
                .filter { excludingWorkoutId == null || it.workoutId != excludingWorkoutId }
                .maxWithOrNull(COMPLETED_EARLIER)

        /**
         * Completion order — `RecordService.completedEarlier`: by `completedAt`; sets done at the
         * same instant by their exercise's position in the workout, then by row. So the last of
         * them is the bottom row, the way the records rule orders ties.
         */
        val COMPLETED_EARLIER: Comparator<CompletedSetRow> = compareBy(
            { it.completedAt },
            { it.workoutExerciseOrder },
            { it.setOrder },
        )

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
