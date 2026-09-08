package app.notomorrow.service

import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.Participant
import app.notomorrow.util.Fmt
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `Services/AttendanceService.swift` — the derivation rules the whole Bro tab hangs on.
 * Every case uses an explicit `today` and a fixed zone, so nothing depends on when the
 * suite runs.
 */
class AttendanceServiceTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")

    /** Monday 2 March 2026. */
    private val monday: LocalDate = LocalDate.of(2026, 3, 2)
    private val wednesday: LocalDate = monday.plusDays(2)
    private val friday: LocalDate = monday.plusDays(4)

    /** Mon / Wed / Fri at 18:00. */
    private val schedule = GymScheduleEntity(
        weekdays = listOf(1, 3, 5),
        defaultMinuteOfDay = 18 * 60,
        overrides = mapOf(5 to 17 * 60),
    )

    private fun record(
        day: LocalDate,
        status: AttendanceStatus,
        participant: Participant = Participant.Me,
        reason: String? = null,
    ) = AttendanceRecordEntity(
        id = "$day-$participant",
        day = Days.millis(day, zone),
        participant = participant,
        scheduledMinuteOfDay = 18 * 60,
        status = status,
        reason = reason,
    )

    private fun state(
        day: LocalDate,
        records: List<AttendanceRecordEntity>,
        today: LocalDate,
        participant: Participant = Participant.Me,
    ) = AttendanceService.state(
        day = day,
        participant = participant,
        isGymDay = schedule.isGymDay(Fmt.isoWeekday(day)),
        records = records,
        today = today,
        zone = zone,
    )

    // MARK: state()

    @Test
    fun `a past planned or confirmed day collapses to missed`() {
        val today = friday
        assertEquals(DayState.Missed, state(monday, listOf(record(monday, AttendanceStatus.Planned)), today))
        assertEquals(DayState.Missed, state(monday, listOf(record(monday, AttendanceStatus.Confirmed)), today))
    }

    @Test
    fun `today's planned or confirmed day is not missed yet`() {
        assertEquals(DayState.Planned, state(monday, listOf(record(monday, AttendanceStatus.Planned)), monday))
        assertEquals(DayState.Confirmed, state(monday, listOf(record(monday, AttendanceStatus.Confirmed)), monday))
    }

    @Test
    fun `a past gym day with no row is rest, not missed`() {
        assertEquals(DayState.Rest, state(monday, emptyList(), today = friday))
    }

    @Test
    fun `a future gym day with no row is planned, a rest day is rest`() {
        assertEquals(DayState.Planned, state(friday, emptyList(), today = monday))
        assertEquals(DayState.Rest, state(monday.plusDays(1), emptyList(), today = monday))
    }

    @Test
    fun `attended, missed and cancelled are taken verbatim`() {
        assertEquals(DayState.Attended, state(monday, listOf(record(monday, AttendanceStatus.Attended)), friday))
        assertEquals(DayState.Missed, state(monday, listOf(record(monday, AttendanceStatus.Missed)), friday))
        assertEquals(
            DayState.Cancelled("sick"),
            state(monday, listOf(record(monday, AttendanceStatus.Cancelled, reason = "sick")), friday),
        )
        assertTrue(DayState.Cancelled(null).isMissedOrCancelled)
        assertTrue(DayState.Missed.isMissedOrCancelled)
        assertFalse(DayState.Planned.isMissedOrCancelled)
    }

    @Test
    fun `states are read per participant`() {
        val records = listOf(
            record(monday, AttendanceStatus.Attended, Participant.Me),
            record(monday, AttendanceStatus.Cancelled, Participant.Partner, reason = "work"),
        )
        assertEquals(DayState.Attended, state(monday, records, friday))
        assertEquals(DayState.Cancelled("work"), state(monday, records, friday, Participant.Partner))
    }

    // MARK: currentWeek()

    @Test
    fun `the week strip is Monday-first and seven columns wide`() {
        val week = AttendanceService.currentWeek(schedule, emptyList(), today = wednesday, zone = zone)
        assertEquals(7, week.size)
        assertEquals(monday, week.first().date)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), week.map { it.isoWeekday })
        assertEquals(listOf(true, false, true, false, true, false, false), week.map { it.isGymDay })
        assertEquals(wednesday, week.first { it.isToday }.date)
    }

    @Test
    fun `a Sunday still belongs to the week that started on Monday`() {
        val sunday = monday.plusDays(6)
        val week = AttendanceService.currentWeek(schedule, emptyList(), today = sunday, zone = zone)
        assertEquals(monday, week.first().date)
        assertEquals(sunday, week.last().date)
        assertTrue(week.last().isToday)
    }

    // MARK: nextSession()

    @Test
    fun `today's session counts while it is at most two hours in the past`() {
        val at18 = Days.at(wednesday, 18 * 60, zone)
        val stillCounts = AttendanceService.nextSession(schedule, at18.plusSeconds(2 * 3600), zone)
        assertEquals(wednesday, stillCounts?.day)

        val expired = AttendanceService.nextSession(schedule, at18.plusSeconds(2 * 3600 + 1), zone)
        assertEquals(friday, expired?.day)
        assertEquals(17 * 60, expired?.minuteOfDay) // the Friday override
    }

    @Test
    fun `a rest day rolls forward to the next gym day`() {
        val tuesday = monday.plusDays(1)
        val next = AttendanceService.nextSession(schedule, Days.at(tuesday, 9 * 60, zone), zone)
        assertEquals(wednesday, next?.day)
        assertEquals(Days.at(wednesday, 18 * 60, zone), next?.at)
    }

    @Test
    fun `no schedule means no next session`() {
        assertNull(AttendanceService.nextSession(null, Days.at(monday, 60, zone), zone))
        assertNull(
            AttendanceService.nextSession(
                schedule.copy(weekdays = emptyList()),
                Days.at(monday, 60, zone),
                zone,
            ),
        )
    }

    // MARK: Counts

    @Test
    fun `the streak counts back and stops at the first non-attended day`() {
        val records = listOf(
            record(friday.plusDays(3), AttendanceStatus.Planned),   // ahead: ignored
            record(friday, AttendanceStatus.Attended),
            record(wednesday, AttendanceStatus.Attended),
            record(monday, AttendanceStatus.Missed),
            record(monday.minusDays(3), AttendanceStatus.Attended), // behind the break
        )
        assertEquals(2, AttendanceService.currentStreak(records, today = friday, zone = zone))
    }

    @Test
    fun `counts cover the last thirty days and treat cancelled as missed`() {
        val records = listOf(
            record(friday, AttendanceStatus.Attended),
            record(wednesday, AttendanceStatus.Cancelled, reason = "sick"),
            record(monday, AttendanceStatus.Missed),
            record(friday.minusDays(40), AttendanceStatus.Attended), // outside the window
        )
        assertEquals(1, AttendanceService.attendedCount(records, today = friday, zone = zone))
        assertEquals(2, AttendanceService.missedCount(records, today = friday, zone = zone))
    }

    @Test
    fun `sessions together counts days both of us attended`() {
        val records = listOf(
            record(monday, AttendanceStatus.Attended, Participant.Me),
            record(monday, AttendanceStatus.Attended, Participant.Partner),
            record(wednesday, AttendanceStatus.Attended, Participant.Me),
            record(friday, AttendanceStatus.Missed, Participant.Me),
        )
        val result = AttendanceService.sessionsTogether(records, zone)
        assertEquals(1, result.together)
        assertEquals(2, result.total)
    }

    // MARK: Writes

    @Test
    fun `markConfirmed keeps the reason and note already on the row`() = runBlocking {
        val dao = FakeAttendanceDao(
            listOf(record(monday, AttendanceStatus.Cancelled, reason = "sick").copy(note = "back pain")),
        )
        val service = AttendanceService(dao, FakeScheduleDao(schedule), FakeProfileDao(), zone)
        val row = service.markConfirmed(monday)
        assertEquals(AttendanceStatus.Confirmed, row.status)
        assertEquals("sick", row.reason)
        assertEquals("back pain", row.note)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `markMissed stores an empty note as null and folds the make-up day to midnight`() = runBlocking {
        val dao = FakeAttendanceDao()
        val service = AttendanceService(dao, FakeScheduleDao(schedule), FakeProfileDao(), zone)
        val row = service.markMissed(wednesday, reason = "work", note = "", makeUp = friday)
        assertEquals(AttendanceStatus.Cancelled, row.status)
        assertNull(row.note)
        assertEquals(Days.millis(friday, zone), row.makeUpDay)
        assertEquals(18 * 60, row.scheduledMinuteOfDay)
    }

    @Test
    fun `upsert returns the existing row untouched and defaults to the schedule minute`() = runBlocking {
        val dao = FakeAttendanceDao(listOf(record(friday, AttendanceStatus.Attended)))
        val service = AttendanceService(dao, FakeScheduleDao(schedule), FakeProfileDao(), zone)

        val existing = service.upsert(friday, Participant.Me)
        assertEquals(AttendanceStatus.Attended, existing.status)

        val fresh = service.upsert(friday, Participant.Partner)
        assertEquals(AttendanceStatus.Planned, fresh.status)
        assertEquals(17 * 60, fresh.scheduledMinuteOfDay) // Friday override
        assertEquals(2, dao.rows.value.size)
    }

    @Test
    fun `upsert falls back to 18 00 without a schedule`() = runBlocking {
        val dao = FakeAttendanceDao()
        val service = AttendanceService(dao, FakeScheduleDao(null), FakeProfileDao(), zone)
        assertEquals(AttendanceService.DEFAULT_MINUTE_OF_DAY, service.upsert(monday, Participant.Me).scheduledMinuteOfDay)
    }

    // MARK: The sweep

    @Test
    fun `the sweep writes misses for past gym days and leaves resolved ones alone`() = runBlocking {
        val today = friday
        val dao = FakeAttendanceDao(
            listOf(
                record(monday, AttendanceStatus.Confirmed),
                record(monday.minusDays(7), AttendanceStatus.Attended),
            ),
        )
        val profile = FakeProfileDao(
            UserProfileEntity(name = "Mark", createdAt = Days.millis(monday.minusDays(10), zone)),
        )
        val service = AttendanceService(dao, FakeScheduleDao(schedule), profile, zone)
        service.markPastPlannedAsMissed(schedule, today)

        val byDay = dao.rows.value.filter { it.participant == Participant.Me }.associateBy { Days.date(it.day, zone) }
        assertEquals(AttendanceStatus.Missed, byDay[monday]?.status)          // confirmed → missed
        assertEquals(AttendanceStatus.Attended, byDay[monday.minusDays(7)]?.status) // untouched
        assertEquals(AttendanceStatus.Missed, byDay[wednesday]?.status)       // inserted
        assertNull(byDay[friday])                                             // today is excluded
        assertNull(byDay[monday.plusDays(1)])                                 // not a gym day
    }

    @Test
    fun `the sweep never reaches back before the profile was created`() = runBlocking {
        val today = friday
        val dao = FakeAttendanceDao()
        val profile = FakeProfileDao(
            UserProfileEntity(name = "Mark", createdAt = Days.millis(wednesday, zone)),
        )
        val service = AttendanceService(dao, FakeScheduleDao(schedule), profile, zone)
        service.markPastPlannedAsMissed(schedule, today)

        val days = dao.rows.value.map { Days.date(it.day, zone) }
        assertEquals(listOf(wednesday), days)
    }

    @Test
    fun `the sweep floors at thirty days when the profile is older`() = runBlocking {
        val today = friday
        val dao = FakeAttendanceDao()
        val profile = FakeProfileDao(
            UserProfileEntity(name = "Mark", createdAt = Days.millis(friday.minusDays(400), zone)),
        )
        val service = AttendanceService(dao, FakeScheduleDao(schedule), profile, zone)
        service.markPastPlannedAsMissed(schedule, today)

        val days = dao.rows.value.map { Days.date(it.day, zone) }.sorted()
        assertTrue(days.all { !it.isBefore(friday.minusDays(30)) })
        assertTrue(days.all { it.isBefore(friday) })
        assertTrue(days.isNotEmpty())
    }

    @Test
    fun `the sweep does nothing without a schedule`() = runBlocking {
        val dao = FakeAttendanceDao()
        val service = AttendanceService(dao, FakeScheduleDao(null), FakeProfileDao(), zone)
        service.markPastPlannedAsMissed(null, friday)
        service.markPastPlannedAsMissed(schedule.copy(weekdays = emptyList()), friday)
        assertTrue(dao.rows.value.isEmpty())
    }

    // MARK: Labels + calendar

    @Test
    fun `iso weekdays and week starts are Monday-first`() {
        assertEquals(1, Fmt.isoWeekday(monday))
        assertEquals(7, Fmt.isoWeekday(monday.plusDays(6)))
        assertEquals(monday, Fmt.startOfIsoWeek(monday.plusDays(6)))
        assertEquals(monday, Fmt.startOfIsoWeek(monday))
    }

    @Test
    fun `label ids are clamped to one through seven`() {
        assertEquals(AttendanceService.labelRes(1), AttendanceService.labelRes(0))
        assertEquals(AttendanceService.labelRes(7), AttendanceService.labelRes(9))
        assertEquals(AttendanceService.shortLabelRes(1), AttendanceService.shortLabelRes(-3))
    }
}
