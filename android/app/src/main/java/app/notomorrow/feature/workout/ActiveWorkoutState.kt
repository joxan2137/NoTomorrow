package app.notomorrow.feature.workout

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.RoutineWithItems
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.RecordService
import app.notomorrow.service.localizedName
import java.util.Locale
import kotlin.math.abs

/**
 * The pure half of `ActiveWorkoutModel.swift`: the immutable state the screen renders and the
 * folds/derivations behind it, none of which touch Room or the rest timer.
 *
 * Split out of [ActiveWorkoutViewModel] so the view model is left with the flows and the
 * mutations; every function here is testable without a database.
 */

// MARK: - State

/** Everything `ActiveWorkoutScreen` renders. */
data class ActiveWorkoutUiState(
    val loading: Boolean = true,
    /** The workout's row is gone — the session lets go of it. */
    val missing: Boolean = false,
    val workoutId: String = "",
    val name: String = "",
    val startedAt: Long = 0L,
    /** The persisted end: stamped by Finish (so a kill on the summary cannot stretch it), cleared by "Edit sets". */
    val endedAt: Long? = null,
    val exercises: List<WorkoutExerciseUi> = emptyList(),
    val expandedExerciseId: Long? = null,
    val hintSetId: Long? = null,
    val hintBest: SetValue? = null,
    val upNext: UpNextTarget? = null,
    val showsDone: Boolean = false,
    /** The user's weight unit: cells, Previous, "Last:", the hint and the rest card show it (stored in kg). */
    val unit: WeightUnit = WeightUnit.Kg,
) {
    /** `Workout.completedSetCount` — warm-ups included; gates the Discard action. */
    val completedSetCount: Int get() = exercises.sumOf { section -> section.sets.count { it.isCompleted } }

    /** "Exercise i of n". */
    val currentExerciseIndex: Int
        get() = ActiveWorkoutViewModel.currentExerciseIndex(exercises, expandedExerciseId)

    /** `currentExercise` — the exercise the user is on, for the mini bar. */
    val currentExercise: WorkoutExerciseUi?
        get() = foldCurrentExercise(exercises, expandedExerciseId)
}

// MARK: - Derivations

/**
 * The rows of the last session with an exercise, split the way the table numbers them — `PreviousRows`:
 * warm-ups apart, working sets (not a warm-up, reps > 0) counted 1, 2, 3… So "Previous" lines up
 * with the prefill even when warm-ups were logged.
 */
data class PreviousRows(
    val warmups: List<SetValue> = emptyList(),
    val working: List<SetValue> = emptyList(),
    /** The working sets that are not drop sets, in row order: what the suggested weight reads. */
    val normal: List<SetValue> = emptyList(),
) {
    /** The row at the same position: the k-th warm-up for a warm-up, the k-th working set otherwise. */
    fun value(slot: SetSlot): SetValue? = (if (slot.isWarmup) warmups else working).getOrNull(slot.index)

    companion object {
        /** From that session's completed sets, in row order. */
        fun of(rows: List<CompletedSetRow>): PreviousRows = PreviousRows(
            warmups = rows.filter { it.kind == SetKind.Warmup }.map { SetValue(it.weightKg, it.reps) },
            working = rows.filter { it.kind != SetKind.Warmup && it.reps > 0 }.map { SetValue(it.weightKg, it.reps) },
            normal = rows.filter { it.kind != SetKind.Warmup && it.kind != SetKind.Drop && it.reps > 0 }
                .map { SetValue(it.weightKg, it.reps) },
        )
    }
}

/** Where a set sits in its table — `Slot`: the k-th warm-up, or the k-th set that is not one (0-based). */
data class SetSlot(val isWarmup: Boolean, val index: Int)

/** `slot(of:in:)` for every row of an exercise, in row order: warm-ups and the other sets are counted apart. */
internal fun slots(kinds: List<SetKind>): List<SetSlot> {
    var warmups = 0
    var others = 0
    return kinds.map { kind ->
        if (kind == SetKind.Warmup) SetSlot(true, warmups++) else SetSlot(false, others++)
    }
}

/**
 * The rows of the most recent **finished** other workout whose entry of the exercise has a completed
 * working set — `ex.usages.filter { finished, has a working set }.max(by: startedAt)?.sortedSets.filter(isCompleted)`.
 * Warm-ups stay in (Previous matches them against warm-ups). One entry, not one workout: a workout
 * that did the exercise twice gives its later entry, as the new-workout prefill does.
 */
internal fun foldLatestEarlierWorkoutRows(
    rows: List<CompletedSetRow>,
    excludingWorkoutId: String,
): List<CompletedSetRow> =
    rows.filter { it.workoutId != excludingWorkoutId && it.workoutEndedAt != null }
        .groupBy { it.workoutExerciseId }
        .values
        .filter { group -> group.any { it.kind != SetKind.Warmup && it.reps > 0 } }
        .maxWithOrNull(
            compareBy<List<CompletedSetRow>> { it.first().workoutStartedAt }.thenBy { it.first().workoutExerciseOrder },
        )
        ?.sortedBy { it.setOrder }
        .orEmpty()

/**
 * `previous(for:in:)` — the same-position set of the last session ([PreviousRows.value]), else,
 * for a working set, the most recent completed set ([last]); a warm-up past the end gets none.
 */
internal fun previousValue(rows: PreviousRows?, last: SetValue?, slot: SetSlot): SetValue? =
    rows?.value(slot) ?: if (slot.isWarmup) null else last

/**
 * What a tick logs — `complete`'s guard: an empty row takes Previous; a row that still has no
 * reps logs nothing (no "0 × 0" sets). `null` = refuse the tick.
 */
internal fun valuesToLog(weightKg: Double, reps: Int, previous: SetValue?): SetValue? {
    val values = if (weightKg == 0.0 && reps == 0 && previous != null) previous else SetValue(weightKg, reps)
    return values.takeIf { it.reps > 0 }
}

/**
 * `prefillFromPrevious` — an open row's empty cells filled from Previous (the table shows what a
 * tick will log). `null` when nothing changes; completed rows are never touched.
 */
internal fun prefilledValues(weightKg: Double, reps: Int, isCompleted: Boolean, previous: SetValue?): SetValue? {
    if (isCompleted || previous == null || (weightKg != 0.0 && reps != 0)) return null
    val weight = if (weightKg == 0.0 && previous.weightKg > 0) previous.weightKg else weightKg
    val count = if (reps == 0 && previous.reps > 0) previous.reps else reps
    return SetValue(weight, count).takeIf { weight != weightKg || count != reps }
}

// MARK: - Suggested weight

/**
 * `ActiveWorkoutModel.Suggestion` — progressive overload for one exercise: the weight to try today
 * and the session it builds on (weights in kg).
 */
data class WeightSuggestion(
    /** The previous session's top weight (every one of its sets was at it). */
    val fromKg: Double,
    val toKg: Double,
    /** That session's reps, in row order ("8 · 8 · 8"). */
    val reps: List<Int>,
)

/** Weights closer than this (1 g) are one weight: a number typed in lb comes back from kg with a rounding tail. */
private const val SAME_WEIGHT = 0.001

/**
 * `ActiveWorkoutModel.suggestion(previous:targetReps:unit:)` — progressive overload from the
 * previous session's sets of an exercise (completed, not warm-ups, not drop sets, [PreviousRows.normal]):
 * the top weight plus one step (2.5 kg, or 5 lb for lb users) when there are at least two such
 * sets, all at one weight above zero, and every one reached [targetReps] (the routine's target,
 * when there is one) or, without a target, none has fewer reps than the first. Otherwise `null`.
 */
internal fun weightSuggestion(previous: List<SetValue>, targetReps: Int?, unit: WeightUnit): WeightSuggestion? {
    val first = previous.firstOrNull() ?: return null
    if (previous.size < 2 || first.weightKg <= 0) return null
    if (previous.any { abs(it.weightKg - first.weightKg) >= SAME_WEIGHT }) return null
    val minReps = targetReps?.takeIf { it > 0 } ?: first.reps
    if (previous.any { it.reps < minReps }) return null
    val step = if (unit == WeightUnit.Kg) 2.5 else SetInput.kg(5.0, WeightUnit.Lb)
    return WeightSuggestion(fromKg = first.weightKg, toKg = first.weightKg + step, reps = previous.map { it.reps })
}

/**
 * `ActiveWorkoutModel.showsSuggestion(_:openWeights:)` — the suggestion stays up while an open set
 * (not a warm-up, not a drop set) still has the previous top weight; "Use" moves them all off it, so
 * the line goes. [openWeights] are those sets' weights in kg.
 */
internal fun showsSuggestion(suggestion: WeightSuggestion, openWeights: List<Double>): Boolean =
    openWeights.any { abs(it - suggestion.fromKg) < SAME_WEIGHT }

/**
 * `routineTargetReps()` — target reps per exercise id of the routine named like the workout (the
 * name is the only link a workout keeps, as for the suggested routine); the first item wins when a
 * routine lists an exercise twice. Empty for an ad-hoc workout. [routines] come in `order`.
 */
internal fun routineTargetReps(routines: List<RoutineWithItems>, workoutName: String): Map<String, Int> {
    val routine = routines.firstOrNull { it.routine.name == workoutName } ?: return emptyMap()
    val targets = mutableMapOf<String, Int>()
    for (row in routine.sortedItems) {
        val exerciseId = row.exercise?.id ?: continue
        if (row.item.targetReps > 0 && exerciseId !in targets) targets[exerciseId] = row.item.targetReps
    }
    return targets
}

/** The sets "Use" writes to and the suggestion watches: open, not a warm-up, not a drop set. */
internal fun SetRowUi.takesSuggestion(): Boolean = !isCompleted && kind != SetKind.Warmup && kind != SetKind.Drop

/**
 * `currentExerciseIndex` — 1-based position of the open exercise, else of the first exercise
 * that still has work.
 */
internal fun foldCurrentExerciseIndex(exercises: List<WorkoutExerciseUi>, expandedId: Long?): Int {
    if (expandedId != null) {
        val index = exercises.indexOfFirst { it.id == expandedId }
        if (index >= 0) return index + 1
    }
    val firstOpen = exercises.indexOfFirst { !it.isDone }
    return minOf(exercises.size, (if (firstOpen >= 0) firstOpen else 0) + 1)
}

/**
 * `ActiveWorkoutModel.currentExercise(in:expandedID:)` — the open exercise, else the first with
 * work left, else the last.
 */
internal fun foldCurrentExercise(exercises: List<WorkoutExerciseUi>, expandedId: Long?): WorkoutExerciseUi? {
    if (expandedId != null) exercises.firstOrNull { it.id == expandedId }?.let { return it }
    return exercises.firstOrNull { !it.isDone } ?: exercises.lastOrNull()
}

/**
 * One Room section folded into one [WorkoutExerciseUi] — `setNumber(for:in:)` (warm-ups do not
 * count), `currentSetID(in:)`, the per-row `previous(for:in:)` ghost by [SetSlot] and
 * `suggestion(for:)` ([suggestion] is the one the previous session allows; it is kept only while
 * an open set still has the previous top weight).
 */
internal fun WorkoutExerciseWithSets.toUi(
    locale: Locale,
    previousLast: SetValue?,
    previous: (SetSlot) -> SetValue?,
    suggestion: WeightSuggestion? = null,
): WorkoutExerciseUi {
    val ordered = sortedSets
    val currentSetId = ordered.firstOrNull { !it.isCompleted }?.id
    val positions = slots(ordered.map { it.kind })
    var number = 0
    val rows = ordered.mapIndexed { index, set ->
        if (set.kind != SetKind.Warmup) number += 1
        SetRowUi(
            id = set.id,
            order = set.order,
            kind = set.kind,
            weightKg = set.weightKg,
            reps = set.reps,
            isCompleted = set.isCompleted,
            number = number,
            previous = previous(positions[index]),
            isCurrent = set.id == currentSetId,
        )
    }
    return WorkoutExerciseUi(
        id = workoutExercise.id,
        exerciseId = workoutExercise.exerciseId,
        name = exercise?.localizedName(locale).orEmpty(),
        primaryMuscle = exercise?.primaryMuscles?.firstOrNull(),
        restSeconds = workoutExercise.restSeconds,
        setCount = sets.size,
        isDone = isDone,
        last = previousLast,
        sets = rows,
        suggestion = suggestion?.takeIf { s ->
            showsSuggestion(s, rows.filter { it.takesSuggestion() }.map { it.weightKg })
        },
    )
}

// MARK: - Next target

/** `ActiveWorkoutModel.NextTarget` — what to rest towards, and whether it is a drop set. */
internal data class NextTarget(val upNext: UpNextTarget, val isDrop: Boolean)

/**
 * `nextTarget(after:in:)` — the next uncompleted set of this exercise, else the first
 * uncompleted set of a later exercise, else this exercise's own numbers.
 */
internal suspend fun nextTarget(
    all: List<WorkoutExerciseUi>,
    section: WorkoutExerciseUi,
    row: SetRowUi,
    fallback: SetValue,
    recordService: RecordService,
    unit: WeightUnit = WeightUnit.Kg,
): NextTarget? {
    section.sets.firstOrNull { it.order > row.order && !it.isCompleted }?.let { next ->
        return NextTarget(makeUpNext(section, next, fallback, recordService, unit), next.kind == SetKind.Drop)
    }
    // Exercise done: rest before the next exercise that still has work.
    val index = all.indexOfFirst { it.id == section.id }
    if (index < 0) return null
    for (other in all.drop(index + 1)) {
        val next = other.sets.firstOrNull { !it.isCompleted } ?: continue
        val lastDone = other.sets.lastOrNull { it.isCompleted }?.let { SetValue(it.weightKg, it.reps) }
        return NextTarget(makeUpNext(other, next, lastDone ?: other.last, recordService, unit), isDrop = false)
    }
    // Nothing left: still rest, aimed at this exercise's numbers.
    return NextTarget(makeUpNext(section, row, fallback, recordService, unit), isDrop = false)
}

/** `makeUpNext(_:in:fallback:)`. */
internal suspend fun makeUpNext(
    section: WorkoutExerciseUi,
    next: SetRowUi,
    fallback: SetValue?,
    recordService: RecordService,
    unit: WeightUnit = WeightUnit.Kg,
): UpNextTarget {
    val index = section.sets.indexOfFirst { it.id == next.id } + 1
    val weight = if (next.weightKg > 0) next.weightKg else fallback?.weightKg ?: next.previous?.weightKg ?: 0.0
    val reps = if (next.reps > 0) next.reps else fallback?.reps ?: next.previous?.reps ?: 0
    val best = section.exerciseId?.let { recordService.bestSet(it) }
    return UpNextTarget(
        exerciseName = section.name,
        setIndex = index,
        setCount = section.sets.size,
        weightKg = weight,
        reps = reps,
        bestKg = best?.weightKg,
        bestReps = best?.reps,
        unit = unit,
    )
}
