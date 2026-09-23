package app.notomorrow.service

import app.notomorrow.data.prefs.AiConsentTarget
import app.notomorrow.model.AIProvider
import app.notomorrow.model.MealSlot
import app.notomorrow.net.BackendClient
import app.notomorrow.net.BackendError
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIPer100
import app.notomorrow.net.dto.LabelReading
import app.notomorrow.util.NtKeys
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

// MARK: - Backend (Fly.io) provider

/**
 * Sends the photo through [BackendClient.estimate] / [BackendClient.readLabel] (multipart, Bearer
 * session, refresh-on-401) and maps the server's error codes to [AIEstimateError] — the port of
 * `BackendAIEstimateService`. The user's own keys never go through the backend (the server answers
 * `X-Anthropic-Key` with 400 `byok_is_device_direct`); the direct providers below handle those
 * paths. The server answers the v2 estimate; an older server's v1 answer still decodes.
 */
class BackendAIEstimateService(private val client: () -> BackendClient) : AIEstimateService {

    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate =
        try {
            client().estimate(imageJpeg, meal, locale, anthropicKey = null, notes = notes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw map(BackendError.wrap(e))
        }

    override suspend fun readLabel(imageJpeg: ByteArray, locale: String): LabelReading =
        try {
            client().readLabel(imageJpeg, locale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw map(BackendError.wrap(e))
        }

    companion object {
        /** The server's `error` code first, the HTTP status second (contract §10). */
        fun map(error: BackendError): AIEstimateError = when (error) {
            BackendError.Unauthorized -> AIEstimateError.SignedOut
            BackendError.Network -> AIEstimateError.Offline
            BackendError.TimedOut -> AIEstimateError.Timeout
            BackendError.Decoding -> AIEstimateError.Unreadable
            is BackendError.Server -> AIEstimateError.ProviderError(0, error.serverMessage)
            is BackendError.Http -> when (error.code) {
                "ai_not_allowed" -> AIEstimateError.NotAllowed
                "ai_daily_limit" -> AIEstimateError.DailyLimit
                "ai_busy" -> AIEstimateError.Busy
                "ai_timeout" -> AIEstimateError.Timeout
                "ai_unparseable" -> AIEstimateError.Unreadable
                "ai_upstream_error", "ai_unavailable" -> AIEstimateError.ProviderError(error.status, error.serverMessage)
                else -> when (error.status) {
                    504 -> AIEstimateError.Timeout
                    429, 503 -> AIEstimateError.Busy
                    else -> AIEstimateError.ProviderError(error.status, error.serverMessage)
                }
            }
        }
    }
}

// MARK: - Shared by the bring-your-own-key providers

/** One POST of a JSON body per call; transport failures become `Timeout` / `Offline`. */
object AIDirectTransport {

    data class Reply(val status: Int, val body: String)

    /** One client for both direct providers; each request sets its own timeout. */
    fun client(engine: HttpClientEngine? = null): HttpClient = HttpClient(engine ?: OkHttp.create()) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = DirectAnthropicEstimateService.ESTIMATE_TIMEOUT_MS
            connectTimeoutMillis = DirectAnthropicEstimateService.ESTIMATE_TIMEOUT_MS
            socketTimeoutMillis = DirectAnthropicEstimateService.ESTIMATE_TIMEOUT_MS
        }
    }

    suspend fun post(
        client: HttpClient,
        url: String,
        headers: Map<String, String>,
        body: JsonElement,
        timeoutMillis: Long,
    ): Reply = try {
        val response = client.post(url) {
            timeout {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
            headers.forEach { (key, value) -> header(key, value) }
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            contentType(ContentType.Application.Json)
            setBody(AIJson.serialize(body))
        }
        Reply(response.status.value, response.bodyAsText())
    } catch (e: Throwable) {
        throw AIEstimateError.transport(e)
    }

    /** Strict parse, null when the text is not JSON. */
    fun parseOrNull(text: String): JsonElement? = try {
        AIJson.parse(text)
    } catch (_: AIJson.ParseException) {
        null
    }

    /** `error.message` of a provider's error envelope. */
    fun errorMessage(body: String): String? = parseOrNull(body)["error"]["message"].stringValue

    /** Model text → finalized estimate with the notes context of the full notes; unusable output is `Unreadable`. */
    fun finalizeEstimate(text: String, spec: AIEstimateSpec, notes: String): AIEstimate = try {
        AIFinalizer.finalizeEstimateText(text, spec, spec.notesContext(notes))
    } catch (_: AIOutputException) {
        throw AIEstimateError.Unreadable
    }

    fun finalizeLabel(text: String, spec: AIEstimateSpec): LabelReading = try {
        AIFinalizer.finalizeLabelText(text, spec)
    } catch (_: AIOutputException) {
        throw AIEstimateError.Unreadable
    }

    /** Base64 without line breaks. */
    fun imageBase64(jpeg: ByteArray): String = Base64.getEncoder().encodeToString(jpeg)
}

/**
 * Barcode grounding for the direct paths, like the backend: an item with a fully readable barcode
 * that Open Food Facts knows takes the product's per-100 g values. Best effort: any failure keeps
 * the model's values.
 *
 * The lookups run side by side (the server's `Promise.all`), one per distinct code, and the whole
 * grounding gets [budgetMs]: the user is already waiting on the model, so a lookup still running
 * then (OFF slow, its retry after a 429 / 5xx) is dropped and that item keeps the model's values.
 * Lookups that finished in time still apply.
 */
class AIBarcodeGrounding(
    private val budgetMs: Long = BUDGET_MS,
    private val lookup: suspend (String) -> AIPer100?,
) {

    /** The first 8 items, as on the server. */
    suspend fun ground(estimate: AIEstimate): AIEstimate {
        val indicesByCode = estimate.foods.indices.take(MAX_ITEMS)
            .mapNotNull { index -> estimate.foods[index].barcode?.takeIf { it.isNotEmpty() }?.let { it to index } }
            .groupBy({ it.first }, { it.second })
        if (indicesByCode.isEmpty()) return estimate
        val found = ConcurrentHashMap<String, AIPer100>()
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                indicesByCode.keys.forEach { code ->
                    launch {
                        val per100 = try {
                            lookup(code)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            null
                        }
                        if (per100 != null) found[code] = per100
                    }
                }
            }
        }
        var grounded = estimate
        indicesByCode.forEach { (code, indices) ->
            val per100 = found[code] ?: return@forEach
            indices.forEach { index -> grounded = AIFinalizer.groundWithDatabase(grounded, index, per100) }
        }
        return grounded
    }

    companion object {
        const val MAX_ITEMS: Int = 8

        /**
         * The whole grounding's budget. The server gives each lookup 6 s; a phone lookup that has
         * to retry after a 429 / 5xx (1.5 s pause) still fits one slow answer.
         */
        const val BUDGET_MS: Long = 7_000

        fun openFoodFacts(service: FoodSearchService, locale: () -> String): AIBarcodeGrounding =
            AIBarcodeGrounding { code ->
                (service.lookup(code, locale()) as? BarcodeLookup.Found)?.candidate?.let {
                    AIPer100(it.kcalPer100, it.proteinPer100, it.carbsPer100, it.fatPer100, 0.0)
                }
            }
    }
}

// MARK: - Direct Claude provider (bring your own key)

/**
 * Calls the Messages API straight from the device with the user's own key — the port of
 * `DirectAnthropicEstimateService`. Only used when the user opted in to "Claude with your key" in
 * Settings; the photo leaves the device by explicit user action. Structured output with the shared
 * schema and `effort: low` (Sonnet 5 thinks adaptively by default; thinking counts toward
 * `max_tokens`).
 */
class DirectAnthropicEstimateService(
    private val apiKey: suspend () -> String?,
    private val spec: suspend () -> AIEstimateSpec,
    private val client: HttpClient,
    private val grounding: AIBarcodeGrounding? = null,
) : AIEstimateService {

    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate {
        val key = key()
        val spec = spec()
        val text = send(
            key, spec,
            system = spec.estimateSystemInstruction(locale),
            text = spec.estimateRequestText(meal.raw, notes),
            schema = spec.estimateSchema(),
            maxTokens = spec.claude.estimateMaxTokens,
            imageJpeg = imageJpeg,
            timeoutMillis = ESTIMATE_TIMEOUT_MS,
        )
        val estimate = AIDirectTransport.finalizeEstimate(text, spec, notes)
        return grounding?.ground(estimate) ?: estimate
    }

    override suspend fun readLabel(imageJpeg: ByteArray, locale: String): LabelReading {
        val key = key()
        val spec = spec()
        val text = send(
            key, spec,
            system = spec.labelSystemInstruction(locale),
            text = spec.labelRequestText,
            schema = spec.labelSchema(),
            maxTokens = spec.claude.labelMaxTokens,
            imageJpeg = imageJpeg,
            timeoutMillis = LABEL_TIMEOUT_MS,
        )
        return AIDirectTransport.finalizeLabel(text, spec)
    }

    private suspend fun key(): String = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: throw AIEstimateError.MissingKey

    private suspend fun send(
        key: String,
        spec: AIEstimateSpec,
        system: String,
        text: String,
        schema: JsonElement,
        maxTokens: Int,
        imageJpeg: ByteArray,
        timeoutMillis: Long,
    ): String {
        val body = body(spec, system, text, schema, maxTokens, AIDirectTransport.imageBase64(imageJpeg))
        val reply = AIDirectTransport.post(
            client, ENDPOINT,
            headers = mapOf("x-api-key" to key, "anthropic-version" to spec.claude.anthropicVersion),
            body = body,
            timeoutMillis = timeoutMillis,
        )
        return answerText(reply.status, reply.body)
    }

    companion object {
        const val ENDPOINT: String = "https://api.anthropic.com/v1/messages"

        /** The first request with a new schema also compiles its grammar. */
        const val ESTIMATE_TIMEOUT_MS: Long = 90_000
        const val LABEL_TIMEOUT_MS: Long = 75_000

        /**
         * `schema` is canonical (with `additionalProperties: false`). No `temperature` / `top_p` /
         * `top_k` (a non-default value is a 400 on Sonnet 5), no `thinking`, no prefill.
         */
        fun body(
            spec: AIEstimateSpec,
            system: String,
            text: String,
            schema: JsonElement,
            maxTokens: Int,
            imageBase64: String,
        ): JsonObject = buildJsonObject {
            put("model", spec.claude.model)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonObject("output_config") {
                put("effort", spec.claude.effort)
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", schema)
                }
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", imageBase64)
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    }
                }
            }
        }

        /** Status first, then `stop_reason`: a cut-off answer is unreadable, a refusal a provider error. */
        fun answerText(status: Int, body: String): String {
            when (status) {
                in 200..299 -> Unit
                401, 403 -> throw AIEstimateError.KeyRejected
                429, 500, 503, 529 -> throw AIEstimateError.Busy
                else -> throw AIEstimateError.ProviderError(status, AIDirectTransport.errorMessage(body))
            }
            val json = AIDirectTransport.parseOrNull(body) ?: throw AIEstimateError.Unreadable
            when (json["stop_reason"].stringValue) {
                "max_tokens" -> throw AIEstimateError.Unreadable
                "refusal" -> throw AIEstimateError.ProviderError(status, "refusal")
            }
            val text = json["content"].arrayValue
                ?.mapNotNull { block -> if (block["type"].stringValue == "text") block["text"].stringValue else null }
                ?.joinToString("")
                .orEmpty()
            if (text.isEmpty()) throw AIEstimateError.Unreadable
            return text
        }
    }
}

// MARK: - Direct Gemini provider (bring your own key)

/**
 * Calls the Generative Language API straight from the device with the user's own key — the port of
 * `DirectGeminiEstimateService`. Only used when the user opted in to "Gemini with your key" in
 * Settings; the photo goes to Google, same as the backend path. Interactions API first (static
 * system instruction, thinking level, image resolution, response schema), `generateContent` when
 * Interactions answers 404 or 400. The key goes in a header, never in the URL.
 */
class DirectGeminiEstimateService(
    private val apiKey: suspend () -> String?,
    private val spec: suspend () -> AIEstimateSpec,
    private val client: HttpClient,
    private val grounding: AIBarcodeGrounding? = null,
) : AIEstimateService {

    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate {
        val key = key()
        val spec = spec()
        val text = send(
            key, spec,
            system = spec.estimateSystemInstruction(locale),
            text = spec.estimateRequestText(meal.raw, notes),
            schema = spec.estimateSchema(),
            imageJpeg = imageJpeg,
        )
        val estimate = AIDirectTransport.finalizeEstimate(text, spec, notes)
        return grounding?.ground(estimate) ?: estimate
    }

    override suspend fun readLabel(imageJpeg: ByteArray, locale: String): LabelReading {
        val key = key()
        val spec = spec()
        val text = send(
            key, spec,
            system = spec.labelSystemInstruction(locale),
            text = spec.labelRequestText,
            schema = spec.labelSchema(),
            imageJpeg = imageJpeg,
        )
        return AIDirectTransport.finalizeLabel(text, spec)
    }

    private suspend fun key(): String =
        apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: throw AIEstimateError.MissingGeminiKey

    private suspend fun send(
        key: String,
        spec: AIEstimateSpec,
        system: String,
        text: String,
        schema: JsonElement,
        imageJpeg: ByteArray,
    ): String {
        val image = AIDirectTransport.imageBase64(imageJpeg)
        val geminiSchema = AIEstimateSpec.forGemini(schema)
        val headers = mapOf("x-goog-api-key" to key)
        val first = AIDirectTransport.post(
            client, INTERACTIONS_URL, headers,
            body = interactionsBody(spec, system, text, geminiSchema, image),
            timeoutMillis = TIMEOUT_MS,
        )
        if (first.status == 400 || first.status == 404) {
            // 404: the model is not served by Interactions for this key. 400: this request shape was
            // refused; the established API answers it, and a bad key fails there too.
            val second = AIDirectTransport.post(
                client, generateContentUrl(spec.gemini.model), headers,
                body = generateContentBody(spec, system, text, geminiSchema, image),
                timeoutMillis = TIMEOUT_MS,
            )
            return answerText(second.status, second.body)
        }
        return answerText(first.status, first.body)
    }

    companion object {
        const val BASE: String = "https://generativelanguage.googleapis.com/v1beta"
        const val INTERACTIONS_URL: String = "$BASE/interactions"
        const val TIMEOUT_MS: Long = 60_000

        fun generateContentUrl(model: String): String = "$BASE/models/$model:generateContent"

        /** `schema` is the Gemini variant (no `additionalProperties`). No `temperature`: Gemini 3 runs at its default. */
        fun interactionsBody(
            spec: AIEstimateSpec,
            system: String,
            text: String,
            schema: JsonElement,
            imageBase64: String,
        ): JsonObject = buildJsonObject {
            put("model", spec.gemini.model)
            put("store", false)
            put("system_instruction", system)
            putJsonObject("generation_config") { put("thinking_level", spec.gemini.thinkingLevel) }
            putJsonArray("input") {
                addJsonObject {
                    put("type", "image")
                    put("data", imageBase64)
                    put("mime_type", "image/jpeg")
                    put("resolution", spec.gemini.imageResolution)
                }
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            putJsonObject("response_format") {
                put("type", "text")
                put("mime_type", "application/json")
                put("schema", schema)
            }
        }

        fun generateContentBody(
            spec: AIEstimateSpec,
            system: String,
            text: String,
            schema: JsonElement,
            imageBase64: String,
        ): JsonObject = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/jpeg")
                                put("data", imageBase64)
                            }
                        }
                        addJsonObject { put("text", text) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", schema)
                putJsonObject("thinkingConfig") { put("thinkingLevel", spec.gemini.thinkingLevel) }
                put("mediaResolution", spec.gemini.generateContentMediaResolution)
            }
        }

        /** Status mapping for either step (the 400/404 fallback is decided by the caller for step 1). */
        fun answerText(status: Int, body: String): String = when (status) {
            in 200..299 -> {
                val json = AIDirectTransport.parseOrNull(body) ?: throw AIEstimateError.Unreadable
                extractText(json)?.takeIf { it.isNotEmpty() } ?: throw AIEstimateError.Unreadable
            }
            401, 403 -> throw AIEstimateError.KeyRejected
            400 -> {
                val message = AIDirectTransport.errorMessage(body)
                // Google answers a bad or expired key with 400 INVALID_ARGUMENT, not 401.
                if (message?.contains("API key") == true) throw AIEstimateError.KeyRejected
                throw AIEstimateError.ProviderError(status, message)
            }
            429, in 500..599 -> throw AIEstimateError.Busy
            else -> throw AIEstimateError.ProviderError(status, AIDirectTransport.errorMessage(body))
        }

        private val TEXT_PATH_KEYS = listOf("steps", "outputs", "output", "candidates", "content", "parts")

        /**
         * The model's text from either API: `output_text`, else a depth-first walk (≤ 8 levels)
         * through `steps`, `outputs`, `output`, `candidates`, `content` and `parts`, skipping thought
         * parts. Joined with "".
         */
        fun extractText(json: JsonElement): String? {
            val root = json as? JsonObject ?: return null
            root["output_text"].stringValue?.let { return it }
            val texts = ArrayList<String>()
            fun visit(node: JsonElement, depth: Int) {
                if (depth > 8) return
                when (node) {
                    is kotlinx.serialization.json.JsonArray -> node.forEach { visit(it, depth + 1) }
                    is JsonObject -> {
                        if (node["thought"].booleanValue == true || node["type"].stringValue == "thought") return
                        val type = node["type"]
                        val text = node["text"].stringValue
                        if (text != null && (type == null || type.stringValue == "text")) texts += text
                        for (key in TEXT_PATH_KEYS) node[key]?.let { visit(it, depth + 1) }
                    }
                    else -> Unit
                }
            }
            visit(json, 0)
            return if (texts.isEmpty()) null else texts.joinToString("")
        }
    }
}

// MARK: - Provider resolution

/**
 * Where a photo would go, given the current config and whether a key is stored (`AIUpload` on iOS).
 * [None] is the offline mock (nothing leaves the device); [Gemini] is the user's own key, [Google]
 * our backend: both end up at Google, so they share one consent. The photo estimate and the label
 * read use the same provider and the same consent.
 */
enum class AIUpload {
    None,
    Google,
    Gemini,
    Anthropic;

    /** `nt.aiConsent.google` / `nt.aiConsent.anthropic`; `null` = nothing leaves the device. */
    val consentTarget: AiConsentTarget?
        get() = when (this) {
            None -> null
            Google, Gemini -> AiConsentTarget.Google
            Anthropic -> AiConsentTarget.Anthropic
        }

    /** `fuel.ai.provider.google` / `fuel.ai.provider.anthropic`. */
    val providerNameRes: Int
        get() = NtKeys.aiProviderName(this == Anthropic)
}

/**
 * `AIUpload.current` + `makeService()`: demo backend → mock, "Claude with your key" or "Gemini with
 * your key" **and** a stored key → that provider directly, otherwise the Fly.io backend. The photo
 * estimate and the label read resolve the provider the same way.
 *
 * [spec] loads the shared spec (off the main thread, once); [grounding] is the direct paths'
 * Open Food Facts barcode lookup.
 */
class AIEstimateProviders(
    private val config: AppConfig,
    private val authStore: AuthStore,
    private val mockStrings: AIEstimateStrings,
    private val spec: suspend () -> AIEstimateSpec,
    private val grounding: AIBarcodeGrounding? = null,
    private val engine: HttpClientEngine? = null,
) {

    private val directClient: HttpClient by lazy { AIDirectTransport.client(engine) }

    suspend fun upload(): AIUpload = when {
        config.useMockBackend.value -> AIUpload.None
        config.aiProvider.value == AIProvider.ClaudeBYOK && !authStore.anthropicKey().isNullOrEmpty() ->
            AIUpload.Anthropic
        config.aiProvider.value == AIProvider.GeminiBYOK && !authStore.geminiKey().isNullOrEmpty() ->
            AIUpload.Gemini
        else -> AIUpload.Google
    }

    suspend fun make(): AIEstimateService = service(upload())

    fun service(upload: AIUpload): AIEstimateService = when (upload) {
        AIUpload.None -> MockAIEstimateService(mockStrings)
        AIUpload.Anthropic -> DirectAnthropicEstimateService(authStore::anthropicKey, spec, directClient, grounding)
        AIUpload.Gemini -> DirectGeminiEstimateService(authStore::geminiKey, spec, directClient, grounding)
        AIUpload.Google -> BackendAIEstimateService(config::makeBackendClient)
    }
}
