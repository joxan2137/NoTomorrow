package app.notomorrow.service

import androidx.annotation.StringRes
import app.notomorrow.data.dao.AttendanceDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.ScheduleDao
import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.Participant
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Derives strip/card states from `GymSchedule` + `AttendanceRecord`s and writes my
 * attendance — 1:1 port of `NoTomorrow/Services/AttendanceService.swift`.
 *
 * Everything that only reads is a pure function on [Companion] taking an explicit
 * `today`, so the whole derivation is testable without Room. The `mark…` functions
 * upsert through [AttendanceDao.upsert] — the only safe write path, because
 * `(day, participant)` is unique.
 *
 * There is **no 21:00 timer here**: the "did you skip?" nudge is a backend cron that
 * arrives as a push (`docs/android-architecture.md`, Services §AttendanceService).
 */
class AttendanceService(
    private val attendanceDao: AttendanceDao,
    private val scheduleDao: ScheduleDao,
    private val profileDao: ProfileDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** How far [markPastPlannedAsMissed] has judged; DataStore in the app ([SweepCursor.prefs]). */
    private val sweepCursor: SweepCursor = SweepCursor.InMemory(),
) {

    // MARK: - Writes (me)

    /** "I'm in." Keeps whatever reason/note/make-up the row already carried. */
    suspend fun markConfirmed(day: LocalDate): AttendanceRecordEntity =
        setStatus(day, AttendanceStatus.Confirmed)

    suspend fun markAttended(day: LocalDate): AttendanceRecordEntity =
        setStatus(day, AttendanceStatus.Attended)

    /**
     * A make-up session on [day] (picked in the Can't make it sheet): the day becomes planned for
     * me — the server's rule, so both sides agree. An empty day, or one that was cancelled or
     * missed, turns planned (reason, note and make-up cleared); a planned, confirmed or attended day
     * is left alone. A make-up day that passes untrained is swept to missed.
     */
    suspend fun markPlanned(day: LocalDate): AttendanceRecordEntity {
        val dayMillis = Days.millis(day, zone)
        val existing = attendanceDao.forDay(dayMillis, Participant.Me)
        if (existing != null &&
            existing.status != AttendanceStatus.Cancelled &&
            existing.status != AttendanceStatus.Missed
        ) {
            return existing
        }
        return attendanceDao.upsert(
            day = dayMillis,
            participant = Participant.Me,
            scheduledMinuteOfDay = existing?.scheduledMinuteOfDay ?: minuteOfDay(day),
            status = AttendanceStatus.Planned,
        )
    }

    /**
     * Records that I am not coming. [status] is [AttendanceStatus.Cancelled] for an
     * announced skip (the "Can't make it" sheet); pass [AttendanceStatus.Missed] for a
     * silent no-show. An empty note is stored as `null`, exactly as iOS.
     *
     * A day I already trained is never cancelled (the server's rule): the attended record comes
     * back unchanged, and the caller sends nothing. Only a workout edit or delete turns attended
     * into missed.
     */
    suspend fun markMissed(
        day: LocalDate,
        reason: String?,
        note: String?,
        makeUp: LocalDate?,
        status: AttendanceStatus = AttendanceStatus.Cancelled,
    ): AttendanceRecordEntity {
        val dayMillis = Days.millis(day, zone)
        val existing = attendanceDao.forDay(dayMillis, Participant.Me)
        if (status == AttendanceStatus.Cancelled && existing?.status == AttendanceStatus.Attended) return existing
        return attendanceDao.upsert(
            day = dayMillis,
            participant = Participant.Me,
            scheduledMinuteOfDay = existing?.scheduledMinuteOfDay ?: minuteOfDay(day),
            status = status,
            reason = reason,
            note = note?.takeIf { it.isNotEmpty() },
            makeUpDay = makeUp?.let { Days.millis(it, zone) },
        )
    }

    /**
     * `upsert(day:participant:context:scheduledMinuteOfDay:)` — ensures a row exists for
     * the pair and returns it. An existing row is returned untouched (iOS relies on that:
     * `mark…` mutates the returned object afterwards). `BroService` uses it for the
     * partner.
     */
    suspend fun upsert(
        day: LocalDate,
        participant: Participant,
        scheduledMinuteOfDay: Int? = null,
    ): AttendanceRecordEntity {
        val dayMillis = Days.millis(day, zone)
        attendanceDao.forDay(dayMillis, participant)?.let { return it }
        return attendanceDao.upsert(
            day = dayMillis,
            participant = participant,
            scheduledMinuteOfDay = scheduledMinuteOfDay ?: minuteOfDay(day),
            status = AttendanceStatus.Planned,
        )
    }

    private suspend fun setStatus(day: LocalDate, status: AttendanceStatus): AttendanceRecordEntity {
        val dayMillis = Days.millis(day, zone)
        val existing = attendanceDao.forDay(dayMillis, Participant.Me)
        return attendanceDao.upsert(
            day = dayMillis,
            participant = Participant.Me,
            scheduledMinuteOfDay = existing?.scheduledMinuteOfDay ?: minuteOfDay(day),
            status = status,
            reason = existing?.reason,
            note = existing?.note,
            makeUpDay = existing?.makeUpDay,
        )
    }

    private suspend fun minuteOfDay(day: LocalDate): Int =
        scheduleDao.schedule()?.minuteOfDay(Fmt.isoWeekday(day)) ?: DEFAULT_MINUTE_OF_DAY

    /** Drops my record for [day], so the day derives from the schedule again. */
    suspend fun clearMine(day: LocalDate) {
        attendanceDao.forDay(Days.millis(day, zone), Participant.Me)?.let { attendanceDao.delete(it) }
    }

    /**
     * My record for [day] back to planned, whatever it held ([markPlanned] leaves an attended day
     * alone) — a make-up day whose workout was edited away or deleted.
     */
    private suspend fun restorePlanned(day: LocalDate) {
        val dayMillis = Days.millis(day, zone)
        val existing = attendanceDao.forDay(dayMillis, Participant.Me)
        attendanceDao.upsert(
            day = dayMillis,
            participant = Participant.Me,
            scheduledMinuteOfDay = existing?.scheduledMinuteOfDay ?: minuteOfDay(day),
            status = AttendanceStatus.Planned,
        )
    }

    /** One of my records (a cancellation with a make-up day) names [day] as its make-up day. */
    suspend fun isMakeUpDay(day: LocalDate): Boolean =
        attendanceDao.allDesc().any { record ->
            record.participant == Participant.Me && record.makeUpDay?.let { Days.date(it, zone) } == day
        }

    // MARK: - Workout edited or deleted

    /**
     * Applies [workoutDayChanges] for a finished workout that counted on [oldDay] and now counts on
     * [newDay] (already saved in its new state, or deleted), and returns them. [oldDayStillAttended]:
     * another finished workout with a completed set started on [oldDay] — the caller asks the
     * workout store.
     */
    suspend fun applyWorkoutDayChange(
        oldDay: LocalDate?,
        newDay: LocalDate?,
        oldDayStillAttended: Boolean,
        today: LocalDate = LocalDate.now(zone),
    ): List<WorkoutDayChange> {
        if (oldDay == newDay) return emptyList()
        val schedule = scheduleDao.schedule()
        val statuses = listOfNotNull(oldDay, newDay).associateWith { record(it, Participant.Me)?.status }
        val oldIsMakeUpDay = oldDay != null && isMakeUpDay(oldDay)
        val changes = workoutDayChanges(
            oldDay = oldDay,
            newDay = newDay,
            oldDayStillAttended = oldDayStillAttended,
            isGymDay = { schedule?.isGymDay(Fmt.isoWeekday(it)) ?: false },
            myStatus = { statuses[it] },
            today = today,
            isMakeUpDay = { it == oldDay && oldIsMakeUpDay },
        )
        for (change in changes) {
            when (change) {
                is WorkoutDayChange.MarkAttended -> markAttended(change.day)
                is WorkoutDayChange.MarkMissed ->
                    markMissed(change.day, reason = null, note = null, makeUp = null, status = AttendanceStatus.Missed)
                is WorkoutDayChange.MarkPlanned -> restorePlanned(change.day)
                is WorkoutDayChange.Clear -> clearMine(change.day)
            }
        }
        return changes
    }

    /**
     * Judges the days that ended since the last sweep: a gym day with no record of mine, and any
     * day whose record is still planned or confirmed (a make-up day included), becomes `missed`.
     * Each day is judged once, by the schedule in force the first time the app opened after it
     * ([sweepCursor]), so changing the gym days never turns past rest days into misses. Bounded by
     * the profile's creation and [SWEEP_LOOKBACK_DAYS] back. Call from the dashboard on appear.
     *
     * The first sweep with a cursor judges yesterday alone, by the schedule in force now: earlier
     * builds already judged the days before it on every appear, and today's schedule must not
     * re-judge them. The cursor only moves forward, and it moves even without a schedule.
     */
    suspend fun markPastPlannedAsMissed(
        schedule: GymScheduleEntity?,
        today: LocalDate = LocalDate.now(zone),
    ) {
        val yesterday = today.minusDays(1)
        // No cursor yet: as if everything up to the day before yesterday had been judged.
        val sweptThrough = sweepCursor.sweptThrough() ?: yesterday.minusDays(1)
        val lookback = today.minusDays(SWEEP_LOOKBACK_DAYS)
        val createdAt = profileDao.createdAt()?.let { Days.date(it, zone) } ?: lookback
        val from = maxOf(lookback, createdAt, sweptThrough.plusDays(1))
        if (from.isBefore(today)) sweep(from, today, schedule)
        if (sweptThrough.isBefore(yesterday)) sweepCursor.setSweptThrough(yesterday)
    }

    /** The loop of [markPastPlannedAsMissed] over `from <= day < today`. */
    private suspend fun sweep(from: LocalDate, today: LocalDate, schedule: GymScheduleEntity?) {
        val existing = attendanceDao.range(Days.millis(from, zone), Days.millis(today, zone))
        var day = from
        while (day.isBefore(today)) {
            val iso = Fmt.isoWeekday(day)
            val dayMillis = Days.millis(day, zone)
            val mine = existing.firstOrNull { it.participant == Participant.Me && it.day == dayMillis }
            if (mine != null) {
                if (mine.status == AttendanceStatus.Planned || mine.status == AttendanceStatus.Confirmed) {
                    attendanceDao.upsert(
                        day = dayMillis,
                        participant = Participant.Me,
                        scheduledMinuteOfDay = mine.scheduledMinuteOfDay,
                        status = AttendanceStatus.Missed,
                        reason = mine.reason,
                        note = mine.note,
                        makeUpDay = mine.makeUpDay,
                    )
                }
            } else if (schedule != null && schedule.isGymDay(iso)) {
                attendanceDao.upsert(
                    day = dayMillis,
                    participant = Participant.Me,
                    scheduledMinuteOfDay = schedule.minuteOfDay(iso),
                    status = AttendanceStatus.Missed,
                )
            }
            day = day.plusDays(1)
        }
    }

    /** [markPastPlannedAsMissed] with the schedule read from the store. */
    suspend fun sweepPastPlanned(today: LocalDate = LocalDate.now(zone)) =
        markPastPlannedAsMissed(scheduleDao.schedule(), today)

    // MARK: - Fetching

    suspend fun schedule(): GymScheduleEntity? = scheduleDao.schedule()

    suspend fun record(day: LocalDate, participant: Participant): AttendanceRecordEntity? =
        attendanceDao.forDay(Days.millis(day, zone), participant)

    /** Records with `from <= day < to` (both participants). */
    suspend fun records(from: LocalDate, to: LocalDate): List<AttendanceRecordEntity> =
        attendanceDao.range(Days.millis(from, zone), Days.millis(to, zone))

    /** Records for the ISO week containing [today]. */
    suspend fun weekRecords(today: LocalDate = LocalDate.now(zone)): List<AttendanceRecordEntity> {
        val start = Fmt.startOfIsoWeek(today)
        return records(start, start.plusDays(7))
    }

    suspend fun allRecords(): List<AttendanceRecordEntity> = attendanceDao.allDesc()

    /** The Mon…Sun strip for the week containing [today], read from the store. */
    suspend fun currentWeek(today: LocalDate = LocalDate.now(zone)): List<WeekDay> =
        currentWeek(scheduleDao.schedule(), weekRecords(today), today, zone)

    /** One write to my attendance after a finished workout moved to another day, was deleted, or stopped counting. */
    sealed interface WorkoutDayChange {
        data class MarkAttended(val day: LocalDate) : WorkoutDayChange
        data class MarkMissed(val day: LocalDate) : WorkoutDayChange

        /** A make-up day that is today or later goes back to the plan the Can't make it sheet made. */
        data class MarkPlanned(val day: LocalDate) : WorkoutDayChange
        data class Clear(val day: LocalDate) : WorkoutDayChange
    }

    /**
     * The last day [markPastPlannedAsMissed] has judged — `AttendanceService.SweepCursor`. The app
     * keeps it in DataStore ([prefs], `nt.attendance.sweptThrough`); tests pass [InMemory].
     */
    interface SweepCursor {
        suspend fun sweptThrough(): LocalDate?
        suspend fun setSweptThrough(day: LocalDate)

        class InMemory(var day: LocalDate? = null) : SweepCursor {
            override suspend fun sweptThrough(): LocalDate? = day
            override suspend fun setSweptThrough(day: LocalDate) {
                this.day = day
            }
        }

        companion object {
            /** `nt.attendance.sweptThrough`, stored as an epoch day. */
            fun prefs(prefs: AppPrefs): SweepCursor = object : SweepCursor {
                override suspend fun sweptThrough(): LocalDate? =
                    prefs.attendanceSweptThroughOnce()?.let(LocalDate::ofEpochDay)

                override suspend fun setSweptThrough(day: LocalDate) =
                    prefs.setAttendanceSweptThrough(day.toEpochDay())
            }
        }
    }

    companion object {

        /** 18:00 — the fallback when there is no schedule row yet. */
        const val DEFAULT_MINUTE_OF_DAY: Int = 18 * 60

        /** Today's session still counts while it is at most 2 h in the past. */
        const val NEXT_SESSION_GRACE_SECONDS: Long = 2 * 3600

        /** The sweep never looks further back than this. */
        const val SWEEP_LOOKBACK_DAYS: Long = 30

        // MARK: - Week

        /** Mon…Sun of the ISO week containing [today]. */
        fun currentWeek(
            schedule: GymScheduleEntity?,
            records: List<AttendanceRecordEntity>,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<WeekDay> {
            val start = Fmt.startOfIsoWeek(today)
            return (0..6).map { offset ->
                val date = start.plusDays(offset.toLong())
                val iso = offset + 1
                val isGymDay = schedule?.isGymDay(iso) ?: false
                WeekDay(
                    date = date,
                    isoWeekday = iso,
                    isToday = date == today,
                    isGymDay = isGymDay,
                    myState = state(date, Participant.Me, isGymDay, records, today, zone),
                    partnerState = state(date, Participant.Partner, isGymDay, records, today, zone),
                )
            }
        }

        /**
         * State of one day for one participant: the record if there is one, else the
         * schedule. A `planned`/`confirmed` row on a day before today collapses to
         * `missed`; a **past gym day with no row at all is `rest`**, not a miss — the
         * sweep (which respects the account creation date) writes explicit misses.
         */
        fun state(
            day: LocalDate,
            participant: Participant,
            isGymDay: Boolean,
            records: List<AttendanceRecordEntity>,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): DayState {
            val record = records.firstOrNull {
                it.participant == participant && Days.date(it.day, zone) == day
            }
            val isPast = day.isBefore(today)
            if (record != null) {
                return when (record.status) {
                    AttendanceStatus.Attended -> DayState.Attended
                    AttendanceStatus.Missed -> DayState.Missed
                    AttendanceStatus.Cancelled -> DayState.Cancelled(record.reason)
                    AttendanceStatus.Confirmed -> if (isPast) DayState.Missed else DayState.Confirmed
                    AttendanceStatus.Planned -> if (isPast) DayState.Missed else DayState.Planned
                }
            }
            if (!isGymDay) return DayState.Rest
            return if (isPast) DayState.Rest else DayState.Planned
        }

        // MARK: - Next session

        /**
         * Today's session while it is at most [NEXT_SESSION_GRACE_SECONDS] in the past,
         * otherwise the next gym day within a week.
         */
        fun nextSession(
            schedule: GymScheduleEntity?,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): NextSession? {
            if (schedule == null || schedule.weekdays.isEmpty()) return null
            val today = Days.date(now, zone)
            for (offset in 0..7) {
                val day = today.plusDays(offset.toLong())
                val iso = Fmt.isoWeekday(day)
                if (!schedule.isGymDay(iso)) continue
                val minute = schedule.minuteOfDay(iso)
                val at = Days.at(day, minute, zone)
                if (offset == 0 && at.plusSeconds(NEXT_SESSION_GRACE_SECONDS).isBefore(now)) continue
                return NextSession(at = at, day = day, minuteOfDay = minute)
            }
            return null
        }

        /** Start of [day] + [minuteOfDay] — `AttendanceService.sessionDate`. */
        fun sessionDate(
            day: LocalDate,
            minuteOfDay: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Instant = Days.at(day, minuteOfDay, zone)

        // MARK: - Workout edited or deleted

        /**
         * What a workout that counted on [oldDay] and now counts on [newDay] does to my attendance.
         * A workout counts on its start day when it has a completed set; `null` = it did not count
         * or no longer counts (deleted, every set unticked). The new day is marked attended with
         * Finish's rule (any day, scheduled or not, that is not in the future) unless it already
         * is. Only `attended` is ever reverted, and only when no other finished workout keeps the
         * old day: a make-up day (one of my cancellations points at it) returns to its plan —
         * planned today or later, missed once past (what the sweep would write); a past gym day
         * becomes missed; today or a rest day loses the record and derives from the schedule again.
         */
        fun workoutDayChanges(
            oldDay: LocalDate?,
            newDay: LocalDate?,
            oldDayStillAttended: Boolean,
            isGymDay: (LocalDate) -> Boolean,
            myStatus: (LocalDate) -> AttendanceStatus?,
            today: LocalDate,
            isMakeUpDay: (LocalDate) -> Boolean = { false },
        ): List<WorkoutDayChange> {
            if (oldDay == newDay) return emptyList()
            val changes = mutableListOf<WorkoutDayChange>()
            if (newDay != null && !newDay.isAfter(today) && myStatus(newDay) != AttendanceStatus.Attended) {
                changes += WorkoutDayChange.MarkAttended(newDay)
            }
            if (oldDay != null && !oldDayStillAttended && myStatus(oldDay) == AttendanceStatus.Attended) {
                changes += when {
                    isMakeUpDay(oldDay) -> if (oldDay.isBefore(today)) {
                        WorkoutDayChange.MarkMissed(oldDay)
                    } else {
                        WorkoutDayChange.MarkPlanned(oldDay)
                    }
                    oldDay.isBefore(today) && isGymDay(oldDay) -> WorkoutDayChange.MarkMissed(oldDay)
                    else -> WorkoutDayChange.Clear(oldDay)
                }
            }
            return changes
        }

        /**
         * The status the server should hold after a local change (it has no delete): attended,
         * missed and planned as written; a cleared gym day is planned again, so the 21:00 skip
         * check can still ask. A cleared rest day has no server equivalent and is not reported.
         */
        fun wireStatus(
            change: WorkoutDayChange,
            isGymDay: (LocalDate) -> Boolean,
        ): Pair<LocalDate, AttendanceStatus>? = when (change) {
            is WorkoutDayChange.MarkAttended -> change.day to AttendanceStatus.Attended
            is WorkoutDayChange.MarkMissed -> change.day to AttendanceStatus.Missed
            is WorkoutDayChange.MarkPlanned -> change.day to AttendanceStatus.Planned
            is WorkoutDayChange.Clear -> if (isGymDay(change.day)) change.day to AttendanceStatus.Planned else null
        }

        // MARK: - Streaks & counts

        /** Days where both of us showed up, out of the days at least one of us did. */
        fun sessionsTogether(
            records: List<AttendanceRecordEntity>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): TogetherCount {
            val byDay = mutableMapOf<LocalDate, Pair<Boolean, Boolean>>()
            for (r in records) {
                if (r.status != AttendanceStatus.Attended) continue
                val key = Days.date(r.day, zone)
                val entry = byDay[key] ?: (false to false)
                byDay[key] = if (r.participant == Participant.Me) {
                    true to entry.second
                } else {
                    entry.first to true
                }
            }
            val together = byDay.values.count { it.first && it.second }
            return TogetherCount(together = together, total = byDay.size)
        }

        /** Attended sessions in the last 30 days. */
        fun attendedCount(
            records: List<AttendanceRecordEntity>,
            participant: Participant = Participant.Me,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Int = recent(records, participant, today, zone).count { it.status == AttendanceStatus.Attended }

        /** Missed **or** cancelled sessions in the last 30 days. */
        fun missedCount(
            records: List<AttendanceRecordEntity>,
            participant: Participant = Participant.Me,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Int = recent(records, participant, today, zone).count {
            it.status == AttendanceStatus.Missed || it.status == AttendanceStatus.Cancelled
        }

        /**
         * Consecutive attended gym days counting back from the most recent one that has
         * passed; planned/confirmed days ahead are skipped, the first non-attended stops
         * the count.
         */
        fun currentStreak(
            records: List<AttendanceRecordEntity>,
            participant: Participant = Participant.Me,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Int {
            val past = records
                .filter {
                    it.participant == participant &&
                        !Days.date(it.day, zone).isAfter(today) &&
                        it.status != AttendanceStatus.Planned &&
                        it.status != AttendanceStatus.Confirmed
                }
                .sortedByDescending { it.day }
            var streak = 0
            for (r in past) {
                if (r.status == AttendanceStatus.Attended) streak++ else break
            }
            return streak
        }

        private fun recent(
            records: List<AttendanceRecordEntity>,
            participant: Participant,
            today: LocalDate,
            zone: ZoneId,
        ): List<AttendanceRecordEntity> {
            val from = today.minusDays(SWEEP_LOOKBACK_DAYS)
            return records.filter {
                val day = Days.date(it.day, zone)
                it.participant == participant && !day.isBefore(from) && !day.isAfter(today)
            }
        }

        // MARK: - Labels

        /** `"weekday.mon.short"` … `"weekday.sun.short"`, clamped 1…7. */
        @StringRes
        fun shortLabelRes(isoWeekday: Int): Int = NtKeys.weekdayShort(isoWeekday)

        /** `"weekday.mon"` … `"weekday.sun"`, clamped 1…7. */
        @StringRes
        fun labelRes(isoWeekday: Int): Int = NtKeys.weekday(isoWeekday)
    }
}
