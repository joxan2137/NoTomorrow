package app.notomorrow.service

import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.prefs.AppPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for the workout in progress, shared by the full-screen workout, the mini
 * bar on every tab and the start guards — 1:1 port of
 * `NoTomorrow/Services/WorkoutSessionController.swift`.
 *
 * The workout itself lives in Room. This holds which one it is (persisted, by `Workout.id`),
 * whether the full screen is up or collapsed into the mini bar, whether its summary is showing,
 * and a rest-sheet request from a notification tap. The per-workout UI model is
 * `ActiveWorkoutViewModel`, keyed by the workout id and hosted by the tab shell, so collapsing and
 * expanding keep the open exercise, the PR hint, "up next" and the summary — where iOS keeps its
 * `ActiveWorkoutModel` on this class itself.
 */
class WorkoutSessionController(
    private val store: Store,
    private val workoutDao: WorkoutDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** Where the session is persisted: `UserDefaults` on iOS, [AppPrefs] here, a map in tests. */
    interface Store {
        suspend fun activeWorkoutId(): String?
        suspend fun setActiveWorkoutId(id: String?)
        suspend fun discarding(): Set<String>
        suspend fun setDiscarding(ids: Set<String>)
    }

    private val _activeWorkoutId = MutableStateFlow<String?>(null)

    /**
     * `activeWorkoutID` — the workout in progress, or the one whose summary is on screen (its
     * `endedAt` is stamped). `null` when nothing is running.
     */
    val activeWorkoutId: StateFlow<String?> = _activeWorkoutId.asStateFlow()

    private val _showsActiveWorkout = MutableStateFlow(false)

    /** `showsActiveWorkout` — full screen up (true) or collapsed into the mini bar (false). */
    val showsActiveWorkout: StateFlow<Boolean> = _showsActiveWorkout.asStateFlow()

    private val _wantsRestSheet = MutableStateFlow(false)

    /** `wantsRestSheet` — set by a rest-notification tap; the full screen opens the sheet and clears it. */
    val wantsRestSheet: StateFlow<Boolean> = _wantsRestSheet.asStateFlow()

    private val _showsSummary = MutableStateFlow(false)

    /**
     * `isShowingSummary` — the "DONE." summary is on screen: the workout is finished already, so
     * there is no mini bar and nothing to resume. iOS reads it off the session-owned model; the
     * model here is a view model the session cannot own, so the flag lives on the session.
     */
    val showsSummary: StateFlow<Boolean> = _showsSummary.asStateFlow()

    /** `isWorkoutInProgress` — drives the mini bar on every tab and the Dashboard's "Resume". */
    val isWorkoutInProgress: Flow<Boolean> =
        combine(_activeWorkoutId, _showsSummary) { id, summary -> isInProgress(id, summary) }
            .distinctUntilChanged()

    /** Workouts on their way out (deleted a moment after [discard]): never adopted again meanwhile. */
    private val discarding = MutableStateFlow<Set<String>>(emptySet())

    private val restored = CompletableDeferred<Unit>()
    private val writes = Mutex()

    init {
        scope.launch {
            runCatching { store.activeWorkoutId() }.getOrNull()?.let { id ->
                if (_activeWorkoutId.value == null) _activeWorkoutId.value = id
            }
            restored.complete(Unit)
        }
    }

    /** Suspends until the persisted id has been read back — start-up, routes and tests. */
    suspend fun awaitRestored() = restored.await()

    // MARK: - State

    val hasActiveWorkout: Boolean get() = _activeWorkoutId.value != null

    fun isWorkoutInProgressNow(): Boolean = isInProgress(_activeWorkoutId.value, _showsSummary.value)

    // MARK: - Lookups

    /**
     * `workout(in:)` — the session's workout whatever its state (in progress or on its summary).
     * Never adopts and never mutates.
     */
    suspend fun workout(): WorkoutEntity? = _activeWorkoutId.value?.let { workoutDao.workout(it) }

    /**
     * `activeWorkout(in:)` — the workout in progress. Adopts the newest unfinished workout when
     * the session lost track of it (cleared data, an older build) and lets go of one that has
     * finished. Never adopts while a summary is showing.
     */
    suspend fun activeWorkout(): WorkoutEntity? {
        awaitRestored()
        workout()?.let { if (it.endedAt == null) return it }
        if (_showsSummary.value) return null
        val skip = discarding.value
        val found = workoutDao.activeWorkouts().firstOrNull { it.id !in skip }
        if (_activeWorkoutId.value != found?.id) setActiveWorkoutId(found?.id)
        if (found == null) {
            _showsActiveWorkout.value = false
            _wantsRestSheet.value = false
        }
        return found
    }

    // MARK: - Actions

    /** `begin(_:)` — a new workout was started: it becomes the session's and opens full screen. */
    fun begin(workoutId: String) {
        setActiveWorkoutId(workoutId)
        _showsSummary.value = false
        _wantsRestSheet.value = false
        _showsActiveWorkout.value = true
    }

    /**
     * `expand(restSheet:)` — opens the workout in progress full screen (mini bar tap, "Resume", a
     * notification). [restSheet] also opens the rest sheet.
     */
    fun expand(restSheet: Boolean = false) {
        if (_activeWorkoutId.value == null) return
        _wantsRestSheet.value = restSheet
        _showsActiveWorkout.value = true
    }

    /**
     * `collapse(context:)` — hands the workout to the mini bar. Every set edit is written through
     * already, and the view model outlives the collapse, so nothing is pending.
     */
    fun collapse() {
        _showsActiveWorkout.value = false
    }

    /** `end()` — Done on the summary, or the workout was discarded: let go, close the full screen. */
    fun end() {
        setActiveWorkoutId(null)
        _showsActiveWorkout.value = false
        _wantsRestSheet.value = false
        _showsSummary.value = false
    }

    /** Finish shows the summary (true); "Edit sets" goes back to the table (false). */
    fun setShowsSummary(shows: Boolean) {
        _showsSummary.value = shows
    }

    /** The full screen has taken the rest-sheet request. */
    fun consumeRestSheet() {
        _wantsRestSheet.value = false
    }

    /**
     * `discard(_:context:)` — drops a workout nobody wants (no completed sets). The session lets go
     * at once; the row is deleted a moment later, after the full screen and the mini bar have
     * animated out, so nothing renders a deleted row. The pending delete is persisted, so a kill
     * inside that window is finished by [restore].
     */
    fun discard(workoutId: String): Job {
        discarding.value = discarding.value + workoutId
        persistDiscarding()
        if (_activeWorkoutId.value == workoutId) end()
        return scope.launch {
            delay(DISCARD_DELAY_MS)
            runCatching { workoutDao.deleteWorkoutById(workoutId) }
            discarding.value = discarding.value - workoutId
            persistDiscarding()
        }
    }

    // MARK: - Launch

    /**
     * `restore(in:)` — launch pass: finishes an interrupted discard, closes orphaned workouts, then
     * settles on the workout in progress, if any (a workout killed on its summary is finished
     * already and is let go). Never opens the full screen: a cold start with a workout running
     * shows the mini bar only.
     */
    suspend fun restore() {
        awaitRestored()
        deleteInterruptedDiscards()
        repairOrphans()
        activeWorkout()
    }

    /**
     * `repairOrphans(in:)` — keeps one unfinished workout (the session's own, else the newest).
     * The others, left by builds without the start guard, are closed: with completed sets they end
     * at their last completed set, without any they are deleted.
     */
    suspend fun repairOrphans() {
        val skip = discarding.value
        val open = workoutDao.activeWorkouts().filter { it.id !in skip }
        if (open.size <= 1) return
        val keep = open.firstOrNull { it.id == _activeWorkoutId.value }?.id ?: open.first().id
        for (workout in open) {
            if (workout.id == keep) continue
            val last = workoutDao.lastCompletedAt(workout.id)
            if (last != null) {
                workoutDao.finishWorkout(workout.id, maxOf(last, workout.startedAt))
            } else {
                workoutDao.deleteWorkoutById(workout.id)
            }
        }
    }

    private suspend fun deleteInterruptedDiscards() {
        val pending = runCatching { store.discarding() }.getOrDefault(emptySet())
        if (pending.isEmpty() || discarding.value.isNotEmpty()) return
        for (id in pending) {
            if (workoutDao.workout(id) == null) continue
            if (workoutDao.completedSetCount(id) != 0) continue
            if (_activeWorkoutId.value == id) end()
            workoutDao.deleteWorkoutById(id)
        }
        writes.withLock { runCatching { store.setDiscarding(emptySet()) } }
    }

    // MARK: - Persistence

    private fun setActiveWorkoutId(id: String?) {
        if (_activeWorkoutId.value == id) return
        _activeWorkoutId.value = id
        // Each write stores the value current when it runs, so two quick changes can never land
        // out of order.
        scope.launch { writes.withLock { runCatching { store.setActiveWorkoutId(_activeWorkoutId.value) } } }
    }

    private fun persistDiscarding() {
        scope.launch { writes.withLock { runCatching { store.setDiscarding(discarding.value) } } }
    }

    companion object {
        /** The 0.7 s between Discard and the delete, while the full screen slides away. */
        const val DISCARD_DELAY_MS = 700L

        /** A workout is in progress: the session has one and it is not on its summary. */
        fun isInProgress(activeWorkoutId: String?, showsSummary: Boolean): Boolean =
            activeWorkoutId != null && !showsSummary

        /** The DataStore half of [Store]: `nt.activeWorkoutId` and `nt.workout.discarding`. */
        fun prefsStore(prefs: AppPrefs): Store = object : Store {
            override suspend fun activeWorkoutId(): String? = prefs.activeWorkoutIdOnce()
            override suspend fun setActiveWorkoutId(id: String?) = prefs.setActiveWorkoutId(id)
            override suspend fun discarding(): Set<String> = prefs.workoutDiscardingOnce()
            override suspend fun setDiscarding(ids: Set<String>) = prefs.setWorkoutDiscarding(ids)
        }

        /**
         * Seconds the workout has been running — the header's ticking duration. Pure, so
         * the caller drives it from `ticker()` rather than from a timer of its own.
         */
        fun elapsedSeconds(startedAt: Long, now: Long = System.currentTimeMillis()): Double =
            maxOf(0L, now - startedAt) / 1000.0
    }
}
