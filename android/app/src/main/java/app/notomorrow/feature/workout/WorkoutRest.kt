package app.notomorrow.feature.workout

/**
 * `WorkoutRest` — the active workout's per-exercise rest menu (Hevy / Strong style). A workout
 * exercise always stores a real length; "Default" is the length this exercise gets from the
 * user's Rest length setting (heavy compounds a little longer), which is also what "Save as
 * routine" turns back into a routine's inherited rest.
 */
object WorkoutRest {
    data class Option(val seconds: Int, val isDefault: Boolean)

    /** Lengths offered after Default: the routine editor's 30 s to 5 min. */
    val lengths: List<Int> = RoutineDraft.REST_OPTIONS.filter { it > 0 }

    /**
     * Default first, then the fixed lengths. A current length that is neither (an imported
     * workout, a routine saved with an odd value) is listed in order too, so the check always has
     * a row.
     */
    fun options(current: Int, defaultSeconds: Int): List<Option> {
        val seconds = if (current > 0 && current != defaultSeconds && current !in lengths) {
            (lengths + current).sorted()
        } else {
            lengths
        }
        return listOf(Option(defaultSeconds, isDefault = true)) + seconds.map { Option(it, isDefault = false) }
    }

    /** One check: Default when the length is the default, else the matching length. */
    fun isChecked(option: Option, current: Int, defaultSeconds: Int): Boolean =
        if (option.isDefault) current == defaultSeconds else current != defaultSeconds && current == option.seconds
}
