package app.notomorrow.feature.workout

import java.util.Locale
import java.util.UUID

/**
 * One exercise line of a routine being edited: target sets × reps and its rest (0 = the user's
 * default) — `RoutineItemDraft` in `NoTomorrow/Features/Workout/RoutineDraft.swift`.
 *
 * Build it with [of], which clamps sets and reps into range as the Swift initialiser does; the
 * primary constructor is what `copy` uses once a value is known to be in range.
 */
data class RoutineItemDraft(
    val id: String,
    val exerciseId: String,
    val name: String,
    val primaryMuscle: String?,
    val sets: Int,
    val reps: Int,
    val restSeconds: Int,
) {
    companion object {
        fun of(
            exerciseId: String,
            name: String,
            primaryMuscle: String? = null,
            sets: Int = RoutineDraft.DEFAULT_SETS,
            reps: Int = RoutineDraft.DEFAULT_REPS,
            restSeconds: Int = RoutineDraft.INHERIT_REST,
            id: String = UUID.randomUUID().toString(),
        ) = RoutineItemDraft(
            id = id,
            exerciseId = exerciseId,
            name = name,
            primaryMuscle = primaryMuscle,
            sets = RoutineDraft.clampSets(sets),
            reps = RoutineDraft.clampReps(reps),
            restSeconds = restSeconds,
        )
    }
}

/**
 * A routine being created or edited: its name and exercise lines — 1:1 port of
 * `NoTomorrow/Features/Workout/RoutineDraft.swift`. Plain values, so the editor can be cancelled
 * without touching the store; [RoutineStore] writes it. Every edit returns a new draft.
 */
data class RoutineDraft(
    val name: String = "",
    val items: List<RoutineItemDraft> = emptyList(),
) {
    val trimmedName: String get() = name.trim()

    val exerciseIds: Set<String> get() = items.mapTo(mutableSetOf()) { it.exerciseId }

    /**
     * A name and at least one exercise; a name another routine already uses is refused so Today's
     * "up next" (which matches workouts to routines by name) stays unambiguous.
     */
    fun canSave(otherNames: List<String>): Boolean =
        trimmedName.isNotEmpty() && items.isNotEmpty() && !isNameTaken(otherNames)

    fun isNameTaken(otherNames: List<String>): Boolean {
        val mine = trimmedName.lowercase(Locale.ROOT)
        return mine.isNotEmpty() && otherNames.any { it.trim().lowercase(Locale.ROOT) == mine }
    }

    // MARK: - Edits

    /** Adds [item] at the end, unless its exercise is already in the routine. */
    fun appending(item: RoutineItemDraft): RoutineDraft =
        if (items.any { it.exerciseId == item.exerciseId }) this else copy(items = items + item)

    fun removing(id: String): RoutineDraft = copy(items = items.filterNot { it.id == id })

    /** Swaps the line with its neighbour [offset] away; a move past either end does nothing. */
    fun moving(id: String, offset: Int): RoutineDraft {
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = from + offset
        if (to !in items.indices) return this
        val swapped = items.toMutableList()
        swapped[from] = items[to]
        swapped[to] = items[from]
        return copy(items = swapped)
    }

    fun steppingSets(id: String, delta: Int): RoutineDraft = updating(id) { it.copy(sets = clampSets(it.sets + delta)) }

    fun steppingReps(id: String, delta: Int): RoutineDraft = updating(id) { it.copy(reps = clampReps(it.reps + delta)) }

    fun settingRest(id: String, seconds: Int): RoutineDraft = updating(id) { it.copy(restSeconds = maxOf(0, seconds)) }

    private inline fun updating(id: String, change: (RoutineItemDraft) -> RoutineItemDraft): RoutineDraft {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return this
        return copy(items = items.toMutableList().also { it[index] = change(it[index]) })
    }

    /** One logged exercise of a workout, reduced to what a routine keeps. */
    data class LoggedExercise(
        val exerciseId: String,
        val name: String,
        val primaryMuscle: String?,
        /** Completed sets that are not warm-ups, in row order. */
        val workingReps: List<Int>,
        val restSeconds: Int,
        /** The rest equals what a routine line with the default rest would give this exercise. */
        val usesDefaultRest: Boolean,
    )

    companion object {
        const val DEFAULT_SETS = 3
        const val DEFAULT_REPS = 8
        val SET_RANGE = 1..10
        val REP_RANGE = 1..50

        /** `RoutineItem.restSeconds` 0 = use the user's Rest length setting when the workout starts. */
        const val INHERIT_REST = 0

        /** The rest menu: the default first, then 30 s to 5 min. */
        val REST_OPTIONS = listOf(INHERIT_REST, 30, 45, 60, 75, 90, 120, 150, 180, 240, 300)

        fun clampSets(n: Int): Int = n.coerceIn(SET_RANGE)

        fun clampReps(n: Int): Int = n.coerceIn(REP_RANGE)

        /** [base], or `base 2`, `base 3`… : the first that no name in [taken] uses (case-insensitive). */
        fun uniqueName(base: String, taken: List<String>): String {
            val used = taken.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
            val trimmed = base.trim()
            if (trimmed.lowercase(Locale.ROOT) !in used) return trimmed
            var n = 2
            while ("$trimmed $n".lowercase(Locale.ROOT) in used) n += 1
            return "$trimmed $n"
        }

        /**
         * "Save as routine": one line per exercise that has a completed working set, sets = how
         * many were done, reps = the first working set's reps. The rest the workout used is kept
         * unless it is the user's default. An exercise logged twice keeps its first line.
         */
        fun from(workoutName: String, exercises: List<LoggedExercise>, takenNames: List<String>): RoutineDraft {
            val lines = exercises.mapNotNull { logged ->
                val firstReps = logged.workingReps.firstOrNull { it > 0 } ?: return@mapNotNull null
                RoutineItemDraft.of(
                    exerciseId = logged.exerciseId,
                    name = logged.name,
                    primaryMuscle = logged.primaryMuscle,
                    sets = logged.workingReps.size,
                    reps = firstReps,
                    restSeconds = if (logged.usesDefaultRest) INHERIT_REST else logged.restSeconds,
                )
            }
            return RoutineDraft(
                name = uniqueName(workoutName, takenNames),
                items = lines.distinctBy { it.exerciseId },
            )
        }
    }
}
