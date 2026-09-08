package app.notomorrow.feature.progress

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.model.SetKind
import app.notomorrow.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The three pure passes `ProgressModel.reload` makes over the store
 * (`ProgressModel.swift:122-190`), lifted out of the view models so they can be
 * unit-tested without Room, Compose or resources.
 *
 * SwiftData walks `set.workoutExercise?.workout`; Room hands the same graph over
 * flat in [CompletedSetRow], so every pass reads one list.
 */
object ProgressDerivations {

    /**
     * Every exercise with at least one working set, sorted by last PR (newest first,
     * PR-less lifts after them, ties broken by the last session).
     *
     * @param name resolves an exercise id to its localized name; `null` drops the sets,
     *   the analogue of Swift's `guard let exercise = set.workoutExercise?.exercise`.
     */
    fun buildLifts(sets: List<CompletedSetRow>, name: (String) -> String?): List<LiftSummary> {
        val byExercise = LinkedHashMap<String, MutableList<CompletedSetRow>>()
        for (set in sets) {
            if (set.kind == SetKind.Warmup || set.reps <= 0 || set.weightKg <= 0) continue
            val exerciseId = set.exerciseId ?: continue
            byExercise.getOrPut(exerciseId) { mutableListOf() }.add(set)
        }

        val summaries = byExercise.mapNotNull { (exerciseId, exerciseSets) ->
            val exerciseName = name(exerciseId) ?: return@mapNotNull null

            // Best e1RM per workout, plus the newest workout's name for the row subtitle.
            val best = LinkedHashMap<String, WorkoutBest>()
            var latestWorkout: Pair<Long, String>? = null
            for (set in exerciseSets) {
                val current = best.getOrPut(set.workoutId) { WorkoutBest(set.workoutStartedAt, 0.0, false) }
                if (set.estimatedOneRepMax > current.e1RM) current.e1RM = set.estimatedOneRepMax
                if (set.isPR) current.isPR = true
                if (latestWorkout == null || set.workoutStartedAt > latestWorkout.first) {
                    latestWorkout = set.workoutStartedAt to set.workoutName
                }
            }

            val history = best
                .map { (workoutId, value) ->
                    E1RMPoint(
                        workoutId = workoutId,
                        date = Instant.ofEpochMilli(value.startedAt),
                        e1RM = value.e1RM,
                        isPR = value.isPR,
                    )
                }
                .sortedBy { it.date }
            val last = history.lastOrNull() ?: return@mapNotNull null

            LiftSummary(
                exerciseId = exerciseId,
                name = exerciseName,
                context = latestWorkout?.second,
                lastPR = exerciseSets.filter { it.isPR }.maxOfOrNull { it.completedAt }
                    ?.let(Instant::ofEpochMilli),
                lastSession = last.date,
                history = history,
                sets = exerciseSets,
            )
        }

        return summaries.sortedWith(LIFT_ORDER)
    }

    /**
     * The last eight ISO weeks of total volume, oldest first. Only finished workouts
     * count, and warm-ups never do — `Workout.totalVolumeKg`.
     */
    fun buildWeekly(
        sets: List<CompletedSetRow>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<WeekVolume> {
        val thisWeek = Fmt.startOfIsoWeek(today)
        val totals = HashMap<LocalDate, Double>()
        for (set in sets) {
            if (set.workoutEndedAt == null || set.kind == SetKind.Warmup) continue
            val day = Instant.ofEpochMilli(set.workoutStartedAt).atZone(zone).toLocalDate()
            val week = Fmt.startOfIsoWeek(day)
            totals[week] = (totals[week] ?: 0.0) + set.weightKg * set.reps
        }
        return (0 until WEEKS).reversed().map { offset ->
            val week = thisWeek.minusWeeks(offset.toLong())
            WeekVolume(
                weekStart = week,
                volumeKg = totals[week] ?: 0.0,
                isCurrent = offset == 0,
            )
        }
    }

    /** 4-week delta, days logged and the 7-day trailing average (`ProgressModel.swift:171`). */
    fun buildBody(entries: List<BodyEntry>, today: LocalDate): BodyStats {
        val latest = entries.lastOrNull() ?: return BodyStats(entries = entries)
        val fourWeeksAgo = today.minusDays(28)
        val baseline = entries.lastOrNull { !it.day.isAfter(fourWeeksAgo) } ?: entries.first()
        val smoothed = entries.indices.map { index ->
            val windowStart = entries[index].day.minusDays(6)
            val window = entries.subList(0, index + 1).filter { !it.day.isBefore(windowStart) }
            window.sumOf { it.kg } / window.size
        }
        return BodyStats(
            entries = entries,
            delta4w = if (baseline.day != latest.day) latest.kg - baseline.kg else null,
            loggedLast28 = entries.count { it.day.isAfter(fourWeeksAgo) },
            smoothed = smoothed,
        )
    }

    /** Volume of every lift in the current ISO week — the weekly section's headline number. */
    fun thisWeekVolume(weekly: List<WeekVolume>): Double = weekly.lastOrNull()?.volumeKg ?: 0.0

    /** `(thisWeek - lastWeek) / lastWeek`, or `null` when last week was empty. */
    fun weekOverWeek(weekly: List<WeekVolume>): Double? {
        val lastWeek = weekly.dropLast(1).lastOrNull()?.volumeKg ?: 0.0
        if (lastWeek <= 0.0) return null
        return (thisWeekVolume(weekly) - lastWeek) / lastWeek
    }

    /** `weekly` covers the last eight ISO weeks, exactly as iOS does. */
    const val WEEKS: Int = 8

    private class WorkoutBest(val startedAt: Long, var e1RM: Double, var isPR: Boolean)

    /**
     * `ProgressModel.swift:157` — newest PR first; a lift that has ever PR'd outranks one
     * that has not; everything else falls back to the most recent session.
     */
    private val LIFT_ORDER = Comparator<LiftSummary> { lhs, rhs ->
        val l = lhs.lastPR
        val r = rhs.lastPR
        when {
            l != null && r != null && l != r -> r.compareTo(l)
            l != null && r == null -> -1
            l == null && r != null -> 1
            else -> rhs.lastSession.compareTo(lhs.lastSession)
        }
    }
}
