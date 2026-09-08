package app.notomorrow.service

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import app.notomorrow.R
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIFood
import app.notomorrow.net.dto.NtJson
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * Who turns a plate photo into macros — the port of the `AIEstimateService` protocol in
 * `NoTomorrow/Services/AIEstimateService.swift`. Four implementations:
 * [BackendAIEstimateService], [DirectAnthropicEstimateService], [DirectGeminiEstimateService]
 * (all three in `AIEstimateProviders.kt`) and [MockAIEstimateService].
 */
interface AIEstimateService {

    suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String = ""): AIEstimate
}

/** `AIEstimateError` (`AIEstimateService.swift:12`). */
sealed class AIEstimateError(message: String? = null) : Exception(message) {

    data object MissingKey : AIEstimateError()

    data object MissingGeminiKey : AIEstimateError()

    /** The user's own Anthropic or Gemini key was rejected. */
    data object Unauthorized : AIEstimateError()

    /** No account session (or it expired and could not be refreshed) for the backend path. */
    data object SignedOut : AIEstimateError()

    /** Backend 429 (`ai_busy`), 502 (`ai_upstream_error`, `ai_unparseable`) or 503 (`ai_unavailable`). */
    data object Busy : AIEstimateError()

    /** Backend 429 `ai_daily_limit`. */
    data object DailyLimit : AIEstimateError()

    /** Backend 403 `ai_not_allowed`: the account is not on the server's AI whitelist. */
    data object NotAllowed : AIEstimateError()

    data class BadResponse(val status: Int, val serverMessage: String?) : AIEstimateError(serverMessage)

    data object InvalidJson : AIEstimateError()

    data object Network : AIEstimateError()

    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            MissingKey -> R.string.fuel_ai_error_missingKey
            MissingGeminiKey -> R.string.fuel_ai_error_missingGeminiKey
            Unauthorized -> R.string.fuel_ai_error_unauthorized
            SignedOut -> R.string.fuel_ai_error_signedOut
            Busy -> R.string.fuel_ai_error_busy
            DailyLimit -> R.string.fuel_ai_error_dailyLimit
            NotAllowed -> R.string.fuel_ai_error_notAllowed
            Network -> R.string.error_network
            is BadResponse -> R.string.fuel_ai_failed
            InvalidJson -> R.string.fuel_ai_error_unreadable
        }

    fun localizedMessage(context: Context): String = context.getString(messageRes)
}

// MARK: - Shared prompt

object AIEstimatePrompt {

    /** The scale-reference ladder and output contract. Shared by the backend (documented) and the direct Claude path. */
    fun text(meal: MealSlot, locale: String): String {
        val language = if (locale.lowercase(Locale.ROOT).startsWith("pl")) "Polish" else "English"
        return """
        You estimate the food on a plate from one photo for a calorie-tracking app.
        Meal slot: ${meal.raw}. Write food names in $language, short and specific (e.g. "Grilled chicken breast").

        Use these scale references when judging portions:
        - a dinner plate is 26–28 cm across; a fork is about 19 cm long
        - a fist ≈ 150 g of cooked rice or pasta
        - a palm (no fingers) ≈ 100–120 g of cooked meat or fish
        - a thumb ≈ 1 tablespoon (≈ 14 g) of fat, butter or oil
        Photos tend to hide oil and sauces: include cooking fat as a separate guessed item (confidence ≤ 0.4) whenever the food looks fried, roasted or glossy.
        Give macros for the whole portion (not per 100 g), in grams. kcal should be consistent with the macros (4/4/9).
        confidence and overall_confidence are 0–1.

        Respond with strict JSON only, no prose, no code fences:
        {"foods":[{"name":"","grams":0,"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0,"confidence":0}],"overall_confidence":0}
        """.trimIndent()
    }

    /** Accepts ```json fences, leading prose and trailing commentary; returns the outermost JSON object. */
    fun extractJson(text: String): String? {
        var s = text.trim()
        if (s.startsWith("```")) s = s.replace("```json", "").replace("```", "")
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end < 0 || start >= end) return null
        return s.substring(start, end + 1)
    }
}

/**
 * Wire shape requested from Claude (snake_case, whole-portion macros), mapped to [AIFood] —
 * the port of `AIEstimateWire`. Swift's `.convertFromSnakeCase` means `protein_g` and `proteinG`
 * both land on the same property; the lookup lists below reproduce that, and additionally tolerate
 * numbers arriving as JSON strings.
 */
object AIEstimateWire {

    fun decode(json: String): AIEstimate {
        val root = runCatching { NtJson.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: throw AIEstimateError.InvalidJson
        val array = root["foods"] as? JsonArray ?: throw AIEstimateError.InvalidJson
        val items = array.map { element ->
            val o = element as? JsonObject ?: throw AIEstimateError.InvalidJson
            val name = string(o, "name") ?: string(o, "name_en") ?: throw AIEstimateError.InvalidJson
            val confidence = (number(o, "confidence") ?: 0.5).coerceIn(0.0, 1.0)
            AIFood(
                name = name,
                grams = number(o, "grams") ?: number(o, "estimated_grams") ?: number(o, "estimatedGrams") ?: 0.0,
                kcal = number(o, "kcal") ?: number(o, "calories") ?: 0.0,
                protein = number(o, "protein_g") ?: number(o, "proteinG") ?: number(o, "protein") ?: 0.0,
                carbs = number(o, "carbs_g") ?: number(o, "carbsG") ?: number(o, "carbs") ?: 0.0,
                fat = number(o, "fat_g") ?: number(o, "fatG") ?: number(o, "fat") ?: 0.0,
                confidence = confidence,
                isGuess = bool(o, "is_guess") ?: bool(o, "isGuess") ?: (confidence < 0.5),
            )
        }
        val declared = number(root, "overall_confidence") ?: number(root, "overallConfidence")
        val overall = declared
            ?: if (items.isEmpty()) 0.0 else items.sumOf { it.confidence } / items.size
        return AIEstimate(foods = items, overallConfidence = overall.coerceIn(0.0, 1.0))
    }

    private fun primitive(o: JsonObject, key: String): JsonPrimitive? =
        (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun number(o: JsonObject, key: String): Double? {
        val p = primitive(o, key) ?: return null
        return if (p.isString) p.content.replace(',', '.').trim().toDoubleOrNull() else p.content.toDoubleOrNull()
    }

    private fun string(o: JsonObject, key: String): String? = primitive(o, key)?.content

    private fun bool(o: JsonObject, key: String): Boolean? = when (primitive(o, key)?.content?.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

// MARK: - Localised mock copy

/** Resolves catalog strings for an explicit locale ("pl"/"en") rather than the app locale. */
fun interface AIEstimateStrings {

    fun string(@StringRes id: Int, locale: String): String
}

/** `AIEstimateLocalizer` (`AIEstimateService.swift:151`). */
object AIEstimateLocalizer {

    fun language(locale: String): String =
        if (locale.lowercase(Locale.ROOT).startsWith("pl")) "pl" else "en"

    fun from(context: Context): AIEstimateStrings {
        val app = context.applicationContext
        val cache = HashMap<String, Context>(2)
        return AIEstimateStrings { id, locale ->
            val lang = language(locale)
            val localized = cache.getOrPut(lang) {
                val config = Configuration(app.resources.configuration)
                config.setLocale(Locale.forLanguageTag(lang))
                app.createConfigurationContext(config)
            }
            localized.getString(id)
        }
    }
}

// MARK: - Mock

/** Offline stand-in: a plausible plate after 1.2 s, matching the AIScan canvas. */
class MockAIEstimateService(
    private val strings: AIEstimateStrings,
    private val delayMillis: Long = MOCK_DELAY_MS,
) : AIEstimateService {

    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate {
        delay(delayMillis)
        val t = { id: Int -> strings.string(id, locale) }
        return when (meal) {
            MealSlot.Breakfast -> AIEstimate(
                foods = listOf(
                    AIFood.of(t(R.string.fuel_ai_mock_eggs), 150.0, 232.0, 19.0, 2.0, 16.0, 0.85),
                    AIFood.of(t(R.string.fuel_ai_mock_toast), 70.0, 186.0, 6.0, 34.0, 2.0, 0.8),
                    AIFood.of(t(R.string.fuel_ai_mock_butter), 10.0, 72.0, 0.0, 0.0, 8.0, 0.35, isGuess = true),
                ),
                overallConfidence = 0.7,
            )
            MealSlot.Snack -> AIEstimate(
                foods = listOf(
                    AIFood.of(t(R.string.fuel_ai_mock_skyr), 200.0, 126.0, 22.0, 8.0, 0.0, 0.75),
                    AIFood.of(t(R.string.fuel_ai_mock_banana), 120.0, 107.0, 1.0, 27.0, 0.0, 0.9),
                    AIFood.of(t(R.string.fuel_ai_mock_almonds), 20.0, 116.0, 4.0, 4.0, 10.0, 0.55),
                ),
                overallConfidence = 0.7,
            )
            MealSlot.Lunch, MealSlot.Dinner -> AIEstimate(
                foods = listOf(
                    AIFood.of(t(R.string.fuel_ai_mock_chicken), 180.0, 297.0, 56.0, 0.0, 6.0, 0.8),
                    AIFood.of(t(R.string.fuel_ai_mock_rice), 220.0, 286.0, 6.0, 62.0, 1.0, 0.7),
                    AIFood.of(t(R.string.fuel_ai_mock_broccoli), 90.0, 31.0, 3.0, 6.0, 0.0, 0.85),
                    AIFood.of(t(R.string.fuel_ai_mock_oliveOil), 14.0, 119.0, 0.0, 0.0, 14.0, 0.3, isGuess = true),
                ),
                overallConfidence = 0.6,
            )
        }
    }

    companion object {
        const val MOCK_DELAY_MS: Long = 1_200
    }
}
