package app.notomorrow.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.app.AppState
import app.notomorrow.data.dao.BodyWeightDao
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.BodyWeightEntryEntity
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.model.AppTab
import app.notomorrow.model.BodyWeightSource
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.Days
import app.notomorrow.service.HealthService
import app.notomorrow.service.localizedName
import app.notomorrow.util.LocaleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The Progress tab's state and its one write — the port of `ProgressModel`
 * (`Features/Progress/ProgressModel.swift`) plus the `@State` `ProgressHomeView` owns.
 *
 * iOS calls `model.reload(context)` on appear and after the weight sheet saves; Room Flows
 * make the second unnecessary. The first still matters for the *relative* phrasing ("PR
 * today", the 4-week baseline, the current ISO week), which is derived from `Date.now` and
 * would otherwise go stale in a session left open across midnight — hence [refresh], which
 * the screen calls on every `ON_START`.
 */
class ProgressHomeViewModel(
    private val profileDao: ProfileDao,
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
    private val bodyWeightDao: BodyWeightDao,
    private val health: HealthService,
    private val appState: AppState,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: () -> Locale = { LocaleProvider.current() },
) : ViewModel() {

    private val tab = MutableStateFlow(ProgressTab.Lifts)
    private val showsLogWeight = MutableStateFlow(false)

    /** `.onAppear` — the day every relative phrase is measured against. */
    private val today = MutableStateFlow(LocalDate.now(zone))

    private val store = combine(
        profileDao.observeProfile(),
        workoutDao.observeCompletedSets(),
        exerciseDao.observeAllByName(),
        bodyWeightDao.observeAll(),
    ) { profile, sets, exercises, weights ->
        Store(
            unit = profile?.units ?: WeightUnit.Kg,
            sets = sets,
            exercises = exercises,
            body = weights.map { BodyEntry(Days.date(it.day, zone), it.kg, it.source) },
        )
    }

    /**
     * `ProgressModel.reload` — a full pass over every completed set plus the exercise-name
     * map, so it runs off the main thread: during a workout the set writes retrigger it on
     * every completed set. Only the two UI flags are combined downstream, on the caller's
     * dispatcher, so a tab switch still lands in the same frame.
     */
    private val derived = combine(store, today) { data, day -> derive(data, day) }
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<ProgressHomeUiState> =
        combine(derived, tab, showsLogWeight) { data, selectedTab, sheet ->
            data.copy(tab = selectedTab, showsLogWeight = sheet)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProgressHomeUiState(),
        )

    // MARK: - Intent

    fun select(tab: ProgressTab) {
        this.tab.value = tab
    }

    fun showLogWeight() {
        showsLogWeight.value = true
    }

    fun dismissLogWeight() {
        showsLogWeight.value = false
    }

    /** `ProgressHomeView.onAppear` — re-read "now" so yesterday's phrasing never sticks. */
    fun refresh() {
        val day = LocalDate.now(zone)
        if (today.value != day) today.value = day
    }

    /** `ProgressHomeView.emptyState` — "Start workout" jumps to the Train tab. */
    fun selectTrainTab() {
        appState.select(AppTab.Train)
    }

    /**
     * `LogWeightSheet.save()` — today's row is an upsert (the `day` primary key), the
     * profile keeps the same number, and Health gets a copy when it is available and
     * authorized.
     */
    fun logWeight(kg: Double) {
        viewModelScope.launch {
            val today = Days.millis(LocalDate.now(zone), zone)
            bodyWeightDao.upsert(
                BodyWeightEntryEntity(day = today, kg = kg, source = BodyWeightSource.Manual),
            )
            profileDao.profile()?.let { profileDao.upsert(it.copy(bodyWeightKg = kg)) }
            if (health.isAvailable && health.isAuthorized.value) {
                runCatching { health.saveBodyWeight(kg) }
            }
            showsLogWeight.value = false
        }
    }

    // MARK: - Derivation

    private fun derive(data: Store, today: LocalDate): ProgressHomeUiState {
        val currentLocale = locale()
        val names = data.exercises.associate { it.id to it.localizedName(currentLocale) }
        val lifts = ProgressDerivations.buildLifts(data.sets) { names[it] }
        val start = ProgressRange.M3.start(today, zone)
        return ProgressHomeUiState(
            unit = data.unit,
            lastPRDate = lifts.mapNotNull { it.lastPR }.maxOrNull()
                ?.let { ProgressPhrase.eyebrowDate(it, today, zone, currentLocale) },
            lifts = lifts.map { lift ->
                LiftRowState(
                    exerciseId = lift.exerciseId,
                    name = lift.name,
                    context = lift.context,
                    lastPR = ProgressPhrase.lastPR(lift.lastPR, today, zone, currentLocale),
                    sparkline = lift.points(start).map { it.e1RM },
                    current = lift.current,
                    delta = lift.delta(start),
                    isHot = lift.prInLast30Days(today, zone),
                )
            },
            hasCompletedSets = lifts.isNotEmpty(),
            body = ProgressDerivations.buildBody(data.body, today),
            loaded = true,
        )
    }

    private data class Store(
        val unit: WeightUnit,
        val sets: List<CompletedSetRow>,
        val exercises: List<ExerciseEntity>,
        val body: List<BodyEntry>,
    )
}
