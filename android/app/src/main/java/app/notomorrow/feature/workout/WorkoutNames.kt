package app.notomorrow.feature.workout

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.notomorrow.util.S

/**
 * An empty workout stores `workout.defaultName` in the language of the moment it started, so one
 * started before switching language would keep showing "Workout" in Polish. Every language's
 * default name shows as the current one; any other name is the user's and stays as typed.
 * Mirrors `WorkoutStrings.displayName` on iOS.
 */
object WorkoutNames {
    /** `workout.defaultName` in every bundled language. */
    val DEFAULTS = setOf("Workout", "Trening")

    fun display(name: String, defaultName: String): String = if (name in DEFAULTS) defaultName else name
}

@Composable
fun workoutDisplayName(name: String): String = WorkoutNames.display(name, stringResource(S.workout_defaultName))
