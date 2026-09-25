package app.notomorrow.rest

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.notomorrow.data.prefs.RestTimerPrefs
import app.notomorrow.data.prefs.RestTimerRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Rest timer that survives backgrounding and process death — a 1:1 port of
 * `NoTomorrow/Services/RestTimerController.swift`.
 *
 * Truth is an absolute [RestTimerState.endAt] persisted through [RestTimerPrefs];
 * the UI derives the remaining time from it and **never runs a timer of its own**
 * (`docs/architecture.md:107`). The four delivery pieces around it are the
 * ongoing notification ([RestTimerNotifier]), the wake-up alarm ([RestAlarms]),
 * the broadcast receiver ([RestTimerReceiver]) and the boot re-scheduler
 * ([BootRescheduleReceiver]).
 *
 * Takes a plain [Context]; it deliberately knows nothing about `di/` so a
 * broadcast landing in a fresh process can reach it through [get].
 */
class RestTimerController(
    context: Context,
    private val prefs: RestTimerPrefs = RestTimerPrefs(context),
    private val alarms: RestAlarms = RestAlarms(context),
    private val notifier: RestTimerNotifier = RestTimerNotifier(context),
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val haptics = RestHaptics(appContext)
    private val chime = RestChime(appContext)

    private val _state = MutableStateFlow(RestTimerState())
    val state: StateFlow<RestTimerState> = _state.asStateFlow()

    private val _lastEndedAt = MutableStateFlow<Long?>(null)

    /**
     * When the last rest ran out on its own (its `endAt`), until the next start or skip — the Break
     * timer widget's "just ended" state (`docs/widgets.md`). In memory only: after a process death
     * the widget simply reads idle.
     */
    val lastEndedAt: StateFlow<Long?> = _lastEndedAt.asStateFlow()

    private val restored = CompletableDeferred<Unit>()

    /**
     * Where the user is when a rest runs out — `NotificationRouter.connect(isWorkoutOnScreen:)`
     * on iOS. Wired by `AppContainer`; a receiver in a fresh process leaves both false, which is
     * right: nothing of the app is on screen then.
     */
    @Volatile
    private var isAppInForeground: () -> Boolean = { false }

    @Volatile
    private var isWorkoutOnScreen: () -> Boolean = { false }

    fun connect(isAppInForeground: () -> Boolean, isWorkoutOnScreen: () -> Boolean) {
        this.isAppInForeground = isAppInForeground
        this.isWorkoutOnScreen = isWorkoutOnScreen
    }

    init {
        scope.launch {
            restore()
            // One writer, started only after the restore, so state changes reach
            // DataStore in order and nothing overwrites the persisted record with
            // defaults during start-up.
            _state.collect { current -> runCatching { prefs.write(current.toRecord()) } }
        }
    }

    // MARK: Control

    /**
     * `total = max(5, seconds)`, `endAt = now + total`; persists, arms the alarm,
     * posts the notification and taps the haptic — exactly as iOS `start(seconds:…)`.
     */
    fun start(seconds: Int, exerciseName: String, nextSetLabel: String, workoutName: String) {
        val total = max(5, seconds)
        val next = RestTimerState(
            endAt = System.currentTimeMillis() + total * 1000L,
            totalSeconds = total,
            exerciseName = exerciseName,
            nextSetLabel = nextSetLabel,
            workoutName = workoutName,
        )
        _lastEndedAt.value = null
        _state.value = next
        // A delivered "Rest is over" belongs to the rest before this one.
        notifier.cancelDone()
        commit(next)
        haptics.tap()
    }

    /**
     * `endAt = max(now + 1 s, endAt + delta)`, `total = max(5, total + delta)`.
     * A no-op when nothing is pending, matching iOS's `guard let end = endDate`.
     */
    fun adjust(delta: Int) {
        val current = _state.value
        val end = current.endAt ?: return
        val next = current.copy(
            endAt = max(System.currentTimeMillis() + 1000L, end + delta * 1000L),
            totalSeconds = max(5, current.totalSeconds + delta),
        )
        _state.value = next
        commit(next)
        haptics.tap()
    }

    /** Clears the rest, cancels the alarm and both notifications. No haptic on iOS. */
    fun skip() {
        val next = _state.value.copy(endAt = null)
        _lastEndedAt.value = null
        _state.value = next
        alarms.cancel()
        notifier.cancelRunning()
        notifier.cancelDone()
    }

    /**
     * Called by the UI when remaining hits zero. Only fires when the rest really elapsed: the
     * success haptic, plus the "Rest is over" alert while the workout is collapsed (iOS shows its
     * notification as a banner then, and nothing but the haptic over the full workout).
     */
    fun finishIfElapsed() {
        val current = _state.value
        val end = current.endAt ?: return
        if (end > System.currentTimeMillis()) return
        clearElapsed(endedAt = end)
        announceEnd(current, restEndAlert(isAppInForeground(), isWorkoutOnScreen()))
    }

    // MARK: Broadcast entry points (fresh-process safe)

    /**
     * `ACTION_END`: the alarm fired. Clears the state, then alerts by where the user is: the
     * notification when the app is away; in the foreground what [finishIfElapsed] does, so an
     * exact alarm that beats the in-app watcher by a few hundred ms changes nothing.
     */
    suspend fun handleAlarmFired() {
        awaitRestored()
        val current = _state.value
        val foreground = isAppInForeground()
        // In the foreground the watcher got there first: the rest is closed and announced.
        if (foreground && current.endAt == null) return
        // A cold start already dropped the past `endAt` (restore); the alarm's own time stands in.
        clearElapsed(endedAt = current.endAt ?: System.currentTimeMillis())
        announceEnd(current, restEndAlert(foreground, isWorkoutOnScreen()))
    }

    /**
     * Over the full-screen workout the notification (and with it the channel's chime) is
     * suppressed, so the chime plays in-app — `RestChime` on iOS (`docs/widgets.md`, "The rest chime").
     */
    private fun announceEnd(rest: RestTimerState, alert: RestEndAlert) {
        if (alert != RestEndAlert.Notification) haptics.success()
        if (alert == RestEndAlert.Haptic) chime.play()
        if (alert != RestEndAlert.Haptic) notifier.notifyDone(rest.exerciseName, rest.nextSetLabel)
    }

    /** `ACTION_PLUS15` from the notification action. */
    suspend fun handlePlus15() {
        awaitRestored()
        adjust(15)
    }

    /** `ACTION_SKIP` from the notification action. */
    suspend fun handleSkip() {
        awaitRestored()
        skip()
    }

    /** `BOOT_COMPLETED`: re-arm from persisted state, or tidy up a rest that expired while off. */
    suspend fun rescheduleAfterBoot() {
        awaitRestored()
        val current = _state.value
        val end = current.endAt ?: return
        alarms.schedule(end)
        notifier.showRunning(end, current.totalSeconds, current.exerciseName, current.nextSetLabel)
    }

    suspend fun awaitRestored() {
        restored.await()
    }

    // MARK: Persistence

    /** Reads the persisted record; a past `endAt` is discarded and any stray notification cancelled. */
    private suspend fun restore() {
        val record = try {
            prefs.read()
        } catch (_: Exception) {
            RestTimerRecord()
        }
        val end = record.endAt?.takeIf { it > System.currentTimeMillis() }
        _state.value = RestTimerState(
            endAt = end,
            totalSeconds = max(5, record.totalSeconds),
            exerciseName = record.exerciseName,
            nextSetLabel = record.nextSetLabel,
            workoutName = record.workoutName,
        )
        if (end == null) {
            alarms.cancel()
            notifier.cancelRunning()
        }
        restored.complete(Unit)
    }

    /** Persist + schedule + notify, in that order. */
    private fun commit(next: RestTimerState) {
        val end = next.endAt ?: return
        alarms.schedule(end)
        notifier.showRunning(end, next.totalSeconds, next.exerciseName, next.nextSetLabel)
    }

    private fun clearElapsed(endedAt: Long) {
        _lastEndedAt.value = endedAt
        _state.value = _state.value.copy(endAt = null)
        alarms.cancel()
        notifier.cancelRunning()
    }

    companion object {
        @Volatile
        private var instance: RestTimerController? = null

        /**
         * Process-wide singleton. `AppContainer` reads it, and so do the receivers,
         * which may run before any container exists.
         */
        fun get(context: Context): RestTimerController =
            instance ?: synchronized(this) {
                instance ?: RestTimerController(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * How the end of a rest reaches the user — iOS's `NotificationRouter.presentation(forNotification:
 * workoutOnScreen:)` plus the in-app `Haptics.success()`.
 */
enum class RestEndAlert {
    /** The full workout is on screen: the success haptic says it. */
    Haptic,

    /** The app is open but the workout is collapsed: the haptic and the heads-up with sound. */
    HapticAndNotification,

    /** The app is not on screen: the notification alone. */
    Notification,
}

fun restEndAlert(appInForeground: Boolean, workoutOnScreen: Boolean): RestEndAlert = when {
    !appInForeground -> RestEndAlert.Notification
    workoutOnScreen -> RestEndAlert.Haptic
    else -> RestEndAlert.HapticAndNotification
}

/**
 * Derived rest-timer view state. `isRunning`, `remaining` and `progress` are
 * computed from [endAt] against a caller-supplied `now`, so the UI recomputes
 * them off the shared [ticker] rather than accumulating elapsed time.
 */
data class RestTimerState(
    val endAt: Long? = null,
    val totalSeconds: Int = RestTimerRecord.DEFAULT_TOTAL_SECONDS,
    val exerciseName: String = "",
    val nextSetLabel: String = "",
    val workoutName: String = "",
) {
    fun isRunning(now: Long = System.currentTimeMillis()): Boolean = (endAt ?: 0L) > now

    /** Seconds left, never negative — iOS `remaining`. */
    fun remaining(now: Long = System.currentTimeMillis()): Double =
        max(0.0, ((endAt ?: now) - now) / 1000.0)

    /** Filled fraction, iOS `progress` = `1 - remaining / total`. */
    fun progress(now: Long = System.currentTimeMillis()): Double =
        if (totalSeconds > 0) 1.0 - remaining(now) / totalSeconds.toDouble() else 1.0

    /** Draining fraction used by the ring in `RestPillView` / `RestTimerView`. */
    fun fractionRemaining(now: Long = System.currentTimeMillis()): Double =
        if (totalSeconds > 0) remaining(now) / totalSeconds.toDouble() else 0.0

    internal fun toRecord(): RestTimerRecord = RestTimerRecord(
        endAt = endAt,
        totalSeconds = totalSeconds,
        exerciseName = exerciseName,
        nextSetLabel = nextSetLabel,
        workoutName = workoutName,
    )
}

/**
 * Wall-clock-aligned ticker — the `TimelineView(.periodic(from:.now, by:))`
 * analogue. Emits `System.currentTimeMillis()` immediately, then once per
 * [periodMs] boundary, so every ticking label on screen flips on the same tick
 * and no drift accumulates. Shared by the rest pill, the rest sheet and the
 * workout elapsed clocks.
 */
fun ticker(periodMs: Long = 1_000L): Flow<Long> = flow {
    require(periodMs > 0) { "periodMs must be positive" }
    while (true) {
        val now = System.currentTimeMillis()
        emit(now)
        val toBoundary = periodMs - (now % periodMs)
        delay(if (toBoundary <= 0L) periodMs else toBoundary)
    }
}

/**
 * The rest timer's own haptics. iOS fires `UIImpactFeedbackGenerator(.light)` on
 * start/±15 and `UINotificationFeedbackGenerator(.success)` when the rest ends;
 * neither has a view to hang `performHapticFeedback` on here, so this goes
 * through the vibrator's predefined effects (`VIBRATE` is declared).
 */
internal class RestHaptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** `Haptics.tap()` — light impact. */
    fun tap() = play(VibrationEffect.EFFECT_TICK)

    /** `Haptics.success()` — the end-of-rest confirmation. */
    fun success() = play(VibrationEffect.EFFECT_HEAVY_CLICK)

    private fun play(effectId: Int) {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
        } catch (_: Exception) {
            // Some OEM vibrators reject predefined effects; a missing tick is cosmetic.
        }
    }
}
