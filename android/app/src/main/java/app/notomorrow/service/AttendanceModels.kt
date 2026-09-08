package app.notomorrow.service

import androidx.annotation.StringRes
import app.notomorrow.util.NtKeys
import java.time.Instant
import java.time.LocalDate

/**
 * What a single day looks like on the week strip / shared-week card, for one participant
 * — port of `DayState` (`Services/AttendanceService.swift:5`).
 */
sealed class DayState {
    /** Not a gym day (or a past gym day the sweep has not resolved). */
    data object Rest : DayState()

    /** Scheduled, nothing said yet. */
    data object Planned : DayState()

    /** "I'm in." */
    data object Confirmed : DayState()

    data object Attended : DayState()

    /** Silent no-show. */
    data object Missed : DayState()

    /** Announced skip — [reason] is a raw `cant.reason.*` key or free text. */
    data class Cancelled(val reason: String?) : DayState()

    val isMissedOrCancelled: Boolean
        get() = this is Missed || this is Cancelled
}

/**
 * One column of the Mon…Sun strip — port of `WeekDay`
 * (`Services/AttendanceService.swift:20`). [date] is the calendar day, not a timestamp.
 */
data class WeekDay(
    val date: LocalDate,
    val isoWeekday: Int,
    val isToday: Boolean,
    val isGymDay: Boolean,
    val myState: DayState,
    val partnerState: DayState,
) {
    /** `WeekDay.shortLabelKey` — the single-letter column label. */
    @get:StringRes
    val shortLabelRes: Int get() = NtKeys.weekdayShort(isoWeekday)

    /** The full weekday name, for accessibility labels and sheets. */
    @get:StringRes
    val labelRes: Int get() = NtKeys.weekday(isoWeekday)
}

/**
 * The result of `AttendanceService.nextSession` — the session's absolute instant plus the
 * day and minute it was derived from.
 */
data class NextSession(
    val at: Instant,
    val day: LocalDate,
    val minuteOfDay: Int,
)

/** `AttendanceService.sessionsTogether` — days both of us showed up, out of days either did. */
data class TogetherCount(val together: Int, val total: Int)
