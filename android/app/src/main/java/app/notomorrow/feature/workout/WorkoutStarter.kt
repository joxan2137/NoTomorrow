package app.notomorrow.feature.workout

import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.di.AppContainer
import app.notomorrow.model.SetKind
import app.notomorrow.service.RoutineSeeder
import app.notomorrow.service.WorkoutSessionController
import java.util.UUID
import kotlin.math.max

/**
 * Builds `Workout` graphs (exercises + set rows) from a routine or from nothing, and hands them to
 * the session — 1:1 port of `NoTomorrow/Features/Workout/WorkoutStarter.swift`. The one start
 * path for Train and Today, so both build the same workout.
 *
 * SwiftData writes the graph through the object relationships; Room needs the parent row first, so
 * every insert goes through [WorkoutDao.insertWorkoutExerciseWithSets], which is `@Transaction`.
 *
 * The prefill rule lives in [templateSets] / [prefilledSets] as pure functions: rows are seeded
 * from the **last** completed working sets of that exercise, once the template runs out every
 * remaining row repeats its last entry, and an exercise never done gets the routine's target reps
 * at no weight.
 */
object WorkoutStarter {

    /** The four tables a start touches. */
    class Stores(
        val routineDao: RoutineDao,
        val workoutDao: WorkoutDao,
        val exerciseDao: ExerciseDao,
        val profileDao: ProfileDao,
    ) {
        companion object {
            fun of(container: AppContainer) = Stores(
                routineDao = container.db.routineDao(),
                workoutDao = container.db.workoutDao(),
                exerciseDao = container.db.exerciseDao(),
                profileDao = container.db.profileDao(),
            )
        }
    }

    /** What a Start tap asks for. [Empty.name] is `workout.defaultName`, resolved by the caller. */
    sealed interface Request {
        data class Routine(val routineId: String) : Request
        data class Empty(val name: String) : Request
    }

    /**
     * Whether a Start tap may go ahead. Only one workout runs at a time: while one is in progress
     * the caller offers Resume, and "Discard it and start new" only when that workout has no
     * completed sets.
     */
    sealed interface Gate {
        data object Clear : Gate
        data class Blocked(val active: WorkoutEntity, val canDiscard: Boolean) : Gate
    }

    suspend fun gate(workoutDao: WorkoutDao, session: WorkoutSessionController): Gate {
        val active = session.activeWorkout() ?: return Gate.Clear
        return Gate.Blocked(active, canDiscard = workoutDao.completedSetCount(active.id) == 0)
    }

    /** Starts [request] and opens it full screen. Returns the new workout id, `null` when the routine is gone. */
    suspend fun start(
        request: Request,
        stores: Stores,
        session: WorkoutSessionController,
        now: Long = System.currentTimeMillis(),
    ): String? = when (request) {
        is Request.Routine -> start(stores, session, request.routineId, now)
        is Request.Empty -> startEmpty(stores.workoutDao, session, request.name, now)
    }

    /**
     * "Discard it and start new": drops the workout in progress (only when nothing was completed in
     * it) and starts [request]. With completed sets it refuses and brings the running workout back
     * instead. The caller skips the rest timer first.
     */
    suspend fun discardAndStart(
        active: WorkoutEntity,
        request: Request,
        stores: Stores,
        session: WorkoutSessionController,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (stores.workoutDao.completedSetCount(active.id) != 0) {
            session.expand()
            return active.id
        }
        session.discard(active.id)
        return start(request, stores, session, now)
    }

    /**
     * `start(routine:in:session:)` — one `WorkoutExercise` per routine item, `targetSets` rows each,
     * prefilled from the last time the exercise was done (target reps when it never was). An item
     * whose rest is [RoutineSeeder.INHERIT_REST] takes the user's default rest. Supersets carry over.
     */
    suspend fun start(
        stores: Stores,
        session: WorkoutSessionController,
        routineId: String,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val routine = stores.routineDao.routineWithItems(routineId) ?: return null
        val workout = WorkoutEntity(
            id = UUID.randomUUID().toString(),
            name = routine.routine.name,
            startedAt = now,
        )
        stores.workoutDao.insertWorkout(workout)
        val defaultRest = defaultRestSeconds(stores.profileDao)

        // `guard let exercise = item.exercise` — a routine item whose exercise was deleted is skipped,
        // which may split a superset: the groups are normalized over the items that stay.
        val kept = routine.sortedItems.filter { it.exercise != null }
        val groups = Superset.normalized(kept.map { it.item.supersetGroup })
        for ((order, entry) in kept.withIndex()) {
            val exerciseId = entry.exercise?.id ?: continue
            append(
                workoutDao = stores.workoutDao,
                exerciseDao = stores.exerciseDao,
                workoutId = workout.id,
                exerciseId = exerciseId,
                order = order,
                setCount = max(1, entry.item.targetSets),
                targetReps = entry.item.targetReps,
                restSeconds = routineItemRest(entry.item.restSeconds, exerciseId, defaultRest),
                defaultRest = defaultRest,
                supersetGroup = groups[order],
                now = now,
            )
        }

        session.begin(workout.id)
        return workout.id
    }

    /** `startEmpty(in:session:)`. [name] is `workout.defaultName` resolved by the caller. */
    suspend fun startEmpty(
        workoutDao: WorkoutDao,
        session: WorkoutSessionController,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): String {
        val workout = WorkoutEntity(id = UUID.randomUUID().toString(), name = name, startedAt = now)
        workoutDao.insertWorkout(workout)
        session.begin(workout.id)
        return workout.id
    }

    /**
     * [append] against the container — the form the exercise picker uses. Rest defaults to the
     * user's Rest length setting (heavy compounds a little longer).
     */
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
        defaultRest = defaultRestSeconds(container.db.profileDao()),
        now = now,
    )

    /**
     * `append(_:to:order:setCount:targetReps:restSeconds:in:)` — appends one exercise with
     * prefilled rows to an existing workout and stamps `lastUsedAt`. Returns the new
     * `workout_exercise` row id.
     */
    suspend fun append(
        workoutDao: WorkoutDao,
        exerciseDao: ExerciseDao,
        workoutId: String,
        exerciseId: String,
        order: Int,
        setCount: Int = DEFAULT_SET_COUNT,
        targetReps: Int = 0,
        restSeconds: Int? = null,
        defaultRest: Int = RoutineSeeder.DEFAULT_REST_SECONDS,
        supersetGroup: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val rest = restSeconds ?: RoutineSeeder.restSeconds(exerciseId, defaultRest)
        val template = lastCompletedSets(workoutDao, exerciseId, excludingWorkoutId = workoutId)
        val id = workoutDao.insertWorkoutExerciseWithSets(
            WorkoutExerciseEntity(
                workoutId = workoutId,
                exerciseId = exerciseId,
                order = order,
                restSeconds = rest,
                supersetGroup = supersetGroup,
            ),
            prefilledSets(setCount, template, targetReps),
        )
        exerciseDao.markUsed(exerciseId, now)
        return id
    }

    /** The user's default rest (Settings > Rest timer > Rest length), 90 s before a profile exists. */
    suspend fun defaultRestSeconds(profileDao: ProfileDao): Int =
        resolveDefaultRest(profileDao.profile()?.defaultRestSeconds)

    /**
     * `lastCompletedSets(for:excluding:)` — the completed working sets of the most recent **other**
     * finished workout that contains the exercise, in row order (the same session the table's
     * Previous column reads).
     */
    suspend fun lastCompletedSets(
        workoutDao: WorkoutDao,
        exerciseId: String,
        excludingWorkoutId: String? = null,
    ): List<CompletedSetRow> =
        templateSets(workoutDao.completedSetsForExercise(exerciseId), excludingWorkoutId)

    // MARK: - Pure derivations

    /** A stored default of 0 or less (never written by Settings) falls back to 90 s. */
    fun resolveDefaultRest(stored: Int?): Int =
        stored?.takeIf { it > 0 } ?: RoutineSeeder.DEFAULT_REST_SECONDS

    /** A routine item's own rest, or — when it inherits (0) — the exercise's rest at [defaultRest]. */
    fun routineItemRest(itemRest: Int, exerciseId: String, defaultRest: Int): Int =
        if (itemRest > 0) itemRest else RoutineSeeder.restSeconds(exerciseId, defaultRest)

    /**
     * The prefill template: working sets (completed, not a warm-up, `reps > 0`) of the latest usage
     * of the exercise in a **finished** workout outside [excludingWorkoutId], sorted by row order.
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
                row.workoutEndedAt != null &&
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
     * — the rows a new exercise starts with; with no template, [targetReps] at no weight.
     * `workoutExerciseId` is filled in by the DAO.
     */
    fun prefilledSets(
        setCount: Int,
        template: List<CompletedSetRow>,
        targetReps: Int = 0,
    ): List<SetEntryEntity> =
        (0 until setCount).map { index ->
            val source = template.getOrNull(index) ?: template.lastOrNull()
            SetEntryEntity(
                workoutExerciseId = 0L,
                order = index,
                kind = SetKind.Normal,
                weightKg = source?.weightKg ?: 0.0,
                reps = source?.reps ?: targetReps,
            )
        }

    /** `setCount: Int = 3` on the iOS signature. */
    const val DEFAULT_SET_COUNT = 3
}
