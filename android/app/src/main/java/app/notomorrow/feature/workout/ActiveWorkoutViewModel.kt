package app.notomorrow.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.RoutineWithItems
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.rest.RestTimerController
import app.notomorrow.rest.RestTimerState
import app.notomorrow.service.AttendanceReporter
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.RecordService
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtStrings
import app.notomorrow.util.S
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Derived state and mutations for one workout in progress — 1:1 port of
 * `NoTomorrow/Features/Workout/ActiveWorkoutModel.swift`.
 *
 * SwiftData's `@Observable` model mutates the graph in place and lets every view re-read it;
 * here the graph comes back through `WorkoutDao.observeWorkoutWithExercises` and is folded
 * into one immutable [ActiveWorkoutUiState] on every emission. The two things Room cannot
 * derive on its own — the *previous* workout's rows per exercise and the "Last: 80 × 8" set —
 * are cached in [previousRows] / [previousLast] as plain values, reloaded exactly where iOS calls
 * `reloadPrevious()` (on every expand, since history may have been edited meanwhile). The user's
 * weight unit and the routine's target reps (for the suggested weight) are re-read there too.
 *
 * Every write to a set runs under [writes], in call order: a tick right after typing sees the
 * number that was typed, and two keystrokes can never land in the wrong order.
 *
 * One per workout, keyed by [workoutId] and hosted by the tab shell (`MainTabScaffold`), not by
 * the full screen: the full screen and the mini bar read the same instance, so collapsing keeps
 * the open exercise, the PR hint, "up next" and the summary — iOS gets the same by owning the
 * model on `WorkoutSessionController`. Once the session lets go (Done, Discard, another workout)
 * it stops reading Room and keeps its last state, so the full screen can finish sliding away.
 */
class ActiveWorkoutViewModel(
    private val workoutId: String,
    private val workoutDao: WorkoutDao,
    private val recordService: RecordService,
    private val attendanceService: AttendanceService,
    private val restTimer: RestTimerController,
    private val session: WorkoutSessionController,
    private val appPrefs: AppPrefs,
    private val strings: NtStrings,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** The user's weight unit (`UserProfile.units`), read on load and on every [reloadPrevious]. */
    private val units: suspend () -> WeightUnit = { WeightUnit.Kg },
    /** Sends the attended day to the backend (`AttendanceSync.report`); a no-op in tests. */
    private val reportAttendance: AttendanceReporter = AttendanceReporter.None,
    /** The routines, read with the unit: the one named like the workout gives the target reps. */
    private val routines: suspend () -> List<RoutineWithItems> = { emptyList() },
) : ViewModel() {

    private val _state = MutableStateFlow(ActiveWorkoutUiState())
    val state: StateFlow<ActiveWorkoutUiState> = _state.asStateFlow()

    /** The rest timer's own state, so the pill and the sheet never poll the controller. */
    val restState: StateFlow<RestTimerState> = restTimer.state

    /** `expandedExerciseID` — survives every Room emission, so it lives outside the state fold. */
    private var expandedExerciseId: Long? = null
    private var expandedInitialised = false

    private var hintSetId: Long? = null
    private var hintBest: SetValue? = null
    private var upNext: UpNextTarget? = null

    /**
     * `showsSummary` — the "DONE." summary is on screen. Mirrored to `session.showsSummary` for the
     * rest of the app; kept here too so the summary keeps rendering while the full screen slides
     * away after Done (the session has already let go by then).
     */
    private var showsDone = false

    /** Rows of the last finished session with the exercise, keyed by exercise id. */
    private val previousRows = mutableMapOf<String, PreviousRows>()
    private val previousLast = mutableMapOf<String, SetValue?>()

    /** `unit` — cells, Previous, "Last:", the hint, the rest card and its notification show it. */
    private var unit = WeightUnit.Kg
    /** `targetReps` — per exercise id, from the routine this workout was started from. */
    private var targetReps: Map<String, Int> = emptyMap()
    /** [unit] and [targetReps] are current. */
    private var unitLoaded = false

    /** Serialises the set writes (see the class comment). */
    private val writes = Mutex()

    private var graph: WorkoutWithExercises? = null

    init {
        val reading = viewModelScope.launch {
            workoutDao.observeWorkoutWithExercises(workoutId).collectLatest { next ->
                if (next == null) {
                    _state.value = ActiveWorkoutUiState(loading = false, missing = true, workoutId = workoutId)
                    // The row is gone (deleted elsewhere): there is nothing left to resume.
                    if (session.activeWorkoutId.value == workoutId) session.end()
                    return@collectLatest
                }
                graph = next
                ensurePrevious(next)
                publish(next)
            }
        }
        viewModelScope.launch {
            session.activeWorkoutId.first { it == workoutId }
            session.activeWorkoutId.first { it != workoutId }
            reading.cancel()
        }
    }

    // MARK: - Previous values

    /**
     * `reloadPrevious()` — drops the caches (and the unit) so they are read again. Called each time
     * the full screen appears and after the exercise picker closes, exactly where iOS re-runs it.
     */
    fun reloadPrevious() {
        previousRows.clear()
        previousLast.clear()
        unitLoaded = false
        viewModelScope.launch {
            val current = graph ?: return@launch
            ensurePrevious(current)
            publish(current)
        }
    }

    /**
     * One query per exercise fills both caches: the rows of the latest *finished* other session
     * with a completed working set ([foldLatestEarlierWorkoutRows]) and the most recent completed
     * working set (`RecordService.lastSet(for:excluding:)`).
     */
    private suspend fun ensurePrevious(graph: WorkoutWithExercises) {
        if (!unitLoaded) {
            unit = units()
            targetReps = routineTargetReps(routines(), graph.workout.name)
            unitLoaded = true
        }
        val myId = graph.workout.id
        for (section in graph.sortedExercises) {
            val exerciseId = section.workoutExercise.exerciseId ?: continue
            if (previousRows.containsKey(exerciseId)) continue
            val rows = workoutDao.completedSetsForExercise(exerciseId)
            previousRows[exerciseId] = PreviousRows.of(foldLatestEarlierWorkoutRows(rows, myId))
            previousLast[exerciseId] = RecordService.lastSet(rows, excludingWorkoutId = myId)
                ?.let { SetValue(it.weightKg, it.reps) }
        }
    }

    /**
     * `previous(for:in:)` — the same-position set of the last session (warm-ups against warm-ups,
     * working sets by number), else, for a working set, the most recent completed set.
     */
    private fun previous(exerciseId: String?, slot: SetSlot): SetValue? {
        if (exerciseId == null) return null
        return previousValue(previousRows[exerciseId], previousLast[exerciseId], slot)
    }

    // MARK: - State fold

    private fun publish(graph: WorkoutWithExercises) {
        val locale = LocaleProvider.current()
        val sections = graph.sortedExercises
        if (!expandedInitialised) {
            expandedInitialised = true
            expandedExerciseId = (sections.firstOrNull { !it.isDone } ?: sections.firstOrNull())
                ?.workoutExercise?.id
        }
        val exercises = sections.map { section ->
            val exerciseId = section.workoutExercise.exerciseId
            section.toUi(
                locale = locale,
                previousLast = exerciseId?.let { previousLast[it] },
                previous = { slot -> previous(exerciseId, slot) },
                suggestion = exerciseId?.let { id ->
                    weightSuggestion(previousRows[id]?.normal.orEmpty(), targetReps[id], unit)
                },
            )
        }
        _state.value = ActiveWorkoutUiState(
            loading = false,
            missing = false,
            workoutId = graph.workout.id,
            name = graph.workout.name,
            startedAt = graph.workout.startedAt,
            endedAt = graph.workout.endedAt,
            exercises = exercises,
            expandedExerciseId = expandedExerciseId,
            hintSetId = hintSetId,
            hintBest = hintBest,
            upNext = upNext,
            showsDone = showsDone,
            unit = unit,
        )
    }

    // MARK: - Set mutations

    /** `toggleExpanded(_:)` — one exercise open at a time. */
    fun toggleExpanded(exerciseUiId: Long) {
        expandedExerciseId = if (expandedExerciseId == exerciseUiId) null else exerciseUiId
        graph?.let(::publish)
    }

    /**
     * Ticks a set: an empty row takes Previous, `completedAt` is stamped, the records are evaluated
     * and the rest starts — unless the next set is a drop set or `nt.rest.autoStart` is off
     * (`ActiveWorkoutModel.complete`). A row that still has no reps is not logged (no "0 × 0" sets)
     * and stays open: [onResult] gets `false`, so the screen can send the user to its reps cell.
     */
    fun complete(setId: Long, onResult: (logged: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            writes.withLock {
                val current = _state.value
                val section = current.exercises.firstOrNull { ex -> ex.sets.any { it.id == setId } } ?: return@launch
                val row = section.sets.first { it.id == setId }
                val entity = workoutDao.set(setId) ?: return@launch

                val values = valuesToLog(entity.weightKg, entity.reps, row.previous)
                if (values == null) {
                    onResult(false)
                    return@launch
                }
                val completed = entity.copy(weightKg = values.weightKg, reps = values.reps, completedAt = clock())
                workoutDao.updateSet(completed)
                onResult(true)

                val result = recordService.mark(completed, section.exerciseId)
                val best = result.bestBefore
                if ((result.isPR || result.isSetRecord) && best != null) {
                    hintSetId = setId
                    hintBest = SetValue(best.weightKg, best.reps)
                } else if (hintSetId == setId) {
                    hintSetId = null
                    hintBest = null
                }

                startRestIfNeeded(section, row, completed)
                graph?.let(::publish)
            }
        }
    }

    /** `uncomplete(_:)` — clears the stamp and both record flags. */
    fun uncomplete(setId: Long) {
        viewModelScope.launch {
            writes.withLock {
                val entity = workoutDao.set(setId) ?: return@launch
                workoutDao.updateSet(entity.copy(completedAt = null, isPR = false, isSetRecord = false))
                if (hintSetId == setId) {
                    hintSetId = null
                    hintBest = null
                }
                graph?.let(::publish)
            }
        }
    }

    fun setKind(setId: Long, kind: SetKind) {
        viewModelScope.launch {
            writes.withLock {
                val entity = workoutDao.set(setId) ?: return@launch
                if (entity.kind == kind) return@launch
                workoutDao.updateSet(entity.copy(kind = kind))
            }
        }
    }

    /** Write-through of the weight cell (already kg). No-op when the value already matches. */
    fun setWeight(setId: Long, weightKg: Double) {
        viewModelScope.launch {
            writes.withLock {
                val entity = workoutDao.set(setId) ?: return@launch
                if (entity.weightKg == weightKg) return@launch
                workoutDao.updateSet(entity.copy(weightKg = weightKg))
            }
        }
    }

    /** Write-through of the reps cell. */
    fun setReps(setId: Long, reps: Int) {
        viewModelScope.launch {
            writes.withLock {
                val entity = workoutDao.set(setId) ?: return@launch
                if (entity.reps == reps) return@launch
                workoutDao.updateSet(entity.copy(reps = reps))
            }
        }
    }

    /**
     * `prefillFromPrevious(_:in:)` — a row that appears open with an empty cell takes Previous, so
     * the table shows (and a tick logs) what it shows. Completed rows are never touched.
     */
    fun prefillFromPrevious(setId: Long) {
        val previous = _state.value.exercises.firstNotNullOfOrNull { ex -> ex.sets.firstOrNull { it.id == setId } }
            ?.previous ?: return
        viewModelScope.launch {
            writes.withLock {
                val entity = workoutDao.set(setId) ?: return@launch
                val values = prefilledValues(entity.weightKg, entity.reps, entity.isCompleted, previous) ?: return@launch
                workoutDao.updateSet(entity.copy(weightKg = values.weightKg, reps = values.reps))
            }
        }
    }

    /**
     * `useSuggestion(_:in:)` — "Use": every open set of the exercise (not a warm-up, not a drop set)
     * takes the suggested weight. Nothing changes a weight without this tap; the line goes once no
     * open set is left at the previous top weight.
     */
    fun useSuggestion(exerciseUiId: Long) {
        val suggestion = _state.value.exercises.firstOrNull { it.id == exerciseUiId }?.suggestion ?: return
        viewModelScope.launch {
            writes.withLock {
                workoutDao.sets(exerciseUiId)
                    .filter { !it.isCompleted && it.kind != SetKind.Warmup && it.kind != SetKind.Drop }
                    .forEach { set ->
                        if (set.weightKg != suggestion.toKg) workoutDao.updateSet(set.copy(weightKg = suggestion.toKg))
                    }
            }
        }
    }

    /** `addSet(to:)` — duplicates the last row (a warm-up duplicates as a normal set). */
    fun addSet(exerciseUiId: Long) {
        viewModelScope.launch {
            writes.withLock {
                val sets = workoutDao.sets(exerciseUiId)
                val last = sets.maxByOrNull { it.order }
                val order = (last?.order ?: -1) + 1
                val next = if (last != null) {
                    SetEntryEntity(
                        workoutExerciseId = exerciseUiId,
                        order = order,
                        kind = if (last.kind == SetKind.Warmup) SetKind.Normal else last.kind,
                        weightKg = last.weightKg,
                        reps = last.reps,
                    )
                } else {
                    SetEntryEntity(workoutExerciseId = exerciseUiId, order = order)
                }
                workoutDao.insertSet(next)
            }
        }
    }

    /**
     * `removeSet(_:in:)` — "Delete set" from the kind menu: removes the row and renumbers the rest.
     * Records are re-derived on Finish.
     */
    fun removeSet(setId: Long) {
        viewModelScope.launch {
            writes.withLock {
                val entity = workoutDao.set(setId) ?: return@launch
                if (hintSetId == setId) {
                    hintSetId = null
                    hintBest = null
                }
                workoutDao.deleteSet(entity)
                workoutDao.sets(entity.workoutExerciseId)
                    .sortedBy { it.order }
                    .forEachIndexed { index, set ->
                        if (set.order != index) workoutDao.updateSet(set.copy(order = index))
                    }
            }
        }
    }

    /** `remove(_:)` — deletes the exercise (sets cascade) and re-indexes the rest. */
    fun removeExercise(exerciseUiId: Long) {
        viewModelScope.launch {
            writes.withLock {
                if (expandedExerciseId == exerciseUiId) expandedExerciseId = null
                val rows = workoutDao.workoutExercises(workoutId)
                val target = rows.firstOrNull { it.id == exerciseUiId } ?: return@launch
                workoutDao.deleteWorkoutExercise(target)
                rows.filter { it.id != exerciseUiId }
                    .sortedBy { it.order }
                    .forEachIndexed { index, row ->
                        if (row.order != index) workoutDao.updateWorkoutExercise(row.copy(order = index))
                    }
            }
        }
    }

    // MARK: - Rest timer

    fun adjustRest(delta: Int) = restTimer.adjust(delta)

    fun skipRest() = restTimer.skip()

    fun finishRestIfElapsed() = restTimer.finishIfElapsed()

    /**
     * `nextTarget(after:in:)` + `RestTimerController.start`. The label the notification shows is
     * `"Set 2 of 3 · 80 × 8"`, built here because it needs the catalog.
     */
    private suspend fun startRestIfNeeded(
        section: WorkoutExerciseUi,
        row: SetRowUi,
        completed: SetEntryEntity,
    ) {
        val target = nextTarget(
            all = _state.value.exercises,
            section = section,
            row = row,
            fallback = SetValue(completed.weightKg, completed.reps),
            recordService = recordService,
            unit = unit,
        ) ?: return
        if (target.isDrop) return
        if (!appPrefs.restAutoStartOnce()) return
        upNext = target.upNext
        val label = strings.string(S.timer_setOf_n_n, target.upNext.setIndex, target.upNext.setCount)
        restTimer.start(
            seconds = section.restSeconds,
            exerciseName = target.upNext.exerciseName,
            nextSetLabel = label + " · " + Fmt.set(target.upNext.weightKg, target.upNext.reps, unit),
            workoutName = _state.value.name,
        )
    }

    // MARK: - Finish

    /**
     * `finish(now:)` — stamps the end now, so a kill on the summary can never stretch the workout,
     * marks the day the workout **started** attended when at least one set was done — any day,
     * scheduled or not (a rest day, an extra session, a make-up day), and the evening it began for
     * a session that runs past midnight — reports it to the backend, and shows the summary. The session keeps the workout (by id, not by `endedAt IS NULL`) until Done.
     * With nothing done the day is given back instead ([uncount]): a Finish after "Edit sets"
     * unticked every set leaves no attended day behind, here or on the server.
     * Records are re-derived for this workout's exercises, so a kind changed, a set unticked or
     * deleted after its tick leaves no stale PR on the summary or in history.
     *
     * The summary flag goes up **before** the stamp: in between, nothing may mistake a workout
     * with an `endedAt` and no summary for one to let go of.
     */
    fun finish() {
        if (showsDone || !isSessionWorkout) return
        showsDone = true
        session.setShowsSummary(true)
        graph?.let(::publish)
        viewModelScope.launch {
            restTimer.skip()
            val now = clock()
            workoutDao.finishWorkout(workoutId, now)
            recordService.rebuild(workoutDao.workoutExercises(workoutId).mapNotNull { it.exerciseId }.toSet())
            val day = dayOf(workoutDao.workout(workoutId)?.startedAt ?: now)
            if (workoutDao.completedSetCount(workoutId) > 0) {
                attendanceService.markAttended(day)
                reportAttendance.report(day, AttendanceStatus.Attended)
            } else {
                uncount(day)
            }
        }
    }

    /**
     * `reopen()` — "Edit sets" from the summary: the workout is back in progress. The end is
     * cleared **before** the summary flag drops, for the same reason as in [finish].
     */
    fun reopen() {
        // A back press while the summary slides away after Done must not un-finish the workout.
        if (!showsDone || !isSessionWorkout) return
        viewModelScope.launch {
            workoutDao.reopenWorkout(workoutId)
            showsDone = false
            session.setShowsSummary(false)
            graph?.let(::publish)
        }
    }

    /** This workout is still the session's — not one sliding away after Done or Discard. */
    private val isSessionWorkout: Boolean get() = session.activeWorkoutId.value == workoutId

    /** `commitFinish(session:)` — Done: release the session, which closes the full screen. The end is stamped already. */
    fun commitFinish() {
        if (isSessionWorkout) session.end()
    }

    /**
     * `discard(session:)` — drops an empty workout. The session lets go at once and deletes the
     * row once the full screen has animated out. The day it started is given back ([uncount]): a
     * Discard after Finish and "Edit sets" leaves no attended day behind.
     */
    fun discard() {
        if (!isSessionWorkout) return
        restTimer.skip()
        val startedAt = graph?.workout?.startedAt
        session.discard(workoutId)
        viewModelScope.launch {
            val start = startedAt ?: workoutDao.workout(workoutId)?.startedAt ?: return@launch
            uncount(dayOf(start))
        }
    }

    /**
     * This workout no longer counts on [day] — `AttendanceService.applyWorkoutDayChange(from: day,
     * to: nil, excluding: id)`: an attended [day] that no other finished workout keeps goes back
     * to what it would be without it (missed, planned or cleared), and the change is reported.
     * A day that is not attended, or that another finished workout keeps, is left alone.
     */
    private suspend fun uncount(day: LocalDate) {
        val today = dayOf(clock())
        val changes = WorkoutEditor.correctAttendance(day, null, workoutId, workoutDao, attendanceService, zone, today)
        WorkoutEditor.report(changes, attendanceService, reportAttendance)
    }

    private fun dayOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    // MARK: - Helpers

    companion object {
        /** @see foldLatestEarlierWorkoutRows */
        fun latestEarlierWorkoutRows(
            rows: List<CompletedSetRow>,
            excludingWorkoutId: String,
        ): List<CompletedSetRow> = foldLatestEarlierWorkoutRows(rows, excludingWorkoutId)

        /** @see foldCurrentExerciseIndex */
        fun currentExerciseIndex(exercises: List<WorkoutExerciseUi>, expandedId: Long?): Int =
            foldCurrentExerciseIndex(exercises, expandedId)

        /** @see foldCurrentExercise */
        fun currentExercise(exercises: List<WorkoutExerciseUi>, expandedId: Long?): WorkoutExerciseUi? =
            foldCurrentExercise(exercises, expandedId)
    }
}
