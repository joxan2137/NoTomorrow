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
    fun `a cancel never downgrades a day I already trained`() = runBlocking {
        val dao = FakeAttendanceDao(listOf(record(wednesday, AttendanceStatus.Attended)))
        val service = AttendanceService(dao, FakeScheduleDao(schedule), FakeProfileDao(), zone)

        val row = service.markMissed(wednesday, reason = "work", note = "late shift", makeUp = friday)

        assertEquals(AttendanceStatus.Attended, row.status, "the attended row comes back unchanged")
        assertNull(row.reason)
        assertNull(row.makeUpDay)
        assertEquals(AttendanceStatus.Attended, service.record(wednesday, Participant.Me)?.status)
        // Only a workout edit or delete turns attended into missed.
        service.markMissed(wednesday, reason = null, note = null, makeUp = null, status = AttendanceStatus.Missed)
        assertEquals(AttendanceStatus.Missed, service.record(wednesday, Participant.Me)?.status)
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

    /** A cursor from long ago: every day since the profile was created is still to be judged. */
    private fun unswept() = AttendanceService.SweepCursor.InMemory(LocalDate.of(2000, 1, 1))

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
        val service = AttendanceService(dao, FakeScheduleDao(schedule), profile, zone, unswept())
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
        val service = AttendanceService(dao, FakeScheduleDao(schedule), profile, zone, unswept())
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
        val service = AttendanceService(dao, FakeScheduleDao(schedule), profile, zone, unswept())
        service.markPastPlannedAsMissed(schedule, today)

        val days = dao.rows.value.map { Days.date(it.day, zone) }.sorted()
        assertTrue(days.all { !it.isBefore(friday.minusDays(30)) })
        assertTrue(days.all { it.isBefore(friday) })
        assertTrue(days.isNotEmpty())
    }

    @Test
    fun `the sweep does nothing without a schedule`() = runBlocking {
        val dao = FakeAttendanceDao()
        val service = AttendanceService(dao, FakeScheduleDao(null), FakeProfileDao(), zone, unswept())
        service.markPastPlannedAsMissed(null, friday)
        service.markPastPlannedAsMissed(schedule.copy(weekdays = emptyList()), friday)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `the first sweep judges yesterday alone`() = runBlocking {
        val everyDay = schedule.copy(weekdays = (1..7).toList())
        val dao = FakeAttendanceDao(listOf(record(friday.minusDays(3), AttendanceStatus.Planned)))
        val profile = FakeProfileDao(UserProfileEntity(name = "Mark", createdAt = Days.millis(monday.minusDays(20), zone)))
        val cursor = AttendanceService.SweepCursor.InMemory()
        val service = AttendanceService(dao, FakeScheduleDao(everyDay), profile, zone, cursor)

        service.markPastPlannedAsMissed(everyDay, friday)

        val byDay = dao.rows.value.associate { Days.date(it.day, zone) to it.status }
        assertEquals(AttendanceStatus.Missed, byDay[friday.minusDays(1)], "the old sweep never judged the day it ran on")
        assertNull(byDay[friday.minusDays(2)], "the past before that is never re-judged by today's schedule")
        assertEquals(AttendanceStatus.Planned, byDay[friday.minusDays(3)], "already the old sweep's")
        assertEquals(friday.minusDays(1), cursor.day)

        service.markPastPlannedAsMissed(everyDay, friday)
        assertEquals(2, dao.rows.value.size, "judged once")
    }

    @Test
    fun `the first sweep without a schedule still starts the cursor`() = runBlocking {
        val dao = FakeAttendanceDao()
        val cursor = AttendanceService.SweepCursor.InMemory()
        val service = AttendanceService(dao, FakeScheduleDao(null), FakeProfileDao(), zone, cursor)

        service.markPastPlannedAsMissed(null, friday)

        assertTrue(dao.rows.value.isEmpty())
        assertEquals(friday.minusDays(1), cursor.day)
    }

    @Test
    fun `only the days since the last sweep are judged, and the cursor only moves forward`() = runBlocking {
        val dao = FakeAttendanceDao()
        val profile = FakeProfileDao(UserProfileEntity(name = "Mark", createdAt = Days.millis(monday.minusDays(20), zone)))
        val cursor = AttendanceService.SweepCursor.InMemory(monday) // Monday was judged already
        val service = AttendanceService(dao, FakeScheduleDao(schedule), profile, zone, cursor)

        service.markPastPlannedAsMissed(schedule, friday)

        assertEquals(listOf(wednesday), dao.rows.value.map { Days.date(it.day, zone) }, "Mon is not judged again")
        assertEquals(friday.minusDays(1), cursor.day)

        service.markPastPlannedAsMissed(schedule, wednesday) // the clock went back
        assertEquals(friday.minusDays(1), cursor.day)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `changing the gym days never rewrites the past`() = runBlocking {
        val dao = FakeAttendanceDao(
            listOf(record(monday, AttendanceStatus.Attended), record(wednesday, AttendanceStatus.Attended)),
        )
        val profile = FakeProfileDao(UserProfileEntity(name = "Mark", createdAt = Days.millis(monday.minusDays(20), zone)))
        val cursor = AttendanceService.SweepCursor.InMemory()
        val service = AttendanceService(dao, FakeScheduleDao(schedule), profile, zone, cursor)
        val saturday = monday.plusDays(5)

        // Mon/Wed/Fri all week: Friday's session is attended, the app is opened on Saturday.
        service.markPastPlannedAsMissed(schedule, friday)
        service.markAttended(friday)
        service.markPastPlannedAsMissed(schedule, saturday)
        val streak = AttendanceService.currentStreak(dao.rows.value, today = saturday, zone = zone)

        // Then Tue/Thu/Sat.
        val newSchedule = schedule.copy(weekdays = listOf(2, 4, 6))
        service.markPastPlannedAsMissed(newSchedule, saturday)
        service.markPastPlannedAsMissed(newSchedule, saturday.plusDays(1))

        val missed = dao.rows.value.filter { it.status == AttendanceStatus.Missed }.map { Days.date(it.day, zone) }
        assertEquals(listOf(saturday), missed, "only Saturday, a gym day of the new schedule that passed untrained")
        assertEquals(3, streak)
        assertEquals(0, AttendanceService.currentStreak(dao.rows.value, today = saturday.plusDays(1), zone = zone))
        assertEquals(
            3,
            AttendanceService.attendedCount(dao.rows.value, today = saturday.plusDays(1), zone = zone),
            "no past rest day turned into a miss",
        )
    }

    @Test
    fun `a make-up day that passes untrained becomes missed`() = runBlocking {
        val tuesday = monday.plusDays(1)
        val dao = FakeAttendanceDao()
        val service = AttendanceService(dao, FakeScheduleDao(schedule), FakeProfileDao(), zone, unswept())
        service.markMissed(monday, reason = "sick", note = null, makeUp = tuesday)
        service.markPlanned(tuesday) // Tuesday is a rest day

        service.markPastPlannedAsMissed(schedule, wednesday)

        assertEquals(AttendanceStatus.Cancelled, service.record(monday, Participant.Me)?.status)
        assertEquals(AttendanceStatus.Missed, service.record(tuesday, Participant.Me)?.status)
    }

    @Test
    fun `the cursor advances without a schedule`() = runBlocking {
        val cursor = AttendanceService.SweepCursor.InMemory(monday)
        val service = AttendanceService(FakeAttendanceDao(), FakeScheduleDao(null), FakeProfileDao(), zone, cursor)
        service.markPastPlannedAsMissed(null, friday)
        assertEquals(friday.minusDays(1), cursor.day)
    }

    // MARK: Make-up day

    @Test
    fun `a make-up day becomes planned by the server's rule`() = runBlocking {
        val tuesday = monday.plusDays(1)
        val dao = FakeAttendanceDao(
            listOf(
                record(monday, AttendanceStatus.Cancelled, reason = "sick"),
                record(wednesday, AttendanceStatus.Attended),
                record(friday, AttendanceStatus.Confirmed),
                record(monday.minusDays(7), AttendanceStatus.Missed),
            ),
        )
        val service = AttendanceService(dao, FakeScheduleDao(schedule), FakeProfileDao(), zone)

        for (day in listOf(tuesday, monday, wednesday, friday, monday.minusDays(7))) service.markPlanned(day)

        assertEquals(AttendanceStatus.Planned, service.record(tuesday, Participant.Me)?.status, "an empty day")
        val cancelled = service.record(monday, Participant.Me)
        assertEquals(AttendanceStatus.Planned, cancelled?.status, "a cancelled day")
        assertNull(cancelled?.reason, "reason, note and make-up are cleared")
        assertEquals(AttendanceStatus.Planned, service.record(monday.minusDays(7), Participant.Me)?.status, "a missed day")
        assertEquals(AttendanceStatus.Attended, service.record(wednesday, Participant.Me)?.status, "left alone")
        assertEquals(AttendanceStatus.Confirmed, service.record(friday, Participant.Me)?.status, "left alone")
    }

    // MARK: Workout edited or deleted

    @Test
    fun `a moved, deleted or unticked workout corrects only what it counted for`() {
        val today = friday
        val mon = monday
        val tue = monday.plusDays(1)
        val everyDay: (LocalDate) -> Boolean = { true }
        val attendedMon: (LocalDate) -> AttendanceStatus? = { if (it == mon) AttendanceStatus.Attended else null }
        fun changes(
            old: LocalDate?,
            new: LocalDate?,
            stillAttended: Boolean = false,
            isGymDay: (LocalDate) -> Boolean = everyDay,
            status: (LocalDate) -> AttendanceStatus? = attendedMon,
            on: LocalDate = today,
        ) = AttendanceService.workoutDayChanges(old, new, stillAttended, isGymDay, status, on)

        assertEquals(
            listOf(AttendanceService.WorkoutDayChange.MarkAttended(tue), AttendanceService.WorkoutDayChange.MarkMissed(mon)),
            changes(mon, tue),
        )
        assertTrue(changes(mon, mon).isEmpty(), "same day: nothing to do")
        assertTrue(changes(mon, null, stillAttended = true).isEmpty(), "another workout still counts for that day")
        assertTrue(
            changes(mon, null, status = { if (it == mon) AttendanceStatus.Cancelled else null }).isEmpty(),
            "only attended is ever reverted",
        )
        assertEquals(
            listOf(
                AttendanceService.WorkoutDayChange.MarkAttended(today.minusDays(1)),
                AttendanceService.WorkoutDayChange.Clear(today),
            ),
            changes(today, today.minusDays(1), status = { if (it == today) AttendanceStatus.Attended else null }),
            "today goes back to the schedule rather than missed",
        )
        assertEquals(
            listOf(AttendanceService.WorkoutDayChange.MarkAttended(tue), AttendanceService.WorkoutDayChange.Clear(mon)),
            changes(mon, tue, isGymDay = { false }),
            "any day counts (Finish's rule); the old rest-day record is dropped",
        )
        assertTrue(changes(null, today.plusDays(1), status = { null }).isEmpty(), "never attended in the future")
        assertEquals(
            listOf(AttendanceService.WorkoutDayChange.MarkMissed(mon)),
            changes(mon, tue, status = { AttendanceStatus.Attended }),
            "a day already attended is not marked again",
        )
    }

    @Test
    fun `applying a day change writes missed, attended and clears`() = runBlocking {
        val dao = FakeAttendanceDao(listOf(record(monday, AttendanceStatus.Attended), record(friday, AttendanceStatus.Attended)))
        val service = AttendanceService(dao, FakeScheduleDao(schedule), FakeProfileDao(), zone)

        service.applyWorkoutDayChange(oldDay = monday, newDay = wednesday, oldDayStillAttended = false, today = friday)
        assertEquals(AttendanceStatus.Missed, service.record(monday, Participant.Me)?.status)
        assertEquals(AttendanceStatus.Attended, service.record(wednesday, Participant.Me)?.status)

        service.applyWorkoutDayChange(oldDay = friday, newDay = null, oldDayStillAttended = false, today = friday)
        assertNull(service.record(friday, Participant.Me), "today loses the record")

        service.clearMine(wednesday)
        assertNull(service.record(wednesday, Participant.Me))
    }

    @Test
    fun `a make-up day returns to its plan instead of being cleared`() {
        val today = wednesday
        val tuesday = monday.plusDays(1)
        val thursday = monday.plusDays(3)
        val restDays: (LocalDate) -> Boolean = { false }
        fun changes(old: LocalDate, isMakeUpDay: Boolean) = AttendanceService.workoutDayChanges(
            oldDay = old,
            newDay = null,
            oldDayStillAttended = false,
            isGymDay = restDays,
            myStatus = { if (it == old) AttendanceStatus.Attended else null },
            today = today,
            isMakeUpDay = { isMakeUpDay && it == old },
        )

        assertEquals(listOf(AttendanceService.WorkoutDayChange.MarkPlanned(today)), changes(today, isMakeUpDay = true))
        assertEquals(
            listOf(AttendanceService.WorkoutDayChange.MarkPlanned(thursday)),
            changes(thursday, isMakeUpDay = true),
            "a future make-up day (a workout moved off it) is planned again",
        )
        assertEquals(
            listOf(AttendanceService.WorkoutDayChange.MarkMissed(tuesday)),
            changes(tuesday, isMakeUpDay = true),
            "a past make-up day is what the sweep would have written",
        )
        assertEquals(
            listOf(AttendanceService.WorkoutDayChange.Clear(today)),
            changes(today, isMakeUpDay = false),
            "a plain rest day still goes back to the schedule",
        )
    }

    @Test
    fun `applying a day change restores a make-up day and reports it`() = runBlocking {
        val tuesday = monday.plusDays(1)
        val thursday = monday.plusDays(3)
        // Monday cancelled with Tuesday as the make-up; Wednesday cancelled with Thursday. Both trained.
        val dao = FakeAttendanceDao(
            listOf(
                record(monday, AttendanceStatus.Cancelled, reason = "work").copy(makeUpDay = Days.millis(tuesday, zone)),
                record(tuesday, AttendanceStatus.Attended),
                record(wednesday, AttendanceStatus.Cancelled, reason = "sick").copy(makeUpDay = Days.millis(thursday, zone)),
                record(thursday, AttendanceStatus.Attended),
            ),
        )
        val service = AttendanceService(dao, FakeScheduleDao(schedule), FakeProfileDao(), zone)
        val gym: (LocalDate) -> Boolean = { schedule.isGymDay(Fmt.isoWeekday(it)) }
        val reported = mutableListOf<Pair<LocalDate, AttendanceStatus>>()
        val reporter = AttendanceReporter { day, status -> reported += day to status }

        assertTrue(service.isMakeUpDay(tuesday))
        assertFalse(service.isMakeUpDay(friday))

        // Thursday's workout deleted the same day: the make-up plan is back.
        reporter.report(service.applyWorkoutDayChange(thursday, null, oldDayStillAttended = false, today = thursday), gym)
        val restored = service.record(thursday, Participant.Me)
        assertEquals(AttendanceStatus.Planned, restored?.status)
        assertNull(restored?.reason)
        assertEquals(DayState.Planned, state(thursday, dao.rows.value, today = thursday), "a planned make-up day, not a rest day")

        // Tuesday's workout deleted two days later: missed, as the sweep would have written.
        reporter.report(service.applyWorkoutDayChange(tuesday, null, oldDayStillAttended = false, today = thursday), gym)
        assertEquals(AttendanceStatus.Missed, service.record(tuesday, Participant.Me)?.status)

        assertEquals(listOf(thursday to AttendanceStatus.Planned, tuesday to AttendanceStatus.Missed), reported)
        assertEquals(
            AttendanceStatus.Cancelled,
            service.record(wednesday, Participant.Me)?.status,
            "the cancellation that planned it is untouched",
        )
    }

    @Test
    fun `the server holds attended, missed, or a gym day planned again`() {
        val gym: (LocalDate) -> Boolean = { schedule.isGymDay(Fmt.isoWeekday(it)) }
        val tuesday = monday.plusDays(1)
        assertEquals(
            monday to AttendanceStatus.Attended,
            AttendanceService.wireStatus(AttendanceService.WorkoutDayChange.MarkAttended(monday), gym),
        )
        assertEquals(
            monday to AttendanceStatus.Missed,
            AttendanceService.wireStatus(AttendanceService.WorkoutDayChange.MarkMissed(monday), gym),
        )
        assertEquals(
            monday to AttendanceStatus.Planned,
            AttendanceService.wireStatus(AttendanceService.WorkoutDayChange.Clear(monday), gym),
        )
        assertNull(AttendanceService.wireStatus(AttendanceService.WorkoutDayChange.Clear(tuesday), gym))
        assertEquals(
            tuesday to AttendanceStatus.Planned,
            AttendanceService.wireStatus(AttendanceService.WorkoutDayChange.MarkPlanned(tuesday), gym),
            "a restored make-up day is reported even on a rest day",
        )
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
