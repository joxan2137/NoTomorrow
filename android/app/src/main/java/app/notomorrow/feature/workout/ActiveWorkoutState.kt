package app.notomorrow.feature.workout

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.model.SetKind
import app.notomorrow.service.RecordService
import app.notomorrow.service.localizedName
import java.util.Locale

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
    /** No workout is running — the screen pops itself. */
    val missing: Boolean = false,
    val workoutId: String = "",
    val name: String = "",
    val startedAt: Long = 0L,
    /** The persisted end. Stays `null` until Done commits the finish, exactly as iOS does. */
    val endedAt: Long? = null,
    /**
     * `private(set) var finishedAt` — the moment Finish was tapped, held in memory while the
     * summary is on screen and written to Room only by `commitFinish()`.
     */
    val finishedAt: Long? = null,
    val exercises: List<WorkoutExerciseUi> = emptyList(),
    val expandedExerciseId: Long? = null,
    val hintSetId: Long? = null,
    val hintBest: SetValue? = null,
    val upNext: UpNextTarget? = null,
    val showsDone: Boolean = false,
) {
    /** `Workout.completedSetCount` — warm-ups included; gates the Discard action. */
    val completedSetCount: Int get() = exercises.sumOf { section -> section.sets.count { it.isCompleted } }

    /** "Exercise i of n". */
    val currentExerciseIndex: Int
        get() = ActiveWorkoutViewModel.currentExerciseIndex(exercises, expandedExerciseId)
}

// MARK: - Derivations

/**
 * The rows of the most recent **finished** earlier workout that used the exercise —
 * `ex.usages.filter { … endedAt != nil }.max(by: startedAt)?.sortedSets.filter(isCompleted)`.
 */
internal fun foldLatestEarlierWorkoutRows(
    rows: List<CompletedSetRow>,
    excludingWorkoutId: String,
): List<CompletedSetRow> =
    rows.filter { it.workoutId != excludingWorkoutId && it.workoutEndedAt != null }
        .groupBy { it.workoutId }
        .maxByOrNull { (_, group) -> group.first().workoutStartedAt }
        ?.value
        ?.sortedBy { it.setOrder }
        .orEmpty()

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
 * One Room section folded into one [WorkoutExerciseUi] — `setNumber(for:in:)` (warm-ups do not
 * count), `currentSetID(in:)` and the per-row `previous(for:in:)` ghost.
 */
internal fun WorkoutExerciseWithSets.toUi(
    locale: Locale,
    previousLast: SetValue?,
    previous: (Int) -> SetValue?,
): WorkoutExerciseUi {
    val ordered = sortedSets
    val currentSetId = ordered.firstOrNull { !it.isCompleted }?.id
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
            previous = previous(index),
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
): NextTarget? {
    section.sets.firstOrNull { it.order > row.order && !it.isCompleted }?.let { next ->
        return NextTarget(makeUpNext(section, next, fallback, recordService), next.kind == SetKind.Drop)
    }
    // Exercise done: rest before the next exercise that still has work.
    val index = all.indexOfFirst { it.id == section.id }
    if (index < 0) return null
    for (other in all.drop(index + 1)) {
        val next = other.sets.firstOrNull { !it.isCompleted } ?: continue
        val lastDone = other.sets.lastOrNull { it.isCompleted }?.let { SetValue(it.weightKg, it.reps) }
        return NextTarget(makeUpNext(other, next, lastDone ?: other.last, recordService), isDrop = false)
    }
    // Nothing left: still rest, aimed at this exercise's numbers.
    return NextTarget(makeUpNext(section, row, fallback, recordService), isDrop = false)
}

/** `makeUpNext(_:in:fallback:)`. */
internal suspend fun makeUpNext(
    section: WorkoutExerciseUi,
    next: SetRowUi,
    fallback: SetValue?,
    recordService: RecordService,
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
    )
}
