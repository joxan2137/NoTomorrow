package app.notomorrow.feature.workout

import androidx.room.withTransaction
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.di.AppContainer
import app.notomorrow.service.AttendanceReporter
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.RecordService
import app.notomorrow.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/**
 * Writes an edited draft back onto a finished workout, or deletes one, and keeps everything derived
 * from it consistent — `WorkoutEditor.swift`: set times on the records timeline, PR / set-record
 * flags of every workout with those exercises, attendance for the old and new day.
 *
 * Everything runs in one transaction, so the Room flows (Train, Today, Progress, Bro) emit once,
 * with the whole edit applied — the Android stand-in for `.workoutHistoryDidChange`.
 */
object WorkoutEditor {

    /**
     * What a save or delete touches. [transaction] is `db.withTransaction` in the app, a plain call
     * in tests; [reportAttendance] sends the attendance it changed to the backend once the
     * transaction has landed (`AttendanceSync.report(changes)`).
     */
    class Stores(
        val workoutDao: WorkoutDao,
        val exerciseDao: ExerciseDao,
        val recordService: RecordService,
        val attendanceService: AttendanceService,
        val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
        val reportAttendance: AttendanceReporter = AttendanceReporter.None,
    ) {
        companion object {
            fun of(container: AppContainer) = Stores(
                workoutDao = container.db.workoutDao(),
                exerciseDao = container.db.exerciseDao(),
                recordService = container.recordService,
                attendanceService = container.attendanceService,
                transaction = { block -> container.db.withTransaction { block() } },
                reportAttendance = container.attendanceReporter,
            )
        }
    }

    /**
     * `apply(_:to:in:today:)`, plan §5.2: name (an empty one keeps the old), notes, start and end;
     * exercises and sets removed, re-indexed, updated or inserted; each logged row's time remapped
     * or borrowed from a neighbour, open rows cleared with their flags; then the records rebuilt
     * for the exercises before ∪ after, and attendance corrected for the day it counted on before
     * and after. Returns `false` when the workout is gone.
     */
    suspend fun save(
        workoutId: String,
        draft: WorkoutDraft,
        stores: Stores,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): Boolean {
        var saved = false
        var changes = emptyList<AttendanceService.WorkoutDayChange>()
        stores.transaction body@{
            val dao = stores.workoutDao
            val graph = dao.workoutWithExercises(workoutId) ?: return@body
            val workout = graph.workout
            val oldStart = workout.startedAt
            val oldEnd = workout.endedAt ?: oldStart
            val oldDay = countedDay(graph, zone)
            val exercisesBefore = exerciseIds(graph)
            val times = WorkoutTimeline.completedTimes(draft, oldStart, oldEnd)

            val name = draft.name.trim()
            dao.updateWorkout(
                workout.copy(
                    name = name.ifEmpty { workout.name },
                    notes = draft.notes.trim(),
                    startedAt = draft.startedAt,
                    endedAt = draft.endedAt,
                ),
            )

            // Exercises no longer in the draft; their sets go with them (foreign-key cascade).
            val keptExercises = draft.exercises.mapNotNull { it.sourceId }.toSet()
            graph.exercises
                .filter { it.workoutExercise.id !in keptExercises }
                .forEach { dao.deleteWorkoutExercise(it.workoutExercise) }

            draft.exercises.forEachIndexed { index, item ->
                val existing = graph.exercises.firstOrNull { it.workoutExercise.id == item.sourceId }
                val entryId = when {
                    existing != null -> {
                        if (existing.workoutExercise.order != index) {
                            dao.updateWorkoutExercise(existing.workoutExercise.copy(order = index))
                        }
                        existing.workoutExercise.id
                    }
                    // An exercise gone from the library since it was picked is skipped, as iOS does.
                    stores.exerciseDao.byId(item.exerciseId) == null -> return@forEachIndexed
                    else -> dao.insertWorkoutExercise(
                        WorkoutExerciseEntity(
                            workoutId = workoutId,
                            exerciseId = item.exerciseId,
                            order = index,
                            restSeconds = item.restSeconds,
                        ),
                    )
                }

                val keptSets = item.sets.mapNotNull { it.sourceId }.toSet()
                existing?.sets?.filter { it.id !in keptSets }?.forEach { dao.deleteSet(it) }
                item.sets.forEachIndexed { row, set ->
                    val completedAt = if (set.isLogged) times[set.id] ?: draft.startedAt else null
                    val source = existing?.sets?.firstOrNull { it.id == set.sourceId }
                    if (source != null) {
                        val updated = source.copy(
                            order = row,
                            kind = set.kind,
                            weightKg = max(0.0, set.weightKg),
                            reps = max(0, set.reps),
                            completedAt = completedAt,
                            isPR = if (set.isLogged) source.isPR else false,
                            isSetRecord = if (set.isLogged) source.isSetRecord else false,
                        )
                        if (updated != source) dao.updateSet(updated)
                    } else {
                        dao.insertSet(
                            SetEntryEntity(
                                workoutExerciseId = entryId,
                                order = row,
                                kind = set.kind,
                                weightKg = max(0.0, set.weightKg),
                                reps = max(0, set.reps),
                                completedAt = completedAt,
                            ),
                        )
                    }
                }
            }

            val after = dao.workoutWithExercises(workoutId) ?: return@body
            stores.recordService.rebuild(exercisesBefore + exerciseIds(after))
            changes = correctAttendance(oldDay, countedDay(after, zone), workoutId, stores, zone, today)
            saved = true
        }
        report(changes, stores)
        return saved
    }

    /**
     * `delete(_:in:today:)` — deletes a finished workout (its exercises and sets cascade), rebuilds
     * the records of its exercises and corrects the day it counted on. Call it once nothing shows the
     * workout any more (its sheet is closing).
     */
    suspend fun delete(
        workoutId: String,
        stores: Stores,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ) {
        var changes = emptyList<AttendanceService.WorkoutDayChange>()
        stores.transaction body@{
            val graph = stores.workoutDao.workoutWithExercises(workoutId) ?: return@body
            val ids = exerciseIds(graph)
            val oldDay = countedDay(graph, zone)
            stores.workoutDao.deleteWorkoutById(workoutId)
            stores.recordService.rebuild(ids)
            changes = correctAttendance(oldDay, null, workoutId, stores, zone, today)
        }
        report(changes, stores)
    }

    // MARK: - Helpers

    private fun exerciseIds(graph: WorkoutWithExercises): Set<String> =
        graph.exercises.mapNotNull { it.workoutExercise.exerciseId }.toSet()

    /** The day a workout counts on for attendance: its start day, when it has a completed set. */
    internal fun countedDay(graph: WorkoutWithExercises, zone: ZoneId): LocalDate? =
        if (graph.completedSetCount > 0) Instant.ofEpochMilli(graph.workout.startedAt).atZone(zone).toLocalDate() else null

    private suspend fun correctAttendance(
        oldDay: LocalDate?,
        newDay: LocalDate?,
        workoutId: String,
        stores: Stores,
        zone: ZoneId,
        today: LocalDate,
    ): List<AttendanceService.WorkoutDayChange> =
        correctAttendance(oldDay, newDay, workoutId, stores.workoutDao, stores.attendanceService, zone, today)

    /**
     * `AttendanceService.applyWorkoutDayChange(from:to:excluding:)`: moves the day workout
     * [workoutId] counts on from [oldDay] to [newDay] (`null` = it no longer counts), keeping
     * [oldDay] attended while another finished workout with a completed set started on it. Also
     * the active workout's Finish with nothing done, and its Discard.
     */
    internal suspend fun correctAttendance(
        oldDay: LocalDate?,
        newDay: LocalDate?,
        workoutId: String,
        workoutDao: WorkoutDao,
        attendanceService: AttendanceService,
        zone: ZoneId,
        today: LocalDate,
    ): List<AttendanceService.WorkoutDayChange> {
        if (oldDay == newDay) return emptyList()
        val stillAttended = oldDay?.let { day ->
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            workoutDao.countedWorkoutsBetween(from, to, excludingId = workoutId) > 0
        } ?: false
        return attendanceService.applyWorkoutDayChange(oldDay, newDay, stillAttended, today)
    }

    private suspend fun report(changes: List<AttendanceService.WorkoutDayChange>, stores: Stores) =
        report(changes, stores.attendanceService, stores.reportAttendance)

    /**
     * Sends what [correctAttendance] wrote to the backend (`AttendanceService.wireStatus`): the new
     * day attended, a past gym day missed, a gym day given back planned. A cleared rest day has no
     * server equivalent.
     */
    internal suspend fun report(
        changes: List<AttendanceService.WorkoutDayChange>,
        attendanceService: AttendanceService,
        reporter: AttendanceReporter,
    ) {
        if (changes.isEmpty()) return
        val schedule = attendanceService.schedule()
        reporter.report(changes) { schedule?.isGymDay(Fmt.isoWeekday(it)) ?: false }
    }
}
