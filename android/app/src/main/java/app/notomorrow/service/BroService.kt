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
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

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
    /** My attendance writes not yet confirmed by the server (see [reportAttendance]). */
    private val attendanceOutbox: AttendanceOutbox = AttendanceOutbox(),
    /**
     * The backend and account my attendance goes to: the demo backend, or the signed-in account of
     * the real one. `null` while signed out of the real backend — nothing can be sent then.
     */
    private val syncTargetProvider: () -> AttendanceOutbox.Target? = { DEFAULT_TARGET },
) {

    val client: BackendClient get() = clientProvider()

    /** The backend and account queued writes are tagged with and sent to; `null` while signed out. */
    val syncTarget: AttendanceOutbox.Target? get() = syncTargetProvider()

    /** Signed in, or on the demo backend: `!AuthStore.needsSignIn`. */
    val canSync: Boolean get() = syncTarget != null

    /** Held while a flush pass runs; a flush asked for meanwhile is left to it ([outboxDirty]). */
    private val outboxFlush = Mutex()

    /**
     * Set by every [flushAttendanceOutbox] call and cleared by the pass that starts after it, so a
     * write queued while another pass runs (even one that has just found the queue empty) is sent
     * by that pass's next round instead of waiting for the next refresh.
     */
    private val outboxDirty = AtomicBoolean(false)

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
            // Reachable and signed in: send any attendance that could not go out when it was logged.
            flushAttendanceOutbox()
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
     * Cancels today's session: local `.cancelled` record, the cancellation (reason, note, make-up
     * day) through the attendance outbox — it replaces whatever was queued for the day and is
     * retried like every other attendance write — and a `.cantMakeIt` heads-up when paired. The
     * caller (`CantMakeItSheet`) inserts its own `HeadsUp(fromMe = true)` row. Nothing reaches the
     * backend while signed out.
     */
    suspend fun cantMakeIt(
        reason: String?,
        note: String?,
        makeUpDay: LocalDate?,
        sessionDay: LocalDate = Days.today(zone),
    ) {
        // Never cancels a day I already trained (`AttendanceService.markMissed` keeps it attended too).
        if (attendanceDao.forDay(Days.millis(sessionDay, zone), Participant.Me)?.status == AttendanceStatus.Attended) return
        upsertAttendance(sessionDay, Participant.Me, AttendanceStatus.Cancelled, reason, note, makeUpDay)
        val target = syncTarget ?: return
        attendanceOutbox.put(
            AttendanceOutbox.Entry(
                day = sessionDay,
                status = AttendanceStatus.Cancelled,
                reason = reason,
                note = note?.takeIf { it.isNotEmpty() },
                makeUpDay = makeUpDay,
                target = target,
            ),
        )
        flushAttendanceOutbox()
        if (_partner.value == null) return
        try {
            val text = if (!note.isNullOrEmpty()) note else (reason ?: "")
            client.sendHeadsUp(HeadsUpKind.CantMakeIt, text, sessionDay)
            _lastError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handle(e)
        }
    }

    /**
     * Sends my status for [day] to the backend, paired or not: the server's reminder and 21:00 skip
     * check read it, and a partner sees it. The local record is the caller's ([AttendanceService]).
     * Queued in the outbox first, so a write made offline goes out with a later [refresh]. Nothing
     * is sent or queued while signed out.
     */
    suspend fun reportAttendance(day: LocalDate, status: AttendanceStatus) {
        val target = syncTarget ?: return
        attendanceOutbox.put(day, status, target)
        flushAttendanceOutbox()
    }

    /**
     * Sends the queued attendance writes of the current backend and account, oldest day first.
     * Stops at the first failure that may clear up (no connection, signed out, server trouble) and
     * drops a write the server rejects outright, so one bad entry cannot block the rest. Failures
     * stay quiet: this is background sync, not something the user asked for, so [lastError] is
     * never set.
     *
     * One pass at a time. A call while a pass runs returns at once and leaves its write to that
     * pass, which goes round again once it is done ([outboxDirty]).
     */
    suspend fun flushAttendanceOutbox() {
        outboxDirty.set(true)
        while (outboxDirty.get()) {
            if (!outboxFlush.tryLock()) return
            try {
                outboxDirty.set(false)
                flushPass()
            } finally {
                outboxFlush.unlock()
            }
        }
    }

    private suspend fun flushPass() {
        val tried = mutableSetOf<AttendanceOutbox.Entry>()
        while (true) {
            val target = syncTarget ?: return
            val entry = attendanceOutbox.entries(target).firstOrNull { it !in tried } ?: return
            tried += entry
            try {
                client.setAttendance(entry.day, entry.status, entry.reason, entry.note, entry.makeUpDay)
                attendanceOutbox.remove(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!AttendanceOutbox.isRejected(e)) return
                attendanceOutbox.remove(entry)
            }
        }
    }

    /**
     * Forgets the queued attendance writes of [target] (delete account), or every one when it is
     * `null`.
     */
    suspend fun clearAttendanceOutbox(target: AttendanceOutbox.Target? = null) = attendanceOutbox.clear(target)

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
        /** A service built without a target (onboarding's own, tests) sends to whatever client it has. */
        private val DEFAULT_TARGET = AttendanceOutbox.Target(AttendanceOutbox.Target.REMOTE, null)

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
