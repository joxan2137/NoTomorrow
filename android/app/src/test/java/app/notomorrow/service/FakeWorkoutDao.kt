package app.notomorrow.service

import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.ExerciseNoteRow
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.data.relation.WorkoutWithExercises
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * In-memory `workout` / `workout_exercise` / `set_entry` tables with the foreign keys' cascades,
 * for the session and the start path. Only the abstract members are implemented; the
 * `@Transaction` helper `insertWorkoutExerciseWithSets` is inherited, so tests exercise the real one.
 *
 * [routineNames] stands in for the `routine` table the one cross-table query reads.
 */
class FakeWorkoutDao : WorkoutDao {

    val workouts = MutableStateFlow<List<WorkoutEntity>>(emptyList())
    val exercises = MutableStateFlow<List<WorkoutExerciseEntity>>(emptyList())
    val sets = MutableStateFlow<List<SetEntryEntity>>(emptyList())
    val routineNames = MutableStateFlow<List<String>>(emptyList())

    private var nextExerciseId = 1L
    private var nextSetId = 1L

    private val tables = combine(workouts, exercises, sets) { _, _, _ -> Unit }

    // MARK: - Graph helpers

    private fun graph(workout: WorkoutEntity): WorkoutWithExercises = WorkoutWithExercises(
        workout,
        exercises.value.filter { it.workoutId == workout.id }.map { we ->
            WorkoutExerciseWithSets(we, exercise = null, sets = sets.value.filter { it.workoutExerciseId == we.id })
        },
    )

    private fun active() = workouts.value.filter { it.endedAt == null }.sortedByDescending { it.startedAt }

    private fun finished() = workouts.value.filter { it.endedAt != null }.sortedByDescending { it.startedAt }

    private fun completedRows(): List<CompletedSetRow> = sets.value.filter { it.completedAt != null }.mapNotNull { set ->
        val we = exercises.value.firstOrNull { it.id == set.workoutExerciseId } ?: return@mapNotNull null
        val w = workouts.value.firstOrNull { it.id == we.workoutId } ?: return@mapNotNull null
        CompletedSetRow(
            setId = set.id,
            setOrder = set.order,
            kind = set.kind,
            weightKg = set.weightKg,
            reps = set.reps,
            completedAt = set.completedAt!!,
            isPR = set.isPR,
            isSetRecord = set.isSetRecord,
            rpe = set.rpe,
            workoutExerciseId = we.id,
            workoutExerciseOrder = we.order,
            exerciseId = we.exerciseId,
            workoutId = w.id,
            workoutName = w.name,
            workoutStartedAt = w.startedAt,
            workoutEndedAt = w.endedAt,
        )
    }

    private fun setsOf(workoutId: String): List<SetEntryEntity> {
        val ids = exercises.value.filter { it.workoutId == workoutId }.map { it.id }.toSet()
        return sets.value.filter { it.workoutExerciseId in ids }
    }

    // MARK: - Active workout

    override fun observeActiveWorkouts(): Flow<List<WorkoutEntity>> = workouts.map { active() }

    override suspend fun newestActiveWorkout(): WorkoutEntity? = active().firstOrNull()

    override suspend fun activeWorkouts(): List<WorkoutEntity> = active()

    override suspend fun completedSetCount(workoutId: String): Int = setsOf(workoutId).count { it.completedAt != null }

    override suspend fun lastCompletedAt(workoutId: String): Long? = setsOf(workoutId).mapNotNull { it.completedAt }.maxOrNull()

    override suspend fun workout(id: String): WorkoutEntity? = workouts.value.firstOrNull { it.id == id }

    override fun observeWorkout(id: String): Flow<WorkoutEntity?> = workouts.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun countedWorkoutsBetween(from: Long, to: Long, excludingId: String): Int =
        finished().count { w ->
            w.id != excludingId && w.startedAt >= from && w.startedAt < to && setsOf(w.id).any { it.completedAt != null }
        }

    override fun observeCountedWorkoutsBetween(from: Long, to: Long): Flow<Int> =
        combine(workouts, exercises, sets) { _, _, _ ->
            finished().count { w ->
                w.startedAt >= from && w.startedAt < to && setsOf(w.id).any { it.completedAt != null }
            }
        }

    override suspend fun countedWorkoutStartsSince(from: Long): List<Long> =
        finished()
            .filter { w -> w.startedAt >= from && setsOf(w.id).any { it.completedAt != null } }
            .map { it.startedAt }
            .sorted()

    // MARK: - History

    override fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>> = workouts.map { finished() }

    override fun observeFinishedCount(): Flow<Int> = workouts.map { finished().size }

    override fun observeLastRoutineWorkoutName(): Flow<String?> =
        combine(workouts, routineNames) { _, names -> finished().firstOrNull { it.name in names }?.name }

    override fun observeFinishedWorkoutsWithExercises(limit: Int): Flow<List<WorkoutWithExercises>> =
        tables.map { finished().take(limit).map(::graph) }

    override suspend fun lastFinishedWorkoutWithExercises(): WorkoutWithExercises? = finished().firstOrNull()?.let(::graph)

    override suspend fun previousWorkoutWithSameName(
        name: String,
        startedBefore: Long,
        excludingId: String,
    ): WorkoutWithExercises? = finished()
        .firstOrNull { it.name == name && it.startedAt < startedBefore && it.id != excludingId }
        ?.let(::graph)

    override suspend fun finishedWorkoutsWithExercisesAsc(): List<WorkoutWithExercises> = finished().reversed().map(::graph)

    override fun observeWorkoutWithExercises(id: String): Flow<WorkoutWithExercises?> =
        tables.map { workouts.value.firstOrNull { it.id == id }?.let(::graph) }

    override suspend fun workoutWithExercises(id: String): WorkoutWithExercises? =
        workouts.value.firstOrNull { it.id == id }?.let(::graph)

    // MARK: - Completed-set projections

    override suspend fun completedSets(): List<CompletedSetRow> = completedRows()

    override fun observeCompletedSets(): Flow<List<CompletedSetRow>> = tables.map { completedRows() }

    override suspend fun completedSetsForExercise(exerciseId: String): List<CompletedSetRow> =
        completedRows().filter { it.exerciseId == exerciseId }

    override fun observeCompletedSetsForExercise(exerciseId: String): Flow<List<CompletedSetRow>> =
        tables.map { completedRows().filter { it.exerciseId == exerciseId } }

    override suspend fun exerciseNotes(exerciseId: String): List<ExerciseNoteRow> =
        exercises.value.filter { it.exerciseId == exerciseId && it.notes.isNotEmpty() }.mapNotNull { we ->
            val w = workouts.value.firstOrNull { it.id == we.workoutId } ?: return@mapNotNull null
            ExerciseNoteRow(we.notes, w.id, w.startedAt, w.endedAt, we.id)
        }

    override suspend fun updateWorkoutExerciseNotes(id: Long, notes: String) {
        exercises.value = exercises.value.map { if (it.id == id) it.copy(notes = notes) else it }
    }

    // MARK: - Writes

    override suspend fun insertWorkout(workout: WorkoutEntity) {
        check(workouts.value.none { it.id == workout.id }) { "duplicate workout ${workout.id}" }
        workouts.value = workouts.value + workout
    }

    override suspend fun updateWorkout(workout: WorkoutEntity) {
        workouts.value = workouts.value.map { if (it.id == workout.id) workout else it }
    }

    override suspend fun deleteWorkout(workout: WorkoutEntity) = deleteWorkoutById(workout.id)

    /** `ON DELETE CASCADE` down both levels. */
    override suspend fun deleteWorkoutById(id: String) {
        val exerciseIds = exercises.value.filter { it.workoutId == id }.map { it.id }.toSet()
        sets.value = sets.value.filterNot { it.workoutExerciseId in exerciseIds }
        exercises.value = exercises.value.filterNot { it.workoutId == id }
        workouts.value = workouts.value.filterNot { it.id == id }
    }

    override suspend fun finishWorkout(id: String, endedAt: Long) {
        workouts.value = workouts.value.map { if (it.id == id) it.copy(endedAt = endedAt) else it }
    }

    override suspend fun reopenWorkout(id: String) {
        workouts.value = workouts.value.map { if (it.id == id) it.copy(endedAt = null) else it }
    }

    override suspend fun updateWorkoutNotes(id: String, notes: String) {
        workouts.value = workouts.value.map { if (it.id == id) it.copy(notes = notes) else it }
    }

    override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long {
        val id = nextExerciseId++
        exercises.value = exercises.value + workoutExercise.copy(id = id)
        return id
    }

    override suspend fun insertWorkoutExercises(workoutExercises: List<WorkoutExerciseEntity>): List<Long> =
        workoutExercises.map { insertWorkoutExercise(it) }

    override suspend fun updateWorkoutExercise(workoutExercise: WorkoutExerciseEntity) {
        exercises.value = exercises.value.map { if (it.id == workoutExercise.id) workoutExercise else it }
    }

    override suspend fun deleteWorkoutExercise(workoutExercise: WorkoutExerciseEntity) {
        sets.value = sets.value.filterNot { it.workoutExerciseId == workoutExercise.id }
        exercises.value = exercises.value.filterNot { it.id == workoutExercise.id }
    }

    override suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseEntity> =
        exercises.value.filter { it.workoutId == workoutId }.sortedBy { it.order }

    override suspend fun insertSet(set: SetEntryEntity): Long {
        val id = nextSetId++
        sets.value = sets.value + set.copy(id = id)
        return id
    }

    override suspend fun insertSets(sets: List<SetEntryEntity>): List<Long> = sets.map { insertSet(it) }

    override suspend fun updateSet(set: SetEntryEntity) {
        sets.value = sets.value.map { if (it.id == set.id) set else it }
    }

    override suspend fun deleteSet(set: SetEntryEntity) {
        sets.value = sets.value.filterNot { it.id == set.id }
    }

    override suspend fun set(id: Long): SetEntryEntity? = sets.value.firstOrNull { it.id == id }

    override suspend fun sets(workoutExerciseId: Long): List<SetEntryEntity> =
        sets.value.filter { it.workoutExerciseId == workoutExerciseId }.sortedBy { it.order }

    override suspend fun updateSetRecords(id: Long, isPR: Boolean, isSetRecord: Boolean) {
        sets.value = sets.value.map { if (it.id == id) it.copy(isPR = isPR, isSetRecord = isSetRecord) else it }
    }

    override suspend fun updateSetCompletion(id: Long, completedAt: Long?) {
        sets.value = sets.value.map { if (it.id == id) it.copy(completedAt = completedAt) else it }
    }

    override suspend fun clearOpenSetRecords(exerciseIds: List<String>): Int {
        val entries = exercises.value.filter { it.exerciseId in exerciseIds }.map { it.id }.toSet()
        var changed = 0
        sets.value = sets.value.map { set ->
            if (set.completedAt == null && (set.isPR || set.isSetRecord) && set.workoutExerciseId in entries) {
                changed += 1
                set.copy(isPR = false, isSetRecord = false)
            } else {
                set
            }
        }
        return changed
    }

    override suspend fun deleteAllSets() {
        sets.value = emptyList()
    }

    override suspend fun deleteAllWorkoutExercises() {
        exercises.value = emptyList()
    }

    override suspend fun deleteAllWorkouts() {
        workouts.value = emptyList()
    }

    // MARK: - Test helpers

    /** A workout with one exercise and [completed] completed sets out of [total], set i done at `startedAt + (i + 1) * 60 s`. */
    suspend fun seed(
        id: String,
        startedAt: Long,
        endedAt: Long? = null,
        name: String = "Push A",
        exerciseId: String = "Barbell_Bench_Press_-_Medium_Grip",
        completed: Int = 0,
        total: Int = 3,
        weightKg: Double = 80.0,
        reps: Int = 8,
    ) {
        insertWorkout(WorkoutEntity(id = id, name = name, startedAt = startedAt, endedAt = endedAt))
        insertWorkoutExerciseWithSets(
            WorkoutExerciseEntity(workoutId = id, exerciseId = exerciseId, order = 0),
            (0 until total).map { index ->
                SetEntryEntity(
                    workoutExerciseId = 0,
                    order = index,
                    weightKg = weightKg,
                    reps = reps,
                    completedAt = if (index < completed) startedAt + (index + 1) * 60_000L else null,
                )
            },
        )
    }
}
