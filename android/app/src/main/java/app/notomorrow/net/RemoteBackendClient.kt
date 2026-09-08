package app.notomorrow.net

import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AttendanceStatusSerializer
import app.notomorrow.net.dto.HeadsUpKindSerializer
import app.notomorrow.net.dto.Me
import app.notomorrow.net.dto.PairCodeReply
import app.notomorrow.net.dto.Partner
import app.notomorrow.net.dto.PartnerState
import app.notomorrow.net.dto.ScheduleDto
import app.notomorrow.net.dto.Session
import app.notomorrow.net.dto.WireDay
import app.notomorrow.net.dto.WireDaySerializer
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * JSON-over-HTTPS implementation of [BackendClient] for the Fly.io backend
 * (the `backend/src/routes` handlers are the source of truth for paths and shapes) — the
 * port of `NoTomorrow/Services/RemoteBackendClient.swift`.
 *
 * The Bearer token is read from [SessionStore] on every call; a 401 triggers one
 * refresh + retry (see [RemoteTransport]). Without a session every authenticated
 * call throws [BackendError.Unauthorized] before touching the network, so the UI
 * can show its signed-out state.
 */
class RemoteBackendClient(
    override val baseUrl: Url,
    store: SessionStore,
    engine: HttpClientEngine? = null,
) : BackendClient {

    private val transport = RemoteTransport(baseUrl = baseUrl, store = store, engine = engine)

    // MARK: Auth (public routes)

    override suspend fun signInApple(identityToken: String, authorizationCode: String): Session =
        transport.send(
            "POST", "auth/apple", Session.serializer(),
            AppleBody.serializer(), AppleBody(identityToken, authorizationCode),
            auth = RemoteTransport.Auth.NONE,
        )

    override suspend fun signInGoogle(idToken: String): Session =
        transport.send(
            "POST", "auth/google", Session.serializer(),
            GoogleBody.serializer(), GoogleBody(idToken),
            auth = RemoteTransport.Auth.NONE,
        )

    override suspend fun signIn(username: String, password: String): Session =
        transport.send(
            "POST", "auth/login", Session.serializer(),
            LoginBody.serializer(), LoginBody(username, password),
            auth = RemoteTransport.Auth.NONE,
        )

    override suspend fun register(username: String, password: String, email: String?): Session =
        transport.send(
            "POST", "auth/register", Session.serializer(),
            RegisterBody.serializer(), RegisterBody(username, password, email),
            auth = RemoteTransport.Auth.NONE,
        )

    override suspend fun logout(refreshToken: String) =
        transport.sendVoid(
            "POST", "auth/logout",
            LogoutBody.serializer(), LogoutBody(refreshToken),
            auth = RemoteTransport.Auth.NONE,
        )

    // MARK: Account

    override suspend fun me(): Me = transport.send("GET", "me", Me.serializer())

    override suspend fun updateMe(locale: String?, timeZone: String?) =
        transport.sendVoid("PATCH", "me", PatchMeBody.serializer(), PatchMeBody(locale, timeZone))

    override suspend fun deleteAccount() = transport.sendVoid("DELETE", "me")

    // MARK: Pairing

    override suspend fun createPairCode(): String =
        transport.send("POST", "pair/code", PairCodeReply.serializer()).code

    /** The reply carries the partner both at the top level and under `partner`; [Partner] decodes the top level. */
    override suspend fun pair(code: String): Partner =
        transport.send("POST", "pair", Partner.serializer(), PairBody.serializer(), PairBody(code))

    override suspend fun unpair() = transport.sendVoid("DELETE", "pair")

    // MARK: Schedule, attendance, heads-ups

    override suspend fun pushSchedule(schedule: ScheduleDto) =
        transport.sendVoid("PUT", "schedule", ScheduleDto.serializer(), schedule.sorted())

    /** `/partner/state` merges partner and own rows into one `attendance` list tagged with `participant`. */
    override suspend fun partnerState(): PartnerState =
        transport.send("GET", "partner/state", PartnerState.serializer())

    override suspend fun setAttendance(
        day: LocalDate,
        status: AttendanceStatus,
        reason: String?,
        note: String?,
        makeUpDay: LocalDate?,
    ) = transport.sendVoid(
        "PUT", "attendance/${WireDay.string(day)}",
        AttendanceBody.serializer(), AttendanceBody(status, reason, note, makeUpDay),
    )

    override suspend fun sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: LocalDate) =
        transport.sendVoid(
            "POST", "headsups",
            HeadsUpBody.serializer(), HeadsUpBody(kind, text, sessionDay),
        )

    // MARK: Push

    /**
     * FCM registration tokens are case-sensitive and contain `:`/`-`/`_`, so they
     * go up verbatim with an explicit `platform`. See the shared-change request in
     * `docs/android-research.md` §7.8 — today's `routes/push.ts` still validates
     * `/^[0-9a-fA-F]{32,512}$/` and lowercases the token.
     */
    override suspend fun registerPushToken(token: String, platform: String) =
        transport.sendVoid("POST", "push/token", PushTokenBody.serializer(), PushTokenBody(token, platform))

    /**
     * Sign-out. The body is `{token}` only — the route matches on the token and the bearer's
     * user id (`backend/src/routes/push.ts`). Same `platform`-blind shape as the POST, so the
     * server's token validation has to accept FCM tokens for either call to work
     * (`docs/android-backend-changes.md`).
     */
    override suspend fun unregisterPushToken(token: String) =
        transport.sendVoid("DELETE", "push/token", DeletePushTokenBody.serializer(), DeletePushTokenBody(token))

    // MARK: AI

    /**
     * Multipart `image` + `meal` + `locale`. The server answers
     * `{foods, overallConfidence}` with `proteinG`-style keys, which the tolerant
     * `AIFood` decoder accepts. Errors surface as [BackendError.Http] with the
     * server code (`ai_daily_limit`, `ai_upstream_error`, `ai_unavailable`, …)
     * for `AIEstimateService` to map.
     */
    override suspend fun estimate(
        imageJpeg: ByteArray,
        meal: MealSlot,
        locale: String,
        anthropicKey: String?,
        notes: String,
    ): AIEstimate {
        val headers = buildMap {
            if (!anthropicKey.isNullOrEmpty()) put("X-Anthropic-Key", anthropicKey)
        }
        val request = RemoteTransport.Request(
            method = "POST",
            path = "ai/estimate",
            auth = RemoteTransport.Auth.REQUIRED,
            payload = RemoteTransport.Payload.Multipart {
                formData {
                    append("meal", meal.raw)
                    append("locale", locale)
                    append("notes", notes.take(1500))
                    append(
                        "image",
                        imageJpeg,
                        Headers.build {
                            append(HttpHeaders.ContentType, "image/jpeg")
                            append(HttpHeaders.ContentDisposition, "filename=\"plate.jpg\"")
                        },
                    )
                }
            },
            headers = headers,
            timeoutMillis = RemoteTransport.AI_TIMEOUT_MS,
        )
        return transport.decode(transport.perform(request), AIEstimate.serializer())
    }
}

// MARK: - Request bodies

@Serializable
private data class AppleBody(val identityToken: String, val authorizationCode: String)

@Serializable
private data class GoogleBody(val idToken: String)

@Serializable
private data class LoginBody(val username: String, val password: String)

@Serializable
private data class RegisterBody(val username: String, val password: String, val email: String? = null)

@Serializable
private data class LogoutBody(val refreshToken: String)

@Serializable
private data class PatchMeBody(val locale: String? = null, val tz: String? = null)

@Serializable
private data class PairBody(val code: String)

@Serializable
private data class AttendanceBody(
    @Serializable(with = AttendanceStatusSerializer::class) val status: AttendanceStatus,
    val reason: String? = null,
    val note: String? = null,
    @Serializable(with = WireDaySerializer::class) val makeUpDay: LocalDate? = null,
)

@Serializable
private data class HeadsUpBody(
    @Serializable(with = HeadsUpKindSerializer::class) val kind: HeadsUpKind,
    val text: String,
    @Serializable(with = WireDaySerializer::class) val sessionDay: LocalDate,
)

@Serializable
private data class PushTokenBody(val token: String, val platform: String)

@Serializable
private data class DeletePushTokenBody(val token: String)
