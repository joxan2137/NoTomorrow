package app.notomorrow.feature.fuel

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.notomorrow.data.prefs.AiConsentTarget
import app.notomorrow.net.dto.LabelReading
import app.notomorrow.service.AIEstimateError
import app.notomorrow.service.AIEstimateService
import app.notomorrow.service.AIUpload
import app.notomorrow.util.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Photograph the label" in [ProductLabelSheet] — the port of `Features/Fuel/LabelPhotoReader.swift`:
 * photo (1600 px, the small print needs the pixels) → the same provider and one-time consent as the
 * AI photo estimate → the label read → the per-100 g fields, which the user checks before saving.
 * Nothing is saved here; a failure leaves the form as it was, still editable by hand.
 *
 * Plain state holder on the sheet's scope (iOS keeps it in the sheet's `@State`), so it goes away
 * with the sheet. Its collaborators are functions, so it is unit tested without Android.
 */
class LabelPhotoReader(
    private val scope: CoroutineScope,
    /** Where the photo would go (`AIEstimateProviders.upload`). */
    private val upload: suspend () -> AIUpload,
    /** The service for that upload (`AIEstimateProviders.service`). */
    private val service: (AIUpload) -> AIEstimateService,
    private val consentOnce: suspend (AiConsentTarget) -> Boolean,
    private val recordConsent: suspend (AiConsentTarget) -> Unit,
    /** `"pl"` / `"en"`, as the estimate sends it. */
    private val language: () -> String,
) {

    @Immutable
    sealed interface Status {
        data object Idle : Status

        data object Reading : Status

        /** Values filled in; [needsReview] when they do not add up or the model was unsure. */
        data class Filled(val needsReview: Boolean) : Status

        /** The photo had no usable nutrition table (a printed name may still have been filled in). */
        data object Unreadable : Status

        data class Failed(@param:StringRes val messageRes: Int) : Status
    }

    /** [consent] is the upload the first-use alert asks about; null while no alert shows. */
    @Immutable
    data class State(
        val status: Status = Status.Idle,
        val consent: AIUpload? = null,
    ) {
        val isReading: Boolean get() = status == Status.Reading
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pending: ByteArray? = null
    private var pendingUpload: AIUpload? = null
    private var apply: ((LabelReading) -> Unit)? = null
    private var job: Job? = null

    /** Armed by [dismissConsent]; a button handler on the same main-loop turn cancels it. */
    private var consentFallback: Job? = null

    /**
     * Prepares the photo ([photo] downscales it, off the main thread), asks for consent if this
     * provider has not had it yet, then reads. [apply] receives the reading (legible or not) to fill
     * the form.
     */
    fun read(photo: suspend () -> ByteArray?, apply: (LabelReading) -> Unit) {
        if (_state.value.isReading) return
        this.apply = apply
        _state.value = State(Status.Reading)
        job?.cancel()
        job = scope.launch { photoReady(photo()) }
    }

    private suspend fun photoReady(data: ByteArray?) {
        if (data == null || data.isEmpty()) {
            _state.value = State(Status.Failed(S.fuel_label_unreadable))
            return
        }
        pending = data
        val upload = upload()
        val target = upload.consentTarget
        if (target != null && !consentOnce(target)) {
            pendingUpload = upload
            _state.value = State(Status.Idle, consent = upload)
        } else {
            send(upload)
        }
    }

    /**
     * The consent alert closed. `NtAlert` calls this **before** the tapped button's own handler,
     * so it only hides the alert; an outside tap or the back gesture (no button handler follows on
     * this main-loop turn) is then treated as Cancel.
     */
    fun dismissConsent() {
        _state.update { it.copy(consent = null) }
        consentFallback?.cancel()
        consentFallback = scope.launch {
            yield()
            declineConsent()
        }
    }

    fun acceptConsent() {
        consentFallback?.cancel()
        consentFallback = null
        val upload = pendingUpload ?: return
        pendingUpload = null
        _state.update { it.copy(consent = null) }
        upload.consentTarget?.let { target -> scope.launch { recordConsent(target) } }
        job?.cancel()
        job = scope.launch { send(upload) }
    }

    fun declineConsent() {
        consentFallback?.cancel()
        consentFallback = null
        pending = null
        pendingUpload = null
        _state.value = State(Status.Idle)
    }

    /** The sheet went away: the request is dropped (iOS `onDisappear`). */
    fun cancel() {
        job?.cancel()
        job = null
        consentFallback?.cancel()
        consentFallback = null
    }

    private suspend fun send(upload: AIUpload) {
        val jpeg = pending ?: return
        pending = null
        _state.value = State(Status.Reading)
        try {
            val reading = service(upload).readLabel(jpeg, language())
            finish(reading)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = State(Status.Failed((error as? AIEstimateError)?.labelMessageRes ?: S.fuel_label_unreadable))
        }
    }

    private fun finish(reading: LabelReading) {
        apply?.invoke(reading)
        val status = if (reading.legible && reading.per100 != null) {
            Status.Filled(reading.needsReview)
        } else {
            Status.Unreadable
        }
        _state.value = State(status)
    }
}

/**
 * What a label reading puts into the form (`LabelFill`). A legible reading fills kcal, protein,
 * carbs and fat, plus fiber and the serving when printed; any reading fills the name, but only into
 * an empty field. `null` leaves a field as it is.
 */
@Immutable
data class LabelFill(
    val name: String? = null,
    val brand: String? = null,
    val kcal: String? = null,
    val protein: String? = null,
    val carbs: String? = null,
    val fat: String? = null,
    val fiber: String? = null,
    val serving: String? = null,
) {
    companion object {
        fun from(reading: LabelReading, currentName: String, locale: Locale): LabelFill {
            val printedName = reading.name.trim()
            val name = if (currentName.trim().isEmpty() && printedName.isNotEmpty()) printedName else null
            val brand = reading.brand.trim().ifEmpty { null }
            val per100 = reading.per100
            if (!reading.legible || per100 == null) return LabelFill(name = name, brand = brand)
            val text: (Double) -> String = { FuelDerive.portionText(it, locale) }
            return LabelFill(
                name = name,
                brand = brand,
                kcal = text(per100.kcal),
                protein = text(per100.protein),
                carbs = text(per100.carbs),
                fat = text(per100.fat),
                fiber = per100.fiber?.let(text),
                serving = reading.servingSizeG?.let(text),
            )
        }
    }
}
