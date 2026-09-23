package app.notomorrow.service

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import app.notomorrow.R
import app.notomorrow.model.MealSlot
import app.notomorrow.net.BackendError
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIFood
import app.notomorrow.net.dto.AIPer100
import app.notomorrow.net.dto.LabelReading
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.CancellationException

// `AIEstimate` / `AIFood` / `LabelReading` are the backend contract types in `net/dto/AiDto.kt`.
// Prompts, schemas and post-processing for the bring-your-own-key paths come from the shared spec
// (`AIEstimateSpec`, `AIFinalizer`), so the backend and both providers agree on every answer.

/**
 * Who turns a plate photo into macros, or a pack photo into label values — the port of the
 * `AIEstimateService` protocol in `NoTomorrow/Services/AIEstimateService.swift`. Four
 * implementations: [BackendAIEstimateService], [DirectAnthropicEstimateService],
 * [DirectGeminiEstimateService] (all three in `AIEstimateProviders.kt`) and [MockAIEstimateService].
 *
 * Every failure is an [AIEstimateError] (cancellation excepted).
 */
interface AIEstimateService {

    /** [notes] is the full notes string (typed details plus any corrections line), ≤ 1500 UTF-16 units. */
    suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String = ""): AIEstimate

    /**
     * A photo of a pack's nutrition table (≤ 1600 px, [app.notomorrow.util.ImageDownscaler.LABEL_LONG_EDGE])
     * → per-100 g values for the label form. `legible == false` is an answer, not an error.
     */
    suspend fun readLabel(imageJpeg: ByteArray, locale: String): LabelReading
}

/**
 * `AIEstimateError` (contract §10): one taxonomy for the photo estimate and the label read; each case
 * maps to one message ([messageRes]; [labelMessageRes] for the label read).
 */
sealed class AIEstimateError(message: String? = null) : Exception(message) {

    /** No answer at all (no connection, DNS, TLS, reset). */
    data object Offline : AIEstimateError()

    /** App-side timeout, or backend 504 `ai_timeout`. */
    data object Timeout : AIEstimateError()

    /** Backend 503 `ai_busy`; the user's provider answered 429 / 5xx / 529. */
    data object Busy : AIEstimateError()

    /** Backend 429 `ai_daily_limit`. */
    data object DailyLimit : AIEstimateError()

    /** Backend 403 `ai_not_allowed`: the account is not on the server's AI whitelist. */
    data object NotAllowed : AIEstimateError()

    /** No account session (or it expired and could not be refreshed) for the backend path. */
    data object SignedOut : AIEstimateError()

    data object MissingKey : AIEstimateError()

    data object MissingGeminiKey : AIEstimateError()

    /** The user's own Anthropic or Gemini key was rejected. */
    data object KeyRejected : AIEstimateError()

    /** No usable JSON in the answer (backend 502 `ai_unparseable`, BYOK parse failure, Claude `max_tokens`). */
    data object Unreadable : AIEstimateError()

    /**
     * Any other provider or server error (backend `ai_upstream_error`, `ai_unavailable`, another 4xx;
     * BYOK 4xx; Claude `refusal`). [serverMessage] is for logs, never shown.
     */
    data class ProviderError(val status: Int, val serverMessage: String?) : AIEstimateError(serverMessage)

    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            Offline -> R.string.error_network
            Timeout -> R.string.fuel_ai_error_timeout
            Busy -> R.string.fuel_ai_error_busy
            DailyLimit -> R.string.fuel_ai_error_dailyLimit
            NotAllowed -> R.string.fuel_ai_error_notAllowed
            SignedOut -> R.string.fuel_ai_error_signedOut
            MissingKey -> R.string.fuel_ai_error_missingKey
            MissingGeminiKey -> R.string.fuel_ai_error_missingGeminiKey
            KeyRejected -> R.string.fuel_ai_error_unauthorized
            Unreadable -> R.string.fuel_ai_error_unreadable
            is ProviderError -> R.string.fuel_ai_error_provider
        }

    /** The label read words "couldn't read" as the nutrition table, not the estimate. */
    @get:StringRes
    val labelMessageRes: Int
        get() = if (this == Unreadable) R.string.fuel_label_unreadable else messageRes

    fun localizedMessage(context: Context): String = context.getString(messageRes)

    companion object {
        /**
         * Transport failures of a direct provider call: a timeout is its own case, anything else is
         * "no connection". Cancellation is rethrown.
         */
        fun transport(error: Throwable): AIEstimateError {
            if (BackendError.isTimeout(error)) return Timeout
            if (error is CancellationException) throw error
            return Offline
        }

        /**
         * Any error from an [AIEstimateService] call as an [AIEstimateError]; cancellation is rethrown.
         * Services already throw only these, so this is a net for the unexpected.
         */
        fun from(error: Throwable): AIEstimateError = when (error) {
            is AIEstimateError -> error
            is CancellationException -> throw error
            else -> BackendAIEstimateService.map(BackendError.wrap(error))
        }
    }
}

// MARK: - Localised mock copy

/** Resolves catalog strings for an explicit locale ("pl"/"en") rather than the app locale. */
fun interface AIEstimateStrings {

    fun string(@StringRes id: Int, locale: String): String
}

/** `AIEstimateLocalizer` (`AIEstimateService.swift`). */
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

/**
 * Offline stand-in: a plausible plate after 1.2 s, matching the AIScan canvas, in the v2 shape
 * (per-100 g values and counted portions, totals computed like the finalizer); and a fixed, legible
 * cottage-cheese label.
 */
class MockAIEstimateService(
    private val strings: AIEstimateStrings,
    private val delayMillis: Long = MOCK_DELAY_MS,
) : AIEstimateService {

    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate {
        delay(delayMillis)
        val t = { id: Int -> strings.string(id, locale) }
        val pl = AIEstimateSpec.languageCode(locale) == "pl"
        val piece = if (pl) "szt." else "piece"
        val slice = if (pl) "kromka" else "slice"
        val portion = if (pl) "porcja" else "portion"
        val spoon = if (pl) "łyżka" else "tbsp"
        val foods: List<AIFood>
        val overall: Double
        when (meal) {
            MealSlot.Breakfast -> {
                foods = listOf(
                    AIFood.mock(t(R.string.fuel_ai_mock_eggs), AIPer100(155.0, 12.7, 1.3, 10.7), 1.0, portion, 150.0, 0.65),
                    AIFood.mock(t(R.string.fuel_ai_mock_toast), AIPer100(266.0, 8.6, 48.6, 2.9), 2.0, slice, 35.0, 0.65),
                    AIFood.mock(
                        t(R.string.fuel_ai_mock_butter), AIPer100(740.0, 0.7, 0.7, 82.0), 2.0, piece, 5.0, 0.35,
                        isGuess = true,
                    ),
                )
                overall = 0.6
            }
            MealSlot.Snack -> {
                foods = listOf(
                    AIFood.mock(t(R.string.fuel_ai_mock_skyr), AIPer100(63.0, 11.0, 4.0, 0.2), 1.0, portion, 200.0, 0.65),
                    AIFood.mock(t(R.string.fuel_ai_mock_banana), AIPer100(95.0, 1.1, 21.0, 0.3), 1.0, piece, 120.0, 0.65),
                    AIFood.mock(t(R.string.fuel_ai_mock_almonds), AIPer100(579.0, 21.0, 9.7, 50.0), 1.0, portion, 20.0, 0.55),
                )
                overall = 0.6
            }
            MealSlot.Lunch, MealSlot.Dinner -> {
                foods = listOf(
                    AIFood.mock(t(R.string.fuel_ai_mock_chicken), AIPer100(160.0, 31.0, 0.0, 3.6), 1.0, portion, 180.0, 0.65),
                    AIFood.mock(t(R.string.fuel_ai_mock_rice), AIPer100(130.0, 2.7, 28.2, 0.3), 1.0, portion, 220.0, 0.6),
                    AIFood.mock(t(R.string.fuel_ai_mock_broccoli), AIPer100(35.0, 2.4, 4.4, 0.4), 1.0, portion, 90.0, 0.65),
                    AIFood.mock(
                        t(R.string.fuel_ai_mock_oliveOil), AIPer100(884.0, 0.0, 0.0, 100.0), 1.0, spoon, 10.0, 0.3,
                        isGuess = true,
                    ),
                )
                overall = 0.55
            }
        }
        return AIEstimate(
            foods = foods,
            overallConfidence = overall,
            assumptions = emptyList(),
            questions = emptyList(),
            scaleReferenceUsed = "none",
            version = 2,
            totals = AIFinalizer.computeTotals(foods),
            skipped = emptyList(),
        )
    }

    /** A fixed, legible cottage-cheese table: 97 kcal, P 11 · C 2 · F 5. */
    override suspend fun readLabel(imageJpeg: ByteArray, locale: String): LabelReading {
        delay(delayMillis)
        return LABEL
    }

    companion object {
        const val MOCK_DELAY_MS: Long = 1_200

        val LABEL: LabelReading = LabelReading(
            legible = true,
            basis = "per100g",
            energyFrom = "kcal",
            name = "Serek wiejski",
            brand = "",
            per100 = LabelReading.Per100(kcal = 97.0, protein = 11.0, carbs = 2.0, fat = 5.0, sugar = 2.0, salt = 0.6),
            servingSizeG = 200.0,
            packageSizeG = 200.0,
            barcode = "",
            confidence = 0.9,
            needsReview = false,
        )
    }
}
