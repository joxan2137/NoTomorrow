package app.notomorrow.service

import app.notomorrow.model.AIProvider
import app.notomorrow.model.MealSlot
import app.notomorrow.net.BackendClient
import app.notomorrow.net.BackendError
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.NtJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.concurrent.CancellationException

// MARK: - Backend (Fly.io) provider

/**
 * Sends the photo through [BackendClient.estimate] (multipart `image` + `meal` + `locale`, Bearer
 * session, refresh-on-401) and maps the server's error codes to user-facing [AIEstimateError]s —
 * the port of `BackendAIEstimateService`.
 *
 * The user's own Claude key never goes through the backend: iOS passes `anthropicKey: nil` here and
 * lets [DirectAnthropicEstimateService] handle that path. [anthropicKey] exists only so a caller
 * that deliberately wants the server-side `X-Anthropic-Key` header can opt in; the resolver never does.
 */
class BackendAIEstimateService(
    private val client: () -> BackendClient,
    private val anthropicKey: (suspend () -> String?)? = null,
) : AIEstimateService {

    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate =
        try {
            client().estimate(imageJpeg, meal, locale, anthropicKey?.invoke(), notes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw map(BackendError.wrap(e))
        }

    companion object {
        /**
         * 429/502/503 (and any `ai_*` code) → "busy, try again in a minute"; `ai_daily_limit` and
         * `ai_not_allowed` get their own lines.
         */
        fun map(error: BackendError): AIEstimateError = when (error) {
            is BackendError.Unauthorized -> AIEstimateError.SignedOut
            is BackendError.Network -> AIEstimateError.Network
            is BackendError.Decoding -> AIEstimateError.InvalidJson
            is BackendError.Server -> AIEstimateError.BadResponse(0, error.serverMessage)
            is BackendError.Http -> when {
                error.code == "ai_not_allowed" -> AIEstimateError.NotAllowed
                error.code == "ai_daily_limit" -> AIEstimateError.DailyLimit
                error.status in setOf(429, 502, 503) || error.code.startsWith("ai_") -> AIEstimateError.Busy
                else -> AIEstimateError.BadResponse(error.status, error.serverMessage)
            }
        }
    }
}

// MARK: - Direct Claude provider (bring your own key)

/**
 * Calls the Messages API straight from the device with the user's own key — the port of
 * `DirectAnthropicEstimateService`. Only used when the user opted in to "Claude with your key" in
 * Settings; the photo leaves the device by explicit user action.
 */
class DirectAnthropicEstimateService(
    private val apiKey: suspend () -> String?,
    engine: HttpClientEngine? = null,
) : AIEstimateService {

    private val client: HttpClient = HttpClient(engine ?: OkHttp.create()) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MS
            connectTimeoutMillis = TIMEOUT_MS
            socketTimeoutMillis = TIMEOUT_MS
        }
    }

    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate {
        val key = apiKey()?.trim()
        if (key.isNullOrEmpty()) throw AIEstimateError.MissingKey

        val body = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", Base64.getEncoder().encodeToString(imageJpeg))
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", AIEstimatePrompt.text(meal, locale) + "\nMeal details: " + notes.take(1500))
                        }
                    }
                }
            }
        }

        val response = try {
            client.post(ENDPOINT) {
                header("x-api-key", key)
                header("anthropic-version", API_VERSION)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                contentType(ContentType.Application.Json)
                setBody(NtJson.encodeToString(JsonObject.serializer(), body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AIEstimateError.Network
        }

        val status = response.status.value
        val payload = response.bodyAsText()
        when {
            status in 200..299 -> Unit
            status == 401 || status == 403 -> throw AIEstimateError.Unauthorized
            else -> throw AIEstimateError.BadResponse(status, errorMessage(payload))
        }

        val json = AIEstimatePrompt.extractJson(textBlocks(payload)) ?: throw AIEstimateError.InvalidJson
        return AIEstimateWire.decode(json)
    }

    companion object {
        const val ENDPOINT: String = "https://api.anthropic.com/v1/messages"
        const val MODEL: String = "claude-sonnet-5"
        const val API_VERSION: String = "2023-06-01"
        const val MAX_TOKENS: Int = 1024
        const val TIMEOUT_MS: Long = 60_000

        /** `AnthropicMessageResponse`: every `type == "text"` block, joined by newlines. */
        fun textBlocks(payload: String): String {
            val root = runCatching { NtJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return ""
            val content = root["content"] as? JsonArray ?: return ""
            return content.mapNotNull { element ->
                val block = element as? JsonObject ?: return@mapNotNull null
                val type = (block["type"] as? JsonPrimitive)?.content
                if (type != "text") null else (block["text"] as? JsonPrimitive)?.content
            }.joinToString("\n")
        }

        /** `AnthropicErrorEnvelope.error.message`. */
        fun errorMessage(payload: String): String? {
            val root = runCatching { NtJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
            val error = root["error"] as? JsonObject ?: return null
            return (error["message"] as? JsonPrimitive)?.content
        }
    }
}

// MARK: - Direct Gemini provider (bring your own key)

/**
 * Calls the Generative Language API straight from the device with the user's own key — the port of
 * `DirectGeminiEstimateService`. Only used when the user opted in to "Gemini with your key" in
 * Settings; the photo goes to Google, same as the backend path.
 */
class DirectGeminiEstimateService(
    private val apiKey: suspend () -> String?,
    engine: HttpClientEngine? = null,
) : AIEstimateService {

    private val client: HttpClient = HttpClient(engine ?: OkHttp.create()) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MS
            connectTimeoutMillis = TIMEOUT_MS
            socketTimeoutMillis = TIMEOUT_MS
        }
    }

    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate {
        val key = apiKey()?.trim()
        if (key.isNullOrEmpty()) throw AIEstimateError.MissingGeminiKey

        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", AIEstimatePrompt.text(meal, locale) + "\nMeal details: " + notes.take(1500))
                        }
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/jpeg")
                                put("data", Base64.getEncoder().encodeToString(imageJpeg))
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
            }
        }

        val response = try {
            client.post(ENDPOINT) {
                header("x-goog-api-key", key)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                contentType(ContentType.Application.Json)
                setBody(NtJson.encodeToString(JsonObject.serializer(), body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AIEstimateError.Network
        }

        val status = response.status.value
        val payload = response.bodyAsText()
        when {
            status in 200..299 -> Unit
            status == 401 || status == 403 -> throw AIEstimateError.Unauthorized
            status == 429 -> throw AIEstimateError.Busy
            else -> {
                val message = errorMessage(payload)
                // Google answers a bad or expired key with 400 INVALID_ARGUMENT, not 401.
                if (status == 400 && message?.contains("API key") == true) throw AIEstimateError.Unauthorized
                throw AIEstimateError.BadResponse(status, message)
            }
        }

        val json = AIEstimatePrompt.extractJson(textParts(payload)) ?: throw AIEstimateError.InvalidJson
        return AIEstimateWire.decode(json)
    }

    companion object {
        /** The same default the backend uses. */
        const val MODEL: String = "gemini-3.8-flash"
        const val ENDPOINT: String =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        const val TIMEOUT_MS: Long = 60_000

        /** `GeminiGenerateContentResponse`: the first candidate's text parts, joined by newlines. */
        fun textParts(payload: String): String {
            val root = runCatching { NtJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return ""
            val candidates = root["candidates"] as? JsonArray ?: return ""
            val content = (candidates.firstOrNull() as? JsonObject)?.get("content") as? JsonObject ?: return ""
            val parts = content["parts"] as? JsonArray ?: return ""
            return parts.mapNotNull { element ->
                ((element as? JsonObject)?.get("text") as? JsonPrimitive)?.content
            }.joinToString("\n")
        }

        /** `GeminiErrorEnvelope.error.message`. */
        fun errorMessage(payload: String): String? {
            val root = runCatching { NtJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
            val error = root["error"] as? JsonObject ?: return null
            return (error["message"] as? JsonPrimitive)?.content
        }
    }
}

// MARK: - Provider resolution

/**
 * Where the photo goes, given the current config and whether a key is stored (`AIScanModel.Upload`).
 * [Gemini] is the user's own key, [Google] our backend — both end up at Google.
 */
enum class AIUpload {
    None,
    Google,
    Gemini,
    Anthropic;

    /** `nt.aiConsent.google` / `nt.aiConsent.anthropic`; `null` = nothing leaves the device. */
    val consentTarget: app.notomorrow.data.prefs.AiConsentTarget?
        get() = when (this) {
            None -> null
            Google, Gemini -> app.notomorrow.data.prefs.AiConsentTarget.Google
            Anthropic -> app.notomorrow.data.prefs.AiConsentTarget.Anthropic
        }

    /** `fuel.ai.provider.google` / `fuel.ai.provider.anthropic`. */
    val providerNameRes: Int
        get() = app.notomorrow.util.NtKeys.aiProviderName(this == Anthropic)
}

/**
 * `AIScanModel.upload` + `makeService()`: demo backend → mock, "Claude with your key" or "Gemini
 * with your key" **and** a stored key → that provider directly, otherwise the Fly.io backend.
 */
class AIEstimateProviders(
    private val config: AppConfig,
    private val authStore: AuthStore,
    private val mockStrings: AIEstimateStrings,
    private val engine: HttpClientEngine? = null,
) {

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
        AIUpload.Anthropic -> DirectAnthropicEstimateService(authStore::anthropicKey, engine)
        AIUpload.Gemini -> DirectGeminiEstimateService(authStore::geminiKey, engine)
        AIUpload.Google -> BackendAIEstimateService(config::makeBackendClient)
    }
}
