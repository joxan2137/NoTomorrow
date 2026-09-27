package app.notomorrow.widget

import android.content.Context
import app.notomorrow.NoTomorrowApp
import app.notomorrow.di.AppContainer
import app.notomorrow.feature.dashboard.DashboardViewModel
import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.feature.fuel.FuelGoals
import app.notomorrow.model.TrainingGoal
import app.notomorrow.rest.RestTimerController
import app.notomorrow.rest.RestTimerState
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.Days
import app.notomorrow.service.NextSession
import app.notomorrow.service.WeekDay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Quick log: today's totals against the goal, and the quick foods. */
data class QuickLogData(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val goals: FuelGoals,
    val foods: List<QuickFood>,
) {
    /** `FuelModel.kcalLeft` — never below 0 (the ring is full then). */
    val kcalLeft: Double get() = maxOf(0.0, goals.kcal - kcal)
}

/** Fuel calendar: kcal per day key since the oldest column, trained days and the stat columns. */
data class CalendarData(
    val today: LocalDate,
    val kcalByDay: Map<LocalDate, Double>,
    val trainedDays: Set<LocalDate>,
    val kcalGoal: Double,
    val goal: TrainingGoal,
    val stats: FuelCalendar.Stats,
    /** Finished workouts with a completed set in the last 30 days, today included. */
    val sessions30: Int,
) {
    /** `FuelCalendar.level` for [day] — 0 for a day with nothing logged. */
    fun level(day: LocalDate): Int {
        val kcal = kcalByDay[day]
        return FuelCalendar.level(kcal ?: 0.0, kcalGoal, goal, hasEntries = kcal != null, isToday = day == today)
    }
}

/** Gym week: the Mon…Sun strip and the next session with the routine the Dashboard suggests. */
data class WeekData(
    val days: List<WeekDay>,
    val isPaired: Boolean,
    val today: LocalDate,
    val next: NextSession?,
    val routineName: String?,
) {
    val hasSchedule: Boolean get() = days.any { it.isGymDay }
}

/** Break timer: the controller's state, the end of the rest that just ran out and the default length. */
data class RestData(
    val state: RestTimerState,
    val endedAt: Long?,
    val defaultRestSeconds: Int,
)

/**
 * Everything the four widgets read, in one place — the Android side of `WidgetSync.makeSnapshot`.
 * Glance runs in the app's process, so this reads Room through the [AppContainer] directly
 * (`docs/widgets.md`, "Data flow"). Every loader returns `null` when the widget must show
 * `widget.setup`: the store cannot be opened, or onboarding has not run.
 */
object WidgetData {

    private const val LOAD_TIMEOUT_MS = 8_000L

    /**
     * The container once `NoTomorrowApp.start()` has loaded it (a widget can be the first thing a
     * cold process renders), or `null` when there is nothing to show yet.
     */
    suspend fun ready(context: Context): AppContainer? {
        val container = (context.applicationContext as NoTomorrowApp).container
        // `load()` opens the store and reads `nt.hasOnboarded`; if it never finishes, open the store
        // ourselves (idempotent, behind the loader's lock) and fall back on the profile row.
        val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) { container.appState.isLoaded.first { it } } != null
        if (!container.store.isOpen && !container.store.open()) return null
        val onboarded = if (loaded) {
            container.appState.hasOnboarded.value
        } else {
            runCatching { container.appPrefs.hasOnboardedOnce() }.getOrDefault(false)
        }
        if (!onboarded) return null
        return runCatching { container.db.profileDao().profile() }.getOrNull()?.let { container }
    }

    suspend fun quickLog(
        context: Context,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): QuickLogData? {
        val container = ready(context) ?: return null
        val db = container.db
        val profile = db.profileDao().profile()
        val today = Days.date(now, zone)
        val bounds = FuelCalendar.storedDayBounds(today, zone)
        val todays = db.mealDao().observeDayRange(bounds.lower, bounds.upper).first()
        val since = FuelCalendar.storedDayBounds(today.minusDays(QuickFoods.WINDOW_DAYS), zone).lower
        // `observeDayRange` is the one read that carries the food (for its name); open-ended here.
        val candidates = db.mealDao().observeDayRange(since, Long.MAX_VALUE).first().map { row ->
            QuickFoods.Candidate(
                foodId = row.entry.foodId,
                name = row.displayName,
                grams = row.entry.grams,
                kcal = row.entry.kcal,
                protein = row.entry.proteinG,
                carbs = row.entry.carbsG,
                fat = row.entry.fatG,
                isAIEstimate = row.entry.isAIEstimate,
                day = row.entry.day,
                loggedAt = row.entry.loggedAt,
            )
        }
        return QuickLogData(
            kcal = todays.sumOf { it.entry.kcal },
            protein = todays.sumOf { it.entry.proteinG },
            carbs = todays.sumOf { it.entry.carbsG },
            fat = todays.sumOf { it.entry.fatG },
            goals = profile?.let {
                FuelGoals(
                    kcal = it.calorieGoal.toDouble(),
                    protein = it.proteinGoalG.toDouble(),
                    carbs = it.carbsGoalG.toDouble(),
                    fat = it.fatGoalG.toDouble(),
                )
            } ?: FuelGoals.Fallback,
            foods = QuickFoods.pick(candidates, now, zone),
        )
    }

    suspend fun calendar(
        context: Context,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): CalendarData? {
        val container = ready(context) ?: return null
        val db = container.db
        val profile = db.profileDao().profile()
        val today = Days.date(now, zone)
        val layout = FuelCalendar.layout(today)
        val from = FuelCalendar.storedDayBounds(layout.start, zone).lower
        val kcalByDay = FuelCalendar.kcalByDay(db.mealDao().observeKcalByDaySince(from).first(), zone)
        val windowStart = minOf(layout.start, today.minusDays(29))
        val starts = db.workoutDao().countedWorkoutStartsSince(Days.millis(windowStart, zone))
        val thirtyDaysAgo = Days.millis(today.minusDays(29), zone)
        val kcalGoal = (profile?.calorieGoal ?: 0).toDouble()
        val goal = profile?.goal ?: TrainingGoal.BuildMuscle
        return CalendarData(
            today = today,
            kcalByDay = kcalByDay,
            trainedDays = starts.map { Days.date(it, zone) }.toSet(),
            kcalGoal = kcalGoal,
            goal = goal,
            stats = FuelCalendar.stats(kcalByDay, today, kcalGoal, goal),
            sessions30 = starts.count { it in thirtyDaysAgo..now },
        )
    }

    suspend fun week(
        context: Context,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeekData? {
        val container = ready(context) ?: return null
        val db = container.db
        val today = Days.date(now, zone)
        val schedule = db.scheduleDao().schedule()
        val routines = db.routineDao().observeRoutines().first()
        val lastRoutineWorkout = db.workoutDao().observeLastRoutineWorkoutName().first()
        // The Dashboard's rule: signed out on the real backend counts as solo.
        val isPaired = !container.authStore.needsSignIn.value && db.broPairingDao().pairing() != null
        return WeekData(
            days = container.attendanceService.currentWeek(today),
            isPaired = isPaired,
            today = today,
            next = AttendanceService.nextSession(schedule, Instant.ofEpochMilli(now), zone),
            routineName = DashboardViewModel.suggestedRoutine(routines, listOfNotNull(lastRoutineWorkout))?.name,
        )
    }

    suspend fun rest(context: Context): RestData? {
        val container = ready(context) ?: return null
        val controller = RestTimerController.get(context)
        controller.awaitRestored()
        val profile = container.db.profileDao().profile()
        return RestData(
            state = controller.state.value,
            endedAt = controller.lastEndedAt.value,
            defaultRestSeconds = profile?.defaultRestSeconds ?: DEFAULT_REST_SECONDS,
        )
    }

    private const val DEFAULT_REST_SECONDS = 90
}
