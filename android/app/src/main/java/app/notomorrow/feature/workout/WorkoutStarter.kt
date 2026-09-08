package app.notomorrow.feature.workout

import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.di.AppContainer
import app.notomorrow.model.SetKind
import app.notomorrow.service.RoutineSeeder
import java.util.UUID
import kotlin.math.max

/**
 * Builds `Workout` graphs (exercises + set rows) from a routine or from nothing, and hands them to
 * the session — 1:1 port of `NoTomorrow/Features/Workout/WorkoutStarter.swift`.
 *
 * SwiftData writes the graph through the object relationships; Room needs the parent row first, so
 * every insert goes through [WorkoutDao.insertWorkoutExerciseWithSets], which is `@Transaction`.
 *
 * The prefill rule is the interesting part and lives in [templateSets] as a pure function: rows are
 * seeded from the **last** completed working sets of that exercise, and once the template runs out
 * every remaining row repeats its last entry (`source = template[index] ?? template.last`).
 */
object WorkoutStarter {

    /**
     * `start(routine:in:session:)` — one `WorkoutExercise` per routine item, `targetSets` rows each,
     * prefilled from the last time the exercise was done. Returns the new workout id, or `null` when
     * the routine is gone.
     */
    suspend fun start(
        container: AppContainer,
        routineId: String,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val routine = container.db.routineDao().routineWithItems(routineId) ?: return null
        val workoutDao = container.db.workoutDao()
        val exerciseDao = container.db.exerciseDao()

        val workout = WorkoutEntity(
            id = UUID.randomUUID().toString(),
            name = routine.routine.name,
            startedAt = now,
        )
        workoutDao.insertWorkout(workout)

        var order = 0
        for (entry in routine.sortedItems) {
            // `guard let exercise = item.exercise` — a routine item whose exercise was deleted is skipped.
            val exerciseId = entry.exercise?.id ?: continue
            append(
                workoutDao = workoutDao,
                exerciseDao = exerciseDao,
                workoutId = workout.id,
                exerciseId = exerciseId,
                order = order,
                setCount = max(1, entry.item.targetSets),
                restSeconds = entry.item.restSeconds,
                now = now,
            )
            order += 1
        }

        container.workoutSession.begin(workout.id)
        return workout.id
    }

    /**
     * `startEmpty(in:session:)`. [name] is `workout.defaultName` resolved by the caller — view
     * models never touch resources.
     */
    suspend fun startEmpty(
        container: AppContainer,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): String {
        val workout = WorkoutEntity(id = UUID.randomUUID().toString(), name = name, startedAt = now)
        container.db.workoutDao().insertWorkout(workout)
        container.workoutSession.begin(workout.id)
        return workout.id
    }

    /** [append] against the container — the form the exercise picker and the active screen use. */
    suspend fun append(
        container: AppContainer,
        workoutId: String,
        exerciseId: String,
        order: Int,
        setCount: Int = DEFAULT_SET_COUNT,
        restSeconds: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): Long = append(
        workoutDao = container.db.workoutDao(),
        exerciseDao = container.db.exerciseDao(),
        workoutId = workoutId,
        exerciseId = exerciseId,
        order = order,
        setCount = setCount,
        restSeconds = restSeconds,
        now = now,
    )

    /**
     * `append(_:to:order:setCount:restSeconds:in:)` — appends one exercise with prefilled rows to an
     * existing workout and stamps `lastUsedAt`. Returns the new `workout_exercise` row id.
     */
    suspend fun append(
        workoutDao: WorkoutDao,
        exerciseDao: ExerciseDao,
        workoutId: String,
        exerciseId: String,
        order: Int,
        setCount: Int = DEFAULT_SET_COUNT,
        restSeconds: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val rest = restSeconds ?: RoutineSeeder.restSeconds(exerciseId)
        val template = lastCompletedSets(workoutDao, exerciseId, excludingWorkoutId = workoutId)
        val id = workoutDao.insertWorkoutExerciseWithSets(
            WorkoutExerciseEntity(
                workoutId = workoutId,
                exerciseId = exerciseId,
                order = order,
                restSeconds = rest,
            ),
            prefilledSets(setCount, template),
        )
        exerciseDao.markUsed(exerciseId, now)
        return id
    }

    /**
     * `lastCompletedSets(for:excluding:)` — the completed working sets of the most recent **other**
     * workout that contains the exercise, in row order.
     */
    suspend fun lastCompletedSets(
        workoutDao: WorkoutDao,
        exerciseId: String,
        excludingWorkoutId: String? = null,
    ): List<CompletedSetRow> =
        templateSets(workoutDao.completedSetsForExercise(exerciseId), excludingWorkoutId)

    // MARK: - Pure derivations

    /**
     * The prefill template: working sets (completed, not a warm-up, `reps > 0`) of the latest usage
     * of the exercise outside [excludingWorkoutId], sorted by row order.
     *
     * Ties on `startedAt` — the same workout holding the exercise twice — resolve to the later
     * `WorkoutExercise`. Swift's `Sequence.max(by:)` only replaces on a strict increase, so it keeps
     * the **first** maximum of the unordered `exercise.usages` set, i.e. an arbitrary one of the two;
     * `.thenBy { workoutExerciseOrder }` is a deliberate stabilisation of that, not a match.
     */
    fun templateSets(
        rows: List<CompletedSetRow>,
        excludingWorkoutId: String? = null,
    ): List<CompletedSetRow> {
        val working = rows.filter { row ->
            row.kind != SetKind.Warmup &&
                row.reps > 0 &&
                (excludingWorkoutId == null || row.workoutId != excludingWorkoutId)
        }
        val latest = working.maxWithOrNull(
            compareBy<CompletedSetRow> { it.workoutStartedAt }.thenBy { it.workoutExerciseOrder },
        ) ?: return emptyList()
        return working
            .filter { it.workoutExerciseId == latest.workoutExerciseId }
            .sortedBy { it.setOrder }
    }

    /**
     * `for index in 0..<setCount { source = index < template.count ? template[index] : template.last }`
     * — the rows a new exercise starts with. `workoutExerciseId` is filled in by the DAO.
     */
    fun prefilledSets(setCount: Int, template: List<CompletedSetRow>): List<SetEntryEntity> =
        (0 until setCount).map { index ->
            val source = template.getOrNull(index) ?: template.lastOrNull()
            SetEntryEntity(
                workoutExerciseId = 0L,
                order = index,
                kind = SetKind.Normal,
                weightKg = source?.weightKg ?: 0.0,
                reps = source?.reps ?: 0,
            )
        }

    /** `setCount: Int = 3` on the iOS signature. */
    const val DEFAULT_SET_COUNT = 3
}
