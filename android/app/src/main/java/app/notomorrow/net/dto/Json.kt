package app.notomorrow.net.dto

import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.MealSlot
import app.notomorrow.model.Participant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The one `Json` the backend layer speaks.
 *
 * - `ignoreUnknownKeys`: the server sends more than the iOS DTOs declare
 *   (`scheduledMinute`, `updatedAt`, `emailVerified`, `user`, `partner`, …).
 * - `explicitNulls = false`: the mirror of Swift's `encodeIfPresent`. Several
 *   Zod schemas use `.optional()` rather than `.nullish()` (`PATCH /me` is
 *   `.strict()`, `PUT /schedule`'s toggles), so an explicit `null` would 400.
 * - `encodeDefaults = true`: request bodies carry constants such as
 *   `platform: "android"` as defaults.
 */
val NtJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Model enums put their **iOS raw string** on the wire, which is not the Kotlin
 * enum-entry name — hence one serializer per enum instead of `@Serializable`.
 */
private inline fun <reified T : Enum<T>> rawEnumSerializer(
    name: String,
    crossinline raw: (T) -> String,
    crossinline from: (String) -> T?,
): KSerializer<T> = object : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(raw(value))
    override fun deserialize(decoder: Decoder): T {
        val s = decoder.decodeString()
        return from(s) ?: throw SerializationException("Unknown $name '$s'")
    }
}

object AttendanceStatusSerializer : KSerializer<AttendanceStatus> by rawEnumSerializer(
    "app.notomorrow.model.AttendanceStatus", { it.raw }, { AttendanceStatus.from(it) },
)

object HeadsUpKindSerializer : KSerializer<HeadsUpKind> by rawEnumSerializer(
    "app.notomorrow.model.HeadsUpKind", { it.raw }, { HeadsUpKind.from(it) },
)

object ParticipantSerializer : KSerializer<Participant> by rawEnumSerializer(
    "app.notomorrow.model.Participant", { it.raw }, { Participant.from(it) },
)

object MealSlotSerializer : KSerializer<MealSlot> by rawEnumSerializer(
    "app.notomorrow.model.MealSlot", { it.raw }, { MealSlot.from(it) },
)
