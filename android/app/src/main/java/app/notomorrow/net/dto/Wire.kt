package app.notomorrow.net.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Wire dates — port of `WireDay` / `WireDateDecoding` in
 * `NoTomorrow/Services/RemoteTransport.swift`.
 *
 * "Day" fields go to the server as `YYYY-MM-DD` in the **device calendar**, so the
 * calendar day never shifts with the server's idea of the user's time zone. The
 * server answers with the instant of local midnight in the caller's time zone;
 * decoding lands it back on a [LocalDate] in the device zone, which is exactly
 * what iOS does downstream with `Calendar.current.startOfDay(for:)`.
 */
object WireDay {

    /** `YYYY-MM-DD`. [LocalDate.toString] is ISO-8601 and zero-pads to four year digits. */
    fun string(date: LocalDate): String = date.toString()

    /** Bare `YYYY-MM-DD` only — the Swift `WireDay.date(_:)`. */
    fun date(string: String): LocalDate? =
        if (DAY_RE.matches(string)) runCatching { LocalDate.parse(string) }.getOrNull() else null

    /**
     * Tolerant day parse: a bare `YYYY-MM-DD`, else an ISO-8601 instant resolved
     * to its calendar day in [zone].
     */
    fun parse(string: String, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        date(string) ?: WireInstant.parse(string)?.atZone(zone)?.toLocalDate()

    private val DAY_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")
}

/**
 * Instant decoding tries ISO-8601 internet date-time, then with fractional seconds,
 * then a bare `YYYY-MM-DD` as local midnight. Encoding is plain ISO-8601 without
 * fractional seconds — the server emits `…Z` with no millis.
 */
object WireInstant {

    fun parse(string: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        // Handles both `…Z` and `…+02:00`, with or without fractional seconds.
        runCatching { OffsetDateTime.parse(string).toInstant() }.getOrNull()?.let { return it }
        runCatching { Instant.parse(string) }.getOrNull()?.let { return it }
        return WireDay.date(string)?.atStartOfDay(zone)?.toInstant()
    }

    fun string(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))
}

/** `LocalDate` <-> `YYYY-MM-DD`, tolerant of the instant form on the way in. */
object WireDaySerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.notomorrow.net.dto.WireDay", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(WireDay.string(value))

    override fun deserialize(decoder: Decoder): LocalDate {
        val raw = decoder.decodeString()
        return WireDay.parse(raw) ?: throw SerializationException("Unrecognised day $raw")
    }
}

/** `Instant` <-> ISO-8601 without fractional seconds. */
object WireInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.notomorrow.net.dto.WireInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(WireInstant.string(value))

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return WireInstant.parse(raw) ?: throw SerializationException("Unrecognised date $raw")
    }
}
