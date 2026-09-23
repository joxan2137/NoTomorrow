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
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIDatabaseFood
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIFood
import app.notomorrow.service.AIEstimateError
import app.notomorrow.service.AIEstimateProviders
import app.notomorrow.service.AIEstimateService
import app.notomorrow.service.AIUpload
import app.notomorrow.service.Days
import app.notomorrow.service.FoodSearchService
import app.notomorrow.util.Fmt
import app.notomorrow.util.ImageDownscaler
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.Parsing
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
    /** The items on screen and the user's corrections to them. */
    val edits: AIScanEdits = AIScanEdits(),
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
    /** "Recipes soon", or why a refine failed (the result stays). */
    @StringRes val toast: Int? = null,
) {
    val foods: List<AIFood> get() = edits.foods

    val totalKcal: Double get() = AIScanDerive.total(foods) { it.kcal }
    val totalProtein: Double get() = AIScanDerive.total(foods) { it.protein }
    val totalCarbs: Double get() = AIScanDerive.total(foods) { it.carbs }
    val totalFat: Double get() = AIScanDerive.total(foods) { it.fat }

    val confidenceLevel: AIScanConfidence get() = AIScanDerive.confidence(overallConfidence)

    /** `AIScanView.showsRetake` — every phase but the source picker. */
    val showsRetake: Boolean get() = phase != AIScanPhase.PickSource

    /**
     * Log and Recalculate need an item: with every one removed, Log would write nothing yet still
     * report success, and a refine would ask about a plate the user has emptied.
     */
    val hasItems: Boolean get() = AIScanDerive.loggable(foods).isNotEmpty()

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
     * `AIScanFoodRow.applyCustom` (`NumberInput.nonNegative`, then `> 0`) — comma decimal
     * separators accepted, whitespace trimmed, non-positive and unparseable input ignored.
     */
    fun customGrams(text: String): Double? = Parsing.positive(text)

    /** `AIScanModel.log` — `for food in foods where food.kcal > 0 || food.grams > 0`. */
    fun loggable(foods: List<AIFood>): List<AIFood> = foods.filter { it.kcal > 0 || it.grams > 0 }
}

/**
 * `AIScanModel.log(into:day:)`: one `MealEntry` per food. Model items log as AI estimates; items
 * the user took from the food database log as ordinary food entries, and the product is saved to
 * the library (or its usage bumped) the way the portion sheet does it.
 */
object AIScanLog {

    suspend fun entries(
        foods: List<AIFood>,
        slot: MealSlot,
        day: Long,
        foodDao: FoodDao,
        foodSearch: FoodSearchService,
        now: Long = System.currentTimeMillis(),
    ): List<MealEntryEntity> = AIScanDerive.loggable(foods).map { food ->
        val item = databaseItem(food, foodDao, foodSearch, now)
        if (item != null) {
            val factor = food.grams / 100.0
            MealEntryEntity(
                id = UUID.randomUUID().toString(),
                day = day,
                slot = slot,
                foodId = item.id,
                grams = food.grams,
                kcal = item.kcalPer100 * factor,
                proteinG = item.proteinPer100 * factor,
                carbsG = item.carbsPer100 * factor,
                fatG = item.fatPer100 * factor,
            )
        } else {
            MealEntryEntity(
                id = UUID.randomUUID().toString(),
                day = day,
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
    }

    /**
     * `AIScanModel.databaseItem(for:in:)`: the library food behind a database pick, inserted on
     * first use; null for the model's own items (or a saved food deleted in the meantime, which
     * then logs with its figures as a custom row).
     */
    suspend fun databaseItem(
        food: AIFood,
        foodDao: FoodDao,
        foodSearch: FoodSearchService,
        now: Long = System.currentTimeMillis(),
    ): FoodItemEntity? = when (val pick = food.databaseFood) {
        is AIDatabaseFood.Item -> foodDao.byId(pick.id)?.also { foodDao.bumpUsage(it.id, now) }
        is AIDatabaseFood.Candidate -> foodSearch.cacheOnTap(pick.candidate, foodDao, now)
        null -> null
    }
}

/**
 * `AIScanModel` (`Features/Fuel/AIScanModel.swift`): pick a source → analyse → correct the
 * estimate → log one `MealEntry` per food.
 *
 * The photo never leaves this object un-downscaled: [ImageDownscaler] produces the same
 * ≤1024 px JPEG 0.8 iOS uploads, and that is both what the preview shows and what the
 * provider receives.
 *
 * [service] is for tests (iOS `injectedService`): it replaces the provider resolution, and the
 * upload then counts as on-device (no consent).
 */
class AIScanViewModel(
    private val application: Application,
    private val prefs: AppPrefs,
    private val providers: AIEstimateProviders,
    private val mealDao: MealDao,
    private val foodDao: FoodDao,
    private val foodSearch: FoodSearchService,
    needsSignIn: StateFlow<Boolean>,
    initialMeal: MealSlot,
    private val locale: () -> Locale = { LocaleProvider.current() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val service: AIEstimateService? = null,
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
            receive(withContext(io) { ImageDownscaler.jpeg(application, uri, ImageDownscaler.PLATE_LONG_EDGE) })
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
        val preview = withContext(cpu) { decode(data) }
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
                edits = AIScanEdits(),
                assumptions = emptyList(),
                questions = emptyList(),
                overallConfidence = 0.0,
                showConsent = false,
            )
        }
    }

    /** The typed details; the request cuts them further to make room for the corrections line. */
    fun setNotes(notes: String) {
        _state.update { it.copy(notes = AIScanCorrections.truncated(notes, AIScanCorrections.MAX_NOTES_LENGTH)) }
    }

    /**
     * Sends the photo. From the result screen ("Recalculate with details") it also sends the
     * user's corrections and merges them back into the answer; if that request fails, the
     * current result stays and a toast says why.
     */
    fun analyze() {
        val data = jpeg ?: return
        val current = _state.value
        val refining = current.phase == AIScanPhase.Result
        val previous = if (refining) Snapshot(current) else null
        val notes = current.edits.outgoingNotes(current.notes, refining)
        val meal = current.meal
        val language = locale().language.ifEmpty { "en" }
        _state.update { it.copy(phase = AIScanPhase.Analyzing) }
        analysis?.cancel()
        analysis = viewModelScope.launch {
            try {
                val estimate = (service ?: providers.make()).estimate(data, meal, language, notes)
                apply(estimate, refining, previous)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(error, previous)
            }
        }
    }

    private fun apply(estimate: AIEstimate, refining: Boolean, previous: Snapshot?) {
        val merged = _state.value.edits.answered(estimate.foods, refining)
        if (merged.isEmpty()) {
            // The model found no food (an empty answer is not an error on the wire).
            fail(null, previous)
            return
        }
        _state.update {
            it.copy(
                edits = it.edits.copy(foods = merged),
                assumptions = estimate.assumptions,
                questions = estimate.questions,
                overallConfidence = estimate.overallConfidence,
                phase = AIScanPhase.Result,
            )
        }
    }

    private fun fail(error: Throwable?, previous: Snapshot?) {
        if (error is AIEstimateError.NotAllowed) {
            _state.update { it.copy(phase = AIScanPhase.NotAllowed) }
            return
        }
        val message = (error as? AIEstimateError)?.messageRes ?: S.fuel_ai_failed
        if (previous != null) {
            _state.update { previous.restore(it) }
            showToast(message)
        } else {
            _state.update { it.copy(phase = AIScanPhase.Failed(message)) }
        }
    }

    /** The result screen as it was before a refine, restored when the refine fails. */
    private class Snapshot(state: AIScanUiState) {
        private val edits = state.edits
        private val assumptions = state.assumptions
        private val questions = state.questions
        private val overallConfidence = state.overallConfidence

        fun restore(into: AIScanUiState): AIScanUiState = into.copy(
            edits = edits,
            assumptions = assumptions,
            questions = questions,
            overallConfidence = overallConfidence,
            phase = AIScanPhase.Result,
        )
    }

    // MARK: - Corrections

    fun setGrams(id: String, grams: Double) {
        _state.update { it.copy(edits = it.edits.setGrams(id, grams)) }
    }

    fun scale(id: String, factor: Double) {
        _state.update { it.copy(edits = it.edits.scale(id, factor)) }
    }

    /** −1 / +1 unit ("6 szt." → "7 szt."), grams follow the unit weight. */
    fun stepCount(id: String, up: Boolean) {
        _state.update { it.copy(edits = it.edits.stepCount(id, up)) }
    }

    /** The item editor's copy (name, count, grams, or a food-database product); no change is no correction. */
    fun update(edited: AIFood) {
        _state.update { it.copy(edits = it.edits.update(edited)) }
    }

    /** Drops a wrong item; a removed model item is reported on refine. */
    fun remove(id: String) {
        _state.update { it.copy(edits = it.edits.remove(id)) }
    }

    /** "Add something it missed": a food-database product sized in the portion sheet. */
    fun append(food: AIFood) {
        _state.update { it.copy(edits = it.edits.append(food)) }
    }

    // MARK: - Logging

    /**
     * One `MealEntry` per food: model items as AI estimates, food-database picks as ordinary food
     * entries. Returns the number of rows written, exactly like the `@discardableResult` Swift
     * function.
     */
    suspend fun log(day: LocalDate): Int {
        val entries = AIScanLog.entries(
            foods = _state.value.foods,
            slot = _state.value.meal,
            day = Days.millis(day),
            foodDao = foodDao,
            foodSearch = foodSearch,
        )
        if (entries.isNotEmpty()) mealDao.insertAll(entries)
        return entries.size
    }

    // MARK: - Stubs

    /** `AIScanModel.saveAsRecipe()`. */
    fun saveAsRecipe() {
        showToast(S.fuel_ai_recipeSoon)
    }

    /** The toast `AIScanView.toastOverlay` clears after [TOAST_MILLIS]. */
    private fun showToast(@StringRes message: Int) {
        toastJob?.cancel()
        _state.update { it.copy(toast = message) }
        toastJob = viewModelScope.launch {
            delay(TOAST_MILLIS)
            _state.update { it.copy(toast = null) }
        }
    }

    private suspend fun refreshUpload(): AIUpload {
        val upload = if (service != null) AIUpload.None else providers.upload()
        _state.update { it.copy(upload = upload, uploadResolved = true) }
        return upload
    }

    companion object {
        /**
         * Long enough to read a failed refine's two-line reason (and for TalkBack to finish saying
         * it) before it goes; two seconds was not.
         */
        const val TOAST_MILLIS: Long = 5_000
    }
}
