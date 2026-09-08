package app.notomorrow.net

import app.notomorrow.R
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.MealSlot
import app.notomorrow.model.Participant
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIFood
import app.notomorrow.net.dto.AttendanceDto
import app.notomorrow.net.dto.HeadsUpDto
import app.notomorrow.net.dto.Me
import app.notomorrow.net.dto.Partner
import app.notomorrow.net.dto.PartnerState
import app.notomorrow.net.dto.ScheduleDto
import app.notomorrow.net.dto.Session
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * In-memory backend for offline demos — the port of
 * `NoTomorrow/Services/MockBackendClient.swift`.
 *
 * Partner "Tomek" shares your schedule, confirms **20 s** after you do, and
 * answers a "can't make it" after **10 s**. Pairing survives relaunch (through
 * [MockPairingStore]) so the Bro tab stays coherent. A [Mutex] plays the part of
 * Swift's `actor` isolation.
 */
class MockBackendClient(
    private val strings: MockStrings,
    private val pairing: MockPairingStore = InMemoryMockPairingStore(),
    override val baseUrl: Url = Url("https://mock.notomorrow.app"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BackendClient {

    private val mutex = Mutex()

    private var session: Session? = null
    private var schedule: ScheduleDto = ScheduleDto.of(listOf(1, 3, 5), 18 * 60)
    private var partner: Partner? = null

    /** Explicit partner attendance, keyed by day. Anything not here is derived from the schedule. */
    private val partnerOverrides = mutableMapOf<LocalDate, AttendanceDto>()
    private val myAttendance = mutableMapOf<LocalDate, AttendanceDto>()
    private val headsUps = mutableListOf<HeadsUpDto>()
    private var replyJob: Job? = null
    private var confirmJob: Job? = null

    init {
        if (pairing.isPaired()) {
            partner = Partner(PARTNER_ID, PARTNER_NAME, Instant.now().minusSeconds(21L * 86_400))
        }
    }

    // MARK: Auth

    override suspend fun signInApple(identityToken: String, authorizationCode: String): Session =
        mutex.withLock { makeSession("mock-apple") }

    override suspend fun signInGoogle(idToken: String): Session =
        mutex.withLock { makeSession("mock-google") }

    override suspend fun signIn(username: String, password: String): Session =
        mutex.withLock { makeSession("mock-$username") }

    override suspend fun register(username: String, password: String, email: String?): Session =
        mutex.withLock { makeSession("mock-$username") }

    private fun makeSession(userId: String): Session =
        Session(
            accessToken = "mock-access-${UUID.randomUUID()}",
            refreshToken = "mock-refresh-${UUID.randomUUID()}",
            userId = userId,
        ).also { session = it }

    override suspend fun logout(refreshToken: String) = mutex.withLock { session = null }

    override suspend fun me(): Me = mutex.withLock {
        Me(
            id = session?.userId ?: "mock-user",
            username = MOCK_USERNAME,
            displayName = MOCK_DISPLAY_NAME,
            email = null,
            partner = partner,
            pairCode = PAIR_CODE,
        )
    }

    override suspend fun updateMe(locale: String?, timeZone: String?) = Unit

    // MARK: Pairing

    override suspend fun createPairCode(): String = PAIR_CODE

    override suspend fun pair(code: String): Partner = mutex.withLock {
        val trimmed = code.trim()
        if (trimmed.length != PAIR_CODE_LENGTH) throw BackendError.Server("Invalid code")
        Partner(PARTNER_ID, PARTNER_NAME, Instant.now()).also { setPartner(it) }
    }

    override suspend fun unpair() = mutex.withLock { unpairLocked() }

    private fun unpairLocked() {
        setPartner(null)
        partnerOverrides.clear()
        headsUps.clear()
        confirmJob?.cancel()
        replyJob?.cancel()
    }

    private fun setPartner(value: Partner?) {
        partner = value
        pairing.setPaired(value != null)
    }

    override suspend fun pushSchedule(schedule: ScheduleDto) = mutex.withLock {
        this.schedule = schedule.sorted()
    }

    override suspend fun partnerState(): PartnerState = mutex.withLock {
        val current = partner ?: throw BackendError.Server("Not paired")
        PartnerState(
            partnerName = current.name,
            partnerSchedule = schedule,
            attendance = partnerAttendance() + myAttendance.values.sortedBy { it.day },
            headsUps = headsUps.toList(),
        )
    }

    /**
     * Tomek's record: every gym day in the last three weeks attended (one missed
     * with a note so the log has something to show), today planned until he
     * confirms, plus any explicit overrides.
     */
    private fun partnerAttendance(): List<AttendanceDto> {
        val today = LocalDate.now()
        val rows = mutableListOf<AttendanceDto>()
        val pastGymDays = (1..21)
            .map { today.minusDays(it.toLong()) }
            .filter { schedule.isGymDay(it.dayOfWeek.value) }
        pastGymDays.forEachIndexed { index, day ->
            val override = partnerOverrides[day]
            if (override != null) {
                rows.add(override)
                return@forEachIndexed
            }
            // The third-most-recent gym day is a miss; the last two are attended.
            rows.add(
                if (index == 2) {
                    AttendanceDto(
                        day = day,
                        participant = Participant.Partner,
                        status = AttendanceStatus.Missed,
                        reason = "sick",
                        note = strings.string(R.string.mock_partner_sickNote),
                    )
                } else {
                    AttendanceDto(day, Participant.Partner, AttendanceStatus.Attended)
                }
            )
        }
        if (schedule.isGymDay(today.dayOfWeek.value)) {
            rows.add(
                partnerOverrides[today]
                    ?: AttendanceDto(today, Participant.Partner, AttendanceStatus.Planned)
            )
        }
        return rows.sortedBy { it.day }
    }

    override suspend fun setAttendance(
        day: LocalDate,
        status: AttendanceStatus,
        reason: String?,
        note: String?,
        makeUpDay: LocalDate?,
    ) {
        mutex.withLock {
            myAttendance[day] = AttendanceDto(day, Participant.Me, status, reason, note, makeUpDay)
            if (partner == null || status != AttendanceStatus.Confirmed || day != LocalDate.now()) return
            confirmJob?.cancel()
            confirmJob = scope.launch {
                delay(PARTNER_CONFIRM_DELAY_MS)
                mutex.withLock { partnerConfirms(day) }
            }
        }
    }

    private fun partnerConfirms(day: LocalDate) {
        if (partner == null) return
        partnerOverrides[day] = AttendanceDto(day, Participant.Partner, AttendanceStatus.Confirmed)
    }

    override suspend fun sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: LocalDate) {
        mutex.withLock {
            headsUps.add(
                HeadsUpDto(
                    id = UUID.randomUUID().toString(),
                    fromMe = true,
                    kind = kind,
                    text = text,
                    sessionDay = sessionDay,
                    sentAt = Instant.now(),
                )
            )
            if (partner == null || kind != HeadsUpKind.CantMakeIt) return
            replyJob?.cancel()
            replyJob = scope.launch {
                delay(PARTNER_REPLY_DELAY_MS)
                mutex.withLock { partnerReplies(sessionDay) }
            }
        }
    }

    private fun partnerReplies(day: LocalDate) {
        if (partner == null) return
        headsUps.add(
            HeadsUpDto(
                id = UUID.randomUUID().toString(),
                fromMe = false,
                kind = HeadsUpKind.Custom,
                text = strings.string(R.string.mock_partner_reply),
                sessionDay = day,
                sentAt = Instant.now(),
            )
        )
    }

    override suspend fun registerPushToken(token: String, platform: String) = Unit

    override suspend fun unregisterPushToken(token: String) = Unit

    // MARK: AI

    override suspend fun estimate(
        imageJpeg: ByteArray,
        meal: MealSlot,
        locale: String,
        anthropicKey: String?,
        notes: String,
    ): AIEstimate {
        delay(ESTIMATE_DELAY_MS)
        return AIEstimate(
            foods = listOf(
                AIFood.of(strings.string(R.string.mock_food_chicken), 180.0, 297.0, 56.0, 0.0, 6.5, 0.92),
                AIFood.of(strings.string(R.string.mock_food_rice), 220.0, 286.0, 5.9, 62.0, 0.6, 0.84),
                AIFood.of(strings.string(R.string.mock_food_broccoli), 90.0, 31.0, 2.5, 6.0, 0.4, 0.88),
                AIFood.of(strings.string(R.string.mock_food_oliveOil), 14.0, 124.0, 0.0, 0.0, 14.0, 0.35, isGuess = true),
            ),
            overallConfidence = 0.78,
        )
    }

    override suspend fun deleteAccount() = mutex.withLock {
        session = null
        unpairLocked()
        myAttendance.clear()
    }

    companion object {
        const val PARTNER_NAME: String = "Tomek"
        const val PARTNER_ID: String = "mock-tomek"
        const val PAIR_CODE: String = "NT-7K4Q"

        /** `BroService.normalizedCode` always produces `NT-XXXX`; any 7-char code pairs. */
        const val PAIR_CODE_LENGTH: Int = 7

        private const val MOCK_USERNAME = "kuba"
        private const val MOCK_DISPLAY_NAME = "Kuba"

        const val PARTNER_CONFIRM_DELAY_MS: Long = 20_000
        const val PARTNER_REPLY_DELAY_MS: Long = 10_000
        const val ESTIMATE_DELAY_MS: Long = 1_200
    }
}
