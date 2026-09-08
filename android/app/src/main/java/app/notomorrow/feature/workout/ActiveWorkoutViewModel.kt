package app.notomorrow.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.model.SetKind
import app.notomorrow.rest.RestTimerController
import app.notomorrow.rest.RestTimerState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * are cached in [previousRows] / [previousLast], reloaded exactly where iOS calls
 * `reloadPrevious()`.
 */
class ActiveWorkoutViewModel(
    private val workoutDao: WorkoutDao,
    private val recordService: RecordService,
    private val attendanceService: AttendanceService,
    private val restTimer: RestTimerController,
    private val session: WorkoutSessionController,
    private val appPrefs: AppPrefs,
    private val strings: NtStrings,
    private val zone: ZoneId = ZoneId.systemDefault(),
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
    private var showsDone = false

    /** Rows of the most recent earlier workout that had the exercise, keyed by exercise id. */
    private val previousRows = mutableMapOf<String, List<CompletedSetRow>>()
    private val previousLast = mutableMapOf<String, SetValue?>()

    private var workoutId: String? = null
    private var graph: WorkoutWithExercises? = null

    /** Outlives the view model, for the delayed discard alone. */
    private val discardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            val workout = session.activeWorkout()
            if (workout == null) {
                _state.value = ActiveWorkoutUiState(loading = false, missing = true)
                return@launch
            }
            workoutId = workout.id
            workoutDao.observeWorkoutWithExercises(workout.id).collectLatest { next ->
                if (next == null) {
                    _state.value = ActiveWorkoutUiState(loading = false, missing = true)
                    return@collectLatest
                }
                graph = next
                ensurePrevious(next)
                publish(next)
            }
        }
    }

    // MARK: - Previous values

    /**
     * `reloadPrevious()` — drops the caches so the next emission re-reads them. Called after the
     * exercise picker closes, exactly where iOS re-runs it.
     */
    fun reloadPrevious() {
        previousRows.clear()
        previousLast.clear()
        viewModelScope.launch {
            val current = graph ?: return@launch
            ensurePrevious(current)
            publish(current)
        }
    }

    /**
     * One query per exercise fills both caches: the rows of the latest *finished* earlier
     * workout that used it (`ex.usages … .max(by: startedAt)`) and the most recent completed
     * working set (`RecordService.lastSet(for:excluding:)`).
     */
    private suspend fun ensurePrevious(graph: WorkoutWithExercises) {
        val myId = graph.workout.id
        for (section in graph.sortedExercises) {
            val exerciseId = section.workoutExercise.exerciseId ?: continue
            if (previousRows.containsKey(exerciseId)) continue
            val rows = workoutDao.completedSetsForExercise(exerciseId)
            previousRows[exerciseId] = foldLatestEarlierWorkoutRows(rows, myId)
            previousLast[exerciseId] = RecordService.lastSet(rows, excludingWorkoutId = myId)
                ?.let { SetValue(it.weightKg, it.reps) }
        }
    }

    /**
     * `previous(for:in:)` — the same-row set of the last earlier workout, else the most recent
     * completed set of the exercise.
     */
    private fun previous(exerciseId: String?, index: Int): SetValue? {
        if (exerciseId == null) return null
        val rows = previousRows[exerciseId].orEmpty()
        if (index < rows.size) return SetValue(rows[index].weightKg, rows[index].reps)
        return previousLast[exerciseId]
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
                previous = { index -> previous(exerciseId, index) },
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
            finishedAt = finishedAt,
        )
    }

    // MARK: - Set mutations

    /** `toggleExpanded(_:)` — one exercise open at a time. */
    fun toggleExpanded(exerciseUiId: Long) {
        expandedExerciseId = if (expandedExerciseId == exerciseUiId) null else exerciseUiId
        graph?.let(::publish)
    }

    /**
     * Ticks a set: prefills empty numbers from the previous workout, stamps `completedAt`,
     * evaluates the records and starts the rest — unless the next set is a drop set or
     * `nt.rest.autoStart` is off (`ActiveWorkoutModel.complete`).
     */
    fun complete(setId: Long) {
        viewModelScope.launch {
            val current = _state.value
            val section = current.exercises.firstOrNull { ex -> ex.sets.any { it.id == setId } } ?: return@launch
            val row = section.sets.first { it.id == setId }
            val entity = workoutDao.set(setId) ?: return@launch

            var weight = entity.weightKg
            var reps = entity.reps
            if (weight == 0.0 && reps == 0) {
                row.previous?.let { weight = it.weightKg; reps = it.reps }
            }
            val completed = entity.copy(weightKg = weight, reps = reps, completedAt = System.currentTimeMillis())
            workoutDao.updateSet(completed)

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

    /** `uncomplete(_:)` — clears the stamp and both record flags. */
    fun uncomplete(setId: Long) {
        viewModelScope.launch {
            val entity = workoutDao.set(setId) ?: return@launch
            workoutDao.updateSet(entity.copy(completedAt = null, isPR = false, isSetRecord = false))
            if (hintSetId == setId) {
                hintSetId = null
                hintBest = null
            }
            graph?.let(::publish)
        }
    }

    fun setKind(setId: Long, kind: SetKind) {
        viewModelScope.launch {
            val entity = workoutDao.set(setId) ?: return@launch
            if (entity.kind == kind) return@launch
            workoutDao.updateSet(entity.copy(kind = kind))
        }
    }

    /** Write-through of the kg cell. No-op when the parsed value already matches. */
    fun setWeight(setId: Long, weightKg: Double) {
        viewModelScope.launch {
            val entity = workoutDao.set(setId) ?: return@launch
            if (entity.weightKg == weightKg) return@launch
            workoutDao.updateSet(entity.copy(weightKg = weightKg))
        }
    }

    /** Write-through of the reps cell. */
    fun setReps(setId: Long, reps: Int) {
        viewModelScope.launch {
            val entity = workoutDao.set(setId) ?: return@launch
            if (entity.reps == reps) return@launch
            workoutDao.updateSet(entity.copy(reps = reps))
        }
    }

    /** `addSet(to:)` — duplicates the last row (a warm-up duplicates as a normal set). */
    fun addSet(exerciseUiId: Long) {
        viewModelScope.launch {
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

    /** `remove(_:)` — deletes the exercise (sets cascade) and re-indexes the rest. */
    fun removeExercise(exerciseUiId: Long) {
        viewModelScope.launch {
            val id = workoutId ?: return@launch
            if (expandedExerciseId == exerciseUiId) expandedExerciseId = null
            val rows = workoutDao.workoutExercises(id)
            val target = rows.firstOrNull { it.id == exerciseUiId } ?: return@launch
            workoutDao.deleteWorkoutExercise(target)
            rows.filter { it.id != exerciseUiId }
                .sortedBy { it.order }
                .forEachIndexed { index, row ->
                    if (row.order != index) workoutDao.updateWorkoutExercise(row.copy(order = index))
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
        ) ?: return
        if (target.isDrop) return
        if (!appPrefs.restAutoStartOnce()) return
        upNext = target.upNext
        val label = strings.string(S.timer_setOf_n_n, target.upNext.setIndex, target.upNext.setCount)
        restTimer.start(
            seconds = section.restSeconds,
            exerciseName = target.upNext.exerciseName,
            nextSetLabel = label + " · " + Fmt.set(target.upNext.weightKg, target.upNext.reps),
            workoutName = _state.value.name,
        )
    }

    // MARK: - Finish

    /**
     * `private(set) var finishedAt` — the moment Finish was tapped. Deliberately **not** written
     * to Room: `endedAt IS NOT NULL` is the only definition of an active workout, so stamping it
     * here would end the session while the summary is still on screen (and a process death there
     * would lose a workout iOS would have resumed). `commitFinish()` writes it on Done.
     */
    private var finishedAt: Long? = null

    /**
     * `finish()` — remembers the finish time, marks today attended when it is a gym day and
     * swaps the summary in. Nothing is persisted about the workout itself.
     */
    fun finish() {
        viewModelScope.launch {
            restTimer.skip()
            val now = System.currentTimeMillis()
            finishedAt = now
            val today = LocalDate.now(zone)
            val schedule = attendanceService.schedule()
            if (schedule != null && schedule.isGymDay(Fmt.isoWeekday(today))) {
                attendanceService.markAttended(today)
            }
            showsDone = true
            graph?.let(::publish)
        }
    }

    /** `reopen()` — "Edit sets" from the summary: back to the table, nothing to revert. */
    fun reopen() {
        finishedAt = null
        showsDone = false
        graph?.let(::publish)
    }

    /** `commitFinish(session:)` — stamp the end, then release the session, which pops the screen. */
    fun commitFinish() {
        val id = workoutId
        if (id == null) {
            session.end()
            return
        }
        viewModelScope.launch {
            workoutDao.finishWorkout(id, finishedAt ?: System.currentTimeMillis())
            session.end()
        }
    }

    /**
     * `discard(session:)` — drops an empty workout. The row is deleted after the cover has
     * animated out so nothing renders a deleted model.
     */
    fun discard() {
        val id = workoutId ?: return
        restTimer.skip()
        session.end()
        // Deliberately **not** `viewModelScope`: the screen pops immediately and would cancel
        // the delay, leaving the empty workout behind.
        discardScope.launch {
            delay(DISCARD_DELAY_MS)
            workoutDao.deleteWorkoutById(id)
        }
    }

    // MARK: - Helpers

    companion object {
        /** `DispatchQueue.main.asyncAfter(deadline: .now() + 0.7)` in `discard`. */
        const val DISCARD_DELAY_MS = 700L

        /** @see foldLatestEarlierWorkoutRows */
        fun latestEarlierWorkoutRows(
            rows: List<CompletedSetRow>,
            excludingWorkoutId: String,
        ): List<CompletedSetRow> = foldLatestEarlierWorkoutRows(rows, excludingWorkoutId)

        /** @see foldCurrentExerciseIndex */
        fun currentExerciseIndex(exercises: List<WorkoutExerciseUi>, expandedId: Long?): Int =
            foldCurrentExerciseIndex(exercises, expandedId)
    }
}
