package app.notomorrow.net.dto

import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.Participant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * Wire shapes, 1:1 with `NoTomorrow/Services/BackendClient.swift` and
 * `backend/src/dto.ts`. Swift `Date` day fields become [LocalDate] (the device
 * calendar day, see [WireDay]); genuine instants stay [Instant].
 */

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
)

@Serializable
data class Me(
    val id: String,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val partner: Partner? = null,
    /** The user's current pair code, if one was issued. */
    val pairCode: String? = null,
)

@Serializable
data class Partner(
    val id: String,
    val name: String,
    @Serializable(with = WireInstantSerializer::class) val pairedAt: Instant,
)

/**
 * Wire form of `GymSchedule`. ISO weekdays (1 = Monday … 7 = Sunday), minutes
 * since midnight. `overrides` is keyed by ISO weekday; kotlinx-serialization
 * writes primitive map keys as JSON strings, which is what the server's
 * `z.record(z.string().regex(/^[1-7]$/), minute)` expects.
 *
 * The Swift initialiser sorts `weekdays`; use [of] for the same guarantee (a
 * `@Serializable` primary constructor cannot rewrite its own arguments).
 */
@Serializable
data class ScheduleDto(
    val weekdays: List<Int>,
    val defaultMinuteOfDay: Int,
    val overrides: Map<Int, Int> = emptyMap(),
    /** Job toggles the server's reminder / 21:00 cron reads. `null` leaves the server value untouched. */
    val remindHourBefore: Boolean? = null,
    val askIfSkippedAt21: Boolean? = null,
) {
    fun minuteOfDay(isoWeekday: Int): Int = overrides[isoWeekday] ?: defaultMinuteOfDay

    fun isGymDay(isoWeekday: Int): Boolean = weekdays.contains(isoWeekday)

    /** The sorted form the Swift initialiser and the server both produce. */
    fun sorted(): ScheduleDto = copy(weekdays = weekdays.sorted())

    companion object {
        fun of(
            weekdays: List<Int>,
            defaultMinuteOfDay: Int,
            overrides: Map<Int, Int> = emptyMap(),
            remindHourBefore: Boolean? = null,
            askIfSkippedAt21: Boolean? = null,
        ): ScheduleDto = ScheduleDto(weekdays.sorted(), defaultMinuteOfDay, overrides, remindHourBefore, askIfSkippedAt21)
    }
}

@Serializable
data class AttendanceDto(
    @Serializable(with = WireDaySerializer::class) val day: LocalDate,
    @Serializable(with = ParticipantSerializer::class) val participant: Participant,
    @Serializable(with = AttendanceStatusSerializer::class) val status: AttendanceStatus,
    val reason: String? = null,
    val note: String? = null,
    @Serializable(with = WireDaySerializer::class) val makeUpDay: LocalDate? = null,
) {
    /** Stable list identity, the analogue of the Swift `Identifiable` conformance. */
    val id: String get() = "${participant.raw}-$day"
}

@Serializable
data class HeadsUpDto(
    val id: String,
    val fromMe: Boolean,
    @Serializable(with = HeadsUpKindSerializer::class) val kind: HeadsUpKind,
    val text: String,
    @Serializable(with = WireDaySerializer::class) val sessionDay: LocalDate,
    @Serializable(with = WireInstantSerializer::class) val sentAt: Instant,
)

/** `GET /partner/state` — one merged attendance list, each row tagged with its participant. */
@Serializable
data class PartnerState(
    val partnerName: String,
    val partnerSchedule: ScheduleDto,
    val attendance: List<AttendanceDto> = emptyList(),
    val headsUps: List<HeadsUpDto> = emptyList(),
)

/** `POST /pair/code` — `{code, expiresAt}`. */
@Serializable
internal data class PairCodeReply(val code: String)

/** Non-2xx envelope: `{error, message}`. */
@Serializable
internal data class ErrorEnvelope(
    @SerialName("error") val error: String? = null,
    @SerialName("message") val message: String? = null,
)
