package app.notomorrow.feature.workout

import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.relation.RoutineWithItems
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.model.SetKind
import app.notomorrow.service.RoutineSeeder
import app.notomorrow.service.localizedName
import java.util.Locale
import java.util.UUID

/**
 * Writes [RoutineDraft]s to the store and the list actions on routines (duplicate, reorder,
 * delete) — 1:1 port of `NoTomorrow/Features/Workout/RoutineStore.swift`.
 *
 * Every write is one Room transaction ([RoutineDao.saveRoutineWithItems],
 * [RoutineDao.insertRoutineInOrder], [RoutineDao.updateRoutines]), so the Train list and Today's
 * card see the whole edit at once.
 */
class RoutineStore(
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
) {

    /** The names of every routine but [excludingId] — what a new or renamed routine must not reuse. */
    suspend fun names(excludingId: String? = null): List<String> =
        routineDao.routinesWithItems().filter { it.routine.id != excludingId }.map { it.routine.name }

    /**
     * Writes [draft] into routine [routineId] (its lines are replaced), or into a new routine at the
     * end of the list when [routineId] is `null`. Lines whose exercise is no longer in the library
     * are skipped. Returns the routine's id, or `null` when [routineId] no longer exists.
     */
    suspend fun save(draft: RoutineDraft, routineId: String?, now: Long = System.currentTimeMillis()): String? {
        val all = routineDao.routinesWithItems().map { it.routine }
        val routine = if (routineId != null) {
            all.firstOrNull { it.id == routineId }?.copy(name = draft.trimmedName) ?: return null
        } else {
            RoutineEntity(
                id = UUID.randomUUID().toString(),
                name = draft.trimmedName,
                order = (all.maxOfOrNull { it.order } ?: -1) + 1,
                createdAt = now,
            )
        }
        routineDao.saveRoutineWithItems(routine, items(routine.id, draft))
        return routine.id
    }

    /**
     * "Add N routines" in the program browser (`RoutineStore.add` on iOS): one new routine per
     * program routine, appended in the program's order through [save], named uniquely ("Full Body A
     * 2" when that name is taken). [localize] turns a routine's catalog key into its name. A routine
     * none of whose exercises is in the library is skipped. Returns the new routines' ids.
     */
    suspend fun addProgram(
        program: TrainingProgram,
        localize: (String) -> String,
        locale: Locale,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val byId = exerciseDao.byIds(program.exerciseIds.toList()).associateBy { it.id }
        val drafts = ProgramLibrary.drafts(
            program = program,
            taken = names(),
            exercise = { id ->
                byId[id]?.let { ProgramLibrary.ExerciseInfo(it.localizedName(locale), it.primaryMuscles.firstOrNull()) }
            },
            localize = localize,
        )
        return drafts.filter { it.items.isNotEmpty() }.mapNotNull { save(it, routineId = null, now = now) }
    }

    suspend fun delete(routineId: String) {
        routineDao.deleteRoutineById(routineId)
    }

    /** A copy right after the original, named "Push A 2". */
    suspend fun duplicate(routineId: String, locale: Locale, now: Long = System.currentTimeMillis()) {
        val source = routineDao.routineWithItems(routineId) ?: return
        val ordered = routineDao.routinesWithItems().map { it.routine }.sortedBy { it.order }
        val draft = draft(of = source, locale = locale)
        val copy = RoutineEntity(
            id = UUID.randomUUID().toString(),
            name = RoutineDraft.uniqueName(source.routine.name, ordered.map { it.name }),
            order = 0,
            createdAt = now,
        )
        val index = ordered.indexOfFirst { it.id == routineId }.let { if (it < 0) ordered.size else it + 1 }
        val renumbered = ordered.toMutableList().apply { add(index, copy) }
            .mapIndexed { order, routine -> routine.copy(order = order) }
        val placed = renumbered.first { it.id == copy.id }
        val before = ordered.associate { it.id to it.order }
        val changed = renumbered.filter { it.id != copy.id && before[it.id] != it.order }
        routineDao.insertRoutineInOrder(placed, items(placed.id, draft), changed)
    }

    /** Moves a routine one place up (-1) or down (+1) in the Train list. */
    suspend fun move(routineId: String, offset: Int) {
        val ordered = routineDao.routinesWithItems().map { it.routine }.sortedBy { it.order }.toMutableList()
        val from = ordered.indexOfFirst { it.id == routineId }
        if (from < 0) return
        val to = from + offset
        if (to !in ordered.indices) return
        ordered[from] = ordered[to].also { ordered[to] = ordered[from] }
        val changed = ordered.mapIndexedNotNull { index, routine ->
            if (routine.order != index) routine.copy(order = index) else null
        }
        if (changed.isNotEmpty()) routineDao.updateRoutines(changed)
    }

    /** The draft's lines as rows of [routineId], numbered from 0, minus exercises the library lost. */
    private suspend fun items(routineId: String, draft: RoutineDraft): List<RoutineItemEntity> {
        val known = exerciseDao.byIds(draft.items.map { it.exerciseId }).mapTo(mutableSetOf()) { it.id }
        val lines = draft.items.filter { it.exerciseId in known }
        val groups = Superset.normalized(lines.map { it.supersetGroup })
        return lines.mapIndexed { order, line ->
            RoutineItemEntity(
                routineId = routineId,
                exerciseId = line.exerciseId,
                order = order,
                targetSets = line.sets,
                targetReps = line.reps,
                restSeconds = line.restSeconds,
                supersetGroup = groups[order],
            )
        }
    }

    companion object {
        /** The draft of an existing routine; lines whose exercise was deleted from the library are left out. */
        fun draft(of: RoutineWithItems, locale: Locale): RoutineDraft = RoutineDraft(
            name = of.routine.name,
            items = of.sortedItems.mapNotNull { entry ->
                val exercise = entry.exercise ?: return@mapNotNull null
                RoutineItemDraft.of(
                    exerciseId = exercise.id,
                    name = exercise.localizedName(locale),
                    primaryMuscle = exercise.primaryMuscles.firstOrNull(),
                    sets = entry.item.targetSets,
                    reps = entry.item.targetReps,
                    restSeconds = entry.item.restSeconds,
                    supersetGroup = entry.item.supersetGroup,
                )
            },
            // A line whose exercise was deleted may have split a superset.
        ).normalizingSupersets()

        /**
         * "Save as routine" from a finished workout: completed sets that are not warm-ups, the rest
         * kept unless it is what the user's [defaultRest] gives the exercise.
         */
        fun draft(from: WorkoutWithExercises, defaultRest: Int, takenNames: List<String>, locale: Locale): RoutineDraft {
            val logged = from.sortedExercises.mapNotNull { entry ->
                val exercise = entry.exercise ?: return@mapNotNull null
                val working = entry.sortedSets.filter { it.isCompleted && it.kind != SetKind.Warmup }
                val rest = entry.workoutExercise.restSeconds
                RoutineDraft.LoggedExercise(
                    exerciseId = exercise.id,
                    name = exercise.localizedName(locale),
                    primaryMuscle = exercise.primaryMuscles.firstOrNull(),
                    workingReps = working.map { it.reps },
                    restSeconds = rest,
                    usesDefaultRest = rest == RoutineSeeder.restSeconds(exercise.id, defaultRest),
                    supersetGroup = entry.workoutExercise.supersetGroup,
                )
            }
            return RoutineDraft.from(from.workout.name, logged, takenNames)
        }
    }
}
