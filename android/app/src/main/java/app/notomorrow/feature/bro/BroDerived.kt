package app.notomorrow.feature.bro

import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.HeadsUpEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.Participant
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.DayState
import app.notomorrow.service.Days
import app.notomorrow.util.Fmt
import app.notomorrow.util.Localizer
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * One row of the Bro log: a past gym day with what each of us did —
 * `BroLogRow` (`Features/Bro/BroDerived.swift:5`).
 *
 * [note] is an **already localized** second line (a quoted heads-up / note, a reason, or
 * "No heads-up sent"); `null` hides the line.
 */
data class BroLogRow(
    val day: LocalDate,
    val routineName: String?,
    val myState: DayState,
    val partnerState: DayState,
    val note: String?,
) {
    val anyoneMissed: Boolean
        get() = myState.isMissedOrCancelled || partnerState.isMissedOrCancelled
}

/** The "Today · 18:00 · Push A" block under the shared-week rows (`BroDerived.swift:19`). */
data class BroSessionLine(
    val day: LocalDate,
    val minuteOfDay: Int,
    val routineName: String?,
    val myState: DayState,
    val partnerState: DayState,
    /** `updatedAt` of my confirmed/attended record, as epoch millis. */
    val myConfirmedAt: Long?,
    val partnerConfirmedAt: Long?,
) {
    val bothIn: Boolean get() = isIn(myState) && isIn(partnerState)

    private fun isIn(s: DayState): Boolean = s == DayState.Confirmed || s == DayState.Attended
}

/**
 * Pure derivations for the Bro tab — the port of `BroDerived`
 * (`Features/Bro/BroDerived.swift`). Nothing here touches Room or Android: the view model
 * feeds it lists it already collected, which is what makes the JVM test possible.
 */
object BroDerived {

    /** `logRows(limit:)` — the log never shows more than ten days. */
    const val LOG_LIMIT: Int = 10

    /** `logRows` never walks further back than 60 days. */
    const val LOG_LOOKBACK_DAYS: Long = 60

    /** `nonGymDays` scans at most two weeks ahead. */
    private const val MAKE_UP_SCAN_DAYS: Int = 14

    // MARK: - Routine names

    /**
     * Name of the routine for [day]: the workout done that day if any, otherwise the next
     * one in rotation after the most recent completed workout, otherwise the first routine.
     */
    fun routineName(
        day: LocalDate,
        routines: List<RoutineEntity>,
        workouts: List<WorkoutEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val completed = workouts.filter { it.endedAt != null }.sortedByDescending { it.startedAt }
        completed.firstOrNull { Days.date(it.startedAt, zone) == day }?.let { return it.name }
        val ordered = routines.sortedBy { it.order }
        if (ordered.isEmpty()) return null
        val last = completed.firstOrNull()
        if (last != null) {
            val index = ordered.indexOfFirst { it.name == last.name }
            if (index >= 0) return ordered[(index + 1) % ordered.size].name
        }
        return ordered.first().name
    }

    /** Completed workout name on [day], if one happened. */
    fun workoutName(
        day: LocalDate,
        workouts: List<WorkoutEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? = workouts.firstOrNull {
        it.endedAt != null && Days.date(it.startedAt, zone) == day
    }?.name

    // MARK: - Session line

    fun sessionLine(
        schedule: GymScheduleEntity?,
        records: List<AttendanceRecordEntity>,
        routines: List<RoutineEntity>,
        workouts: List<WorkoutEntity>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): BroSessionLine? {
        val next = AttendanceService.nextSession(schedule, now, zone) ?: return null
        val today = Days.date(now, zone)
        val day = next.day
        val isGymDay = schedule?.isGymDay(Fmt.isoWeekday(day)) ?: false
        val mine = record(records, Participant.Me, day, zone)
        val theirs = record(records, Participant.Partner, day, zone)
        return BroSessionLine(
            day = day,
            minuteOfDay = next.minuteOfDay,
            routineName = routineName(day, routines, workouts, zone),
            myState = AttendanceService.state(day, Participant.Me, isGymDay, records, today, zone),
            partnerState = AttendanceService.state(day, Participant.Partner, isGymDay, records, today, zone),
            myConfirmedAt = confirmedAt(mine),
            partnerConfirmedAt = confirmedAt(theirs),
        )
    }

    /**
     * The Kolega tab's "Can't make it" chip, by the Today card's rule
     * (`NextSessionCard.offersCantMakeIt`): not once the session's day is attended. The sheet
     * would promise a missed day and a message to the partner, and `AttendanceService.markMissed`
     * keeps an attended day as it is, so Send could only close it. With no session line the
     * sheet falls back to today, as before.
     */
    fun offersCantMakeIt(line: BroSessionLine?): Boolean = line?.myState != DayState.Attended

    private fun confirmedAt(record: AttendanceRecordEntity?): Long? {
        if (record == null) return null
        if (record.status != AttendanceStatus.Confirmed && record.status != AttendanceStatus.Attended) return null
        return record.updatedAt
    }

    // MARK: - Log

    /**
     * The last [limit] gym days (most recent first) since we both existed: gym days per the
     * schedule, plus any day that has a terminal record for me. Days before the profile or
     * the pairing are skipped.
     */
    fun logRows(
        schedule: GymScheduleEntity?,
        records: List<AttendanceRecordEntity>,
        workouts: List<WorkoutEntity>,
        headsUps: List<HeadsUpEntity>,
        profileCreatedAt: Long?,
        pairedAt: Long?,
        strings: Localizer,
        today: LocalDate = LocalDate.now(),
        limit: Int = LOG_LIMIT,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<BroLogRow> {
        val lookback = today.minusDays(LOG_LOOKBACK_DAYS)
        val floor = listOfNotNull(
            lookback,
            profileCreatedAt?.let { Days.date(it, zone) },
            pairedAt?.let { Days.date(it, zone) },
        ).max()

        val rows = ArrayList<BroLogRow>(limit)
        var day = today
        while (!day.isBefore(floor) && rows.size < limit) {
            val iso = Fmt.isoWeekday(day)
            val isGymDay = schedule?.isGymDay(iso) ?: false
            val mine = record(records, Participant.Me, day, zone)
            val theirs = record(records, Participant.Partner, day, zone)
            val isPast = day.isBefore(today)
            val include = (isPast && (isGymDay || mine != null)) ||
                (!isPast && (isTerminal(mine) || isTerminal(theirs)))
            if (include) {
                val myState = AttendanceService.state(day, Participant.Me, isGymDay, records, today, zone)
                val partnerState = AttendanceService.state(day, Participant.Partner, isGymDay, records, today, zone)
                rows.add(
                    BroLogRow(
                        day = day,
                        routineName = workoutName(day, workouts, zone),
                        // Today with only one terminal record: leave the other side as planned, not missed.
                        myState = if (isPast) myState else if (isTerminal(mine)) myState else DayState.Planned,
                        partnerState = if (isPast) partnerState else if (isTerminal(theirs)) partnerState else DayState.Planned,
                        note = noteLine(
                            day = day,
                            mine = mine,
                            theirs = theirs,
                            headsUps = headsUps,
                            anyoneMissed = myState.isMissedOrCancelled || partnerState.isMissedOrCancelled,
                            strings = strings,
                            zone = zone,
                        ),
                    )
                )
            }
            day = day.minusDays(1)
        }
        return rows
    }

    private fun isTerminal(record: AttendanceRecordEntity?): Boolean {
        val status = record?.status ?: return false
        return status == AttendanceStatus.Attended ||
            status == AttendanceStatus.Missed ||
            status == AttendanceStatus.Cancelled
    }

    /**
     * Quoted heads-up text for the day, else the note or reason of whoever bailed, else
     * "No heads-up sent" when someone missed.
     */
    fun noteLine(
        day: LocalDate,
        mine: AttendanceRecordEntity?,
        theirs: AttendanceRecordEntity?,
        headsUps: List<HeadsUpEntity>,
        anyoneMissed: Boolean,
        strings: Localizer,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val dayHeadsUps = headsUps
            .filter {
                Days.date(it.sessionDay, zone) == day &&
                    it.kind == HeadsUpKind.CantMakeIt &&
                    it.text.isNotEmpty()
            }
            .sortedByDescending { it.sentAt }
        dayHeadsUps.firstOrNull()?.let { return quoted(it.text) }

        for (record in listOfNotNull(theirs, mine)) {
            if (record.status != AttendanceStatus.Missed && record.status != AttendanceStatus.Cancelled) continue
            val note = record.note
            if (!note.isNullOrEmpty()) return quoted(note)
            val reason = record.reason
            if (reason != null) reasonLabel(reason, strings)?.let { return it }
        }
        return if (anyoneMissed) strings.string(S.bro_noHeadsUp) else null
    }

    private fun quoted(text: String): String = "“$text”"

    /** `"sick"` → "Sick" via the `cant.reason.*` keys; unknown reasons are shown as-is. */
    fun reasonLabel(reason: String, strings: Localizer): String? {
        val trimmed = reason.trim()
        if (trimmed.isEmpty()) return null
        val key = NtKeys.cantReason(trimmed) ?: return reason
        return strings.string(key)
    }

    // MARK: - Labels

    /** "Wed 2" — abbreviated weekday plus day of month, for the log column and make-up chips. */
    fun weekdayDay(date: LocalDate, locale: Locale = LocaleProvider.current()): String =
        Fmt.weekdayShort(date, locale) + " " + Fmt.dayOfMonth(date, locale)

    /** Next [count] days after [day] that are not gym days (within two weeks). */
    fun nonGymDays(
        day: LocalDate,
        schedule: GymScheduleEntity?,
        count: Int = 2,
    ): List<LocalDate> {
        val result = ArrayList<LocalDate>(count)
        for (offset in 1..MAKE_UP_SCAN_DAYS) {
            if (result.size >= count) break
            val candidate = day.plusDays(offset.toLong())
            if (schedule?.isGymDay(Fmt.isoWeekday(candidate)) == true) continue
            result.add(candidate)
        }
        return result
    }

    private fun record(
        records: List<AttendanceRecordEntity>,
        participant: Participant,
        day: LocalDate,
        zone: ZoneId,
    ): AttendanceRecordEntity? = records.firstOrNull {
        it.participant == participant && Days.date(it.day, zone) == day
    }
}
