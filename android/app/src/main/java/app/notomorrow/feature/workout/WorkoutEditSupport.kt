package app.notomorrow.feature.workout

import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.model.SetKind
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The pure half of the workout editor — `WorkoutEditSupport.swift`: the draft a finished workout is
 * edited in, every edit on it, and the set timeline a save writes back. None of it touches Room.
 */

// MARK: - Draft

/**
 * Unsaved copy of a finished workout for the editor — `WorkoutDraft`. Plain values only: nothing
 * reaches Room before Save, so Cancel is free.
 *
 * Ids of rows added in the editor are negative (handed out by the caller), so they never collide
 * with a saved row's id and `isDirty = draft != original` stays a plain comparison.
 */
data class WorkoutDraft(
    val name: String,
    val notes: String,
    /** Epoch millis. */
    val startedAt: Long,
    /** Milliseconds. Kept exact until the user steps it, so an untouched duration never moves a set's time. */
    val durationMs: Long,
    val exercises: List<ExerciseDraft>,
) {
    val endedAt: Long get() = startedAt + durationMs

    /** Exercise ids in the draft (the picker shows them as "In"). */
    val exerciseIds: Set<String> get() = exercises.map { it.exerciseId }.toSet()

    /** Save needs a start in the past and an end no later than a minute from now. */
    fun isTimeValid(now: Long): Boolean = startedAt <= now && endedAt <= now + FUTURE_GRACE_MS

    val canShorten: Boolean get() = durationMs > DURATION_MIN_MINUTES * MINUTE_MS
    val canLengthen: Boolean get() = durationMs < DURATION_MAX_MINUTES * MINUTE_MS

    // MARK: Editing

    /** Moves the start to [day], keeping the time of day (wall clock). */
    fun withDay(day: LocalDate, zone: ZoneId): WorkoutDraft {
        val start = Instant.ofEpochMilli(startedAt).atZone(zone)
        val delta = ChronoUnit.DAYS.between(start.toLocalDate(), day)
        if (delta == 0L) return this
        return copy(startedAt = start.plusDays(delta).toInstant().toEpochMilli())
    }

    /**
     * Moves the start to [hour]:[minute] on the same day (wall clock, so a DST night doesn't shift
     * it). Seconds stay, so picking the old time again gives back the exact start.
     */
    fun withTime(hour: Int, minute: Int, zone: ZoneId): WorkoutDraft {
        val start = Instant.ofEpochMilli(startedAt).atZone(zone)
        if (start.hour == hour && start.minute == minute) return this
        return copy(startedAt = start.withHour(hour).withMinute(minute).toInstant().toEpochMilli())
    }

    /** − / + on the duration: snaps to the 5-minute grid (52 min → 50 or 55), within 5 min … 12 h. */
    fun steppingDuration(direction: Int): WorkoutDraft {
        val step = DURATION_STEP_MINUTES.toDouble()
        val minutes = Fmt.roundHalfAwayFromZero(durationMs / MINUTE_MS.toDouble() * 100) / 100
        val snapped = if (direction > 0) (floor(minutes / step) + 1) * step else (ceil(minutes / step) - 1) * step
        val clamped = snapped.coerceIn(DURATION_MIN_MINUTES.toDouble(), DURATION_MAX_MINUTES.toDouble())
        return copy(durationMs = clamped.toLong() * MINUTE_MS)
    }

    /** Runs [change] on one set. */
    fun updatingSet(setId: Long, exerciseId: Long, change: (SetDraft) -> SetDraft): WorkoutDraft =
        copy(
            exercises = exercises.map { exercise ->
                if (exercise.id != exerciseId) {
                    exercise
                } else {
                    exercise.copy(sets = exercise.sets.map { if (it.id == setId) change(it) else it })
                }
            },
        )

    /**
     * Ticks or unticks a row. A tick that would not count can't be made (no new "0 × 0" sets):
     * `null` means it was refused. An untouched legacy 0-rep row unticked by mistake can be ticked
     * back ([SetDraft.isUntouchedCompleted]).
     */
    fun togglingDone(setId: Long, exerciseId: Long): WorkoutDraft? {
        val set = exercises.firstOrNull { it.id == exerciseId }?.sets?.firstOrNull { it.id == setId } ?: return this
        if (set.isLogged) return updatingSet(setId, exerciseId) { it.copy(isDone = false) }
        val ticked = set.copy(isDone = true)
        return if (ticked.isLogged) updatingSet(setId, exerciseId) { ticked } else null
    }

    /**
     * "+ Add set": copies the last row (a warm-up becomes a normal set). Added rows start ticked:
     * editing a past workout is logging after the fact. An empty one counts once it has reps.
     */
    fun addingSet(exerciseId: Long, newId: Long): WorkoutDraft = copy(
        exercises = exercises.map { exercise ->
            if (exercise.id != exerciseId) return@map exercise
            val last = exercise.sets.lastOrNull()
            val kind = last?.kind?.let { if (it == SetKind.Warmup) SetKind.Normal else it } ?: SetKind.Normal
            exercise.copy(
                sets = exercise.sets + SetDraft(
                    id = newId,
                    sourceId = null,
                    kind = kind,
                    weightKg = last?.weightKg ?: 0.0,
                    reps = last?.reps ?: 0,
                    isDone = true,
                    originalCompletedAt = null,
                ),
            )
        },
    )

    fun deletingSet(setId: Long, exerciseId: Long): WorkoutDraft = copy(
        exercises = exercises.map { exercise ->
            if (exercise.id != exerciseId) exercise else exercise.copy(sets = exercise.sets.filterNot { it.id == setId })
        },
    )

    fun removingExercise(exerciseId: Long): WorkoutDraft = copy(exercises = exercises.filterNot { it.id == exerciseId })

    /** Move up (-1) / down (+1) among the exercises. */
    fun movingExercise(exerciseId: Long, offset: Int): WorkoutDraft {
        val from = exercises.indexOfFirst { it.id == exerciseId }
        val to = from + offset
        if (from < 0 || to !in exercises.indices) return this
        val list = exercises.toMutableList()
        list[from] = exercises[to]
        list[to] = exercises[from]
        return copy(exercises = list)
    }

    /** An exercise added from the picker: one ticked row, prefilled from the last time it was done. */
    fun appendingExercise(
        id: Long,
        setId: Long,
        exerciseId: String,
        name: String,
        primaryMuscle: String?,
        restSeconds: Int,
        template: SetValue?,
    ): WorkoutDraft {
        if (exerciseId in exerciseIds) return this
        val set = SetDraft(
            id = setId,
            sourceId = null,
            kind = SetKind.Normal,
            weightKg = template?.weightKg ?: 0.0,
            reps = template?.reps ?: 0,
            isDone = true,
            originalCompletedAt = null,
        )
        return copy(
            exercises = exercises + ExerciseDraft(
                id = id,
                sourceId = null,
                exerciseId = exerciseId,
                name = name,
                primaryMuscle = primaryMuscle,
                restSeconds = restSeconds,
                sets = listOf(set),
            ),
        )
    }

    /** The lowest id in the draft, so the caller can hand out new (negative) ones below it. */
    val lowestId: Long
        get() = (exercises.map { it.id } + exercises.flatMap { ex -> ex.sets.map { it.id } }).minOrNull() ?: 0L

    companion object {
        /** Duration steps: 5 minutes, from 5 minutes to 12 hours. */
        const val DURATION_STEP_MINUTES = 5
        const val DURATION_MIN_MINUTES = 5
        const val DURATION_MAX_MINUTES = 720

        const val MINUTE_MS = 60_000L

        /** An end up to a minute ahead of now still saves (the clock moved while editing). */
        const val FUTURE_GRACE_MS = 60_000L
    }
}

/** `ExerciseDraft`. [id] is the `workout_exercise` row id, or negative for one added in the editor. */
data class ExerciseDraft(
    val id: Long,
    /** The `workout_exercise` row this came from; `null` when it was added in the editor. */
    val sourceId: Long?,
    val exerciseId: String,
    val name: String,
    val primaryMuscle: String?,
    val restSeconds: Int,
    val sets: List<SetDraft>,
)

/** `SetDraft`. [id] is the `set_entry` row id, or negative for one added in the editor. */
data class SetDraft(
    val id: Long,
    /** The `set_entry` row this came from; `null` when it was added in the editor. */
    val sourceId: Long?,
    val kind: SetKind,
    val weightKg: Double,
    val reps: Int,
    /**
     * Ticked. It only counts (✓, saved as completed) while it has reps: clearing the reps to retype
     * them does not lose the tick, and nothing is ever logged as "0 × 0".
     */
    val isDone: Boolean,
    /** `completedAt` as saved. Save remaps it when the start or the duration changes. */
    val originalCompletedAt: Long?,
    /** The row as saved, for a row that came from the workout; `null` for one added in the editor. */
    val saved: Saved? = null,
) {
    /** The values a row had when the editor opened — `SetDraft.Saved`. */
    data class Saved(val kind: SetKind, val weightKg: Double, val reps: Int, val isDone: Boolean)

    /**
     * Counts as completed: ticked with reps, or a row completed before the no-"0 × 0" rule (reps 0)
     * that the user has left exactly as it was. Saving any other edit (a rename) must not un-log it,
     * which could also take the day's attendance with it.
     */
    val isLogged: Boolean get() = isDone && (reps > 0 || isUntouchedCompleted)

    /** Completed when the editor opened and not changed since. */
    val isUntouchedCompleted: Boolean
        get() {
            val saved = saved ?: return false
            if (!saved.isDone || originalCompletedAt == null) return false
            return saved == Saved(kind, weightKg, reps, isDone)
        }
}

/** `WorkoutDraft(workout:)` — built from the sorted exercises and sets. */
fun WorkoutWithExercises.toDraft(locale: Locale): WorkoutDraft = WorkoutDraft(
    name = workout.name,
    notes = workout.notes,
    startedAt = workout.startedAt,
    durationMs = max(0L, (workout.endedAt ?: workout.startedAt) - workout.startedAt),
    exercises = sortedExercises.map { entry ->
        ExerciseDraft(
            id = entry.workoutExercise.id,
            sourceId = entry.workoutExercise.id,
            exerciseId = entry.workoutExercise.exerciseId.orEmpty(),
            name = entry.exercise?.localizedName(locale).orEmpty(),
            primaryMuscle = entry.exercise?.primaryMuscles?.firstOrNull(),
            restSeconds = entry.workoutExercise.restSeconds,
            sets = entry.sortedSets.map { set ->
                SetDraft(
                    id = set.id,
                    sourceId = set.id,
                    kind = set.kind,
                    weightKg = set.weightKg,
                    reps = set.reps,
                    isDone = set.isCompleted,
                    originalCompletedAt = set.completedAt,
                    saved = SetDraft.Saved(set.kind, set.weightKg, set.reps, set.isCompleted),
                )
            },
        )
    },
)

/**
 * One draft exercise as the set table renders it (`WorkoutEditExerciseSection`): numbered like the
 * active table (warm-ups don't count), no Previous, no current row, ✓ = [SetDraft.isLogged].
 */
fun ExerciseDraft.toSectionUi(): WorkoutExerciseUi {
    var number = 0
    val rows = sets.mapIndexed { index, set ->
        if (set.kind != SetKind.Warmup) number += 1
        SetRowUi(
            id = set.id,
            order = index,
            kind = set.kind,
            weightKg = set.weightKg,
            reps = set.reps,
            isCompleted = set.isLogged,
            number = number,
            previous = null,
            isCurrent = false,
        )
    }
    return WorkoutExerciseUi(
        id = id,
        exerciseId = exerciseId,
        name = name,
        primaryMuscle = primaryMuscle,
        restSeconds = restSeconds,
        setCount = sets.size,
        isDone = false,
        last = null,
        sets = rows,
    )
}

// MARK: - Timeline

/**
 * Where each done set lands in time after an edit — `WorkoutTimeline`. The records rule orders sets
 * by `completedAt`, so the save keeps the timeline consistent: existing times follow the workout's
 * new start and duration, new ones borrow a neighbour's.
 */
object WorkoutTimeline {

    /**
     * Order-preserving map of [t] from `[oldStart, oldEnd]` onto `[newStart, newEnd]`, clamped into
     * the new range. Untouched times come back exactly and a pure shift moves every set by the same
     * amount, so ties stay ties. Integer millis throughout; [t] is clamped into the old range before
     * scaling (the map is monotonic, so the result is the same) and the product never overflows.
     */
    fun remap(t: Long, oldStart: Long, oldEnd: Long, newStart: Long, newEnd: Long): Long {
        if (newStart == oldStart && newEnd == oldEnd) return t
        val oldSpan = oldEnd - oldStart
        val newSpan = newEnd - newStart
        val mapped = if (newSpan == oldSpan || oldSpan <= 0) {
            t + (newStart - oldStart)
        } else {
            newStart + (t.coerceIn(oldStart, oldEnd) - oldStart) * newSpan / oldSpan
        }
        return mapped.coerceIn(newStart, max(newStart, newEnd))
    }

    /**
     * `completedAt` for every logged row of the draft, by row id. A row that had a time gets it
     * remapped. A row without one (added, or ticked in the editor) takes the nearest earlier timed
     * row of its exercise, else the nearest later one (ties then resolve by row order, as the
     * records rule does); an exercise with no timed row takes the latest time of the exercises
     * above it, else the start.
     */
    fun completedTimes(draft: WorkoutDraft, oldStart: Long, oldEnd: Long): Map<Long, Long> {
        val newStart = draft.startedAt
        val newEnd = draft.endedAt
        val times = LinkedHashMap<Long, Long>()
        var latestAbove: Long? = null
        for (exercise in draft.exercises) {
            val anchors: List<Long?> = exercise.sets.map { set ->
                val original = set.originalCompletedAt
                if (!set.isLogged || original == null) null else remap(original, oldStart, oldEnd, newStart, newEnd)
            }
            exercise.sets.forEachIndexed { index, set ->
                if (!set.isLogged) return@forEachIndexed
                val time = anchors[index]
                    ?: anchors.subList(0, index).lastOrNull { it != null }
                    ?: anchors.subList(index + 1, anchors.size).firstOrNull { it != null }
                    ?: latestAbove
                    ?: newStart
                times[set.id] = time
                latestAbove = max(latestAbove ?: time, time)
            }
        }
        return times
    }
}
