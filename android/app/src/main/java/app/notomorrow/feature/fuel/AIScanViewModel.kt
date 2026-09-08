package app.notomorrow.feature.fuel

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIFood
import app.notomorrow.service.AIEstimateError
import app.notomorrow.service.AIEstimateProviders
import app.notomorrow.service.AIUpload
import app.notomorrow.service.Days
import app.notomorrow.util.Fmt
import app.notomorrow.util.ImageDownscaler
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.S
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * `AIScanModel.Phase` (`Features/Fuel/AIScanModel.swift:8`). The failure carries a catalog id
 * rather than a resolved string: iOS builds its message with `String(localized:)` inside the
 * model, here the resource is resolved by the composable so a language change re-renders it.
 */
@Immutable
sealed interface AIScanPhase {
    data object PickSource : AIScanPhase
    data object Analyzing : AIScanPhase
    data object Result : AIScanPhase
    data class Failed(@StringRes val messageRes: Int) : AIScanPhase

    /** Backend 403 `ai_not_allowed`: the account is not whitelisted, so the card offers Settings instead. */
    data object NotAllowed : AIScanPhase
}

/** `AIScanModel.ConfidenceLevel` (`AIScanModel.swift:60`). */
enum class AIScanConfidence(val bars: Int, @StringRes val labelRes: Int) {
    High(3, S.fuel_ai_confidence_high),
    Medium(2, S.fuel_ai_confidence_medium),
    Low(1, S.fuel_ai_confidence_low),
}

/** Everything `AIScanView` renders, in one immutable snapshot. */
@Immutable
data class AIScanUiState(
    val meal: MealSlot,
    val phase: AIScanPhase = AIScanPhase.PickSource,
    val photo: ImageBitmap? = null,
    val foods: List<AIFood> = emptyList(),
    val overallConfidence: Double = 0.0,
    val notes: String = "",
    val assumptions: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    /** Resolved from `AppConfig` + the stored BYOK key; drives consent and the signed-out gate. */
    val upload: AIUpload = AIUpload.Google,
    /**
     * `false` until the first `providers.upload()` lands. iOS reads `config.useMockBackend` and
     * the keychain synchronously, so the signed-out gate can never flash on a mock/BYOK build;
     * here the read suspends, so the gate stays closed until [upload] is real.
     */
    val uploadResolved: Boolean = false,
    val showConsent: Boolean = false,
    val needsSignIn: Boolean = true,
    @StringRes val toast: Int? = null,
) {
    val totalKcal: Double get() = AIScanDerive.total(foods) { it.kcal }
    val totalProtein: Double get() = AIScanDerive.total(foods) { it.protein }
    val totalCarbs: Double get() = AIScanDerive.total(foods) { it.carbs }
    val totalFat: Double get() = AIScanDerive.total(foods) { it.fat }

    val confidenceLevel: AIScanConfidence get() = AIScanDerive.confidence(overallConfidence)

    /** `AIScanView.showsRetake` — every phase but the source picker. */
    val showsRetake: Boolean get() = phase != AIScanPhase.PickSource

    /** `fuel.ai.provider.google` / `fuel.ai.provider.anthropic`. */
    @get:StringRes
    val providerNameRes: Int get() = upload.providerNameRes

    /**
     * `AIScanView.content`: `model.upload == .google, !AuthStore.shared.isSignedIn`. The demo
     * backend resolves to [AIUpload.None] and the BYOK path needs no account, so only the
     * backend path can land here.
     */
    val showsSignedOut: Boolean
        get() = uploadResolved &&
            phase == AIScanPhase.PickSource &&
            upload == AIUpload.Google &&
            needsSignIn
}

/**
 * The pure half of `AIScanModel` — no Room, no Compose, no Android, so it is unit tested on the
 * JVM (`app/src/test/java/app/notomorrow/feature/fuelaiscan`).
 */
object AIScanDerive {

    /** The percentages the grams menu offers (`AIScanFoodRow.swift:58`). */
    val ScaleDeltas: List<Double> = listOf(-0.25, -0.10, 0.10, 0.25)

    /** `AIScanPhoto.anchors` — fractions of the photo frame, in tag order. */
    val TagAnchors: List<Pair<Float, Float>> = listOf(
        0.11f to 0.13f, 0.62f to 0.21f, 0.34f to 0.78f,
        0.66f to 0.60f, 0.08f to 0.48f, 0.40f to 0.42f,
    )

    inline fun total(foods: List<AIFood>, selector: (AIFood) -> Double): Double =
        foods.fold(0.0) { acc, food -> acc + selector(food) }

    /** `AIScanModel.confidenceLevel` — `0.75…` high, `0.45..<0.75` medium, else low. */
    fun confidence(overall: Double): AIScanConfidence = when {
        overall >= 0.75 -> AIScanConfidence.High
        overall >= 0.45 -> AIScanConfidence.Medium
        else -> AIScanConfidence.Low
    }

    /** `AIScanFoodRow.showsGuess`. */
    fun showsGuess(food: AIFood): Boolean = food.isGuess || food.confidence < 0.4

    /** `AIScanModel.scale(by:for:)` — `(grams * factor).rounded()`, half away from zero. */
    fun scaledGrams(grams: Double, factor: Double): Double =
        Fmt.roundHalfAwayFromZero(grams * factor)

    /**
     * `AIScanFoodRow.applyCustom` — comma decimal separators accepted, whitespace trimmed,
     * non-positive and unparseable input ignored.
     */
    fun customGrams(text: String): Double? {
        val normalised = text.replace(",", ".").trim()
        val value = normalised.toDoubleOrNull() ?: return null
        return if (value > 0) value else null
    }

    /** `AIScanModel.setGrams(_:for:)` — a whole new list with the one food rescaled. */
    fun applyGrams(foods: List<AIFood>, id: String, grams: Double): List<AIFood> {
        if (grams <= 0) return foods
        val index = foods.indexOfFirst { it.id == id }
        if (index < 0) return foods
        return foods.toMutableList().also { it[index] = it[index].scaled(grams) }
    }

    /** `AIScanModel.log` — `for food in foods where food.kcal > 0 || food.grams > 0`. */
    fun loggable(foods: List<AIFood>): List<AIFood> = foods.filter { it.kcal > 0 || it.grams > 0 }
}

/**
 * `AIScanModel` (`Features/Fuel/AIScanModel.swift`): pick a source → analyse → edit the
 * estimate → log one `MealEntry` per food.
 *
 * The photo never leaves this object un-downscaled: [ImageDownscaler] produces the same
 * ≤1024 px JPEG 0.8 iOS uploads, and that is both what the preview shows and what the
 * provider receives.
 */
class AIScanViewModel(
    private val application: Application,
    private val prefs: AppPrefs,
    private val providers: AIEstimateProviders,
    private val mealDao: MealDao,
    needsSignIn: StateFlow<Boolean>,
    initialMeal: MealSlot,
    private val locale: () -> Locale = { LocaleProvider.current() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    // Seeded from the flow's current value, so the signed-out gate never flashes on the first frame.
    private val _state = MutableStateFlow(
        AIScanUiState(meal = initialMeal, needsSignIn = needsSignIn.value),
    )
    val state: StateFlow<AIScanUiState> = _state.asStateFlow()

    /** The downscaled bytes the provider gets — `AIScanModel.jpeg`. */
    private var jpeg: ByteArray? = null
    private var analysis: Job? = null
    private var toastJob: Job? = null
    /** Armed by [dismissConsent]; a button handler on the same main-loop turn cancels it. */
    private var consentFallback: Job? = null

    init {
        viewModelScope.launch {
            needsSignIn.collect { value -> _state.update { it.copy(needsSignIn = value) } }
        }
        viewModelScope.launch { refreshUpload() }
    }

    // MARK: - Presentation

    /**
     * `AIScanView.init` (`AIScanView.swift:16-21`) builds a **fresh** `AIScanModel(meal:)` for
     * every presentation, so each open of the AI scan starts at `.pickSource` for the tapped
     * slot. The Android view model is scoped to the Fuel tab's back-stack entry and therefore
     * outlives the sheet, so the screen calls this on every entry to get the same behaviour.
     */
    fun start(meal: MealSlot) {
        analysis?.cancel()
        analysis = null
        toastJob?.cancel()
        toastJob = null
        consentFallback?.cancel()
        consentFallback = null
        jpeg = null
        _state.value = AIScanUiState(meal = meal, needsSignIn = _state.value.needsSignIn)
        viewModelScope.launch { refreshUpload() }
    }

    // MARK: - Slot

    /** `LogToMealButton`'s menu writes straight back into the model, as the iOS binding does. */
    fun setMeal(slot: MealSlot) {
        _state.update { it.copy(meal = slot) }
    }

    // MARK: - Photo intake

    /** The system photo picker handed back a content URI. */
    fun pickedFromLibrary(uri: Uri) {
        viewModelScope.launch {
            receive(withContext(io) { ImageDownscaler.jpeg(application, uri) })
        }
    }

    /** The camera screen already downscaled its capture (it holds the rotation). */
    fun capturedPhoto(jpegBytes: ByteArray?) {
        viewModelScope.launch { receive(jpegBytes) }
    }

    /**
     * `AIScanModel.handlePicked` — downscale, then either ask for first-use consent or start
     * the analysis. A photo that cannot be encoded fails the same way a bad estimate does.
     */
    private suspend fun receive(data: ByteArray?) {
        if (data == null || data.isEmpty()) {
            _state.update { it.copy(phase = AIScanPhase.Failed(S.fuel_ai_failed)) }
            return
        }
        jpeg = data
        val preview = withContext(Dispatchers.Default) { decode(data) }
        _state.update { it.copy(photo = preview) }

        val upload = refreshUpload()
        val target = upload.consentTarget
        if (target != null && !prefs.aiConsentOnce(target)) {
            _state.update { it.copy(showConsent = true) }
        } else {
            analyze()
        }
    }

    private fun decode(data: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull()

    // MARK: - Consent

    /**
     * The consent alert closed. `NtAlert` calls this **before** the tapped button's own handler
     * (a button both hides the alert and acts), so it must not be the decline itself — that
     * would drop the photo before [acceptConsent] could use it, and the tap would do nothing.
     * A button's handler follows synchronously on this same main-loop turn and cancels the
     * fallback; an outside tap or the back gesture does not, and is then treated as Cancel,
     * the way SwiftUI's `role: .cancel` action is the alert's dismissal.
     */
    fun dismissConsent() {
        _state.update { it.copy(showConsent = false) }
        consentFallback?.cancel()
        consentFallback = viewModelScope.launch {
            yield()
            declineConsent()
        }
    }

    fun acceptConsent() {
        consentFallback?.cancel()
        consentFallback = null
        _state.update { it.copy(showConsent = false) }
        // Start now, so the analysing view is on screen this frame; the consent record itself is
        // a background write that nothing waits on.
        analyze()
        _state.value.upload.consentTarget?.let { target ->
            viewModelScope.launch { prefs.setAiConsent(target, true) }
        }
    }

    fun declineConsent() {
        consentFallback?.cancel()
        consentFallback = null
        _state.update { it.copy(showConsent = false) }
        retake()
    }

    // MARK: - Analysis

    /** `AIScanModel.retake()` — cancels the request and drops everything back to the source view. */
    fun retake() {
        analysis?.cancel()
        analysis = null
        jpeg = null
        _state.update {
            it.copy(
                phase = AIScanPhase.PickSource,
                photo = null,
                foods = emptyList(),
                overallConfidence = 0.0,
                showConsent = false,
            )
        }
    }

    fun setNotes(notes: String) { _state.update { it.copy(notes = notes.take(1500)) } }

    fun analyze() {
        val data = jpeg ?: return
        _state.update { it.copy(phase = AIScanPhase.Analyzing) }
        val notes = _state.value.notes
        val meal = _state.value.meal
        val language = locale().language.ifEmpty { "en" }
        analysis?.cancel()
        analysis = viewModelScope.launch {
            try {
                val estimate = providers.make().estimate(data, meal, language, notes)
                apply(estimate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val phase = if (error is AIEstimateError.NotAllowed) {
                    AIScanPhase.NotAllowed
                } else {
                    AIScanPhase.Failed((error as? AIEstimateError)?.messageRes ?: S.fuel_ai_failed)
                }
                _state.update { it.copy(phase = phase) }
            }
        }
    }

    private fun apply(estimate: AIEstimate) {
        if (estimate.foods.isEmpty()) {
            _state.update { it.copy(phase = AIScanPhase.Failed(S.fuel_ai_failed)) }
            return
        }
        _state.update {
            it.copy(
                foods = estimate.foods,
                assumptions = estimate.assumptions,
                questions = estimate.questions,
                overallConfidence = estimate.overallConfidence,
                phase = AIScanPhase.Result,
            )
        }
    }

    // MARK: - Editing

    fun setGrams(id: String, grams: Double) {
        _state.update { it.copy(foods = AIScanDerive.applyGrams(it.foods, id, grams)) }
    }

    fun scale(id: String, factor: Double) {
        val food = _state.value.foods.firstOrNull { it.id == id } ?: return
        setGrams(id, AIScanDerive.scaledGrams(food.grams, factor))
    }

    /** `AIScanModel.append` — the hook the "add something it missed" flow writes into. */
    fun append(food: AIFood) {
        _state.update { it.copy(foods = it.foods + food) }
    }

    // MARK: - Logging

    /**
     * One `MealEntry` per food, flagged as an AI estimate. Returns the number of rows written,
     * exactly like the `@discardableResult` Swift function.
     */
    suspend fun log(day: LocalDate): Int {
        val slot = _state.value.meal
        val midnight = Days.millis(day)
        val entries = AIScanDerive.loggable(_state.value.foods).map { food ->
            MealEntryEntity(
                id = UUID.randomUUID().toString(),
                day = midnight,
                slot = slot,
                customName = food.name,
                grams = food.grams,
                kcal = food.kcal,
                proteinG = food.protein,
                carbsG = food.carbs,
                fatG = food.fat,
                isAIEstimate = true,
                confidence = food.confidence,
            )
        }
        if (entries.isNotEmpty()) mealDao.insertAll(entries)
        return entries.size
    }

    // MARK: - Stubs

    /** `AIScanModel.saveAsRecipe()` — the toast iOS clears after two seconds. */
    fun saveAsRecipe() {
        toastJob?.cancel()
        _state.update { it.copy(toast = S.fuel_ai_recipeSoon) }
        toastJob = viewModelScope.launch {
            delay(TOAST_MILLIS)
            _state.update { it.copy(toast = null) }
        }
    }

    private suspend fun refreshUpload(): AIUpload {
        val upload = providers.upload()
        _state.update { it.copy(upload = upload, uploadResolved = true) }
        return upload
    }

    private companion object {
        /** `try? await Task.sleep(for: .seconds(2))` in `AIScanView.toastOverlay`. */
        const val TOAST_MILLIS: Long = 2_000
    }
}
