package app.notomorrow.push

import com.google.firebase.messaging.RemoteMessage
import kotlin.math.absoluteValue

/**
 * The message kinds the backend sends. The wire values are
 * `PushMessage["kind"]` in `backend/src/i18n.ts`, put on the payload as `type`
 * by `buildApnsPayload` (`backend/src/push.ts`).
 *
 * `docs/android-architecture.md` names four of them in snake_case
 * (`heads_up | partner_confirmed | partner_cancelled | pair_accepted`); those
 * spellings are accepted as aliases so the client works whichever naming the
 * FCM sender lands on.
 */
enum class PushKind(val wire: String) {
    Paired("paired"),
    Unpaired("unpaired"),
    AttendanceConfirmed("attendanceConfirmed"),
    AttendanceCancelled("attendanceCancelled"),
    HeadsUp("headsUp"),
    Reminder("reminder"),
    SkipCheck("skipCheck");

    /**
     * `AppState.pendingRoute` value this message opens. Every push the backend
     * sends is about the gym bro (pairing, attendance, heads-ups, the reminder
     * and the evening skip check), so they all land on the Bro tab.
     */
    val route: String get() = NtPushIntents.ROUTE_BRO

    companion object {
        private val byWire: Map<String, PushKind> = buildMap {
            PushKind.entries.forEach { kind -> put(kind.wire.lowercase(), kind) }
            // Aliases from docs/android-architecture.md, plus snake_case forms.
            put("heads_up", HeadsUp)
            put("headsup", HeadsUp)
            put("partner_confirmed", AttendanceConfirmed)
            put("attendance_confirmed", AttendanceConfirmed)
            put("partner_cancelled", AttendanceCancelled)
            put("attendance_cancelled", AttendanceCancelled)
            put("pair_accepted", Paired)
            put("skip_check", SkipCheck)
        }

        fun from(raw: String?): PushKind? =
            raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let(byWire::get)
    }
}

/** Intent extras and route names shared with the app shell. */
object NtPushIntents {
    /**
     * `AppState.pendingRoute` to consume on launch — one of
     * `restTimer | activeWorkout | bro | settings`.
     */
    const val EXTRA_ROUTE = "app.notomorrow.push.route"

    /** [PushKind.wire] of the message that was tapped, when it was recognised. */
    const val EXTRA_KIND = "app.notomorrow.push.kind"

    /** `YYYY-MM-DD` day the message is about, when the payload carries one. */
    const val EXTRA_SESSION_DAY = "app.notomorrow.push.sessionDay"

    const val ROUTE_BRO = "bro"
}

/**
 * A push message flattened into what the client actually needs.
 *
 * The copy is localized **server-side** from `users.locale`
 * (`backend/src/i18n.ts`), so the client only displays and routes — it never
 * builds a sentence.
 *
 * Key sources, in order:
 *  - `title` / `body`: the FCM data keys the backend must send for Android
 *    (a data-only message, so [NtMessagingService.onMessageReceived] runs in
 *    every app state), falling back to an FCM `notification` block if the
 *    sender uses one.
 *  - `type`: `buildApnsPayload`'s `type` field; `kind` accepted as an alias.
 *  - `sessionDay` / `day`, `partnerId`, `headsUpId`, `headsUpKind`: the `extra`
 *    objects spread into the payload at the seven `push.send(...)` call sites.
 */
data class PushPayload(
    val kind: PushKind?,
    val title: String?,
    val body: String?,
    val sessionDay: String?,
    val partnerId: String?,
    val headsUpId: String?,
    val headsUpKind: String?,
    val threadId: String?,
    val route: String,
) {

    /** True when there is nothing to show — a silent or malformed message. */
    val isEmpty: Boolean get() = title.isNullOrBlank() && body.isNullOrBlank()

    /**
     * Stable identity for the message. Re-delivery of the same heads-up (or the
     * same day's attendance change) replaces its notification instead of
     * stacking a duplicate; different messages stack.
     */
    val collapseId: String
        get() = headsUpId
            ?: listOfNotNull(kind?.wire, sessionDay, partnerId)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(":")
            ?: (title.orEmpty() + "|" + body.orEmpty())

    /** Notification id derived from [collapseId]; never collides with `rest/`'s 2601/2602. */
    val notificationId: Int
        get() = NOTIFICATION_ID_BASE + (collapseId.hashCode().absoluteValue % NOTIFICATION_ID_SPAN)

    companion object {
        /** APNs `thread-id`, mirrored as the Android notification group. */
        const val DEFAULT_GROUP = "bro"

        private const val NOTIFICATION_ID_BASE = 2700
        private const val NOTIFICATION_ID_SPAN = 1000

        fun from(message: RemoteMessage): PushPayload = from(message.data, message.notification)

        fun from(
            data: Map<String, String>,
            notification: RemoteMessage.Notification? = null,
        ): PushPayload {
            fun value(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { key -> data[key]?.trim()?.takeIf { it.isNotEmpty() } }

            val kind = PushKind.from(value("type", "kind"))
            return PushPayload(
                kind = kind,
                title = value("title", "alertTitle") ?: notification?.title?.trim()?.ifEmpty { null },
                body = value("body", "alertBody", "message") ?: notification?.body?.trim()?.ifEmpty { null },
                sessionDay = value("sessionDay", "day"),
                partnerId = value("partnerId"),
                headsUpId = value("headsUpId"),
                headsUpKind = value("headsUpKind"),
                threadId = value("thread-id", "threadId") ?: DEFAULT_GROUP,
                route = value("route") ?: kind?.route ?: NtPushIntents.ROUTE_BRO,
            )
        }
    }
}
