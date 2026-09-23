package app.notomorrow.feature.bro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.AttendanceDao
import app.notomorrow.data.dao.BroPairingDao
import app.notomorrow.data.dao.HeadsUpDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.dao.ScheduleDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.BroPairingEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.HeadsUpEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.BroService
import app.notomorrow.service.Days
import app.notomorrow.service.TogetherCount
import app.notomorrow.service.WeekDay
import app.notomorrow.util.Localizer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * State for the Bro tab — the port of the `@Query`s and computed properties `BroView`
 * (`Features/Bro/BroView.swift`) declares inline.
 *
 * Nothing here re-implements a service: pairing, refresh and the outgoing heads-up all go
 * through [BroService], attendance through [AttendanceService], and the derivations live in
 * [BroDerived] so they can be unit-tested without Android.
 */
class BroViewModel(
    private val profileDao: ProfileDao,
    private val scheduleDao: ScheduleDao,
    private val attendanceDao: AttendanceDao,
    private val headsUpDao: HeadsUpDao,
    private val workoutDao: WorkoutDao,
    private val routineDao: RoutineDao,
    private val pairingDao: BroPairingDao,
    private val bro: BroService,
    private val attendance: AttendanceService,
    private val needsSignIn: StateFlow<Boolean>,
    private val strings: Localizer,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    // MARK: - Local (form) state

    private val codeEntry = MutableStateFlow("")
    private val isPairing = MutableStateFlow(false)
    private val pairFailed = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)

    // MARK: - Room

    private val stored: Flow<Stored> = combine(
        profileDao.observeProfile(),
        scheduleDao.observeSchedule(),
        pairingDao.observePairing(),
        routineDao.observeRoutines(),
        workoutDao.observeFinishedWorkouts(),
    ) { profile, schedule, pairing, routines, workouts ->
        Stored(profile, schedule, pairing, routines, workouts)
    }

    private val live: Flow<Live> = combine(
        attendanceDao.observeAllDesc(),
        headsUpDao.observeAllDesc(),
    ) { records, headsUps -> Live(records, headsUps) }

    private val session: Flow<Session> = combine(
        bro.partner,
        bro.myCode,
        bro.isLoading,
        needsSignIn,
    ) { partner, myCode, loading, signedOut ->
        Session(
            partnerName = partner?.name,
            isPaired = partner != null,
            partnerPairedAt = partner?.pairedAt?.toEpochMilli(),
            myCode = myCode,
            isLoading = loading,
            needsSignIn = signedOut,
        )
    }

    private val form: Flow<Form> = combine(
        codeEntry,
        isPairing,
        pairFailed,
        isRefreshing,
    ) { code, pairing, failed, refreshing -> Form(code, pairing, failed, refreshing) }

    val uiState: StateFlow<BroUiState> =
        combine(stored, live, session, form) { stored, live, session, form ->
            build(stored, live, session, form)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            // `combine` waits for all four upstreams (two of them Room), so the seed has to carry
            // what is already known synchronously — iOS reads `AuthStore.needsSignIn` in `body`.
            // `loaded` keeps everything below the header off screen until the first real emission.
            BroUiState(needsSignIn = needsSignIn.value),
        )

    private fun build(stored: Stored, live: Live, session: Session, form: Form): BroUiState {
        val instant = now()
        val today = Days.date(instant, zone)
        val isPaired = !session.needsSignIn && (session.isPaired || stored.pairing != null)
        val partnerName = session.partnerName ?: stored.pairing?.partnerName.orEmpty()
        val line = BroDerived.sessionLine(
            schedule = stored.schedule,
            records = live.records,
            routines = stored.routines,
            workouts = stored.workouts,
            now = instant,
            zone = zone,
        )
        return BroUiState(
            loaded = true,
            needsSignIn = session.needsSignIn,
            isPaired = isPaired,
            myName = stored.profile?.name.orEmpty(),
            partnerName = partnerName,
            myCode = session.myCode ?: stored.pairing?.myCode,
            isLoading = session.isLoading,
            isRefreshing = form.isRefreshing,
            codeEntry = form.codeEntry,
            isPairing = form.isPairing,
            pairFailed = form.pairFailed,
            together = AttendanceService.sessionsTogether(live.records, zone),
            week = AttendanceService.currentWeek(stored.schedule, live.records, today, zone),
            session = line,
            logRows = BroDerived.logRows(
                schedule = stored.schedule,
                records = live.records,
                workouts = stored.workouts,
                headsUps = live.headsUps,
                profileCreatedAt = stored.profile?.createdAt,
                pairedAt = session.partnerPairedAt ?: stored.pairing?.pairedAt,
                strings = strings,
                today = today,
                zone = zone,
            ),
            cantMakeItDay = line?.day ?: today,
        )
    }

    // MARK: - Lifecycle

    /**
     * `BroView.lifecycle()` — mark stale planned days as missed, then keep the partner state
     * fresh while the tab is visible. Cancelled with the caller's `LaunchedEffect`.
     */
    suspend fun runLifecycle() {
        attendance.sweepPastPlanned(Days.date(now(), zone))
        while (currentCoroutineContext().isActive) {
            bro.refresh()
            if (bro.isPaired && bro.myCode.value == null) bro.createCode()
            delay(REFRESH_MILLIS)
        }
    }

    /** Pull-to-refresh — the only one in the app. */
    fun refresh() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                bro.refresh()
            } finally {
                isRefreshing.value = false
            }
        }
    }

    /**
     * `BroSignedOutView { Task { await bro.refresh(...) } }` — the same network call as [refresh]
     * without the pull-to-refresh affordance, so signing in never flashes the pull spinner.
     */
    fun refreshQuietly() {
        viewModelScope.launch { bro.refresh() }
    }

    // MARK: - Pairing

    /**
     * `BroUnpairedView.enteredCode` — `.textInputAutocapitalization(.characters)` on iOS, which
     * several Android IMEs quietly ignore, so the uppercasing happens here; the cap is 12 characters.
     */
    fun setCodeEntry(raw: String) {
        pairFailed.value = false
        val upper = raw.uppercase(Locale.ROOT)
        codeEntry.value = if (upper.length > MAX_CODE_LENGTH) upper.take(MAX_CODE_LENGTH) else upper
    }

    fun pair() {
        if (isPairing.value || !canPair(codeEntry.value)) return
        viewModelScope.launch {
            isPairing.value = true
            pairFailed.value = false
            val ok = bro.pair(codeEntry.value)
            isPairing.value = false
            pairFailed.value = !ok
            if (ok) codeEntry.value = ""
        }
    }

    // MARK: - Heads-ups

    /**
     * `BroHeadsUpRow.send` — the local row is ours, the network call is the service's.
     * Custom text is capped at 80 characters on the way out as well as while typing.
     */
    fun sendHeadsUp(kind: HeadsUpKind, text: String) {
        val day = uiState.value.cantMakeItDay
        val capped = if (text.length > MAX_CUSTOM_LENGTH) text.take(MAX_CUSTOM_LENGTH) else text
        viewModelScope.launch {
            headsUpDao.insert(
                HeadsUpEntity(
                    id = UUID.randomUUID().toString(),
                    fromMe = true,
                    kind = kind,
                    text = capped,
                    sessionDay = Days.millis(day, zone),
                    sentAt = now().toEpochMilli(),
                )
            )
            bro.sendHeadsUp(kind, capped, day)
        }
    }

    // MARK: - Plumbing

    private data class Stored(
        val profile: UserProfileEntity?,
        val schedule: GymScheduleEntity?,
        val pairing: BroPairingEntity?,
        val routines: List<RoutineEntity>,
        val workouts: List<WorkoutEntity>,
    )

    private data class Live(
        val records: List<AttendanceRecordEntity>,
        val headsUps: List<HeadsUpEntity>,
    )

    private data class Session(
        val partnerName: String?,
        val isPaired: Boolean,
        /** `bro.partner?.pairedAt` — the server's real pairing moment, not this device's first sync. */
        val partnerPairedAt: Long?,
        val myCode: String?,
        val isLoading: Boolean,
        val needsSignIn: Boolean,
    )

    private data class Form(
        val codeEntry: String,
        val isPairing: Boolean,
        val pairFailed: Boolean,
        val isRefreshing: Boolean,
    )

    companion object {
        /** `BroView.lifecycle()` sleeps 15 s between refreshes. */
        const val REFRESH_MILLIS: Long = 15_000L

        /** `BroUnpairedView` truncates the entry field at 12 characters. */
        const val MAX_CODE_LENGTH: Int = 12

        /** `BroHeadsUpRow.maxCustomLength`. */
        const val MAX_CUSTOM_LENGTH: Int = 80

        /** `BroUnpairedView.canPair` — four characters is the shortest thing worth sending. */
        fun canPair(code: String): Boolean = code.trim().length >= 4
    }
}

/** Everything the Bro tab renders, in one immutable snapshot. */
data class BroUiState(
    /** `false` until the Room + service flows have produced their first snapshot. */
    val loaded: Boolean = false,
    val needsSignIn: Boolean = true,
    val isPaired: Boolean = false,
    val myName: String = "",
    val partnerName: String = "",
    val myCode: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val codeEntry: String = "",
    val isPairing: Boolean = false,
    val pairFailed: Boolean = false,
    val together: TogetherCount = TogetherCount(together = 0, total = 0),
    val week: List<WeekDay> = emptyList(),
    val session: BroSessionLine? = null,
    val logRows: List<BroLogRow> = emptyList(),
    val cantMakeItDay: LocalDate = LocalDate.now(),
) {
    val canPair: Boolean get() = BroViewModel.canPair(codeEntry)

    /** The "Can't make it" chip: not for a session already trained ([BroDerived.offersCantMakeIt]). */
    val offersCantMakeIt: Boolean get() = BroDerived.offersCantMakeIt(session)
}
