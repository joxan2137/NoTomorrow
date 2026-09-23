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
import app.notomorrow.model.TrainingGoal
import app.notomorrow.service.Days
import app.notomorrow.service.FoodSearchService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId

/**
 * `FuelModel` (`Features/Fuel/FuelModel.swift`) plus the two `@Query`s `FuelHomeView`
 * declares: the selected day's entries and the 120-day protein scan behind the streak chip,
 * and the History sheet's kcal-per-day read ([calendarKcal]).
 *
 * iOS refreshes imperatively (`.task`, `onChange(of: day)`, and after every sheet closes);
 * Room `Flow`s make all three of those automatic, so a move of [FuelDayNavigator] is the only
 * trigger left — it re-subscribes the day and streak queries through `flatMapLatest`.
 *
 * Also owns the day boundary through the navigator: a model showing today follows midnight
 * ([syncToday]), and a past day left in the background for more than 30 min returns to today
 * ([appWillEnterForeground]). Deletes and copies to today leave one [pendingUndo] for the toast
 * ([FuelEntryActions]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelViewModel(
    private val profileDao: ProfileDao,
    private val foodDao: FoodDao,
    private val mealDao: MealDao,
    private val foodSearch: FoodSearchService,
    /** `AppState.fuelTodayRequests`: every new value lands Fuel on today (the Dashboard's Fuel row). */
    todayRequests: Flow<Int> = emptyFlow(),
    /**
     * The device zone, read live: a time-zone change (`ACTION_TIMEZONE_CHANGED`) moves "today" and
     * the stored-day bounds on the next [syncToday]. The tests swap it.
     */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Epoch millis for the 30-minute snap-back; the tests move it. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /**
     * The zone the day queries are keyed in: the last one [today] read. The queries re-subscribe
     * when it changes, so the same calendar day still lists its entries under the new zone's
     * midnights.
     */
    private val keyZone = MutableStateFlow(zone())

    private val navigator = FuelDayNavigator(LocalDate.now(keyZone.value))

    /** The calendar day now, in the zone read now; a new zone re-keys the day queries. */
    private fun today(): LocalDate {
        val current = zone()
        keyZone.value = current
        return LocalDate.now(current)
    }

    /** The meal a scan was opened for and the portion sheet it opens; `FuelHomeView` keeps these in `@State`. */
    private val lookup = MutableStateFlow(FuelLookupState())

    private val targets = profileDao.observeProfile().map { profile ->
        profile?.let {
            FuelTargets(
                goals = FuelGoals(
                    kcal = it.calorieGoal.toDouble(),
                    protein = it.proteinGoalG.toDouble(),
                    carbs = it.carbsGoalG.toDouble(),
                    fat = it.fatGoalG.toDouble(),
                ),
                trainingGoal = it.goal,
            )
        } ?: FuelTargets()
    }

    /** Same day key as the History grid ([FuelCalendar.storedDayBounds]). */
    private val entries = combine(navigator.days.map { it.day }, keyZone) { a, b -> a to b }
        .distinctUntilChanged()
        .flatMapLatest { (day, dayZone) ->
            val bounds = FuelCalendar.storedDayBounds(day, dayZone)
            mealDao.observeDayRange(bounds.lower, bounds.upper)
        }

    /**
     * `computeStreak` scans 120 days back from *today*, not from the selected day, and the window
     * moves at midnight. Bucketed by [FuelCalendar.dayKey], so two stored midnights that fall on
     * one day are summed rather than overwritten.
     */
    private val proteinByDay = combine(navigator.days.map { it.today }, keyZone) { a, b -> a to b }
        .distinctUntilChanged()
        .flatMapLatest { (today, dayZone) ->
            val from = today.minusDays(FuelDerive.STREAK_WINDOW_DAYS)
            mealDao.observeProteinByDaySince(FuelCalendar.storedDayBounds(from, dayZone).lower).map { rows ->
                rows.groupingBy { FuelCalendar.dayKey(it.day, dayZone) }.fold(0.0) { sum, row -> sum + row.proteinG }
            }
        }

    val state: StateFlow<FuelUiState> =
        combine(navigator.days, targets, entries, proteinByDay, lookup) { days, targets, entries, protein, lookup ->
            build(days, targets, entries, protein, lookup)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FuelUiState(day = navigator.day, today = navigator.today),
        )

    /**
     * kcal per day (keyed by [FuelCalendar.dayKey]) over the History grid's 26 weeks: one
     * `SUM … GROUP BY day` read. Observed only while the sheet collects it, so nothing runs while
     * it is closed; Room re-emits after every edit.
     */
    val calendarKcal: StateFlow<Map<LocalDate, Double>> = combine(navigator.days.map { it.today }, keyZone) { a, b -> a to b }
        .distinctUntilChanged()
        .flatMapLatest { (today, dayZone) ->
            val start = FuelCalendar.layout(today).start
            mealDao.observeKcalByDaySince(FuelCalendar.storedDayBounds(start, dayZone).lower)
                .map { rows -> FuelCalendar.kcalByDay(rows, dayZone) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    init {
        // A request made before this model existed is already satisfied: it starts on today.
        viewModelScope.launch { todayRequests.drop(1).collect { goToday() } }
    }

    // MARK: - Day navigation

    /** The four user moves return whether the day changed; the screen ticks the haptic on it. */
    fun goPreviousDay(): Boolean = navigator.goPreviousDay()

    fun goNextDay(): Boolean = navigator.goNextDay(today())

    /** Jump straight to a day (the History sheet). Future days clamp to today. */
    fun goTo(date: LocalDate): Boolean = navigator.goTo(date, today())

    fun goToday(): Boolean = navigator.goToday(today())

    // MARK: - Day boundary

    /**
     * Midnight rollover: on appear, on a date/time/zone change and on every resume. Re-reads the
     * zone, so a zone change alone re-keys the day queries.
     */
    fun syncToday() = navigator.syncToday(today())

    /** The activity's `ON_STOP` — `UIApplication.didEnterBackgroundNotification`. */
    fun appDidEnterBackground() = navigator.appDidEnterBackground(clock())

    /** The activity's `ON_START` — `UIApplication.willEnterForegroundNotification`. */
    fun appWillEnterForeground() =
        navigator.appWillEnterForeground(clock(), today())

    // MARK: - Entries

    private val actions = FuelEntryActions(mealDao, foodDao, zone)

    /**
     * `FuelModel.pendingUndo`: the last delete or copy to today, for the toast. Kept out of
     * [state] so the toast's comings and goings never rebuild the day.
     */
    val pendingUndo: StateFlow<FuelUndo?> = actions.pending

    /** `FuelModel.delete(_:in:)` — leaves an undo. */
    fun delete(entryId: String) {
        viewModelScope.launch { actions.delete(entryId) }
    }

    /** "Log again today" (past days only): a copy of the entry in the same slot today. */
    fun logAgainToday(entryId: String) {
        viewModelScope.launch { actions.logAgainToday(entryId, System.currentTimeMillis()) }
    }

    /** "Copy to today" on a past day's slot header: [entryIds] are the slot's rows, in order. */
    fun copyToToday(entryIds: List<String>) {
        viewModelScope.launch { actions.copyToToday(entryIds, System.currentTimeMillis()) }
    }

    /** The toast's Undo. */
    fun undo() {
        viewModelScope.launch { actions.undo() }
    }

    /** The toast timed out (`FuelModel.expireUndo`). */
    fun expireUndo(undoId: String) = actions.expire(undoId)

    // MARK: - Barcode

    /** Scan → saved foods → Open Food Facts: the pill, the one alert and the label sheet. */
    val barcode = BarcodeLookupFlow(foodDao, foodSearch, viewModelScope)

    /**
     * The scanner handed over a code: remember the meal **and the day** it was opened for and look
     * it up; a hit opens the portion sheet ([openPortion]). The day is the one the scanner was
     * opened on ([FuelSheet.Barcode.day]), so the portion or quick-add sheet that follows logs
     * there even if a snap-back or midnight moves [FuelUiState.day] meanwhile.
     */
    fun lookupBarcode(code: String, meal: MealSlot, day: LocalDate) {
        lookup.update { it.copy(meal = meal, day = day) }
        barcode.start(code, ::openPortion)
    }

    /** A lookup hit, or a label just saved: size it in the portion sheet. */
    fun openPortion(food: PortionFood) {
        lookup.update { it.copy(portionFood = food) }
    }

    fun clearPortionFood() {
        lookup.update { it.copy(portionFood = null) }
    }

    // MARK: - Building

    private fun build(
        days: FuelDays,
        targets: FuelTargets,
        entries: List<MealEntryWithFood>,
        proteinByDay: Map<LocalDate, Double>,
        lookup: FuelLookupState,
    ): FuelUiState {
        val goals = targets.goals
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
        val today = days.today
        return FuelUiState(
            day = days.day,
            today = today,
            goals = goals,
            trainingGoal = targets.trainingGoal,
            slots = slots,
            kcalEaten = entries.sumOf { it.entry.kcal },
            proteinEaten = entries.sumOf { it.entry.proteinG },
            carbsEaten = entries.sumOf { it.entry.carbsG },
            fatEaten = entries.sumOf { it.entry.fatG },
            proteinStreak = FuelDerive.proteinStreak(proteinByDay, goals.protein, today),
            lookupMeal = lookup.meal,
            lookupDay = lookup.day ?: days.day,
            portionFood = lookup.portionFood,
        )
    }
}

/** The profile's goals plus its training direction (`FuelModel.goals` / `trainingGoal`). */
@Immutable
private data class FuelTargets(
    val goals: FuelGoals = FuelGoals.Fallback,
    val trainingGoal: TrainingGoal = TrainingGoal.BuildMuscle,
)

/**
 * What a barcode round-trip opens, held apart so the day/goal flows never re-emit for it. The
 * lookup itself (pill, alert, label form) is [FuelViewModel.barcode].
 */
@Immutable
private data class FuelLookupState(
    /** `@State private var lookupMeal: MealSlot = .suggested()` — seeded once, never re-read. */
    val meal: MealSlot = suggestedMealSlot(),
    /** The day the scanner was opened on; `null` before the first scan. */
    val day: LocalDate? = null,
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
    /** The calendar day this state treats as today ([FuelDayNavigator.today]). */
    val today: LocalDate = LocalDate.now(),
    val goals: FuelGoals = FuelGoals.Fallback,
    /** `FuelModel.trainingGoal` — how the History grid scores a day. */
    val trainingGoal: TrainingGoal = TrainingGoal.BuildMuscle,
    val slots: List<FuelSlotUi> = MealSlotOrdered.map { FuelSlotUi(it, emptyList(), 0.0) },
    val kcalEaten: Double = 0.0,
    val proteinEaten: Double = 0.0,
    val carbsEaten: Double = 0.0,
    val fatEaten: Double = 0.0,
    val proteinStreak: Int = 0,
    val lookupMeal: MealSlot = suggestedMealSlot(),
    /**
     * The day a barcode round-trip logs to — captured when the scanner opened, not [day] at the
     * time the portion sheet saves (the selected day before any scan).
     */
    val lookupDay: LocalDate = day,
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
     * (not the pack's serving), and the day and meal slot become editable. The day starts at the
     * one the entry is listed under ([FuelCalendar.dayKey]).
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
                day = FuelCalendar.dayKey(row.day, zone),
                today = LocalDate.now(zone),
                isEditing = true,
            ).withMacros(food)
        }
    }

    fun setSlot(slot: MealSlot) {
        _state.value = _state.value.copy(slot = slot)
    }

    /** `EntryDayStepper`: past days and today only. */
    fun setDay(day: LocalDate) {
        val current = _state.value
        _state.value = current.copy(day = minOf(day, current.today))
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
     * The edit-mode button ([FuelDerive.editedPortion]): the macros are re-derived from the food
     * only when the grams really changed, the day and slot move, the row keeps its `id` and
     * `loggedAt`, and the food's usage stats stay where the original insert left them.
     */
    fun save(onSaved: () -> Unit) {
        val entry = entry ?: return
        val item = (food as? PortionFood.Item)?.item ?: return
        val snapshot = _state.value
        if (snapshot.grams <= 0) return
        viewModelScope.launch {
            mealDao.update(
                FuelDerive.editedPortion(entry, item, snapshot.grams, snapshot.slot, snapshot.day, zone),
            )
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
    /** Edit mode only: the day the entry is listed under, movable with [EntryDayStepper]. */
    val day: LocalDate = LocalDate.now(),
    /** The stepper's upper bound, read when the entry was bound. */
    val today: LocalDate = LocalDate.now(),
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
