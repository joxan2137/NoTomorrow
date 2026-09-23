package app.notomorrow.feature.fuel

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.service.BarcodeKey
import app.notomorrow.service.BarcodeLookup
import app.notomorrow.service.FoodSearchError
import app.notomorrow.service.FoodSearchService
import app.notomorrow.service.ProductStub
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Scan → saved foods → Open Food Facts, shared by the Fuel tab and the search sheet — the port of
 * `Features/Fuel/BarcodeLookupFlow.swift`. The owning view model builds one on its scope; the
 * screen collects [state] and hosts [BarcodeLookupPrompts] (the one alert and the label sheet).
 *
 * iOS starts the lookup from the scanner sheet's `onDismiss`, because SwiftUI drops a sheet
 * presented while another is still going away. Compose sheets are plain composition — the scanner
 * leaves in the frame its code arrives — so [start] runs at once and a saved food's portion sheet
 * always shows.
 */
class BarcodeLookupFlow(
    private val foodDao: FoodDao,
    private val service: FoodSearchService,
    private val scope: CoroutineScope,
    private val locale: () -> Locale = { LocaleProvider.current() },
    private val retryPauseMs: Long = RETRY_PAUSE_MS,
) {

    /** The alert after a lookup that found nothing ready to size. */
    @Immutable
    sealed interface Prompt {
        val code: String

        /** Open Food Facts has the product but no usable nutrition: the label form starts from its name and brand. */
        data class Partial(val stub: ProductStub, override val code: String) : Prompt

        data class NotFound(override val code: String) : Prompt

        /** 429 / 5xx after the retry, offline, or no answer: the message says which, and Try again repeats the lookup. */
        data class Failed(@param:StringRes val messageRes: Int, override val code: String) : Prompt
    }

    @Immutable
    data class LabelRequest(val code: String, val stub: ProductStub?)

    @Immutable
    data class State(
        val isLookingUp: Boolean = false,
        val prompt: Prompt? = null,
        val labelRequest: LabelRequest? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The running lookup; a newer scan or a reset supersedes it. */
    private var job: Job? = null

    /**
     * Counts [run]s; each remembers its own number. A cancelled lookup can finish only after the
     * one that replaced it started (its read or request completes before it sees the cancel), so
     * it clears [State.isLookingUp] only while it is still the latest run.
     */
    private val runs = AtomicLong(0)

    /** Looks up a scanned or typed code; [onFood] opens the portion sheet. */
    fun start(code: String, onFood: (PortionFood) -> Unit) {
        job?.cancel()
        job = scope.launch { run(code, onFood) }
    }

    suspend fun run(code: String, onFood: (PortionFood) -> Unit) {
        val normalized = FoodSearchService.barcodeForms(code).firstOrNull() ?: code
        val token = runs.incrementAndGet()
        _state.update { it.copy(isLookingUp = true) }
        try {
            val saved = savedFood(normalized, foodDao)
            if (saved != null) {
                onFood(PortionFood.Item(saved))
                return
            }
            when (val result = service.lookup(normalized, locale().language)) {
                is BarcodeLookup.Found -> onFood(PortionFood.Candidate(result.candidate))
                is BarcodeLookup.Partial -> show(Prompt.Partial(result.stub, normalized))
                BarcodeLookup.NotFound -> show(Prompt.NotFound(normalized))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FoodSearchError.AlreadyInFlight) {
            // The same code is already being looked up; that lookup answers.
        } catch (error: Exception) {
            show(Prompt.Failed(FoodSearchError.lookupMessageRes(error), normalized))
        } finally {
            // A superseded lookup leaves the pill to the one that replaced it.
            if (runs.get() == token) _state.update { it.copy(isLookingUp = false) }
        }
    }

    /** Try again: lets the alert finish going away before the repeat can raise the next one. */
    fun retry(code: String, onFood: (PortionFood) -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(retryPauseMs)
            run(code, onFood)
        }
    }

    fun dismissPrompt() = _state.update { it.copy(prompt = null) }

    fun openLabel(code: String, stub: ProductStub?) =
        _state.update { it.copy(prompt = null, labelRequest = LabelRequest(code, stub)) }

    fun closeLabel() = _state.update { it.copy(labelRequest = null) }

    /**
     * Saves the label under the code's storage key and closes the form; the caller opens the
     * portion sheet. [brand] is the one read from a label photo, used when the stub has none.
     */
    suspend fun saveLabel(request: LabelRequest, name: String, values: LabelValues, brand: String? = null): FoodItemEntity {
        val item = ProductLabel.save(BarcodeKey.storageKey(request.code), name, request.stub, values, foodDao, brand)
        closeLabel()
        return item
    }

    /** The owning sheet closed: forget the prompt, the form and any lookup still running. */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = State()
    }

    private fun show(prompt: Prompt) = _state.update { it.copy(prompt = prompt) }

    companion object {
        const val RETRY_PAUSE_MS: Long = 400

        /**
         * The saved food for a scanned code: any of its EAN/UPC forms or its in-store item key,
         * picked by [BarcodeKey.preferred]'s order when several share it.
         */
        suspend fun savedFood(code: String, dao: FoodDao): FoodItemEntity? =
            dao.preferredByBarcode(BarcodeKey.localKeys(code))

        /**
         * Partial: the product ("Łosoś świeży · MOWI"), so the user sees what was found. A name
         * that already starts with the brand (Open Food Facts had no name, so it reads
         * "Pudliszki 200 g") does not repeat it.
         */
        fun partialTitle(stub: ProductStub): String {
            val brand = stub.brand?.trim()?.takeIf { it.isNotEmpty() && !startsWithWord(stub.name, it) }
            return listOfNotNull(stub.name, brand).joinToString(" · ")
        }

        /** [text] is [word], or begins with it followed by a non-letter ("Pudliszki 200 g", not "Mlekovita" for "Mleko"). */
        private fun startsWithWord(text: String, word: String): Boolean {
            val trimmed = text.trim()
            if (!trimmed.startsWith(word, ignoreCase = true)) return false
            return trimmed.length == word.length || !trimmed[word.length].isLetterOrDigit()
        }
    }
}

/**
 * The lookup's alert and the label sheet (`View.barcodeLookupFlow`). [onFood] opens the portion
 * sheet; [onQuickAdd] opens quick add with a name (null hides the action, e.g. when picking a food
 * for an AI estimate).
 *
 * `NtAlert` runs its own `onDismiss` before an action's `onClick`, so dismissing only clears the
 * prompt and each action sets what comes next.
 */
@Composable
fun BarcodeLookupPrompts(
    flow: BarcodeLookupFlow,
    state: BarcodeLookupFlow.State,
    onFood: (PortionFood) -> Unit,
    onQuickAdd: ((String) -> Unit)?,
) {
    state.prompt?.let { prompt ->
        val addFromLabel = stringResource(S.fuel_label_title)
        val quickAdd = stringResource(S.fuel_quickAdd)
        val actions = when (prompt) {
            is BarcodeLookupFlow.Prompt.Partial -> listOfNotNull(
                NtAlertAction(title = addFromLabel, onClick = { flow.openLabel(prompt.code, prompt.stub) }),
                onQuickAdd?.let { NtAlertAction(title = quickAdd, onClick = { it(prompt.stub.name) }) },
            )
            is BarcodeLookupFlow.Prompt.NotFound -> listOfNotNull(
                NtAlertAction(title = addFromLabel, onClick = { flow.openLabel(prompt.code, null) }),
                onQuickAdd?.let { NtAlertAction(title = quickAdd, onClick = { it("") }) },
            )
            is BarcodeLookupFlow.Prompt.Failed -> listOf(
                NtAlertAction(title = stringResource(S.fuel_search_retry), onClick = { flow.retry(prompt.code, onFood) }),
                NtAlertAction(title = addFromLabel, onClick = { flow.openLabel(prompt.code, null) }),
            )
        } + NtAlertAction(title = stringResource(S.common_cancel), role = NtAlertRole.Cancel)
        NtAlert(
            title = when (prompt) {
                is BarcodeLookupFlow.Prompt.Partial -> BarcodeLookupFlow.partialTitle(prompt.stub)
                is BarcodeLookupFlow.Prompt.NotFound -> stringResource(S.fuel_barcodeNotFound)
                is BarcodeLookupFlow.Prompt.Failed -> stringResource(prompt.messageRes)
            },
            message = if (prompt is BarcodeLookupFlow.Prompt.Partial) stringResource(S.fuel_barcodePartial) else null,
            actions = actions,
            onDismiss = flow::dismissPrompt,
        )
    }

    state.labelRequest?.let { request ->
        ProductLabelSheet(
            code = request.code,
            stub = request.stub,
            onDismiss = flow::closeLabel,
        ) { name, values, brand ->
            val item = flow.saveLabel(request, name, values, brand)
            onFood(PortionFood.Item(item))
        }
    }
}
