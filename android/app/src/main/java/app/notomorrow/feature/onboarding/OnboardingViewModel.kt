package app.notomorrow.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.app.AppState
import app.notomorrow.data.dao.BodyWeightDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.ScheduleDao
import app.notomorrow.data.entity.BodyWeightEntryEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.model.BodyWeightSource
import app.notomorrow.model.TrainingGoal
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.AuthStore
import app.notomorrow.service.BroService
import app.notomorrow.service.Days
import app.notomorrow.service.RoutineSeeder
import app.notomorrow.service.TargetCalculator
import app.notomorrow.service.toDto
import app.notomorrow.util.Fmt
import app.notomorrow.util.Parsing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlin.random.Random

/** `OnboardingStep` (`OnboardingModel.swift:6`). */
enum class OnboardingStep { Welcome, You, Schedule, Pair }

/**
 * Draft values for the whole flow — the port of `OnboardingModel`'s stored and computed
 * properties. Nothing here is persisted until [OnboardingViewModel.finish].
 *
 * Every derivation below is a pure function of the state, so it is unit-testable without a
 * `ViewModel`, a DAO or the Android framework.
 */
data class OnboardingUiState(
    // MARK: Navigation
    val step: OnboardingStep = OnboardingStep.Welcome,
    /** Insertion direction for the step transition; set before `step` changes. */
    val movesForward: Boolean = true,
    val order: List<OnboardingStep> = STANDARD_ORDER,

    // MARK: You
    val name: String = "",
    val weightText: String = "",
    val unit: WeightUnit = WeightUnit.Kg,
    val goal: TrainingGoal = TrainingGoal.BuildMuscle,
    /** Set when the user edits the suggestion; cleared with "Use the suggestion". */
    val customTargets: TargetCalculator.Targets? = null,

    // MARK: Schedule
    val weekdays: Set<Int> = setOf(1, 3, 5),
    val usualMinuteOfDay: Int = 18 * 60,
    val overrides: Map<Int, Int> = emptyMap(),
    val remindHourBefore: Boolean = true,
    val askIfSkippedAt21: Boolean = true,

    // MARK: Pair
    val myCode: String = "",
    val codeEntry: String = "",
    val isPairing: Boolean = false,
    val pairFailed: Boolean = false,
    val partnerName: String? = null,
    val isPaired: Boolean = false,
    /** `AuthStore.shared.needsSignIn` — drives the "pair later" helper line. */
    val needsSignIn: Boolean = false,
    val isFinishing: Boolean = false,
) {

    /** `stepCount` — Welcome is not one of the three. */
    val stepCount: Int get() = STEP_COUNT

    /** `stepIndex` — `null` on Welcome. */
    val stepIndex: Int? get() = order.indexOf(step).takeIf { it >= 0 }

    val isLastStep: Boolean get() = stepIndex == order.lastIndex

    val trimmedName: String get() = name.trim()

    val canContinueFromYou: Boolean get() = trimmedName.isNotEmpty()

    /** The number as typed, comma-tolerant; `null` when it is not a positive number. */
    val enteredWeight: Double?
        get() {
            val cleaned = weightText.replace(',', '.').trim()
            val value = cleaned.toDoubleOrNull() ?: return null
            if (!value.isFinite() || value <= 0) return null
            return value
        }

    /** The entered weight in kilograms, whatever unit it was typed in. */
    val bodyWeightKg: Double?
        get() {
            val value = enteredWeight ?: return null
            return if (unit == WeightUnit.Kg) value else value / Fmt.LB_PER_KG
        }

    val suggestedTargets: TargetCalculator.Targets
        get() = TargetCalculator.targets(bodyWeightKg, goal)

    val targets: TargetCalculator.Targets get() = customTargets ?: suggestedTargets

    /** `OnboardingModel.normalizedCode` — the looser onboarding form of a pair code. */
    val normalizedCode: String get() = Parsing.onboardingPairCode(codeEntry)

    val canPair: Boolean get() = normalizedCode.length == 7 && !isPairing

    val usualHour: Int get() = Math.floorMod(usualMinuteOfDay, 24 * 60) / 60

    val usualMinute: Int get() = Math.floorMod(usualMinuteOfDay, 24 * 60) % 60

    companion object {
        val STANDARD_ORDER: List<OnboardingStep> =
            listOf(OnboardingStep.You, OnboardingStep.Schedule, OnboardingStep.Pair)

        val PAIR_FIRST_ORDER: List<OnboardingStep> =
            listOf(OnboardingStep.Pair, OnboardingStep.You, OnboardingStep.Schedule)

        const val STEP_COUNT: Int = 3

        /** `OnboardingModel.localCode()` — no I/O/0/1, so a shared code cannot be misread. */
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun localCode(random: Random = Random.Default): String =
            "NT-" + (0 until 4).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")

        /**
         * `SetupYouView.setUnit` — switching units converts what was typed so the number stays
         * the same weight. Returns the new text for [OnboardingUiState.weightText].
         */
        fun convertedWeightText(state: OnboardingUiState, newUnit: WeightUnit): String {
            val value = state.enteredWeight ?: return state.weightText
            val converted =
                if (newUnit == WeightUnit.Lb) value * Fmt.LB_PER_KG else value / Fmt.LB_PER_KG
            return Fmt.weight(converted, WeightUnit.Kg, withUnit = false)
        }

        /** `SetupScheduleView.splitKey(days:)`. */
        fun splitKind(days: Int): SplitKind = when {
            days <= 2 -> SplitKind.FullBody
            days <= 4 -> SplitKind.PushPull
            else -> SplitKind.PushPullLegs
        }

        /** `OBTargetEditorSheet.save()` — digits only, falling back to the current value. */
        fun editedTargets(
            current: TargetCalculator.Targets,
            kcal: String,
            protein: String,
            carbs: String,
            fat: String,
        ): TargetCalculator.Targets = TargetCalculator.Targets(
            kcal = digits(kcal) ?: current.kcal,
            proteinG = digits(protein) ?: current.proteinG,
            carbsG = digits(carbs) ?: current.carbsG,
            fatG = digits(fat) ?: current.fatG,
        )

        private fun digits(raw: String): Int? = raw.filter { it.isDigit() }.toIntOrNull()
    }
}

/** The three split names the schedule step suggests. */
enum class SplitKind { FullBody, PushPull, PushPullLegs }

/**
 * The onboarding flow's one view model — `OnboardingModel` (`OnboardingModel.swift`).
 *
 * It owns **its own** [BroService], exactly like iOS (`let bro = BroService()`), so an aborted
 * pairing attempt during onboarding never disturbs the app-wide instance.
 */
class OnboardingViewModel(
    private val profileDao: ProfileDao,
    private val scheduleDao: ScheduleDao,
    private val bodyWeightDao: BodyWeightDao,
    private val routineSeeder: RoutineSeeder,
    private val bro: BroService,
    authStore: AuthStore,
    private val appState: AppState,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState(myCode = OnboardingUiState.localCode()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            bro.partner.collect { partner ->
                _state.update { it.copy(partnerName = partner?.name, isPaired = partner != null) }
            }
        }
        viewModelScope.launch {
            authStore.needsSignIn.collect { needs -> _state.update { it.copy(needsSignIn = needs) } }
        }
    }

    // MARK: - Navigation

    fun startStandard() {
        _state.update { it.copy(order = OnboardingUiState.STANDARD_ORDER) }
        go(OnboardingStep.You, forward = true)
    }

    /** "Got a code from your bro? Pair now" — pairing first, the rest afterwards. */
    fun startWithPair() {
        _state.update { it.copy(order = OnboardingUiState.PAIR_FIRST_ORDER) }
        go(OnboardingStep.Pair, forward = true)
    }

    fun next() {
        val current = _state.value
        val index = current.stepIndex ?: return
        if (index + 1 >= current.order.size) return
        go(current.order[index + 1], forward = true)
    }

    fun back() {
        val current = _state.value
        val index = current.stepIndex ?: return
        go(if (index == 0) OnboardingStep.Welcome else current.order[index - 1], forward = false)
    }

    private fun go(target: OnboardingStep, forward: Boolean) {
        _state.update { it.copy(step = target, movesForward = forward) }
    }

    // MARK: - You

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setWeightText(value: String) = _state.update { it.copy(weightText = value) }

    fun setUnit(newUnit: WeightUnit) = _state.update {
        if (newUnit == it.unit) it
        else it.copy(unit = newUnit, weightText = OnboardingUiState.convertedWeightText(it, newUnit))
    }

    fun setGoal(goal: TrainingGoal) = _state.update { it.copy(goal = goal) }

    /** "Use the suggestion" — back to the calculator. */
    fun useSuggestedTargets() = _state.update { it.copy(customTargets = null) }

    /** `OBTargetEditorSheet.save()`: an edit equal to the suggestion is not a custom target. */
    fun saveTargets(kcal: String, protein: String, carbs: String, fat: String) = _state.update {
        val edited = OnboardingUiState.editedTargets(it.targets, kcal, protein, carbs, fat)
        it.copy(customTargets = if (edited == it.suggestedTargets) null else edited)
    }

    // MARK: - Schedule

    fun toggleDay(day: Int) = _state.update {
        val days = if (it.weekdays.contains(day)) it.weekdays - day else it.weekdays + day
        it.copy(weekdays = days)
    }

    fun setUsualTime(hour: Int, minute: Int) =
        _state.update { it.copy(usualMinuteOfDay = hour * 60 + minute) }

    /** `null` removes the per-day override. */
    fun setOverride(day: Int, minuteOfDay: Int?) = _state.update {
        val overrides = it.overrides.toMutableMap()
        if (minuteOfDay == null) overrides.remove(day) else overrides[day] = minuteOfDay
        it.copy(overrides = overrides.toMap())
    }

    fun setRemindHourBefore(value: Boolean) = _state.update { it.copy(remindHourBefore = value) }

    fun setAskIfSkippedAt21(value: Boolean) = _state.update { it.copy(askIfSkippedAt21 = value) }

    // MARK: - Pair

    fun setCodeEntry(value: String) = _state.update { it.copy(codeEntry = value) }

    /** Replaces the locally generated code with the backend's one when reachable. */
    fun loadCode() {
        viewModelScope.launch {
            val code = try {
                bro.createCode()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (code != null) _state.update { it.copy(myCode = code) }
        }
    }

    fun pair() {
        if (!_state.value.canPair) return
        viewModelScope.launch {
            _state.update { it.copy(isPairing = true, pairFailed = false) }
            val code = _state.value.normalizedCode
            val ok = try {
                bro.pair(code)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            _state.update {
                it.copy(
                    isPairing = false,
                    pairFailed = !ok,
                    codeEntry = if (ok) "" else it.codeEntry,
                )
            }
        }
    }

    // MARK: - Finish

    /**
     * The one write of the whole flow. [fallbackName] is `bro.you`, resolved by the caller so it
     * follows the language picked on the Welcome screen.
     */
    fun finish(fallbackName: String, onFinished: () -> Unit = {}) {
        if (_state.value.isFinishing) return
        _state.update { it.copy(isFinishing = true) }
        viewModelScope.launch {
            val draft = _state.value
            val targets = draft.targets
            profileDao.upsert(
                UserProfileEntity(
                    name = draft.trimmedName.ifEmpty { fallbackName },
                    bodyWeightKg = draft.bodyWeightKg,
                    goal = draft.goal,
                    units = draft.unit,
                    calorieGoal = targets.kcal,
                    proteinGoalG = targets.proteinG,
                    carbsGoalG = targets.carbsG,
                    fatGoalG = targets.fatG,
                )
            )

            val schedule = GymScheduleEntity(
                weekdays = draft.weekdays.toList(),
                defaultMinuteOfDay = draft.usualMinuteOfDay,
                overrides = draft.overrides,
                remindHourBefore = draft.remindHourBefore,
                askIfSkippedAt21 = draft.askIfSkippedAt21,
            )
            scheduleDao.save(schedule)

            draft.bodyWeightKg?.let { kg ->
                bodyWeightDao.upsert(
                    BodyWeightEntryEntity(
                        day = Days.millis(Days.today(zone), zone),
                        kg = kg,
                        source = BodyWeightSource.Manual,
                    )
                )
            }

            runCatching { routineSeeder.seedIfNeeded() }
            if (draft.isPaired) {
                try {
                    bro.client.pushSchedule(schedule.normalized().toDto())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // `try?` on iOS — a failed push is retried on the next sync.
                }
            }

            _state.update { it.copy(isFinishing = false) }
            appState.setHasOnboarded(true)
            onFinished()
        }
    }
}
