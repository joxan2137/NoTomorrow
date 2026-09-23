package app.notomorrow.service

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * `FoodSearchTransportTests.swift`: [FoodSearchService] against a stubbed network — the single
 * retry on 429 / 5xx, the order of barcode forms, the search-a-licious → legacy fallback, the
 * search budget and the cooldown after a 429.
 */
class FoodSearchTransportTest {

    private sealed interface Reply {
        data class Status(val code: Int, val body: String = "", val retryAfter: String? = null) : Reply
        data class Fail(val error: Throwable) : Reply
    }

    private fun ok(body: String) = Reply.Status(200, body)

    private val requests = mutableListOf<HttpRequestData>()
    private val replies = ArrayDeque<Reply>()
    private var clock = 1_000_000L

    private fun service(vararg queued: Reply, online: Boolean = true): FoodSearchService {
        replies.addAll(queued)
        val engine = MockEngine { request ->
            requests += request
            when (val next = replies.removeFirstOrNull() ?: Reply.Status(599)) {
                is Reply.Fail -> throw next.error
                is Reply.Status -> respond(
                    content = next.body,
                    status = HttpStatusCode.fromValue(next.code),
                    headers = next.retryAfter?.let { headersOf(HttpHeaders.RetryAfter, it) } ?: headersOf(),
                )
            }
        }
        return FoodSearchService(engine = engine, now = { clock }, retryDelayMs = 10, isOnline = { online })
    }

    private suspend fun assertThrows(expected: FoodSearchError, body: suspend () -> Any?) {
        try {
            body()
            fail("expected $expected")
        } catch (error: FoodSearchError) {
            assertEquals(expected, error)
        }
    }

    private val found =
        """{"code":"5901234123457","status":1,"product":{"code":"5901234123457","product_name_pl":"Skyr","nutriments":{"energy-kcal_100g":62,"proteins_100g":11}}}"""

    // MARK: - Barcode

    @Test
    fun `a rate limit is retried once`() = runTest {
        val service = service(Reply.Status(429), ok(found))
        val result = service.lookup("5901234123457")
        assertEquals("Skyr", (result as BarcodeLookup.Found).candidate.name)
        assertEquals(2, requests.size)
    }

    @Test
    fun `busy twice surfaces as busy`() = runTest {
        val service = service(Reply.Status(503), Reply.Status(503))
        assertThrows(FoodSearchError.Busy) { service.lookup("5901234123457") }
        assertEquals("one retry, no more", 2, requests.size)
    }

    @Test
    fun `rate limited twice surfaces as rate limited`() = runTest {
        val service = service(Reply.Status(429), Reply.Status(429))
        assertThrows(FoodSearchError.RateLimited) { service.lookup("5901234123457") }
    }

    @Test
    fun `offline is not retried`() = runTest {
        val service = service(Reply.Fail(IOException("Unable to resolve host")), online = false)
        assertThrows(FoodSearchError.Offline) { service.lookup("5901234123457") }
        assertEquals(1, requests.size)
    }

    @Test
    fun `no answer while online reads as unreachable`() = runTest {
        val service = service(Reply.Fail(IOException("timeout")))
        assertThrows(FoodSearchError.Unreachable) { service.lookup("5901234123457") }
    }

    @Test
    fun `every form is tried before not found`() = runTest {
        val notFound = OffFixtures.text(OffFixtures.NOT_FOUND)
        val service = service(Reply.Status(404, notFound), Reply.Status(404, notFound))
        assertEquals(BarcodeLookup.NotFound, service.lookup("0049000028911"))
        assertEquals(
            listOf("/api/v2/product/0049000028911.json", "/api/v2/product/049000028911.json"),
            requests.map { it.url.encodedPath },
        )
        // A repeat within five minutes is answered from the cache.
        service.lookup("0049000028911")
        assertEquals(2, requests.size)
    }

    @Test
    fun `a stub is kept while the other forms are tried`() = runTest {
        val stub = """{"status":1,"product":{"code":"0049000028911","product_name":"Cola","brands":"Coca-Cola"}}"""
        val service = service(ok(stub), Reply.Status(503), Reply.Status(503))
        val result = service.lookup("0049000028911") as BarcodeLookup.Partial
        assertEquals("Cola", result.stub.name)
        assertEquals("Coca-Cola", result.stub.brand)
    }

    @Test
    fun `the lookup asks for the rescue fields`() = runTest {
        val service = service(ok(found))
        service.lookup("5901234123457", locale = "en_GB")
        val request = requests.single()
        val fields = request.url.parameters["fields"].orEmpty()
        assertTrue(fields.contains("nutriments_estimated"))
        assertTrue(fields.contains("product_name_en"))
        assertEquals("en", request.url.parameters["lc"])
        assertEquals(FoodSearchService.USER_AGENT, request.headers[HttpHeaders.UserAgent])
    }

    // MARK: - Text search

    @Test
    fun `search uses search-a-licious`() = runTest {
        val service = service(ok(OffFixtures.text(OffFixtures.SEARCH_A_LICIOUS)))
        val hits = service.search("serek wiejski", "pl_PL")
        assertEquals(4, hits.size)
        val url = requests.first().url
        assertEquals("search.openfoodfacts.org", url.host)
        assertEquals("serek wiejski", url.parameters["q"])
        assertEquals("pl,en", url.parameters["langs"])
    }

    @Test
    fun `search falls back to the legacy endpoint`() = runTest {
        val legacy =
            """{"products":[{"code":"5900531000935","product_name_pl":"Serek wiejski","brands":"Piątnica","nutriments":{"energy-kcal_100g":110}}]}"""
        val service = service(Reply.Status(503), ok(legacy))
        val hits = service.search("serek", "pl")
        assertEquals(listOf("Piątnica"), hits.map { it.brand })
        assertEquals(listOf("search.openfoodfacts.org", "world.openfoodfacts.org"), requests.map { it.url.host })
        assertEquals("/cgi/search.pl", requests.last().url.encodedPath)
    }

    @Test
    fun `an undecodable search-a-licious body falls back too`() = runTest {
        val service = service(ok("<html>maintenance</html>"), ok("""{"products":[]}"""))
        assertEquals(emptyList<Any>(), service.search("serek", "pl"))
        assertEquals(2, requests.size)
    }

    @Test
    fun `both search endpoints down reads as busy`() = runTest {
        val service = service(Reply.Status(502), Reply.Status(503))
        assertThrows(FoodSearchError.Busy) { service.search("serek", "pl") }
    }

    @Test
    fun `offline search does not fall back`() = runTest {
        val service = service(Reply.Fail(IOException("offline")), online = false)
        assertThrows(FoodSearchError.Offline) { service.search("serek", "pl") }
        assertEquals(1, requests.size)
    }

    @Test
    fun `search cools down after a rate limit`() = runTest {
        val service = service(Reply.Status(429, retryAfter = "30"))
        assertThrows(FoodSearchError.RateLimited) { service.search("serek", "pl") }
        assertThrows(FoodSearchError.RateLimited) { service.search("jogurt", "pl") }
        assertEquals("the second search never reaches the network", 1, requests.size)
        clock += 31_000
        replies.add(ok("""{"hits":[]}"""))
        assertEquals(emptyList<Any>(), service.search("kefir", "en"))
        assertEquals("Retry-After 30 s has passed", 2, requests.size)
    }

    @Test
    fun `an eleventh search in a minute fails fast`() = runTest {
        // Full answers, so no Polish supplement takes a slot of its own.
        val service = service(*Array(10) { index -> ok(hits((1..FoodSearchService.SUPPLEMENT_BELOW).map { "59$index$it" })) })
        repeat(10) { service.search("produkt $it", "pl") }
        assertThrows(FoodSearchError.RateLimited) { service.search("produkt 10", "pl") }
        assertEquals(10, requests.size)
    }

    // MARK: - Polish supplement

    /** A search-a-licious answer with one hit per code. */
    private fun hits(codes: List<String>): String = codes.joinToString(",", prefix = """{"hits":[""", postfix = "]}") {
        """{"code":"$it","product_name":"Mięta $it","brands":["Herbapol"],"nutriments":{"energy-kcal_100g":2}}"""
    }

    private val legacyZurek =
        """{"products":[{"code":"5900000000011","product_name_pl":"Mięta 5900000000011","brands":"Herbapol","nutriments":{"energy-kcal_100g":2}},""" +
            """{"code":"5900397016613","product_name_pl":"Żurek","brands":"Krakus","nutriments":{"energy-kcal_100g":30}}]}"""

    @Test
    fun `a thin Polish answer is topped up from the legacy search`() = runTest {
        val service = service(ok(hits(listOf("5900000000011"))), ok(legacyZurek))

        val found = service.search("mieta", "pl")

        assertEquals("primary first, the legacy extra after, no duplicate", listOf("5900000000011", "5900397016613"), found.map { it.code })
        assertEquals(listOf("search.openfoodfacts.org", "world.openfoodfacts.org"), requests.map { it.url.host })
        assertEquals("mieta", requests[1].url.parameters["search_terms"])
    }

    @Test
    fun `an empty Polish answer shows the legacy results`() = runTest {
        // search-a-licious neither folds nor finds "żurek"; cgi/search.pl does (Krakus "ZUPA ŻUREK").
        val service = service(ok("""{"hits":[]}"""), ok(legacyZurek))

        val found = service.search("żurek", "pl")

        assertEquals(listOf("Herbapol", "Krakus"), found.map { it.brand })
    }

    @Test
    fun `a full Polish answer is not topped up`() = runTest {
        val codes = (1..FoodSearchService.SUPPLEMENT_BELOW).map { "590000000001$it" }
        val service = service(ok(hits(codes)))

        val found = service.search("mięta", "pl")

        assertEquals(FoodSearchService.SUPPLEMENT_BELOW, found.size)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a thin English answer is not topped up`() = runTest {
        val service = service(ok(hits(listOf("5900000000011"))))

        val found = service.search("mint", "en_GB")

        assertEquals(1, found.size)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a failed supplement keeps the primary answer`() = runTest {
        val service = service(ok(hits(listOf("5900000000011"))), Reply.Status(503))

        val found = service.search("mieta", "pl")

        assertEquals(listOf("5900000000011"), found.map { it.code })
        assertEquals("the supplement is tried once, without a retry", 2, requests.size)
    }

    @Test
    fun `the supplement never waits for a search slot`() = runTest {
        // Use up the budget but one slot: the primary takes it, the supplement finds none and is skipped.
        val service = service()
        repeat(FoodSearchService.SEARCH_BUDGET - 1) { index ->
            replies.add(ok(hits((1..FoodSearchService.SUPPLEMENT_BELOW).map { "59$index$it" })))
            service.search("serek $index", "pl")
        }
        requests.clear()
        replies.add(ok(hits(listOf("5900000000011"))))

        val found = service.search("mieta", "pl")

        assertEquals(1, found.size)
        assertEquals(1, requests.size)
    }

    @Test
    fun `punctuation-only queries send nothing`() = runTest {
        val service = service()
        assertEquals(emptyList<Any>(), service.search("!!", "pl"))
        assertEquals(0, requests.size)
    }
}
