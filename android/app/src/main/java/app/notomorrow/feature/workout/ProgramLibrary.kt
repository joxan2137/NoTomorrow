package app.notomorrow.feature.workout

import android.content.Context
import androidx.annotation.StringRes
import app.notomorrow.util.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A built-in training program ("Browse programs" on the Train tab) — `TrainingProgram` in
 * `NoTomorrow/Features/Workout/ProgramLibrary.swift`. Decoded from `data/programs/programs.json`,
 * the one file both apps read (an asset source dir here, see `app/build.gradle.kts`).
 *
 * [name], [summary] and each routine's name are iOS catalog keys; [ProgramKeys] turns them into
 * resource ids.
 */
@Serializable
data class TrainingProgram(
    val id: String,
    val name: String,
    val summary: String,
    val level: String,
    val daysPerWeek: Int,
    val routines: List<Day>,
) {
    /** One routine of the program. */
    @Serializable
    data class Day(val id: String, val name: String, val items: List<Line>)

    /** One exercise of a routine: a library id and its targets ([rest] in seconds). */
    @Serializable
    data class Line(val exercise: String, val sets: Int, val reps: Int, val rest: Int)

    /** Every library id the program uses. */
    val exerciseIds: Set<String> get() = routines.flatMapTo(mutableSetOf()) { day -> day.items.map { it.exercise } }

    /** Every catalog key the program shows: its name, description and routine names. */
    val localizationKeys: List<String> get() = listOf(name, summary) + routines.map { it.name }

    /** `Level.titleKey` — `"program.level." + level`. */
    val levelKey: String get() = "program.level.$level"
}

/** The bundled programs and the routine drafts "Add N routines" writes ([RoutineStore.addProgram]). */
object ProgramLibrary {

    const val ASSET = "programs.json"

    @Serializable
    private data class File(val version: Int, val programs: List<TrainingProgram>)

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): List<TrainingProgram> = json.decodeFromString<File>(text).programs

    @Volatile
    private var cached: List<TrainingProgram>? = null

    /** The bundled programs, read off the main thread once per process; empty when unreadable. */
    suspend fun load(context: Context): List<TrainingProgram> = cached ?: withContext(Dispatchers.IO) {
        runCatching {
            decode(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        }.getOrElse { emptyList() }.also { cached = it }
    }

    /** What a routine line needs from the library exercise. */
    data class ExerciseInfo(val name: String, val primaryMuscle: String?)

    /**
     * One draft per program routine, in the program's order. Names are localized with [localize]
     * and made unique against [taken] and against each other ([RoutineDraft.uniqueName], so a second
     * add gives "Full Body A 2"). Lines whose exercise [exercise] does not know are left out.
     */
    fun drafts(
        program: TrainingProgram,
        taken: List<String>,
        exercise: (String) -> ExerciseInfo?,
        localize: (String) -> String,
    ): List<RoutineDraft> {
        val used = taken.toMutableList()
        return program.routines.map { day ->
            val name = RoutineDraft.uniqueName(localize(day.name), used)
            used += name
            RoutineDraft(
                name = name,
                items = day.items.mapNotNull { line ->
                    val info = exercise(line.exercise) ?: return@mapNotNull null
                    RoutineItemDraft.of(
                        exerciseId = line.exercise,
                        name = info.name,
                        primaryMuscle = info.primaryMuscle,
                        sets = line.sets,
                        reps = line.reps,
                        restSeconds = line.rest,
                    )
                },
            )
        }
    }
}

/**
 * The catalog keys `programs.json` names, as resource ids — like `NtKeys`, an exhaustive map rather
 * than `Resources.getIdentifier`, so R8 sees every string. `null` for a key it does not know (a
 * unit test keeps it in step with the JSON).
 */
object ProgramKeys {

    @StringRes
    fun text(key: String): Int? = when (key) {
        "program.level.beginner" -> S.program_level_beginner
        "program.level.intermediate" -> S.program_level_intermediate

        "program.fullBody.name" -> S.program_fullBody_name
        "program.fullBody.summary" -> S.program_fullBody_summary
        "program.fullBody.a" -> S.program_fullBody_a
        "program.fullBody.b" -> S.program_fullBody_b
        "program.fullBody.c" -> S.program_fullBody_c

        "program.fiveByFive.name" -> S.program_fiveByFive_name
        "program.fiveByFive.summary" -> S.program_fiveByFive_summary
        "program.fiveByFive.a" -> S.program_fiveByFive_a
        "program.fiveByFive.b" -> S.program_fiveByFive_b

        "program.ppl.name" -> S.program_ppl_name
        "program.ppl.summary" -> S.program_ppl_summary
        "program.ppl.push" -> S.program_ppl_push
        "program.ppl.pull" -> S.program_ppl_pull
        "program.ppl.legs" -> S.program_ppl_legs

        "program.upperLower.name" -> S.program_upperLower_name
        "program.upperLower.summary" -> S.program_upperLower_summary
        "program.upperLower.upperA" -> S.program_upperLower_upperA
        "program.upperLower.lowerA" -> S.program_upperLower_lowerA
        "program.upperLower.upperB" -> S.program_upperLower_upperB
        "program.upperLower.lowerB" -> S.program_upperLower_lowerB

        "program.fiveThreeOne.name" -> S.program_fiveThreeOne_name
        "program.fiveThreeOne.summary" -> S.program_fiveThreeOne_summary
        "program.fiveThreeOne.press" -> S.program_fiveThreeOne_press
        "program.fiveThreeOne.deadlift" -> S.program_fiveThreeOne_deadlift
        "program.fiveThreeOne.bench" -> S.program_fiveThreeOne_bench
        "program.fiveThreeOne.squat" -> S.program_fiveThreeOne_squat

        "program.dumbbellHome.name" -> S.program_dumbbellHome_name
        "program.dumbbellHome.summary" -> S.program_dumbbellHome_summary
        "program.dumbbellHome.a" -> S.program_dumbbellHome_a
        "program.dumbbellHome.b" -> S.program_dumbbellHome_b
        else -> null
    }
}
