package app.notomorrow.net

import app.notomorrow.net.dto.Me
import app.notomorrow.net.dto.Session
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The 401 dance from `RemoteTransport.swift`: no session → `unauthorized`
 * without touching the network; a 401 → one coalesced refresh + one retry; a
 * second 401 wipes the session; a rejected refresh drops it, an unreachable one
 * keeps it and surfaces `network`.
 */
class RemoteTransportAuthTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val meBody = """{"id":"u1","username":"kuba","displayName":"Kuba"}"""

    private fun session(access: String) = Session(access, "refresh-1", "u1")

    private fun transport(store: SessionStore, engine: MockEngine) =
        RemoteTransport(baseUrl = Url("https://example.test"), store = store, engine = engine)

    private fun client(store: SessionStore, engine: MockEngine) =
        RemoteBackendClient(baseUrl = Url("https://example.test"), store = store, engine = engine)

    @Test
    fun `no session throws unauthorized without touching the network`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine { calls.incrementAndGet(); respond(meBody, HttpStatusCode.OK, jsonHeaders) }
        try {
            client(InMemorySessionStore(), engine).me()
            fail("expected unauthorized")
        } catch (e: BackendError) {
            assertEquals(BackendError.Unauthorized, e)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `a 401 refreshes once and retries`() = runTest {
        val store = InMemorySessionStore(session("stale"))
        val refreshes = AtomicInteger()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> {
                    refreshes.incrementAndGet()
                    respond("""{"accessToken":"fresh","refreshToken":"refresh-2","userId":"u1"}""", HttpStatusCode.OK, jsonHeaders)
                }
                "/me" -> if (bearer(request) == "fresh") {
                    respond(meBody, HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val me = client(store, engine).me()
        assertEquals(Me(id = "u1", username = "kuba", displayName = "Kuba"), me)
        assertEquals(1, refreshes.get())
        assertEquals(Session("fresh", "refresh-2", "u1"), store.session())
    }

    @Test
    fun `concurrent 401s rotate the pair once`() = runTest {
        val store = InMemorySessionStore(session("stale"))
        val refreshes = AtomicInteger()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> {
                    refreshes.incrementAndGet()
                    respond("""{"accessToken":"fresh","refreshToken":"refresh-2","userId":"u1"}""", HttpStatusCode.OK, jsonHeaders)
                }
                else -> if (bearer(request) == "fresh") {
                    respond(meBody, HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                }
            }
        }
        val transport = transport(store, engine)

        val results = (1..8).map {
            async { transport.perform(RemoteTransport.Request("GET", "me")) }
        }.awaitAll()

        assertTrue(results.all { it == meBody })
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `a rejected refresh drops the session and reports unauthorized`() = runTest {
        val store = InMemorySessionStore(session("stale"))
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" ->
                    respond("""{"error":"invalid_refresh_token"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                else -> respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            }
        }
        try {
            client(store, engine).me()
            fail("expected unauthorized")
        } catch (e: BackendError) {
            assertEquals(BackendError.Unauthorized, e)
        }
        assertNull(store.session())
    }

    @Test
    fun `an unreachable refresh keeps the session and surfaces network`() = runTest {
        val kept = session("stale")
        val store = InMemorySessionStore(kept)
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, jsonHeaders)
                else -> respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            }
        }
        try {
            client(store, engine).me()
            fail("expected network")
        } catch (e: BackendError) {
            assertEquals(BackendError.Network, e)
        }
        assertEquals(kept, store.session())
    }

    @Test
    fun `a second 401 after a successful refresh wipes the session`() = runTest {
        val store = InMemorySessionStore(session("stale"))
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" ->
                    respond("""{"accessToken":"fresh","refreshToken":"refresh-2","userId":"u1"}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            }
        }
        try {
            client(store, engine).me()
            fail("expected unauthorized")
        } catch (e: BackendError) {
            assertEquals(BackendError.Unauthorized, e)
        }
        assertNull(store.session())
    }

    @Test
    fun `a non-2xx reply carries the server envelope`() = runTest {
        val store = InMemorySessionStore(session("good"))
        val engine = MockEngine {
            respond("""{"error":"not_paired","message":"You are not paired with anyone"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
        try {
            client(store, engine).partnerState()
            fail("expected http")
        } catch (e: BackendError) {
            assertEquals(BackendError.Http(404, "not_paired", "You are not paired with anyone"), e)
            assertEquals("not_paired", e.code)
            assertEquals(404, e.status)
        }
    }

    @Test
    fun `a non-json error body falls back to http_status`() = runTest {
        val store = InMemorySessionStore(session("good"))
        val engine = MockEngine { respond("<html>bad gateway</html>", HttpStatusCode.BadGateway) }
        try {
            client(store, engine).me()
            fail("expected http")
        } catch (e: BackendError) {
            assertEquals(BackendError.Http(502, "http_502", "HTTP 502"), e)
        }
    }

    @Test
    fun `public auth routes never carry a token and surface their own errors`() = runTest {
        val store = InMemorySessionStore()
        var authorization: String? = "unset"
        val engine = MockEngine { request ->
            authorization = bearer(request)
            respond("""{"error":"invalid_credentials","message":"Wrong username or password"}""", HttpStatusCode.Unauthorized, jsonHeaders)
        }
        try {
            client(store, engine).signIn("kuba", "hunter2")
            fail("expected http")
        } catch (e: BackendError) {
            assertEquals(BackendError.Http(401, "invalid_credentials", "Wrong username or password"), e)
        }
        assertNull(authorization)
    }

    @Test
    fun `endpoints hit the documented paths and methods`() = runTest {
        val store = InMemorySessionStore(session("good"))
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath}"
            respond("""{"code":"NT-7K4Q"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val client = client(store, engine)
        assertEquals("NT-7K4Q", client.createPairCode())
        client.registerPushToken("fcm:Token-1_x")
        client.setAttendance(java.time.LocalDate.of(2026, 9, 5), app.notomorrow.model.AttendanceStatus.Confirmed)
        client.deleteAccount()

        assertEquals(
            listOf(
                "POST /pair/code",
                "POST /push/token",
                "PUT /attendance/2026-09-05",
                "DELETE /me",
            ),
            seen,
        )
    }

    private fun bearer(request: HttpRequestData): String? =
        request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
}
