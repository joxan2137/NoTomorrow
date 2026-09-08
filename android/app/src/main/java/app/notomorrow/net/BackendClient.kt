package app.notomorrow.net

import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.Me
import app.notomorrow.net.dto.Partner
import app.notomorrow.net.dto.PartnerState
import app.notomorrow.net.dto.ScheduleDto
import app.notomorrow.net.dto.Session
import io.ktor.http.Url
import java.time.LocalDate

/**
 * Everything the app needs from the Fly.io backend (auth, pairing, shared
 * attendance, AI estimates) — 1:1 with the `BackendClient` protocol in
 * `NoTomorrow/Services/BackendClient.swift`.
 *
 * [MockBackendClient] implements it in memory for offline demos;
 * [RemoteBackendClient] speaks JSON over HTTPS. Every method throws
 * [BackendError]; `Unauthorized` is a *state* for the UI, not a banner.
 */
interface BackendClient {

    val baseUrl: Url

    suspend fun signInApple(identityToken: String, authorizationCode: String): Session

    suspend fun signInGoogle(idToken: String): Session

    suspend fun signIn(username: String, password: String): Session

    suspend fun register(username: String, password: String, email: String?): Session

    /** Best-effort server-side revoke of the refresh token; the caller clears storage regardless. */
    suspend fun logout(refreshToken: String)

    suspend fun me(): Me

    /** Locale/time zone the server uses for push copy and for "day" instants in replies. */
    suspend fun updateMe(locale: String?, timeZone: String?)

    suspend fun createPairCode(): String

    suspend fun pair(code: String): Partner

    suspend fun unpair()

    suspend fun pushSchedule(schedule: ScheduleDto)

    /** Partner schedule, attendance for the current week (plus recent history) and unread heads-ups. */
    suspend fun partnerState(): PartnerState

    suspend fun setAttendance(
        day: LocalDate,
        status: AttendanceStatus,
        reason: String? = null,
        note: String? = null,
        makeUpDay: LocalDate? = null,
    )

    suspend fun sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: LocalDate)

    /** iOS sends the APNs token as lowercase hex; Android sends the FCM registration token verbatim. */
    suspend fun registerPushToken(token: String, platform: String = ANDROID_PLATFORM)

    /**
     * `DELETE push/token {token}` — drops this device's row so a signed-out install stops
     * receiving the previous account's pushes.
     *
     * Android-only: iOS never unregisters (`BackendClient.swift:29` has `registerPushToken`
     * alone) because APNs tokens are per-install and the server prunes on a 410. An FCM token
     * survives a sign-out until `deleteToken()` completes, so the row has to go explicitly —
     * and it has to go **before** the session is cleared, since the route needs the bearer.
     */
    suspend fun unregisterPushToken(token: String)

    suspend fun estimate(
        imageJpeg: ByteArray,
        meal: MealSlot,
        locale: String,
        anthropicKey: String? = null,
        notes: String = "",
    ): AIEstimate

    suspend fun deleteAccount()

    companion object {
        const val ANDROID_PLATFORM: String = "android"

        /** `AppConfig.defaultBackendURL`. */
        val DEFAULT_BASE_URL: Url = Url("https://notomorrow-api.fly.dev")

        /** Placeholder from before the backend existed; a stored copy of it is ignored. */
        const val LEGACY_BASE_URL: String = "https://api.notomorrow.app"
    }
}
