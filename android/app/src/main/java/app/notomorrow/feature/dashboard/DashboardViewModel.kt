package app.notomorrow.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.app.AppState
import app.notomorrow.data.dao.AttendanceDao
import app.notomorrow.data.dao.BroPairingDao
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.dao.ScheduleDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.BroPairingEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.feature.workout.WorkoutStarter
import app.notomorrow.model.AppTab
import app.notomorrow.model.Participant
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.BroService
import app.notomorrow.service.Days
import app.notomorrow.service.DayState
import app.notomorrow.service.RoutineSeeder
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.Localizer
import app.notomorrow.util.S
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The Dashboard's state and write side — the port of `DashboardModel`
 * (`Features/Dashboard/DashboardModel.swift`) plus the eight `@Query`s `DashboardScreen`
 * declares in its initialiser.
 *
 * iOS re-creates the inner screen (and therefore its day-scoped queries) whenever the
 * calendar day changes; here [setDay] feeds a `flatMapLatest`, which re-subscribes the
 * week and meal queries for the new day and leaves everything else alone.
 *
 * `nextSession` is deliberately **not** re-derived on a timer: SwiftUI recomputes it only
 * when a query or the day changes, and so does this.
 */
class DashboardViewModel(
    private val profileDao: ProfileDao,
    private val scheduleDao: ScheduleDao,
    private val pairingDao: BroPairingDao,
    private val routineDao: RoutineDao,
    private val attendanceDao: AttendanceDao,
    private val mealDao: MealDao,
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
    private val attendance: AttendanceService,
    private val bro: BroService,
    private val routineSeeder: RoutineSeeder,
    private val session: WorkoutSessionController,
    private val appState: AppState,
    private val needsSignIn: StateFlow<Boolean>,
    private val strings: Localizer,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val stores = WorkoutStarter.Stores(routineDao, workoutDao, exerciseDao, profileDao)

    private val day = MutableStateFlow(LocalDate.now(zone))
    private val isConfirming = MutableStateFlow(false)
    private val showsCantMakeIt = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Live> = day.flatMapLatest { today ->
        val weekStart = Fmt.startOfIsoWeek(today)
        val from = Days.millis(weekStart, zone)
        val to = Days.millis(weekStart.plusDays(7), zone)
        combine(
            attendanceDao.observeRange(from, to),
            todayMeals(mealDao, today, zone),
            workoutDao.observeFinishedWorkoutsWithExercises(limit = 1).map { it.firstOrNull() },
            // `session.isWorkoutInProgress`: not "any unfinished row" — a workout on its summary
            // or being discarded is not one to resume.
            session.isWorkoutInProgress,
            // `DashboardView.trainedToday`: a finished workout with a completed set started today.
            workoutDao.observeCountedWorkoutsBetween(Days.millis(today, zone), Days.millis(today.plusDays(1), zone))
                .map { it > 0 },
        ) { records, meals, lastWorkout, hasActive, trainedToday ->
            Live(today, records, meals, lastWorkout, hasActive, trainedToday)
        }
    }

    private val basis: Flow<Basis> = combine(
        profileDao.observeProfile(),
        scheduleDao.observeSchedule(),
        pairingDao.observePairing(),
        routineDao.observeRoutines(),
        workoutDao.observeLastRoutineWorkoutName(),
    ) { profile, schedule, pairing, routines, lastRoutineWorkout ->
        Basis(profile, schedule, pairing, routines, listOfNotNull(lastRoutineWorkout))
    }

    private val local: Flow<Local> = combine(
        bro.partner,
        needsSignIn,
        isConfirming,
        showsCantMakeIt,
    ) { partner, signedOut, confirming, cantMakeIt ->
        Local(partner?.name, signedOut, confirming, cantMakeIt)
    }

    val uiState: StateFlow<DashboardUiState> =
        combine(basis, live, local) { b, l, c -> build(b, l, c, zone = zone) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    // MARK: - Day boundary

    /** Called by the screen when `ACTION_DATE_CHANGED` (or a resume check) moves the day. */
    fun setDay(value: LocalDate) {
        if (day.value != value) day.value = value
    }

    // MARK: - Lifecycle

    /**
     * `DashboardScreen.onAppear` — resolve every past gym day that was left `planned` and
     * make sure the starter routines exist.
     */
    suspend fun onAppear() {
        runCatching { attendance.sweepPastPlanned(LocalDate.now(zone)) }
        runCatching { routineSeeder.seedIfNeeded() }
    }

    /**
     * `DashboardModel.runBroRefreshLoop` — refresh now, then every 15 s for as long as the
     * screen is on. Cancelled with the caller's `LaunchedEffect`, the `.task` analogue.
     */
    suspend fun runBroRefreshLoop() {
        bro.refresh()
        while (currentCoroutineContext().isActive) {
            delay(BRO_REFRESH_MILLIS)
            if (!currentCoroutineContext().isActive) return
            bro.refresh()
        }
    }

    // MARK: - Actions

    /** `DashboardModel.confirmToday` — "I'm in". */
    fun confirm() {
        if (isConfirming.value) return
        viewModelScope.launch {
            isConfirming.value = true
            try {
                val paired = uiState.value.isPaired
                attendance.markConfirmed(LocalDate.now(zone))
                if (paired) {
                    bro.confirmToday()
                    bro.refresh()
                }
            } finally {
                isConfirming.value = false
            }
        }
    }

    /** A start is being resolved: a second tap in that window is dropped, never a second workout. */
    private var starting = false

    /**
     * `DashboardModel.startWorkout` — starts the suggested routine through the shared
     * [WorkoutStarter] path (the Train tab's), or brings back the workout already in progress.
     * Either way the full screen opens over Today: no tab switch.
     */
    fun startWorkout() {
        if (starting) return
        starting = true
        viewModelScope.launch {
            try {
                if (session.activeWorkout() != null) {
                    session.expand()
                    return@launch
                }
                val request = uiState.value.suggestedRoutineId
                    ?.let { WorkoutStarter.Request.Routine(it) }
                    ?: WorkoutStarter.Request.Empty(strings.string(S.workout_defaultName))
                WorkoutStarter.start(request, stores, session)
            } finally {
                starting = false
            }
        }
    }

    /** The avatar opens the modal Settings sheet (hoisted on `AppState`). */
    fun openSettings() {
        appState.showsSettings.value = true
    }

    fun showCantMakeIt() {
        showsCantMakeIt.value = true
    }

    /**
     * Closing the sheet without sending — `CantMakeItSheet`'s "Never mind" and the swipe
     * down. iOS's `dismiss()` alone: no partner round-trip.
     */
    fun dismissCantMakeIt() {
        showsCantMakeIt.value = false
    }

    /**
     * `DashboardScreen`'s `onDone` closure (`DashboardView.swift:129-131`), which
     * `CantMakeItSheet.send()` calls after a successful send and nothing else does.
     */
    fun onCantMakeItSent() {
        viewModelScope.launch { bro.refresh() }
    }

    /** The Fuel summary row: Fuel always opens on today (`AppState.openFuelToday`). */
    fun selectFuelTab() = appState.openFuelToday()

    fun selectTrainTab() = appState.select(AppTab.Train)

    companion object {

        /** `DashboardModel.runBroRefreshLoop` sleeps 15 s between refreshes. */
        const val BRO_REFRESH_MILLIS: Long = 15_000L

        /**
         * The Fuel row's entries for [today]: the stored-day range the Fuel tab and the History
         * grid read ([FuelCalendar.storedDayBounds]), not the exact midnight, so an entry stored
         * at another zone's midnight counts here exactly when Fuel lists it on today.
         */
        fun todayMeals(mealDao: MealDao, today: LocalDate, zone: ZoneId): Flow<List<MealEntryEntity>> {
            val bounds = FuelCalendar.storedDayBounds(today, zone)
            return mealDao.observeDayRange(bounds.lower, bounds.upper).map { rows -> rows.map { it.entry } }
        }

        /**
         * `DashboardModel.suggestedRoutine(routines:recentWorkoutNames:)` — the routine after the
         * one done most recently, wrapping around; the first routine when no finished workout came
         * from a routine. [recentWorkoutNames] is newest first; ad-hoc workouts (no matching
         * routine) are skipped, so they do not shift the rotation.
         */
        fun suggestedRoutine(routines: List<RoutineEntity>, recentWorkoutNames: List<String>): RoutineEntity? {
            if (routines.isEmpty()) return null
            for (name in recentWorkoutNames) {
                val index = routines.indexOfFirst { it.name == name }
                if (index >= 0) return routines[(index + 1) % routines.size]
            }
            return routines.first()
        }

        /**
         * The whole derivation, as one pure function of the three query groups — the
         * `DashboardScreen` computed properties in `DashboardView.swift:161-185`.
         */
        internal fun build(
            basis: Basis,
            live: Live,
            local: Local,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = LocaleProvider.current(),
        ): DashboardUiState {
            val day = live.day
            val week = AttendanceService.currentWeek(basis.schedule, live.weekRecords, day, zone)
            val today = week.firstOrNull { it.isToday }

            // Signed out on the real backend counts as solo: the bro row must not show a
            // stale partner (`DashboardView.swift:164`).
            val isPaired = !local.needsSignIn && (local.partnerName != null || basis.pairing != null)
            val partnerName = if (isPaired) local.partnerName ?: basis.pairing?.partnerName else null

            val next = AttendanceService.nextSession(basis.schedule, now, zone)
            val session = next?.let {
                DashboardSession(
                    at = it.at,
                    date = it.day,
                    minuteOfDay = it.minuteOfDay,
                    isToday = it.day == day,
                )
            }

            val routine = suggestedRoutine(basis.routines, basis.recentRoutineWorkoutNames)
            val myState = today?.myState ?: DayState.Rest
            val sessionIsToday = session?.isToday == true

            return DashboardUiState(
                day = day,
                avatarInitial = basis.profile?.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DashboardUiState.FALLBACK_INITIAL,
                week = week,
                session = session,
                routineName = sessionRoutineName(sessionIsToday, live.lastWorkout, day, zone) ?: routine?.name,
                suggestedRoutineId = routine?.id,
                sessionDone = sessionDone(sessionIsToday, myState, live.trainedToday),
                isPaired = isPaired,
                partnerName = partnerName,
                myState = myState,
                myTime = record(live.weekRecords, Participant.Me, day, zone)?.let { Instant.ofEpochMilli(it.updatedAt) },
                partnerState = today?.partnerState ?: DayState.Rest,
                partnerTime = record(live.weekRecords, Participant.Partner, day, zone)
                    ?.let { Instant.ofEpochMilli(it.updatedAt) },
                hasActiveWorkout = live.hasActiveWorkout,
                trainedToday = live.trainedToday,
                isConfirming = local.isConfirming,
                totals = FuelTotals.of(live.meals),
                goals = FuelGoals.of(basis.profile),
                lastSession = live.lastWorkout?.let { lastSession(it, locale) },
                units = basis.profile?.units ?: WeightUnit.Kg,
                showsCantMakeIt = local.showsCantMakeIt,
            )
        }

        /**
         * Today's session is behind me: the day is attended, or a finished workout with a completed
         * set started today. The card then says so instead of counting down to a session already
         * trained.
         */
        internal fun sessionDone(sessionIsToday: Boolean, myState: DayState, trainedToday: Boolean): Boolean =
            sessionIsToday && (myState == DayState.Attended || trainedToday)

        /**
         * The card's routine for a session today that already has a finished workout: that workout's
         * name, the Kolega tab's rule (`BroDerived.routineName`, the workout done that day), so both
         * tabs name today's session alike. `null` otherwise — the card shows the suggested routine.
         * [lastWorkout] is the newest finished workout by start, so it is today's when there is one.
         */
        internal fun sessionRoutineName(
            sessionIsToday: Boolean,
            lastWorkout: WorkoutWithExercises?,
            day: LocalDate,
            zone: ZoneId,
        ): String? {
            if (!sessionIsToday) return null
            val workout = lastWorkout?.workout ?: return null
            if (workout.endedAt == null || Days.date(workout.startedAt, zone) != day) return null
            return workout.name
        }

        /** `DashboardScreen.record(_:)` — the row for one participant on the screen's day. */
        internal fun record(
            records: List<AttendanceRecordEntity>,
            participant: Participant,
            day: LocalDate,
            zone: ZoneId,
        ): AttendanceRecordEntity? = records.firstOrNull {
            it.participant == participant && Days.date(it.day, zone) == day
        }

        /** `LastSessionRow`'s inputs, flattened off the workout graph. */
        internal fun lastSession(workout: WorkoutWithExercises, locale: Locale): LastSession {
            val ended = workout.workout.endedAt
            val prSets = workout.sortedExercises.flatMap { entry ->
                entry.sortedSets.filter { it.isPR }.map { set ->
                    PrSet(
                        exerciseName = entry.exercise?.localizedName(locale).orEmpty(),
                        weightKg = set.weightKg,
                        reps = set.reps,
                    )
                }
            }
            return LastSession(
                workoutId = workout.workout.id,
                name = workout.workout.name,
                at = Instant.ofEpochMilli(ended ?: workout.workout.startedAt),
                durationSeconds = ((ended ?: System.currentTimeMillis()) - workout.workout.startedAt) / 1000.0,
                prCount = workout.prCount,
                prSets = prSets,
            )
        }
    }
}

/** The queries that do not depend on the screen's day. */
internal data class Basis(
    val profile: UserProfileEntity?,
    val schedule: GymScheduleEntity?,
    val pairing: BroPairingEntity?,
    val routines: List<RoutineEntity>,
    /** Newest first; the query keeps only the latest finished workout named after a routine. */
    val recentRoutineWorkoutNames: List<String>,
)

/** The day-scoped queries, re-subscribed when the calendar day rolls over. */
internal data class Live(
    val day: LocalDate,
    val weekRecords: List<AttendanceRecordEntity>,
    val meals: List<MealEntryEntity>,
    val lastWorkout: WorkoutWithExercises?,
    val hasActiveWorkout: Boolean,
    /** A finished workout with a completed set started on [day]. */
    val trainedToday: Boolean = false,
)

/** State that is neither in Room nor day-scoped: the partner, the session and the sheet flags. */
internal data class Local(
    val partnerName: String?,
    val needsSignIn: Boolean,
    val isConfirming: Boolean,
    val showsCantMakeIt: Boolean,
)
