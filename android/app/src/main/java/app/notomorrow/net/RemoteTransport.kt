package app.notomorrow.net

import app.notomorrow.net.dto.ErrorEnvelope
import app.notomorrow.net.dto.NtJson
import app.notomorrow.net.dto.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.content.PartData
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.util.concurrent.CancellationException

/**
 * HTTP plumbing for the Fly.io backend: JSON encoding, the Bearer header, the
 * error envelope and the refresh-on-401 dance — the port of
 * `NoTomorrow/Services/RemoteTransport.swift`. Endpoint methods live in
 * [RemoteBackendClient].
 */
class RemoteTransport(
    val baseUrl: Url,
    val store: SessionStore,
    engine: HttpClientEngine? = null,
    private val refresher: TokenRefresher = TokenRefresher(),
) {

    /** Public auth routes never carry a token and a 401 there is a plain server reply, not a session problem. */
    enum class Auth { NONE, REQUIRED }

    sealed interface Payload {
        data object Empty : Payload

        /** Already-encoded JSON — the mirror of Swift's `encoder.encode(body)`. */
        data class Json(val encoded: String) : Payload

        /** Rebuilt per attempt: a multipart body is consumed by the send, and a 401 costs us a retry. */
        data class Multipart(val parts: () -> List<PartData>) : Payload
    }

    data class Request(
        val method: String,
        val path: String,
        val auth: Auth = Auth.REQUIRED,
        val payload: Payload = Payload.Empty,
        val headers: Map<String, String> = emptyMap(),
        val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    )

    private data class Reply(val status: Int, val body: String)

    private val client: HttpClient = HttpClient(engine ?: OkHttp.create()) {
        expectSuccess = false
        install(ContentNegotiation) { json(NtJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = AI_TIMEOUT_MS
        }
    }

    // MARK: Convenience

    suspend fun <T> send(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        auth: Auth = Auth.REQUIRED,
    ): T = decode(perform(Request(method, path, auth)), serializer)

    suspend fun <T, B> send(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        bodySerializer: KSerializer<B>,
        body: B,
        auth: Auth = Auth.REQUIRED,
    ): T = decode(
        perform(Request(method, path, auth, Payload.Json(NtJson.encodeToString(bodySerializer, body)))),
        serializer,
    )

    suspend fun sendVoid(method: String, path: String, auth: Auth = Auth.REQUIRED) {
        perform(Request(method, path, auth))
    }

    suspend fun <B> sendVoid(
        method: String,
        path: String,
        bodySerializer: KSerializer<B>,
        body: B,
        auth: Auth = Auth.REQUIRED,
    ) {
        perform(Request(method, path, auth, Payload.Json(NtJson.encodeToString(bodySerializer, body))))
    }

    fun <T> decode(body: String, serializer: KSerializer<T>): T =
        try {
            NtJson.decodeFromString(serializer, body)
        } catch (e: SerializationException) {
            throw BackendError.Decoding
        } catch (e: IllegalArgumentException) {
            throw BackendError.Decoding
        }

    // MARK: Core

    /**
     * Runs the request. With [Auth.REQUIRED]: no session → `unauthorized` without
     * touching the network; a 401 → one refresh (coalesced across concurrent
     * callers) and one retry; a second 401 wipes the session.
     */
    suspend fun perform(request: Request): String = when (request.auth) {
        Auth.NONE -> execute(request, null).body
        Auth.REQUIRED -> {
            val session = store.session() ?: throw BackendError.Unauthorized
            val first = execute(request, session.accessToken)
            if (first.status != 401) {
                unwrap(first)
            } else {
                val refreshed = refresher.refresh(session.accessToken, store) { rotate(it) }
                    ?: throw BackendError.Unauthorized
                val second = execute(request, refreshed.accessToken)
                if (second.status == 401) {
                    store.clear()
                    throw BackendError.Unauthorized
                }
                unwrap(second)
            }
        }
    }

    private suspend fun execute(request: Request, token: String?): Reply {
        val reply = try {
            val response = client.request {
                method = HttpMethod.parse(request.method)
                url.takeFrom(baseUrl)
                url.appendPathSegments(request.path.split('/').filter { it.isNotEmpty() })
                timeout { requestTimeoutMillis = request.timeoutMillis }
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                header(HttpHeaders.UserAgent, USER_AGENT)
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                request.headers.forEach { (key, value) -> header(key, value) }
                when (val payload = request.payload) {
                    Payload.Empty -> Unit
                    is Payload.Json -> {
                        contentType(ContentType.Application.Json)
                        setBody(payload.encoded)
                    }
                    is Payload.Multipart -> setBody(MultiPartFormDataContent(payload.parts()))
                }
            }
            Reply(response.status.value, response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendError) {
            throw e
        } catch (e: Throwable) {
            // A reply that did not come in time is not "no connection" (the AI flows word it apart).
            throw if (BackendError.isTimeout(e)) BackendError.TimedOut else BackendError.Network
        }
        // A public route's non-2xx is a plain server reply; surface it straight away.
        return if (request.auth == Auth.NONE) Reply(reply.status, unwrap(reply)) else reply
    }

    /** 2xx → body; anything else → the server's `{error, message}` envelope as [BackendError.Http]. */
    private fun unwrap(reply: Reply): String {
        if (reply.status in 200..299) return reply.body
        val parsed = runCatching { NtJson.decodeFromString(ErrorEnvelope.serializer(), reply.body) }.getOrNull()
        val code = parsed?.error ?: "http_${reply.status}"
        val message = parsed?.message ?: parsed?.error ?: "HTTP ${reply.status}"
        throw BackendError.Http(status = reply.status, code = code, serverMessage = message)
    }

    // MARK: Refresh

    /**
     * `POST auth/refresh`. **400 or 401 ⇒ rejected** (drop the session); anything
     * else ⇒ unreachable (keep it, surface `network`).
     */
    private suspend fun rotate(refreshToken: String): RefreshOutcome {
        val body = runCatching {
            NtJson.encodeToString(RefreshBody.serializer(), RefreshBody(refreshToken))
        }.getOrElse { return RefreshOutcome.Unreachable }
        val request = Request("POST", "auth/refresh", Auth.NONE, Payload.Json(body))
        return try {
            RefreshOutcome.Rotated(decode(execute(request, null).body, Session.serializer()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendError.Http) {
            if (e.status == 401 || e.status == 400) RefreshOutcome.Rejected else RefreshOutcome.Unreachable
        } catch (e: Throwable) {
            RefreshOutcome.Unreachable
        }
    }

    @kotlinx.serialization.Serializable
    private data class RefreshBody(val refreshToken: String)

    companion object {
        /** `NoTomorrow/0.1 iOS` on the other platform (`docs/android-research.md` §7.8). */
        const val USER_AGENT: String = "NoTomorrow/0.1 Android"

        const val DEFAULT_TIMEOUT_MS: Long = 30_000
        const val CONNECT_TIMEOUT_MS: Long = 30_000

        /** `POST ai/estimate` uploads a photo and waits on a vision model. */
        const val AI_TIMEOUT_MS: Long = 90_000

        /** `POST ai/label`: the server gives up at 65 s (contract §8). */
        const val AI_LABEL_TIMEOUT_MS: Long = 75_000
    }
}
