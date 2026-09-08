package app.notomorrow.service

import app.notomorrow.data.dao.AttendanceDao
import app.notomorrow.data.dao.BroPairingDao
import app.notomorrow.data.dao.HeadsUpDao
import app.notomorrow.data.dao.ScheduleDao
import app.notomorrow.data.entity.BroPairingEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.Participant
import app.notomorrow.net.BackendClient
import app.notomorrow.net.BackendError
import app.notomorrow.net.dto.AttendanceDto
import app.notomorrow.net.dto.Me
import app.notomorrow.net.dto.Partner
import app.notomorrow.net.dto.PartnerState
import app.notomorrow.net.dto.ScheduleDto
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.Parsing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException

/**
 * Everything the Bro tab and the Dashboard bro row need: pairing, the partner's week, heads-ups —
 * the port of `BroService` (`NoTomorrow/Services/BroService.swift`).
 *
 * Talks to a [BackendClient]; after every refresh it mirrors the partner's attendance and incoming
 * heads-ups into Room so the week strip and the log work offline. One instance per process
 * (`AppContainer.broService`, the analogue of `BroShared.service`).
 *
 * `unauthorized` is a **state** ([isSignedOut]), not an error.
 */
class BroService(
    /** Resolved on **every** use so flipping "Use demo data" in Settings takes effect without a relaunch. */
    private val clientProvider: () -> BackendClient,
    private val scheduleDao: ScheduleDao,
    private val attendanceDao: AttendanceDao,
    private val headsUpDao: HeadsUpDao,
    private val pairingDao: BroPairingDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) {

    val client: BackendClient get() = clientProvider()

    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me.asStateFlow()

    private val _partner = MutableStateFlow<Partner?>(null)
    val partner: StateFlow<Partner?> = _partner.asStateFlow()

    private val _partnerState = MutableStateFlow<PartnerState?>(null)
    val partnerState: StateFlow<PartnerState?> = _partnerState.asStateFlow()

    private val _myCode = MutableStateFlow<String?>(null)
    val myCode: StateFlow<String?> = _myCode.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** The backend refused us and the session could not be refreshed: the views show "Sign in". */
    private val _isSignedOut = MutableStateFlow(false)
    val isSignedOut: StateFlow<Boolean> = _isSignedOut.asStateFlow()

    private val _lastError = MutableStateFlow<BackendError?>(null)
    val lastError: StateFlow<BackendError?> = _lastError.asStateFlow()

    val isPaired: Boolean get() = _partner.value != null

    fun clearError() {
        _lastError.value = null
    }

    // MARK: - Refresh

    /** Pulls `me()` (who am I paired with) and, when paired, the partner's state; then syncs into Room. */
    suspend fun refresh() {
        _isLoading.value = true
        try {
            val client = client
            val me = client.me()
            _me.value = me
            _isSignedOut.value = false
            _partner.value = me.partner
            me.pairCode?.let { _myCode.value = it }
            if (me.partner == null) {
                _partnerState.value = null
                clearPairing()
                _lastError.value = null
                return
            }
            val state = client.partnerState()
            _partnerState.value = state
            sync(state)
            upsertPairing()
            _lastError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handle(e)
        } finally {
            _isLoading.value = false
        }
    }

    /** `unauthorized` is a state (signed out), everything else is an error worth surfacing. */
    private fun handle(error: Throwable) {
        val wrapped = BackendError.wrap(error)
        if (wrapped is BackendError.Unauthorized) resetSession() else _lastError.value = wrapped
    }

    /**
     * Forgets everything that belongs to the account (partner, code, week). Local rows stay.
     * Called after sign-out, after an unrecoverable 401 and when the demo switch flips.
     */
    fun resetSession() {
        _me.value = null
        _partner.value = null
        _partnerState.value = null
        _myCode.value = null
        _lastError.value = null
        _isSignedOut.value = true
    }

    /** After a sign-in: push the local schedule and locale/time zone, then load who we are paired with. */
    suspend fun didSignIn() {
        _isSignedOut.value = false
        val client = client
        scheduleDao.schedule()?.let { schedule ->
            runCatching { client.pushSchedule(schedule.toDto()) }.ignoreCancellation()
        }
        val language = LocaleProvider.current().language.ifEmpty { "en" }
        runCatching { client.updateMe(language, ZoneId.systemDefault().id) }.ignoreCancellation()
        refresh()
        if (_myCode.value == null && !_isSignedOut.value) createCode()
    }

    // MARK: - Pairing

    suspend fun createCode(): String? = try {
        val code = client.createPairCode()
        _myCode.value = code
        _lastError.value = null
        code
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        handle(e)
        null
    }

    suspend fun pair(code: String): Boolean {
        val trimmed = normalizedCode(code)
        if (!Parsing.isValidPairCode(trimmed)) return false
        _isLoading.value = true
        return try {
            val client = client
            _partner.value = client.pair(trimmed)
            _isLoading.value = false
            _lastError.value = null
            if (_myCode.value == null) createCode()
            scheduleDao.schedule()?.let { schedule ->
                runCatching { client.pushSchedule(schedule.toDto()) }.ignoreCancellation()
            }
            refresh()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _isLoading.value = false
            handle(e)
            false
        }
    }

    suspend fun unpair() {
        try {
            client.unpair()
            _partner.value = null
            _partnerState.value = null
            _lastError.value = null
            pairingDao.deleteAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handle(e)
        }
    }

    // MARK: - Attendance & heads-ups

    /** "I'm in" for today. Writes my `AttendanceRecord(.confirmed)` locally and tells the partner. */
    suspend fun confirmToday() {
        val today = Days.today(zone)
        upsertAttendance(today, Participant.Me, AttendanceStatus.Confirmed, null, null, null)
        try {
            client.setAttendance(today, AttendanceStatus.Confirmed, null, null, null)
            _lastError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handle(e)
        }
    }

    /**
     * Cancels today's session: local `.cancelled` record plus backend attendance and a `.cantMakeIt`
     * heads-up. The caller (`CantMakeItSheet`) inserts its own `HeadsUp(fromMe = true)` row.
     */
    suspend fun cantMakeIt(
        reason: String?,
        note: String?,
        makeUpDay: LocalDate?,
        sessionDay: LocalDate = Days.today(zone),
    ) {
        upsertAttendance(sessionDay, Participant.Me, AttendanceStatus.Cancelled, reason, note, makeUpDay)
        try {
            client.setAttendance(sessionDay, AttendanceStatus.Cancelled, reason, note, makeUpDay)
            if (_partner.value != null) {
                val text = if (!note.isNullOrEmpty()) note else (reason ?: "")
                client.sendHeadsUp(HeadsUpKind.CantMakeIt, text, sessionDay)
            }
            _lastError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handle(e)
        }
    }

    /** Backend only; the caller owns the local row. */
    suspend fun sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: LocalDate = Days.today(zone)) {
        if (_partner.value == null) return
        try {
            client.sendHeadsUp(kind, text, sessionDay)
            _lastError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handle(e)
        }
    }

    // MARK: - Derived

    /** Partner's record for a given day, if the backend reported one. */
    fun partnerAttendance(day: LocalDate): AttendanceDto? =
        _partnerState.value?.attendance?.firstOrNull { it.participant == Participant.Partner && it.day == day }

    // MARK: - Room sync

    /**
     * Partner attendance is diffed by day (written only when status/reason/note/makeUpDay changed);
     * incoming heads-ups are de-duped by (**±1 s `sentAt`**, equal `text`).
     */
    internal suspend fun sync(state: PartnerState) {
        val partnerRows = state.attendance.filter { it.participant == Participant.Partner }
        val earliestDay = partnerRows.minByOrNull { it.day.toEpochDay() }?.day
        if (earliestDay != null) {
            val existing = attendanceDao.since(Days.millis(earliestDay, zone))
                .filter { it.participant == Participant.Partner }
            for (dto in partnerRows) {
                val day = Days.millis(dto.day, zone)
                val minute = state.partnerSchedule.minuteOfDay(Fmt.isoWeekday(dto.day))
                val makeUp = dto.makeUpDay?.let { Days.millis(it, zone) }
                val row = existing.firstOrNull { it.day == day }
                if (row != null &&
                    row.status == dto.status && row.reason == dto.reason &&
                    row.note == dto.note && row.makeUpDay == makeUp
                ) {
                    continue
                }
                attendanceDao.upsert(
                    day = day,
                    participant = Participant.Partner,
                    scheduledMinuteOfDay = minute,
                    status = dto.status,
                    reason = dto.reason,
                    note = dto.note,
                    makeUpDay = makeUp,
                    now = now().toEpochMilli(),
                )
            }
        }

        val incoming = state.headsUps.filter { !it.fromMe }
        val earliestSent = incoming.minByOrNull { it.sentAt }?.sentAt ?: return
        val existing = headsUpDao.since(earliestSent.toEpochMilli()).filter { !it.fromMe }
        val fresh = incoming.filterNot { dto ->
            existing.any { row ->
                kotlin.math.abs(row.sentAt - dto.sentAt.toEpochMilli()) < 1_000 && row.text == dto.text
            }
        }
        if (fresh.isEmpty()) return
        headsUpDao.insertAll(
            fresh.map { dto ->
                app.notomorrow.data.entity.HeadsUpEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    fromMe = false,
                    kind = dto.kind,
                    text = dto.text,
                    sessionDay = Days.millis(dto.sessionDay, zone),
                    sentAt = dto.sentAt.toEpochMilli(),
                )
            }
        )
    }

    /**
     * `upsertAttendance` — one row per (day, participant). An existing row keeps its
     * `scheduledMinuteOfDay`, exactly as the Swift version does.
     */
    private suspend fun upsertAttendance(
        day: LocalDate,
        participant: Participant,
        status: AttendanceStatus,
        reason: String?,
        note: String?,
        makeUpDay: LocalDate?,
    ) {
        val dayMillis = Days.millis(day, zone)
        val existing = attendanceDao.forDay(dayMillis, participant)
        attendanceDao.upsert(
            day = dayMillis,
            participant = participant,
            scheduledMinuteOfDay = existing?.scheduledMinuteOfDay ?: scheduledMinute(day),
            status = status,
            reason = reason,
            note = note,
            makeUpDay = makeUpDay?.let { Days.millis(it, zone) },
            now = now().toEpochMilli(),
        )
    }

    private suspend fun scheduledMinute(day: LocalDate): Int =
        scheduleDao.schedule()?.minuteOfDay(Fmt.isoWeekday(day)) ?: GymScheduleEntity.DEFAULT_MINUTE_OF_DAY

    /** The server says we are not paired: drop the cached pairing so the tabs do not show a stale partner. */
    private suspend fun clearPairing() {
        if (pairingDao.pairing() == null) return
        pairingDao.deleteAll()
    }

    private suspend fun upsertPairing() {
        val partner = _partner.value ?: return
        val existing = pairingDao.pairing()
        val row = existing?.copy(
            partnerId = partner.id,
            partnerName = partner.name,
            myCode = _myCode.value ?: existing.myCode,
        ) ?: BroPairingEntity(
            partnerId = partner.id,
            partnerName = partner.name,
            myCode = _myCode.value.orEmpty(),
            pairedAt = now().toEpochMilli(),
        )
        pairingDao.upsert(row)
    }

    companion object {
        /**
         * `"abcd"`, `"nt-abcd"`, `"NTABCD "` → `"NT-ABCD"`. Anything that does not reduce to four
         * code characters is returned as-is (uppercased) and rejected by [pair].
         */
        fun normalizedCode(raw: String): String = Parsing.normalizedPairCode(raw)
    }
}

/** `ScheduleDTO(_ schedule: GymSchedule)` (`BackendClient.swift:78`). */
internal fun GymScheduleEntity.toDto(): ScheduleDto = ScheduleDto.of(
    weekdays = weekdays,
    defaultMinuteOfDay = defaultMinuteOfDay,
    overrides = overrides,
    remindHourBefore = remindHourBefore,
    askIfSkippedAt21 = askIfSkippedAt21,
)

/** Swift's `try?` around a network call: swallow the failure, but never a cancellation. */
private fun <T> Result<T>.ignoreCancellation() {
    val error = exceptionOrNull() ?: return
    if (error is CancellationException) throw error
}
