package app.notomorrow.feature.bro

import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.HeadsUpEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.Participant
import app.notomorrow.service.DayState
import app.notomorrow.service.Days
import app.notomorrow.util.Localizer
import app.notomorrow.util.S
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * `Features/Bro/BroDerived.swift` — the pure half of the Bro tab: routine rotation, the
 * session line, the ten-row log and the note precedence. Everything takes an explicit
 * `today`, zone and locale, so nothing depends on when or where the suite runs.
 */
class BroDerivedTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val locale: Locale = Locale.UK

    /** Monday 2 March 2026. */
    private val monday: LocalDate = LocalDate.of(2026, 3, 2)
    private val wednesday: LocalDate = monday.plusDays(2)
    private val friday: LocalDate = monday.plusDays(4)

    /** Mon / Wed / Fri at 18:00. */
    private val schedule = GymScheduleEntity(weekdays = listOf(1, 3, 5), defaultMinuteOfDay = 18 * 60)

    /** Resource ids in, recognisable text out — the tests assert on the key, not the copy. */
    private val strings = Localizer { id, args ->
        when (id) {
            S.bro_noHeadsUp -> "NO_HEADS_UP"
            S.cant_reason_sick -> "Sick"
            S.cant_reason_work -> "Work"
            else -> "id:$id" + args.joinToString("") { " $it" }
        }
    }

    // MARK: - Fixtures

    private fun record(
        day: LocalDate,
        status: AttendanceStatus,
        participant: Participant = Participant.Me,
        reason: String? = null,
        note: String? = null,
        updatedAt: Long = 0L,
    ) = AttendanceRecordEntity(
        id = "$day-$participant",
        day = Days.millis(day, zone),
        participant = participant,
        scheduledMinuteOfDay = 18 * 60,
        status = status,
        reason = reason,
        note = note,
        updatedAt = updatedAt,
    )

    private fun workout(day: LocalDate, name: String, finished: Boolean = true) = WorkoutEntity(
        id = "$name-$day",
        name = name,
        startedAt = Days.millis(day, zone) + 10 * 3_600_000L,
        endedAt = if (finished) Days.millis(day, zone) + 11 * 3_600_000L else null,
    )

    private fun routines(vararg names: String): List<RoutineEntity> =
        names.mapIndexed { index, name -> RoutineEntity(id = name, name = name, order = index) }

    private fun headsUp(day: LocalDate, text: String, sentAt: Long) = HeadsUpEntity(
        id = "$day-$sentAt",
        fromMe = false,
        kind = HeadsUpKind.CantMakeIt,
        text = text,
        sessionDay = Days.millis(day, zone),
        sentAt = sentAt,
    )

    // MARK: - Routine names

    @Test
    fun `routineName prefers the workout actually done that day`() {
        val name = BroDerived.routineName(
            day = wednesday,
            routines = routines("Push A", "Pull A", "Legs"),
            workouts = listOf(workout(wednesday, "Legs")),
            zone = zone,
        )
        assertEquals("Legs", name)
    }

    @Test
    fun `routineName rotates past the most recent completed workout`() {
        val name = BroDerived.routineName(
            day = friday,
            routines = routines("Push A", "Pull A", "Legs"),
            workouts = listOf(workout(wednesday, "Pull A")),
            zone = zone,
        )
        assertEquals("Legs", name)
    }

    @Test
    fun `routineName wraps around and falls back to the first routine`() {
        assertEquals(
            "Push A",
            BroDerived.routineName(friday, routines("Push A", "Pull A"), listOf(workout(wednesday, "Pull A")), zone),
        )
        assertEquals(
            "Push A",
            BroDerived.routineName(friday, routines("Push A", "Pull A"), emptyList(), zone),
        )
        assertNull(BroDerived.routineName(friday, emptyList(), emptyList(), zone))
    }

    @Test
    fun `workoutName ignores a workout that is still running`() {
        assertNull(BroDerived.workoutName(wednesday, listOf(workout(wednesday, "Legs", finished = false)), zone))
        assertEquals("Legs", BroDerived.workoutName(wednesday, listOf(workout(wednesday, "Legs")), zone))
    }

    // MARK: - Session line

    @Test
    fun `sessionLine reports both sides and their confirmation times`() {
        val records = listOf(
            record(wednesday, AttendanceStatus.Confirmed, Participant.Me, updatedAt = 1_000L),
            record(wednesday, AttendanceStatus.Confirmed, Participant.Partner, updatedAt = 2_000L),
        )
        val line = BroDerived.sessionLine(
            schedule = schedule,
            records = records,
            routines = routines("Push A"),
            workouts = emptyList(),
            now = Days.at(wednesday, 9 * 60, zone),
            zone = zone,
        )
        requireNotNull(line)
        assertEquals(wednesday, line.day)
        assertEquals(18 * 60, line.minuteOfDay)
        assertEquals("Push A", line.routineName)
        assertEquals(1_000L, line.myConfirmedAt)
        assertEquals(2_000L, line.partnerConfirmedAt)
        assertTrue(line.bothIn)
    }

    @Test
    fun `sessionLine leaves confirmation times empty for a planned day`() {
        val line = BroDerived.sessionLine(
            schedule = schedule,
            records = emptyList(),
            routines = routines("Push A"),
            workouts = emptyList(),
            now = Days.at(monday, 9 * 60, zone),
            zone = zone,
        )
        requireNotNull(line)
        assertNull(line.myConfirmedAt)
        assertNull(line.partnerConfirmedAt)
        assertEquals(false, line.bothIn)
        assertEquals(DayState.Planned, line.myState)
    }

    @Test
    fun `sessionLine is null without a schedule`() {
        assertNull(
            BroDerived.sessionLine(
                schedule = null,
                records = emptyList(),
                routines = emptyList(),
                workouts = emptyList(),
                now = Days.at(monday, 9 * 60, zone),
                zone = zone,
            )
        )
    }

    // MARK: - Log

    // MARK: - Can't make it

    @Test
    fun `the cant make it chip goes once today's session is trained`() {
        // Pull A done this morning: the day is attended, the line still shows today until 20:00.
        val line = BroDerived.sessionLine(
            schedule = schedule,
            records = listOf(record(wednesday, AttendanceStatus.Attended, updatedAt = 1_000L)),
            routines = routines("Push A", "Pull A"),
            workouts = listOf(workout(wednesday, "Pull A")),
            now = Days.at(wednesday, 12 * 60, zone),
            zone = zone,
        )
        requireNotNull(line)
        assertEquals(wednesday, line.day)
        assertEquals(DayState.Attended, line.myState)
        assertEquals("Pull A", line.routineName)
        assertFalse(BroDerived.offersCantMakeIt(line), "Send could only close the sheet")
        assertFalse(BroUiState(session = line).offersCantMakeIt)
    }

    @Test
    fun `the cant make it chip stays for a session still ahead`() {
        for (status in listOf(AttendanceStatus.Planned, AttendanceStatus.Confirmed, AttendanceStatus.Cancelled)) {
            val line = BroDerived.sessionLine(
                schedule = schedule,
                records = listOf(record(wednesday, status)),
                routines = routines("Push A"),
                workouts = emptyList(),
                now = Days.at(wednesday, 9 * 60, zone),
                zone = zone,
            )
            assertTrue(BroDerived.offersCantMakeIt(line), "offered when $status")
        }
        // Past the grace the line moves on to Friday, which nobody trained yet.
        val later = BroDerived.sessionLine(
            schedule = schedule,
            records = listOf(record(wednesday, AttendanceStatus.Attended)),
            routines = routines("Push A"),
            workouts = listOf(workout(wednesday, "Push A")),
            now = Days.at(wednesday, 21 * 60, zone),
            zone = zone,
        )
        assertEquals(friday, later?.day)
        assertTrue(BroDerived.offersCantMakeIt(later))
        // No schedule, no line: the sheet falls back to today, as before.
        assertTrue(BroDerived.offersCantMakeIt(null))
    }

    @Test
    fun `logRows lists past gym days newest first and stops at the pairing`() {
        val today = monday.plusDays(7)          // Monday 9 March
        val rows = BroDerived.logRows(
            schedule = schedule,
            records = emptyList(),
            workouts = emptyList(),
            headsUps = emptyList(),
            profileCreatedAt = null,
            pairedAt = Days.millis(wednesday, zone),
            strings = strings,
            today = today,
            zone = zone,
        )
        assertEquals(listOf(friday, wednesday), rows.map { it.day })
    }

    @Test
    fun `logRows keeps today only when someone has a terminal record`() {
        val today = wednesday
        val records = listOf(record(today, AttendanceStatus.Cancelled, Participant.Me, reason = "sick"))
        val rows = BroDerived.logRows(
            schedule = schedule,
            records = records,
            workouts = emptyList(),
            headsUps = emptyList(),
            profileCreatedAt = Days.millis(today, zone),
            pairedAt = null,
            strings = strings,
            today = today,
            zone = zone,
        )
        assertEquals(listOf(today), rows.map { it.day })
        // The other side stays planned rather than collapsing to missed.
        assertEquals(DayState.Planned, rows.first().partnerState)
        assertTrue(rows.first().anyoneMissed)
    }

    @Test
    fun `logRows never returns more than the limit`() {
        val today = monday.plusDays(60)
        val rows = BroDerived.logRows(
            schedule = schedule,
            records = emptyList(),
            workouts = emptyList(),
            headsUps = emptyList(),
            profileCreatedAt = null,
            pairedAt = null,
            strings = strings,
            today = today,
            zone = zone,
        )
        assertEquals(BroDerived.LOG_LIMIT, rows.size)
    }

    // MARK: - Note line

    @Test
    fun `noteLine prefers the newest heads-up, quoted`() {
        val note = BroDerived.noteLine(
            day = wednesday,
            mine = record(wednesday, AttendanceStatus.Cancelled, note = "slept in"),
            theirs = null,
            headsUps = listOf(
                headsUp(wednesday, "stuck at work", 100L),
                headsUp(wednesday, "car died", 200L),
            ),
            anyoneMissed = true,
            strings = strings,
            zone = zone,
        )
        assertEquals("“car died”", note)
    }

    @Test
    fun `noteLine falls back to the partner's note, then to a reason label`() {
        assertEquals(
            "“flu”",
            BroDerived.noteLine(
                day = wednesday,
                mine = record(wednesday, AttendanceStatus.Missed, note = "mine"),
                theirs = record(wednesday, AttendanceStatus.Cancelled, Participant.Partner, note = "flu"),
                headsUps = emptyList(),
                anyoneMissed = true,
                strings = strings,
                zone = zone,
            )
        )
        assertEquals(
            "Sick",
            BroDerived.noteLine(
                day = wednesday,
                mine = null,
                theirs = record(wednesday, AttendanceStatus.Cancelled, Participant.Partner, reason = "sick"),
                headsUps = emptyList(),
                anyoneMissed = true,
                strings = strings,
                zone = zone,
            )
        )
    }

    @Test
    fun `noteLine says nothing when nobody missed and everything is silent`() {
        assertNull(
            BroDerived.noteLine(
                day = wednesday,
                mine = record(wednesday, AttendanceStatus.Attended),
                theirs = null,
                headsUps = emptyList(),
                anyoneMissed = false,
                strings = strings,
                zone = zone,
            )
        )
        assertEquals(
            "NO_HEADS_UP",
            BroDerived.noteLine(
                day = wednesday,
                mine = record(wednesday, AttendanceStatus.Missed),
                theirs = null,
                headsUps = emptyList(),
                anyoneMissed = true,
                strings = strings,
                zone = zone,
            )
        )
    }

    @Test
    fun `reasonLabel localizes the known reasons and passes anything else through`() {
        assertEquals("Work", BroDerived.reasonLabel("  WORK ", strings))
        assertEquals("hangover", BroDerived.reasonLabel("hangover", strings))
        assertNull(BroDerived.reasonLabel("   ", strings))
    }

    // MARK: - Labels

    @Test
    fun `weekdayDay is the short weekday plus the day of month`() {
        assertEquals("Mon 2", BroDerived.weekdayDay(monday, locale))
    }

    @Test
    fun `nonGymDays returns the next two days that are not gym days`() {
        // Wednesday's next non-gym days are Thursday 5 and Saturday 7.
        assertEquals(
            listOf(wednesday.plusDays(1), wednesday.plusDays(3)),
            BroDerived.nonGymDays(wednesday, schedule),
        )
    }

    @Test
    fun `nonGymDays treats a missing schedule as all rest`() {
        assertEquals(
            listOf(wednesday.plusDays(1), wednesday.plusDays(2)),
            BroDerived.nonGymDays(wednesday, null),
        )
    }
}
