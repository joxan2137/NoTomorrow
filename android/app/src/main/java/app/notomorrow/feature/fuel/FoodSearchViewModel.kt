package app.notomorrow.feature.fuel

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.model.FoodCandidate
import app.notomorrow.service.FoodSearchError
import app.notomorrow.service.FoodSearchService
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.S
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * `FoodSearchModel` (`Features/Fuel/FoodSearchModel.swift`) plus the `@Query` for the
 * recent list that `FoodSearchView` declares.
 *
 * Debounce and the ≥2-character minimum live here, not in [FoodSearchService] — exactly
 * as on iOS. `alreadyInFlight` means an identical query is still running from a previous
 * keystroke, so the model waits 700 ms and re-reads the (by then populated) cache.
 */
class FoodSearchViewModel(
    private val foodDao: FoodDao,
    private val service: FoodSearchService,
    private val locale: () -> Locale = { LocaleProvider.current() },
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val phase = MutableStateFlow<FoodSearchPhase>(FoodSearchPhase.Idle)
    private val lookingUp = MutableStateFlow(false)

    private var searchJob: Job? = null
    private var lastQuery: String = ""

    /**
     * iOS sorts every used food by `lastUsedAt`, filters by the query and *then* keeps 10,
     * so the DAO has to hand over more than ten rows.
     */
    private val recent = foodDao.observeRecent(RECENT_POOL)

    val state: StateFlow<FoodSearchUiState> =
        combine(query, phase, lookingUp, recent) { query, phase, lookingUp, recent ->
            val trimmed = query.trim()
            val needle = trimmed.lowercase(locale())
            val filtered = if (needle.isEmpty()) {
                recent
            } else {
                recent.filter { it.name.lowercase(locale()).contains(needle) }
            }
            FoodSearchUiState(
                query = query,
                phase = phase,
                recent = filtered.take(RECENT_SHOWN),
                isLookingUpBarcode = lookingUp,
                canSearch = trimmed.length >= FoodSearchService.MINIMUM_QUERY_LENGTH,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodSearchUiState())

    /** `FoodSearchView.onChange(of: model.query)` -> `queryChanged()`. */
    fun setQuery(text: String) {
        query.value = text
        queryChanged()
    }

    private fun queryChanged() {
        searchJob?.cancel()
        val q = query.value.trim()
        if (q.length < FoodSearchService.MINIMUM_QUERY_LENGTH) {
            phase.value = FoodSearchPhase.Idle
            lastQuery = ""
            return
        }
        if (q == lastQuery && phase.value != FoodSearchPhase.Idle) return
        searchJob = viewModelScope.launch {
            delay(FuelDerive.SEARCH_DEBOUNCE_MS)
            run(q)
        }
    }

    /** `.onSubmit` on the search field and the "Try again" ghost button. */
    fun retry() {
        searchJob?.cancel()
        val q = query.value.trim()
        if (q.length < FoodSearchService.MINIMUM_QUERY_LENGTH) return
        searchJob = viewModelScope.launch { run(q) }
    }

    private suspend fun run(q: String) {
        phase.value = FoodSearchPhase.Loading
        lastQuery = q
        try {
            val hits = service.search(q, locale().language)
            phase.value =
                if (hits.isEmpty()) FoodSearchPhase.Empty else FoodSearchPhase.Results(hits)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FoodSearchError) {
            if (error is FoodSearchError.AlreadyInFlight) {
                delay(FuelDerive.IN_FLIGHT_RETRY_MS)
                run(q)
            } else {
                phase.value = FoodSearchPhase.Error(error.messageRes)
            }
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            phase.value = FoodSearchPhase.Error(S.fuel_search_error_network)
        }
    }

    /** The sheet closed: drop the query and the phase, as a fresh `@State` model would. */
    fun reset() {
        searchJob?.cancel()
        searchJob = null
        lastQuery = ""
        query.value = ""
        phase.value = FoodSearchPhase.Idle
        lookingUp.value = false
    }

    /** `FoodSearchModel.lookup(barcode:)` — nil when neither EAN/UPC form is known. */
    suspend fun lookup(barcode: String): FoodCandidate? {
        lookingUp.value = true
        return try {
            service.lookup(barcode)
        } finally {
            lookingUp.value = false
        }
    }

    suspend fun savedBarcode(barcode: String): FoodItemEntity? {
        for (code in FoodSearchService.barcodeForms(barcode)) foodDao.byBarcode(code)?.let { return it }
        return null
    }

    suspend fun saveLabel(barcode: String, name: String, values: List<Double>): FoodItemEntity {
        val item = FoodItemEntity(id = "label:$barcode", name = name, source = app.notomorrow.model.FoodSource.Custom,
            barcode = barcode, kcalPer100 = values[0], proteinPer100 = values[1], carbsPer100 = values[2], fatPer100 = values[3])
        foodDao.upsert(item)
        return item
    }

    private companion object {
        /** Rows read from Room before the query filter; iOS filters the whole table. */
        const val RECENT_POOL = 200

        /** `Array(filtered.prefix(10))` */
        const val RECENT_SHOWN = 10
    }
}

/** `FoodSearchModel.Phase`. */
@Immutable
sealed interface FoodSearchPhase {
    data object Idle : FoodSearchPhase
    data object Loading : FoodSearchPhase
    data class Results(val hits: List<FoodCandidate>) : FoodSearchPhase
    data object Empty : FoodSearchPhase

    /**
     * iOS shows `error.localizedDescription`; every failure this service can raise is a
     * `FoodSearchError` with a catalog message, so the state carries the resource id.
     */
    data class Error(@param:StringRes val messageRes: Int) : FoodSearchPhase
}

@Immutable
data class FoodSearchUiState(
    val query: String = "",
    val phase: FoodSearchPhase = FoodSearchPhase.Idle,
    val recent: List<FoodItemEntity> = emptyList(),
    val isLookingUpBarcode: Boolean = false,
    val canSearch: Boolean = false,
)
