package app.notomorrow.service

import app.notomorrow.R
import app.notomorrow.model.MealSlot
import app.notomorrow.net.BackendError
import app.notomorrow.net.InMemorySessionStore
import app.notomorrow.net.RemoteBackendClient
import app.notomorrow.net.dto.AIPer100
import app.notomorrow.net.dto.Session
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64

/**
 * The bring-your-own-key request bodies (contract §9), their status / `stop_reason` mapping, the
 * Gemini Interactions → generateContent fallback, transport failures, Open Food Facts grounding,
 * the backend error table (§10) and the label endpoint — `AIProviderTests.swift`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIProviderTest {

    private val spec = AISpecFixtures.spec
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private sealed interface Reply {
        data class Status(val code: Int, val body: String) : Reply
        data class Fail(val error: Throwable) : Reply
    }

    private val requests = mutableListOf<HttpRequestData>()

    private fun engine(vararg replies: Reply): MockEngine {
        val queue = ArrayDeque(replies.toList())
        return MockEngine { request ->
            requests += request
            when (val next = queue.removeFirstOrNull() ?: Reply.Status(599, "")) {
                is Reply.Fail -> throw next.error
                is Reply.Status -> respond(next.body, HttpStatusCode.fromValue(next.code), jsonHeaders)
            }
        }
    }

    private fun claude(vararg replies: Reply, key: String? = "sk-ant-test", grounding: AIBarcodeGrounding? = null) =
        DirectAnthropicEstimateService({ key }, { spec }, AIDirectTransport.client(engine(*replies)), grounding)

    private fun gemini(vararg replies: Reply, key: String? = "AIza-test", grounding: AIBarcodeGrounding? = null) =
        DirectGeminiEstimateService({ key }, { spec }, AIDirectTransport.client(engine(*replies)), grounding)

    private fun body(request: HttpRequestData): JsonElement {
        val text = when (val content = request.body) {
            is TextContent -> content.text
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            else -> error("unexpected body $content")
        }
        return AIJson.parse(text)
    }

    private suspend fun expect(expected: AIEstimateError, block: suspend () -> Any?) {
        try {
            block()
            fail("expected $expected")
        } catch (e: AIEstimateError) {
            assertEquals(expected, e)
        }
    }

    /** A schema-v2 model answer: one generic-table item, one with a barcode. */
    private val modelEstimate = """
        {"foods":[
          {"name":"Pierogi ruskie","cooking":"boiled","genericKey":"pierogi_ruskie","portionCount":6,"portionUnit":"szt.",
           "gramsPerUnit":35,"grams":210,"per100":{"kcal":200,"protein":6,"carbs":29,"fat":7,"alcohol":0},
           "nutritionSource":"estimated","barcode":"","isGuess":false,"confidence":0.8},
          {"name":"Skyr","cooking":"packaged","genericKey":"none","portionCount":1,"portionUnit":"kubek","gramsPerUnit":150,
           "grams":150,"per100":{"kcal":60,"protein":10,"carbs":4,"fat":0.2,"alcohol":0},"nutritionSource":"visible_label",
           "barcode":"5901234123457","isGuess":false,"confidence":0.9}],
         "scaleReferenceUsed":"widelec","assumptions":[],"questions":[],"overallConfidence":0.8}
    """.trimIndent()

    private val modelLabel = """
        {"legible":true,"name":"Serek","brand":"","basis":"per100g","servingSizeG":-1,
         "values":{"kcal":97,"kj":-1,"protein":11,"carbs":2,"fat":5,"fiber":-1,"sugar":2,"salt":0.6},
         "packageSizeG":200,"barcode":"","confidence":0.9}
    """.trimIndent()

    private fun claudeReply(text: String, stopReason: String = "end_turn") = Reply.Status(
        200,
        AIJson.serialize(
            AIJson.parse(
                """{"id":"msg_1","type":"message","role":"assistant","stop_reason":"$stopReason",
                   "content":[{"type":"thinking","thinking":""},{"type":"text","text":${AIJson.stringLiteral(text)}}]}""",
            ),
        ),
    )

    private fun interactionsReply(text: String) = Reply.Status(
        200,
        """{"id":"int_1","outputs":[{"type":"thought","summary":"…"},{"type":"text","text":${AIJson.stringLiteral(text)}}]}""",
    )

    private fun generateContentReply(text: String) = Reply.Status(
        200,
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"hmm","thought":true},
           {"text":${AIJson.stringLiteral(text)}}]}}]}""",
    )

    // MARK: Claude

    @Test
    fun `claude estimate body follows the contract`() = runTest {
        val service = claude(claudeReply(modelEstimate))
        val estimate = service.estimate(jpeg, MealSlot.Dinner, "pl", "6 pierogów")
        val request = requests.single()
        assertEquals(DirectAnthropicEstimateService.ENDPOINT, request.url.toString())
        assertEquals("sk-ant-test", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        val b = body(request) as JsonObject
        assertEquals(listOf("model", "max_tokens", "system", "output_config", "messages"), b.keys.toList())
        assertEquals("claude-sonnet-5", b["model"].stringValue)
        assertEquals(8192.0, b["max_tokens"].numberValue!!, 0.0)
        assertEquals(spec.estimateSystemInstruction("pl"), b["system"].stringValue)
        assertEquals("low", b["output_config"]["effort"].stringValue)
        assertEquals("json_schema", b["output_config"]["format"]["type"].stringValue)
        assertEquals(AIJson.serialize(spec.estimateSchema()), AIJson.serialize(b["output_config"]["format"]["schema"]!!))
        val content = b["messages"].arrayValue!!.single()["content"].arrayValue!!
        assertEquals("image", content[0]["type"].stringValue)
        assertEquals("base64", content[0]["source"]["type"].stringValue)
        assertEquals("image/jpeg", content[0]["source"]["media_type"].stringValue)
        assertEquals(Base64.getEncoder().encodeToString(jpeg), content[0]["source"]["data"].stringValue)
        assertEquals(spec.estimateRequestText("dinner", "6 pierogów"), content[1]["text"].stringValue)
        for (banned in listOf("temperature", "top_p", "top_k", "thinking", "output_format")) assertNull(b[banned])

        // The answer went through the finalizer: generic table, portion fields, no weight → cap.
        val first = estimate.foods[0]
        assertEquals("generic_table", first.nutritionSource)
        assertEquals(210.0, first.grams, 0.0)
        assertEquals(6.0, first.portionCount!!, 0.0)
        assertEquals(0.65, first.confidence, 0.0)
        assertEquals(2, estimate.version)
        assertEquals("widelec", estimate.scaleReferenceUsed)
    }

    @Test
    fun `claude label uses the label prompt, schema and 4096 tokens`() = runTest {
        val reading = claude(claudeReply(modelLabel)).readLabel(jpeg, "en")
        val b = body(requests.single())
        assertEquals(4096.0, b["max_tokens"].numberValue!!, 0.0)
        assertEquals(spec.labelSystemInstruction("en"), b["system"].stringValue)
        assertEquals(AIJson.serialize(spec.labelSchema()), AIJson.serialize(b["output_config"]["format"]["schema"]!!))
        assertEquals(spec.labelRequestText, b["messages"].arrayValue!![0]["content"].arrayValue!![1]["text"].stringValue)
        assertTrue(reading.legible)
        assertEquals(97.0, reading.per100!!.kcal, 0.0)
        assertEquals(200.0, reading.packageSizeG!!, 0.0)
        assertNull(reading.servingSizeG)
    }

    @Test
    fun `claude status and stop_reason map to the taxonomy`() = runTest {
        expect(AIEstimateError.KeyRejected) { claude(Reply.Status(401, "")).estimate(jpeg, MealSlot.Lunch, "pl") }
        expect(AIEstimateError.KeyRejected) { claude(Reply.Status(403, "")).estimate(jpeg, MealSlot.Lunch, "pl") }
        for (status in listOf(429, 500, 503, 529)) {
            expect(AIEstimateError.Busy) { claude(Reply.Status(status, "")).estimate(jpeg, MealSlot.Lunch, "pl") }
        }
        expect(AIEstimateError.ProviderError(400, "bad schema")) {
            claude(Reply.Status(400, """{"type":"error","error":{"type":"invalid_request_error","message":"bad schema"}}"""))
                .estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.ProviderError(404, null)) { claude(Reply.Status(404, "nope")).readLabel(jpeg, "pl") }
        expect(AIEstimateError.Unreadable) {
            claude(claudeReply(modelEstimate, stopReason = "max_tokens")).estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.ProviderError(200, "refusal")) {
            claude(claudeReply(modelEstimate, stopReason = "refusal")).estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.Unreadable) { claude(claudeReply("I can't see food.")).estimate(jpeg, MealSlot.Lunch, "pl") }
        expect(AIEstimateError.Unreadable) { claude(claudeReply("{\"food\":[]}")).estimate(jpeg, MealSlot.Lunch, "pl") }
        expect(AIEstimateError.Unreadable) { claude(Reply.Status(200, "not json")).estimate(jpeg, MealSlot.Lunch, "pl") }
    }

    @Test
    fun `claude without a key fails before the network`() = runTest {
        expect(AIEstimateError.MissingKey) { claude(key = "  ").estimate(jpeg, MealSlot.Lunch, "pl") }
        expect(AIEstimateError.MissingKey) { claude(key = null).readLabel(jpeg, "pl") }
        assertTrue(requests.isEmpty())
    }

    // MARK: Gemini

    @Test
    fun `gemini interactions body follows the contract and the key stays out of the url`() = runTest {
        val estimate = gemini(interactionsReply(modelEstimate)).estimate(jpeg, MealSlot.Breakfast, "pl-PL", "")
        val request = requests.single()
        assertEquals(DirectGeminiEstimateService.INTERACTIONS_URL, request.url.toString())
        assertEquals("AIza-test", request.headers["x-goog-api-key"])
        assertFalse(request.url.toString().contains("AIza"))
        val b = body(request) as JsonObject
        assertEquals("gemini-3.8-flash", b["model"].stringValue)
        assertEquals(false, b["store"].booleanValue)
        assertEquals(spec.estimateSystemInstruction("pl"), b["system_instruction"].stringValue)
        assertEquals("low", b["generation_config"]["thinking_level"].stringValue)
        val input = b["input"].arrayValue!!
        assertEquals("image", input[0]["type"].stringValue)
        assertEquals("image/jpeg", input[0]["mime_type"].stringValue)
        assertEquals("high", input[0]["resolution"].stringValue)
        assertEquals(spec.estimateRequestText("breakfast", ""), input[1]["text"].stringValue)
        assertEquals("application/json", b["response_format"]["mime_type"].stringValue)
        val schema = AIJson.serialize(b["response_format"]["schema"]!!)
        assertEquals(AIJson.serialize(AIEstimateSpec.forGemini(spec.estimateSchema())), schema)
        assertFalse(schema.contains("additionalProperties"))
        assertNull(b["temperature"])
        assertNull(b["generation_config"]["temperature"])
        assertEquals(2, estimate.foods.size)
    }

    @Test
    fun `gemini falls back to generateContent on 404 and 400`() = runTest {
        for (status in listOf(404, 400)) {
            requests.clear()
            val reading = gemini(Reply.Status(status, "{}"), generateContentReply(modelLabel)).readLabel(jpeg, "pl")
            assertEquals(2, requests.size)
            val second = requests[1]
            assertEquals(DirectGeminiEstimateService.generateContentUrl("gemini-3.8-flash"), second.url.toString())
            assertEquals("AIza-test", second.headers["x-goog-api-key"])
            val b = body(second)
            assertEquals(spec.labelSystemInstruction("pl"), b["systemInstruction"]["parts"].arrayValue!![0]["text"].stringValue)
            val parts = b["contents"].arrayValue!![0]["parts"].arrayValue!!
            assertEquals("image/jpeg", parts[0]["inlineData"]["mimeType"].stringValue)
            assertEquals(spec.labelRequestText, parts[1]["text"].stringValue)
            val config = b["generationConfig"]
            assertEquals("application/json", config["responseMimeType"].stringValue)
            assertEquals(
                AIJson.serialize(AIEstimateSpec.forGemini(spec.labelSchema())),
                AIJson.serialize(config["responseJsonSchema"]!!),
            )
            assertEquals("low", config["thinkingConfig"]["thinkingLevel"].stringValue)
            assertEquals("MEDIA_RESOLUTION_HIGH", config["mediaResolution"].stringValue)
            // The thought part is skipped.
            assertEquals("Serek", reading.name)
        }
    }

    @Test
    fun `gemini status mapping`() = runTest {
        expect(AIEstimateError.KeyRejected) {
            gemini(Reply.Status(400, "{}"), Reply.Status(400, """{"error":{"message":"API key not valid. Please pass a valid API key."}}"""))
                .estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.ProviderError(400, "Invalid schema")) {
            gemini(Reply.Status(400, "{}"), Reply.Status(400, """{"error":{"message":"Invalid schema"}}"""))
                .estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.ProviderError(404, "model not found")) {
            gemini(Reply.Status(404, "{}"), Reply.Status(404, """{"error":{"message":"model not found"}}"""))
                .estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.KeyRejected) { gemini(Reply.Status(403, "")).estimate(jpeg, MealSlot.Lunch, "pl") }
        expect(AIEstimateError.Busy) { gemini(Reply.Status(429, "")).estimate(jpeg, MealSlot.Lunch, "pl") }
        expect(AIEstimateError.Busy) { gemini(Reply.Status(503, "")).readLabel(jpeg, "pl") }
        expect(AIEstimateError.Unreadable) { gemini(Reply.Status(200, """{"outputs":[]}""")).estimate(jpeg, MealSlot.Lunch, "pl") }
        expect(AIEstimateError.MissingGeminiKey) { gemini(key = "").estimate(jpeg, MealSlot.Lunch, "pl") }
    }

    @Test
    fun `extractText prefers output_text, then walks and skips thoughts`() {
        assertEquals("a", DirectGeminiEstimateService.extractText(AIJson.parse("""{"output_text":"a","outputs":[{"text":"b"}]}""")))
        assertEquals(
            "xy",
            DirectGeminiEstimateService.extractText(
                AIJson.parse(
                    """{"steps":[{"content":[{"type":"thought","text":"no"},{"type":"text","text":"x"},
                       {"type":"image","text":"no"}]},{"output":{"parts":[{"text":"y"},{"text":"z","thought":true}]}}]}""",
                ),
            ),
        )
        assertNull(DirectGeminiEstimateService.extractText(AIJson.parse("""{"outputs":[]}""")))
    }

    // MARK: Transport

    @Test
    fun `timeouts and dead networks are told apart`() = runTest {
        expect(AIEstimateError.Timeout) {
            claude(Reply.Fail(HttpRequestTimeoutException("https://api.anthropic.com/v1/messages", 90_000)))
                .estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.Timeout) {
            gemini(Reply.Fail(SocketTimeoutException("read timed out"))).estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.Offline) {
            gemini(Reply.Fail(UnknownHostException("generativelanguage.googleapis.com"))).readLabel(jpeg, "pl")
        }
        expect(AIEstimateError.Offline) { claude(Reply.Fail(IOException("reset"))).readLabel(jpeg, "pl") }
    }

    // MARK: Grounding

    @Test
    fun `a barcode item takes the Open Food Facts values and the others stay`() = runTest {
        val lookups = mutableListOf<String>()
        val grounding = AIBarcodeGrounding { code ->
            lookups += code
            AIPer100(kcal = 62.0, protein = 11.04, carbs = 3.96, fat = 0.2)
        }
        val estimate = claude(claudeReply(modelEstimate), grounding = grounding).estimate(jpeg, MealSlot.Lunch, "pl")
        assertEquals(listOf("5901234123457"), lookups)
        val skyr = estimate.foods[1]
        assertEquals("open_food_facts", skyr.nutritionSource)
        assertEquals(listOf("open_food_facts"), skyr.adjustments!!.takeLast(1))
        assertEquals(11.0, skyr.per100!!.protein, 0.0)
        assertEquals(93.0, skyr.kcal, 1e-9) // round1(62 × 150 / 100)
        assertEquals(0.65, skyr.confidence, 0.0) // capped (no weight in the notes), unchanged by grounding
        assertEquals(AIFinalizer.round1(estimate.foods[0].kcal + 93.0), estimate.totals!!.kcal, 1e-9)
        assertEquals("generic_table", estimate.foods[0].nutritionSource)
    }

    @Test
    fun `grounding runs the lookups side by side and stops at its budget`() = runTest {
        val model = claude(claudeReply(modelEstimate)).estimate(jpeg, MealSlot.Lunch, "pl")
        val fast = "5901234123457"
        val slow = "5900000000002"
        // Two items share the fast code: one lookup serves both.
        val estimate = model.copy(
            foods = listOf(
                model.foods[0].copy(barcode = slow),
                model.foods[1].copy(barcode = fast),
                model.foods[1].copy(id = "skyr-2", barcode = fast),
            ),
        )
        val lookups = mutableListOf<String>()
        val grounding = AIBarcodeGrounding { code ->
            lookups += code
            if (code == slow) {
                delay(60_000) // OFF hanging, or retrying
                AIPer100(kcal = 999.0, protein = 1.0, carbs = 1.0, fat = 1.0)
            } else {
                delay(1_000)
                AIPer100(kcal = 62.0, protein = 11.04, carbs = 3.96, fat = 0.2)
            }
        }

        val started = currentTime
        val grounded = grounding.ground(estimate)

        assertEquals("the whole grounding's budget, not the slow lookup", AIBarcodeGrounding.BUDGET_MS, currentTime - started)
        assertEquals(setOf(slow, fast), lookups.toSet())
        assertEquals("one lookup per distinct code", 2, lookups.size)
        assertEquals("the unfinished lookup keeps the model's values", model.foods[0].kcal, grounded.foods[0].kcal, 1e-9)
        assertEquals(model.foods[0].nutritionSource, grounded.foods[0].nutritionSource)
        listOf(1, 2).forEach { index ->
            assertEquals("open_food_facts", grounded.foods[index].nutritionSource)
            assertEquals(93.0, grounded.foods[index].kcal, 1e-9)
        }
        assertEquals(
            AIFinalizer.round1(grounded.foods.sumOf { it.kcal }),
            grounded.totals!!.kcal,
            1e-9,
        )
    }

    @Test
    fun `quick lookups finish without waiting for the budget`() = runTest {
        val model = claude(claudeReply(modelEstimate)).estimate(jpeg, MealSlot.Lunch, "pl")
        val estimate = model.copy(
            foods = listOf(model.foods[0].copy(barcode = "5900000000002"), model.foods[1]),
        )
        val grounding = AIBarcodeGrounding { delay(2_000); AIPer100(kcal = 62.0, protein = 11.04, carbs = 3.96, fat = 0.2) }
        val started = currentTime
        val grounded = grounding.ground(estimate)
        assertEquals("the lookups ran side by side", 2_000L, currentTime - started)
        assertTrue(grounded.foods.all { it.nutritionSource == "open_food_facts" })
    }

    @Test
    fun `a failed lookup keeps the model's values`() = runTest {
        val grounding = AIBarcodeGrounding { throw IOException("offline") }
        val estimate = gemini(interactionsReply(modelEstimate), grounding = grounding).estimate(jpeg, MealSlot.Lunch, "pl")
        assertEquals("visible_label", estimate.foods[1].nutritionSource)
        assertEquals(90.0, estimate.foods[1].kcal, 1e-9)
    }

    // MARK: Backend

    @Test
    fun `backend errors map on the code first, then the status`() {
        fun http(status: Int, code: String) = BackendError.Http(status, code, "msg")
        val cases = listOf(
            BackendError.Unauthorized to AIEstimateError.SignedOut,
            BackendError.Network to AIEstimateError.Offline,
            BackendError.TimedOut to AIEstimateError.Timeout,
            BackendError.Decoding to AIEstimateError.Unreadable,
            BackendError.Server("boom") to AIEstimateError.ProviderError(0, "boom"),
            http(403, "ai_not_allowed") to AIEstimateError.NotAllowed,
            http(429, "ai_daily_limit") to AIEstimateError.DailyLimit,
            http(503, "ai_busy") to AIEstimateError.Busy,
            http(504, "ai_timeout") to AIEstimateError.Timeout,
            http(502, "ai_unparseable") to AIEstimateError.Unreadable,
            http(502, "ai_upstream_error") to AIEstimateError.ProviderError(502, "msg"),
            http(503, "ai_unavailable") to AIEstimateError.ProviderError(503, "msg"),
            http(413, "image_too_large") to AIEstimateError.ProviderError(413, "msg"),
            http(400, "byok_is_device_direct") to AIEstimateError.ProviderError(400, "msg"),
            http(504, "http_504") to AIEstimateError.Timeout,
            http(429, "http_429") to AIEstimateError.Busy,
            http(503, "http_503") to AIEstimateError.Busy,
            http(502, "http_502") to AIEstimateError.ProviderError(502, "msg"),
        )
        for ((input, expected) in cases) assertEquals("$input", expected, BackendAIEstimateService.map(input))
    }

    @Test
    fun `every case has its own message`() {
        val all = listOf(
            AIEstimateError.Offline, AIEstimateError.Timeout, AIEstimateError.Busy, AIEstimateError.DailyLimit,
            AIEstimateError.NotAllowed, AIEstimateError.SignedOut, AIEstimateError.MissingKey,
            AIEstimateError.MissingGeminiKey, AIEstimateError.KeyRejected, AIEstimateError.Unreadable,
            AIEstimateError.ProviderError(500, null),
        )
        assertEquals(all.size, all.map { it.messageRes }.toSet().size)
        assertEquals(R.string.fuel_ai_error_timeout, AIEstimateError.Timeout.messageRes)
        assertEquals(R.string.fuel_ai_error_provider, AIEstimateError.ProviderError(502, "x").messageRes)
        assertEquals(R.string.error_network, AIEstimateError.Offline.messageRes)
        assertEquals(R.string.fuel_label_unreadable, AIEstimateError.Unreadable.labelMessageRes)
        assertEquals(R.string.fuel_ai_error_busy, AIEstimateError.Busy.labelMessageRes)
    }

    @Test
    fun `from wraps anything else through the backend table`() {
        assertEquals(AIEstimateError.Busy, AIEstimateError.from(AIEstimateError.Busy))
        assertEquals(AIEstimateError.Timeout, AIEstimateError.from(HttpRequestTimeoutException("u", 1)))
        assertEquals(AIEstimateError.Offline, AIEstimateError.from(IOException("x")))
        assertEquals(AIEstimateError.Unreadable, AIEstimateError.from(BackendError.Decoding))
    }

    private fun remote(engine: MockEngine) = RemoteBackendClient(
        baseUrl = Url("https://example.test"),
        store = InMemorySessionStore(Session("token", "refresh", "u1")),
        engine = engine,
    )

    @Test
    fun `the backend label read posts multipart to ai label and decodes the reading`() = runTest {
        val client = remote(
            engine(
                Reply.Status(
                    200,
                    """{"version":1,"legible":true,"unreadableReason":null,"basis":"perServing","energyFrom":"kj",
                       "name":"Baton","brand":"X","per100":{"kcal":450,"protein":8,"carbs":60,"fat":20,"fiber":3,
                       "sugar":30,"salt":0.3},"servingSizeG":40,"packageSizeG":null,"barcode":"5900531000508",
                       "confidence":0.8,"needsReview":false}""",
                ),
            ),
        )
        val reading = BackendAIEstimateService { client }.readLabel(jpeg, "pl")
        val request = requests.single()
        assertEquals("/ai/label", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))
        assertEquals("kj", reading.energyFrom)
        assertEquals(40.0, reading.servingSizeG!!, 0.0)
        assertEquals(3.0, reading.per100!!.fiber!!, 0.0)
    }

    @Test
    fun `backend timeouts and codes reach the taxonomy`() = runTest {
        expect(AIEstimateError.Timeout) {
            BackendAIEstimateService { remote(engine(Reply.Fail(HttpRequestTimeoutException("u", 75_000)))) }
                .readLabel(jpeg, "pl")
        }
        expect(AIEstimateError.Offline) {
            BackendAIEstimateService { remote(engine(Reply.Fail(UnknownHostException("x")))) }
                .estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.Timeout) {
            BackendAIEstimateService { remote(engine(Reply.Status(504, """{"error":"ai_timeout","message":"slow"}"""))) }
                .estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.Unreadable) {
            BackendAIEstimateService { remote(engine(Reply.Status(502, """{"error":"ai_unparseable","message":"x"}"""))) }
                .estimate(jpeg, MealSlot.Lunch, "pl")
        }
        expect(AIEstimateError.Unreadable) {
            BackendAIEstimateService { remote(engine(Reply.Status(200, """{"legible":"maybe"}"""))) }.readLabel(jpeg, "pl")
        }
    }

    // MARK: Mock

    @Test
    fun `the mock answers the v2 shape and the fixed label`() = runTest {
        val mock = MockAIEstimateService({ id, locale -> "$locale:$id" })
        val estimate = mock.estimate(jpeg, MealSlot.Breakfast, "pl")
        assertEquals(2, estimate.version)
        assertEquals(listOf("porcja", "kromka", "szt."), estimate.foods.map { it.portionUnit })
        val toast = estimate.foods[1]
        assertEquals(70.0, toast.grams, 0.0)
        assertEquals(AIFinalizer.round1(266.0 * 70 / 100), toast.kcal, 0.0)
        assertEquals(AIFinalizer.computeTotals(estimate.foods), estimate.totals)
        assertEquals(listOf("portion", "slice", "piece"), mock.estimate(jpeg, MealSlot.Breakfast, "en").foods.map { it.portionUnit })
        val label = mock.readLabel(jpeg, "pl")
        assertEquals("Serek wiejski", label.name)
        assertEquals(97.0, label.per100!!.kcal, 0.0)
        assertEquals(200.0, label.servingSizeG!!, 0.0)
    }
}
