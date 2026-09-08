package app.notomorrow.service

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The `Calendar.startOfDay(for:)` bridge.
 *
 * Every `day` column in the schema (`attendance_record.day`, `meal_entry.day`,
 * `body_weight_entry.day`, `heads_up.sessionDay`, `attendance_record.makeUpDay`) is
 * **local midnight in the device zone, as epoch millis**. This is the only place that
 * conversion is written; nothing else should call `atStartOfDay` by hand.
 *
 * The zone is a parameter (defaulted, never captured) so tests are deterministic.
 */
object Days {

    /** Local midnight of [date] as epoch millis — the value stored in a `day` column. */
    fun millis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** The calendar day a `day` column (or any timestamp) falls on. */
    fun date(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /** `Calendar.startOfDay(for: date)` — the timestamp folded down to its midnight. */
    fun startOfDay(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        millis(date(epochMillis, zone), zone)

    fun date(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        instant.atZone(zone).toLocalDate()

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

    /** Start of [date] + [minuteOfDay] minutes — `AttendanceService.sessionDate`. */
    fun at(date: LocalDate, minuteOfDay: Int, zone: ZoneId = ZoneId.systemDefault()): Instant =
        date.atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toInstant()
}
