package app.notomorrow.feature.workout

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.designsystem.appLocale
import app.notomorrow.util.Localizer
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import java.util.Locale

/**
 * Copy helpers for the Train tab — 1:1 port of
 * `NoTomorrow/Features/Workout/WorkoutStrings.swift`.
 *
 * Two halves survive the port:
 *
 *  * the **counted nouns** (`WorkoutStrings.counted`), whose Polish CLDR selector already
 *    lives in [NtKeys.pluralCategory] because the catalog carries `one` / `few` / `many` as
 *    three plain strings rather than as a `<plurals>`;
 *  * the **muscle / equipment vocabulary**, whose runtime-built keys
 *    (`"muscleName.\(slug)"`) are the exhaustive `when` maps in [NtKeys].
 *
 * `WorkoutStrings.fold` is **not** here: it is `ExerciseLibrary.fold`, next to the
 * `sorted`/`filter` pair that uses it.
 *
 * Every function comes in two flavours: a pure one taking a [Localizer] (view models,
 * services, tests) and a `@Composable` one reading `stringResource` (views).
 */
object WorkoutStrings {

    // MARK: - Plurals

    /** "5 exercises" / "5 ćwiczeń" */
    fun exercises(n: Int, strings: Localizer): String = strings.string(NtKeys.exerciseCount(n), n)

    /** "3 sets" / "3 serie" */
    fun sets(n: Int, strings: Localizer): String = strings.string(NtKeys.setCount(n), n)

    /** "2 PRs" — the dashboard key; Polish keeps "PR" invariant. */
    fun prs(n: Int, strings: Localizer): String = strings.string(S.dashboard_prs, n)

    /** "7 results" */
    fun results(n: Int, strings: Localizer): String = strings.string(S.exercises_results, n)

    /** "Add 3" */
    fun add(n: Int, strings: Localizer): String = strings.string(S.exercises_addCount, n)

    /** Create "&lt;query&gt;" as a new exercise */
    fun create(query: String, strings: Localizer): String = strings.string(S.exercises_create, query)

    // MARK: - Muscles & equipment

    /**
     * Localized muscle name ("chest" → "Chest" / "Klatka"). An unknown value falls back to
     * the capitalized raw string, exactly as the iOS `value == key` check does.
     */
    fun muscle(raw: String, strings: Localizer, locale: Locale): String =
        NtKeys.muscle(raw)?.let(strings::string) ?: capitalized(raw, locale)

    fun equipment(raw: String, strings: Localizer, locale: Locale): String =
        NtKeys.equipment(raw)?.let(strings::string) ?: capitalized(raw, locale)

    /**
     * "Chest · Triceps, Shoulders" — primary muscles, then the first two secondaries;
     * the equipment stands in when there are no secondaries.
     */
    fun subtitle(exercise: ExerciseEntity, strings: Localizer, locale: Locale): String {
        val primary = exercise.primaryMuscles.joinToString(", ") { muscle(it, strings, locale) }
        val secondary = exercise.secondaryMuscles.take(2).joinToString(", ") { muscle(it, strings, locale) }
        val parts = buildList {
            if (primary.isNotEmpty()) add(primary)
            if (secondary.isNotEmpty()) {
                add(secondary)
            } else {
                val equipment = exercise.equipment
                if (!equipment.isNullOrEmpty()) add(equipment(equipment, strings, locale))
            }
        }
        return parts.joinToString(" · ")
    }

    /** `String.capitalized(with: .current)` — first letter only, under the app locale. */
    private fun capitalized(raw: String, locale: Locale): String =
        raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

// MARK: - Composable flavours

/** [WorkoutStrings.exercises] inside composition. */
@Composable
fun workoutExerciseCount(n: Int): String = stringResource(NtKeys.exerciseCount(n), n)

/** [WorkoutStrings.sets] inside composition. */
@Composable
fun workoutSetCount(n: Int): String = stringResource(NtKeys.setCount(n), n)

/** [WorkoutStrings.muscle] inside composition. */
@Composable
fun workoutMuscleName(raw: String): String {
    val locale = appLocale()
    val id = NtKeys.muscle(raw)
    return if (id != null) {
        stringResource(id)
    } else {
        raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
