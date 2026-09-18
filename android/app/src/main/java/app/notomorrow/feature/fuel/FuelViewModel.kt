package app.notomorrow.feature.fuel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.MealDao
import app.notomorrow.util.Parsing
import app.notomorrow.util.LocaleProvider
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.relation.MealEntryWithFood
import app.notomorrow.model.MealSlot
import app.notomorrow.service.Days
import app.notomorrow.service.FoodSearchService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId

/**
 * `FuelModel` (`Features/Fuel/FuelModel.swift`) plus the two `@Query`s `FuelHomeView`
 * declares: the selected day's entries and the 120-day protein scan behind the streak chip.
 *
 * iOS refreshes imperatively (`.task`, `onChange(of: day)`, and after every sheet closes);
 * Room `Flow`s make all three of those automatic, so [setDay] is the only trigger left —
 * it re-subscribes the day query through `flatMapLatest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelViewModel(
    private val profileDao: ProfileDao,
    private val foodDao: FoodDao,
    private val mealDao: MealDao,
    private val foodSearch: FoodSearchService,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val day = MutableStateFlow(LocalDate.now(zone))

    /** Barcode lookup + the sheet it opens; `FuelHomeView` keeps these in `@State`. */
    private val lookup = MutableStateFlow(FuelLookupState())

    private val goals = profileDao.observeProfile().map { profile ->
        profile?.let {
            FuelGoals(
                kcal = it.calorieGoal.toDouble(),
                protein = it.proteinGoalG.toDouble(),
                carbs = it.carbsGoalG.toDouble(),
                fat = it.fatGoalG.toDouble(),
            )
        } ?: FuelGoals.Fallback
    }

    private val entries = day.flatMapLatest { mealDao.observeDay(Days.millis(it, zone)) }

    /** `computeStreak` scans 120 days back from *today*, not from the selected day. */
    private val proteinByDay = mealDao
        .observeProteinByDaySince(
            Days.millis(LocalDate.now(zone).minusDays(FuelDerive.STREAK_WINDOW_DAYS), zone),
        )
        .map { rows -> rows.associate { Days.date(it.day, zone) to it.proteinG } }

    val state: StateFlow<FuelUiState> =
        combine(day, goals, entries, proteinByDay, lookup) { day, goals, entries, protein, lookup ->
            build(day, goals, entries, protein, lookup)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FuelUiState(day = day.value, today = LocalDate.now(zone)),
        )

    // MARK: - Day navigation

    fun goPreviousDay() {
        day.value = day.value.minusDays(1)
    }

    fun goNextDay() {
        val today = LocalDate.now(zone)
        if (!FuelDerive.canGoForward(day.value, today)) return
        day.value = day.value.plusDays(1)
    }

    /** Re-key on a date change (midnight, or a resume on a new day). */
    fun refreshToday() {
        val today = LocalDate.now(zone)
        if (day.value.isAfter(today)) day.value = today
    }

    // MARK: - Entries

    /** `FuelModel.delete(_:in:)`. */
    fun delete(entryId: String) {
        viewModelScope.launch { mealDao.deleteById(entryId) }
    }

    // MARK: - Barcode

    /**
     * `FuelHomeView.lookup(barcode:)`: show the pill, ask Open Food Facts, then either open
     * the portion sheet on the hit or raise the "not found" alert.
     */
    fun lookupBarcode(code: String, meal: MealSlot) {
        lookup.value = lookup.value.copy(isLookingUp = true, meal = meal)
        viewModelScope.launch {
            try {
                val forms = FoodSearchService.barcodeForms(code)
                for (form in forms) {
                    val saved = foodDao.byBarcode(form)
                    if (saved != null) { lookup.value = FuelLookupState(meal = meal, portionFood = PortionFood.Item(saved)); return@launch }
                }
                val found = foodSearch.lookup(code)
                lookup.value = if (found != null) FuelLookupState(meal = meal, portionFood = PortionFood.Candidate(found))
                    else FuelLookupState(meal = meal, notFound = true, missingBarcode = forms.firstOrNull() ?: code)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { lookup.value = FuelLookupState(meal = meal, networkError = true) }
        }
    }

    fun dismissLookupError() { lookup.value = lookup.value.copy(networkError = false) }

    suspend fun saveLabel(name: String, values: List<Double>) {
        val code = lookup.value.missingBarcode
        val item = app.notomorrow.data.entity.FoodItemEntity(id = "label:$code", name = name, source = app.notomorrow.model.FoodSource.Custom,
            barcode = code, kcalPer100 = values[0], proteinPer100 = values[1], carbsPer100 = values[2], fatPer100 = values[3])
        foodDao.upsert(item)
        lookup.value = lookup.value.copy(notFound = false, portionFood = PortionFood.Item(item))
    }

    fun dismissNotFound() {
        lookup.value = lookup.value.copy(notFound = false)
    }

    fun clearPortionFood() {
        lookup.value = lookup.value.copy(portionFood = null)
    }

    // MARK: - Building

    private fun build(
        day: LocalDate,
        goals: FuelGoals,
        entries: List<MealEntryWithFood>,
        proteinByDay: Map<LocalDate, Double>,
        lookup: FuelLookupState,
    ): FuelUiState {
        val slots = MealSlotOrdered.map { slot ->
            val rows = entries
                .filter { it.entry.slot == slot }
                .map {
                    FuelEntryUi(
                        id = it.entry.id,
                        name = it.displayName,
                        grams = it.entry.grams,
                        kcal = it.entry.kcal,
                        isAIEstimate = it.entry.isAIEstimate,
                        hasFood = it.entry.foodId != null,
                    )
                }
            FuelSlotUi(slot = slot, entries = rows, kcal = rows.sumOf { it.kcal })
        }
        val today = LocalDate.now(zone)
        return FuelUiState(
            day = day,
            today = today,
            goals = goals,
            slots = slots,
            kcalEaten = entries.sumOf { it.entry.kcal },
            proteinEaten = entries.sumOf { it.entry.proteinG },
            carbsEaten = entries.sumOf { it.entry.carbsG },
            fatEaten = entries.sumOf { it.entry.fatG },
            proteinStreak = FuelDerive.proteinStreak(proteinByDay, goals.protein, today),
            isLookingUpBarcode = lookup.isLookingUp,
            barcodeNotFound = lookup.notFound,
            lookupError = lookup.networkError,
            missingBarcode = lookup.missingBarcode,
            lookupMeal = lookup.meal,
            portionFood = lookup.portionFood,
        )
    }
}

/** The barcode round-trip, held apart so the day/goal flows never re-emit for it. */
@Immutable
private data class FuelLookupState(
    val isLookingUp: Boolean = false,
    val notFound: Boolean = false,
    val networkError: Boolean = false,
    val missingBarcode: String = "",
    /** `@State private var lookupMeal: MealSlot = .suggested()` — seeded once, never re-read. */
    val meal: MealSlot = suggestedMealSlot(),
    val portionFood: PortionFood? = null,
)

/** One logged entry as the list renders it (`FuelEntryRow`). */
@Immutable
data class FuelEntryUi(
    val id: String,
    val name: String,
    val grams: Double,
    val kcal: Double,
    val isAIEstimate: Boolean,
    /** A food-backed row edits through [PortionEditSheet]; a custom one through [QuickAddEditSheet]. */
    val hasFood: Boolean,
)

/** One meal section (`FuelMealRows`). */
@Immutable
data class FuelSlotUi(
    val slot: MealSlot,
    val entries: List<FuelEntryUi>,
    val kcal: Double,
)

@Immutable
data class FuelUiState(
    val day: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val goals: FuelGoals = FuelGoals.Fallback,
    val slots: List<FuelSlotUi> = MealSlotOrdered.map { FuelSlotUi(it, emptyList(), 0.0) },
    val kcalEaten: Double = 0.0,
    val proteinEaten: Double = 0.0,
    val carbsEaten: Double = 0.0,
    val fatEaten: Double = 0.0,
    val proteinStreak: Int = 0,
    val isLookingUpBarcode: Boolean = false,
    val barcodeNotFound: Boolean = false,
    val lookupError: Boolean = false,
    val missingBarcode: String = "",
    val lookupMeal: MealSlot = suggestedMealSlot(),
    val portionFood: PortionFood? = null,
) {
    val canGoForward: Boolean get() = FuelDerive.canGoForward(day, today)
    val kcalLeft: Double get() = FuelDerive.kcalLeft(kcalEaten, goals.kcal)
    val ringProgress: Double get() = FuelDerive.ringProgress(kcalEaten, goals.kcal)
    val proteinRemaining: Double get() = FuelDerive.proteinRemaining(proteinEaten, goals.protein)

    /** The ember "protein to go" hint shows only under the **first** empty slot. */
    val firstEmptySlot: MealSlot? get() = slots.firstOrNull { it.entries.isEmpty() }?.slot
}

// ─────────────────────────────────────────────────────────────────────────────
// Portion sheet
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The sheet's write side: grams ⇄ text, the derived macros, and the insert.
 *
 * `PortionFood.resolveItem(in:)` (`FuelSupport.swift:115`) is
 * [FoodSearchService.cacheOnTap] for a candidate and a usage bump for a food already in
 * the library — the service owns that logic, this only calls it.
 */
class PortionViewModel(
    private val foodDao: FoodDao,
    private val mealDao: MealDao,
    private val foodSearch: FoodSearchService,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: () -> Locale = { LocaleProvider.current() },
) : ViewModel() {

    /**
     * The food being sized. It arrives after construction — the sheet is one view model
     * that different foods flow through, so the initial grams are set in [bind], the
     * analogue of `PortionSheet.init` seeding `_grams` from `servingSizeG ?? 100`.
     */
    private var food: PortionFood? = null

    /** The row being re-sized in edit mode; `null` while the sheet is adding. */
    private var entry: MealEntryEntity? = null

    private val _state = MutableStateFlow(PortionUiState())

    val state: StateFlow<PortionUiState> = _state.asStateFlow()

    fun bind(food: PortionFood) {
        if (this.food?.id == food.id) return
        this.food = food
        val start = food.servingSizeG ?: 100.0
        _state.value = PortionUiState(
            food = food,
            grams = start,
            gramsText = FuelDerive.portionText(start, locale()),
        ).withMacros(food)
    }

    /**
     * The same sheet re-opened on a logged row: the portion starts at the entry's own grams
     * (not the pack's serving) and the meal slot becomes editable.
     */
    fun bindEntry(entryId: String) {
        if (entry?.id == entryId) return
        _state.value = PortionUiState(isEditing = true)
        viewModelScope.launch {
            val row = mealDao.byId(entryId) ?: return@launch
            val item = row.foodId?.let { foodDao.byId(it) } ?: return@launch
            val food = PortionFood.Item(item)
            this@PortionViewModel.food = food
            entry = row
            _state.value = PortionUiState(
                food = food,
                grams = row.grams,
                gramsText = FuelDerive.portionText(row.grams, locale()),
                slot = row.slot,
                isEditing = true,
            ).withMacros(food)
        }
    }

    fun setSlot(slot: MealSlot) {
        _state.value = _state.value.copy(slot = slot)
    }

    /** The sheet closed: the next presentation starts from its own food's serving size. */
    fun unbind() {
        food = null
        entry = null
        _state.value = PortionUiState()
    }

    /** `.onChange(of: gramsText)` — a parseable non-negative number moves the portion. */
    fun setGramsText(text: String) {
        val food = food ?: return
        val value = Parsing.nonNegative(text)
        _state.value = _state.value
            .copy(gramsText = text, grams = value ?: _state.value.grams)
            .withMacros(food)
    }

    /** The −/+ buttons; `set(_:)` clamps at the 5 g minimum and rewrites the field. */
    fun step(delta: Double) = setGrams(_state.value.grams + delta)

    fun setGrams(value: Double) {
        val food = food ?: return
        val clamped = FuelDerive.clampPortion(value)
        _state.value = _state.value
            .copy(grams = clamped, gramsText = FuelDerive.portionText(clamped, locale()))
            .withMacros(food)
    }

    /** `PortionSheet.add()` — resolve the food row, insert the entry, hand control back. */
    fun add(meal: MealSlot, day: LocalDate, onAdded: () -> Unit) {
        val food = food ?: return
        val snapshot = _state.value
        if (snapshot.grams <= 0) return
        viewModelScope.launch {
            val foodId = resolveFoodId(food)
            mealDao.insert(
                MealEntryEntity(
                    id = UUID.randomUUID().toString(),
                    day = Days.millis(day, zone),
                    slot = meal,
                    foodId = foodId,
                    grams = snapshot.grams,
                    kcal = snapshot.kcal,
                    proteinG = snapshot.protein,
                    carbsG = snapshot.carbs,
                    fatG = snapshot.fat,
                ),
            )
            onAdded()
        }
    }

    /**
     * The edit-mode button: the macros are re-derived from the food, the row keeps its `id` and
     * `loggedAt`, and the food's usage stats stay where the original insert left them.
     */
    fun save(onSaved: () -> Unit) {
        val entry = entry ?: return
        val item = (food as? PortionFood.Item)?.item ?: return
        val snapshot = _state.value
        if (snapshot.grams <= 0) return
        viewModelScope.launch {
            mealDao.update(FuelDerive.resized(entry, item, snapshot.grams, snapshot.slot))
            onSaved()
        }
    }

    private suspend fun resolveFoodId(food: PortionFood): String = when (food) {
        is PortionFood.Candidate -> foodSearch.cacheOnTap(food.candidate, foodDao).id
        is PortionFood.Item -> {
            foodDao.bumpUsage(food.item.id, System.currentTimeMillis())
            food.item.id
        }
    }
}

data class PortionUiState(
    /** Add mode has it from the call site; edit mode only once [PortionViewModel.bindEntry] lands. */
    val food: PortionFood? = null,
    val grams: Double = 100.0,
    val gramsText: String = "100",
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val slot: MealSlot = suggestedMealSlot(),
    val isEditing: Boolean = false,
)

/** `factor = grams / 100` applied to the four per-100 g figures. */
private fun PortionUiState.withMacros(food: PortionFood): PortionUiState {
    val factor = grams / 100.0
    return copy(
        kcal = food.kcalPer100 * factor,
        protein = food.proteinPer100 * factor,
        carbs = food.carbsPer100 * factor,
        fat = food.fatPer100 * factor,
    )
}
