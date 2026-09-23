package app.notomorrow.feature.fuel

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.model.FoodCandidate
import app.notomorrow.service.FoodMatch
import app.notomorrow.service.FoodSearchError
import app.notomorrow.service.FoodSearchService
import app.notomorrow.util.LocaleProvider
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
 * `FoodSearchModel` (`Features/Fuel/FoodSearchModel.swift`) plus the saved-food `@Query` that
 * `FoodSearchView` declares, and the sheet's own barcode lookup ([barcode]).
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

    /** Scan → saved foods → Open Food Facts, from the field's barcode tile. */
    val barcode = BarcodeLookupFlow(foodDao, service, viewModelScope)

    private var searchJob: Job? = null
    private var lastQuery: String = ""

    /** The whole saved-food library, most recently used first (never-used labels last). */
    private val library = foodDao.observeLibrary()

    val state: StateFlow<FoodSearchUiState> =
        combine(query, phase, library) { query, phase, library ->
            val trimmed = query.trim()
            FoodSearchUiState(
                query = query,
                phase = phase,
                recent = savedFoods(library, trimmed),
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
        } catch (_: FoodSearchError.AlreadyInFlight) {
            delay(FuelDerive.IN_FLIGHT_RETRY_MS)
            run(q)
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            phase.value = FoodSearchPhase.Error(FoodSearchError.messageRes(error))
        }
    }

    /** The sheet closed: drop the query, the phase and the lookup, as a fresh `@State` model would. */
    fun reset() {
        searchJob?.cancel()
        searchJob = null
        lastQuery = ""
        query.value = ""
        phase.value = FoodSearchPhase.Idle
        barcode.reset()
    }

    companion object {
        /** `Array(matches.prefix(10))` */
        const val RECENT_SHOWN = 10

        /**
         * `FoodSearchView.recent`. No query: the 10 most recently used foods. A query: saved foods
         * whose name or brand contains every typed word, ignoring case and Polish diacritics, so
         * they stay reachable offline and when Open Food Facts is down.
         */
        fun savedFoods(library: List<FoodItemEntity>, query: String): List<FoodItemEntity> {
            val q = query.trim()
            val matches = if (q.isEmpty()) {
                library.asSequence().filter { it.lastUsedAt != null }
            } else {
                library.asSequence().filter { FoodMatch.matches(q, it.name, it.brand) }
            }
            return matches.take(RECENT_SHOWN).toList()
        }
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
    /** The saved foods on show: recent ones, or the ones matching the query. */
    val recent: List<FoodItemEntity> = emptyList(),
    val canSearch: Boolean = false,
) {
    /** The saved-food section reads "Your foods" while a query filters it, "Recent" otherwise. */
    val hasQuery: Boolean get() = query.isNotBlank()
}
