package app.notomorrow.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.notomorrow.app.StoreLoader
import app.notomorrow.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The four home-screen widgets (`docs/widgets.md`). */
enum class WidgetKind {
    QuickLog,
    Calendar,
    Week,
    Rest;

    fun widget(): GlanceAppWidget = when (this) {
        QuickLog -> QuickLogWidget()
        Calendar -> FuelCalendarWidget()
        Week -> WeekWidget()
        Rest -> BreakTimerWidget()
    }
}

/**
 * Keeps the widgets current — the Android side of `WidgetSync` (`docs/widgets.md`, "Data flow"):
 *
 * - a Room invalidation flow over the tables the widgets read, debounced 0.5 s;
 * - the rest timer's state (start, ±15, skip, the end), plus a slow tick for the draining ring
 *   while a rest runs and the end of the "just ended" window two minutes after it;
 * - local midnight (`updatePeriodMillis` of 30 min is the floor when the process is gone).
 *
 * A Glance session outlives a single update and does not call `provideGlance` again, so every
 * widget reloads its data whenever its [revision] moves; [refresh] bumps it before `updateAll`.
 */
object WidgetUpdater {

    private const val TAG = "WidgetUpdater"
    private const val DEBOUNCE_MS = 500L

    /** The draining ring of a running rest is redrawn this often (the countdown itself is live). */
    private const val RING_TICK_MS = 10_000L

    /** "Just ended" lasts this long after a rest runs out. */
    const val JUST_ENDED_MS = 2 * 60_000L

    /** What each table change can move. `routine`, `food_item` and `bro_pairing` are names the widgets show. */
    private val TABLES: Map<String, Set<WidgetKind>> = mapOf(
        "meal_entry" to setOf(WidgetKind.QuickLog, WidgetKind.Calendar),
        "food_item" to setOf(WidgetKind.QuickLog),
        "workout" to setOf(WidgetKind.Calendar, WidgetKind.Week),
        "set_entry" to setOf(WidgetKind.Calendar, WidgetKind.Week),
        "attendance_record" to setOf(WidgetKind.Week),
        "gym_schedule" to setOf(WidgetKind.Week),
        "routine" to setOf(WidgetKind.Week),
        "bro_pairing" to setOf(WidgetKind.Week),
        "user_profile" to WidgetKind.entries.toSet(),
    )

    private val revisions = WidgetKind.entries.associateWith { MutableStateFlow(0L) }

    private val started = AtomicBoolean(false)

    /** Moves whenever [kind]'s data may have changed; the widget's content reloads on it. */
    fun revision(kind: WidgetKind): StateFlow<Long> = revisions.getValue(kind).asStateFlow()

    /** Redraws every placed widget of [kinds] (all when empty) with freshly loaded data. */
    suspend fun refresh(context: Context, vararg kinds: WidgetKind) {
        val targets = if (kinds.isEmpty()) WidgetKind.entries else kinds.toList()
        for (kind in targets) {
            revisions.getValue(kind).update { it + 1 }
            try {
                kind.widget().updateAll(context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not update the $kind widget", e)
            }
        }
    }

    /**
     * Called once from `NoTomorrowApp.start()` after `container.load()`. Waits for the store (the
     * error screen's Try again may open it later), then follows Room, the rest timer and the clock.
     */
    @OptIn(FlowPreview::class)
    fun start(context: Context, container: AppContainer) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val scope = container.scope

        scope.launch {
            container.store.state.first { it == StoreLoader.State.Open }
            refresh(app)
            val pending = MutableStateFlow<Set<WidgetKind>>(emptySet())
            launch {
                container.db.invalidationTracker
                    .createFlow(*TABLES.keys.toTypedArray(), emitInitialState = false)
                    .collect { tables ->
                        val kinds = tables.flatMap { TABLES[it].orEmpty() }.toSet()
                        if (kinds.isNotEmpty()) pending.update { it + kinds }
                    }
            }
            pending.debounce(DEBOUNCE_MS).collect { kinds ->
                if (kinds.isEmpty()) return@collect
                pending.update { it - kinds }
                refresh(app, *kinds.toTypedArray())
            }
        }

        scope.launch {
            val timer = container.restTimer
            timer.awaitRestored()
            combine(timer.state, timer.lastEndedAt) { state, ended -> state.endAt to ended }
                .distinctUntilChanged()
                .collectLatest { (end, ended) ->
                    refresh(app, WidgetKind.Rest)
                    if (end != null) {
                        while (true) {
                            val left = end - System.currentTimeMillis()
                            if (left <= 0) break
                            delay(minOf(RING_TICK_MS, left))
                            refresh(app, WidgetKind.Rest)
                        }
                    } else if (ended != null) {
                        val wait = ended + JUST_ENDED_MS - System.currentTimeMillis()
                        if (wait > 0) {
                            // Survives this process: the redraw then reads idle.
                            WidgetRefreshWorker.enqueue(app, WidgetKind.Rest, wait)
                            delay(wait)
                            refresh(app, WidgetKind.Rest)
                        }
                    }
                }
        }

        scope.launch {
            while (true) {
                delay(millisToNextMidnight())
                refresh(app)
            }
        }
    }

    private fun millisToNextMidnight(zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.now(zone)
        val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone)
        // A second past, so "today" has certainly moved when the widgets reload.
        return (midnight.toInstant().toEpochMilli() - now.toInstant().toEpochMilli() + 1_000L).coerceAtLeast(1_000L)
    }
}

/**
 * A one-shot, process-death-proof redraw — the end of the Break timer's "just ended" window.
 * Unique per widget kind, so a newer end replaces an older pending one.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND)?.let { raw -> WidgetKind.entries.firstOrNull { it.name == raw } }
        if (kind == null) WidgetUpdater.refresh(applicationContext) else WidgetUpdater.refresh(applicationContext, kind)
        return Result.success()
    }

    companion object {
        private const val KEY_KIND = "kind"

        fun enqueue(context: Context, kind: WidgetKind, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(androidx.work.workDataOf(KEY_KIND to kind.name))
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("nt.widget.refresh.${kind.name}", ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}
