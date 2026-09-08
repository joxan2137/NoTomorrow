package app.notomorrow.service

import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.prefs.AppPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the id of the workout in progress so any tab can get back to it — 1:1 port of
 * `NoTomorrow/Services/WorkoutSessionController.swift`.
 *
 * The workout itself lives in Room (`workout.endedAt IS NULL` is the **only** definition
 * of "active"); this class owns nothing but the id, persisted to `nt.activeWorkoutId`,
 * and the flag that presents the full-screen cover.
 */
class WorkoutSessionController(
    private val prefs: AppPrefs,
    private val workoutDao: WorkoutDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _activeWorkoutId = MutableStateFlow<String?>(null)

    /** `activeWorkoutID` — `null` when nothing is running. */
    val activeWorkoutId: StateFlow<String?> = _activeWorkoutId.asStateFlow()

    private val _showsActiveWorkout = MutableStateFlow(false)

    /** `showsActiveWorkout` — drives the full-screen cover at the root. */
    val showsActiveWorkout: StateFlow<Boolean> = _showsActiveWorkout.asStateFlow()

    private val restored = CompletableDeferred<Unit>()

    init {
        scope.launch {
            runCatching { prefs.activeWorkoutIdOnce() }.getOrNull()?.let { id ->
                if (_activeWorkoutId.value == null) _activeWorkoutId.value = id
            }
            restored.complete(Unit)
        }
    }

    /** Suspends until the persisted id has been read back — start-up and tests. */
    suspend fun awaitRestored() = restored.await()

    /**
     * The workout in progress. Resolves the stored id and checks it is still unfinished;
     * otherwise **adopts** any unfinished workout (newest first), exactly as iOS's
     * fallback fetch.
     */
    suspend fun activeWorkout(): WorkoutEntity? {
        _activeWorkoutId.value?.let { id ->
            val workout = workoutDao.workout(id)
            if (workout != null && workout.endedAt == null) return workout
        }
        val fallback = workoutDao.newestActiveWorkout()
        if (fallback != null) setActiveWorkoutId(fallback.id) else if (_activeWorkoutId.value != null) {
            // The stored id points at a finished or deleted workout: forget it.
            setActiveWorkoutId(null)
        }
        return fallback
    }

    /** `begin(_:)` — remembers the workout and presents it. */
    fun begin(workoutId: String) {
        setActiveWorkoutId(workoutId)
        _showsActiveWorkout.value = true
    }

    /** `end()` — clears the id and dismisses the cover. */
    fun end() {
        setActiveWorkoutId(null)
        _showsActiveWorkout.value = false
    }

    /** Re-presents the workout that is already running (the "Resume" row). */
    fun show() {
        _showsActiveWorkout.value = true
    }

    /** Dismisses the cover without ending the workout (minimise). */
    fun hide() {
        _showsActiveWorkout.value = false
    }

    fun setShowsActiveWorkout(shows: Boolean) {
        _showsActiveWorkout.value = shows
    }

    private fun setActiveWorkoutId(id: String?) {
        if (_activeWorkoutId.value == id) return
        _activeWorkoutId.value = id
        scope.launch { runCatching { prefs.setActiveWorkoutId(id) } }
    }

    companion object {
        /**
         * Seconds the workout has been running — the header's ticking duration. Pure, so
         * the caller drives it from `ticker()` rather than from a timer of its own.
         */
        fun elapsedSeconds(startedAt: Long, now: Long = System.currentTimeMillis()): Double =
            maxOf(0L, now - startedAt) / 1000.0
    }
}
