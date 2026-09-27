package app.notomorrow.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.relation.RoutineWithItems
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.di.AppContainer
import app.notomorrow.feature.dashboard.DashboardViewModel
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The Train tab's state — the port of the `@Query`s in `TrainView.swift`, plus its start guard.
 *
 * iOS reads routines, finished workouts (newest first) and the profile straight from SwiftData;
 * here the same Room `Flow`s are combined into one immutable [TrainUiState]. Nothing is formatted:
 * the screen turns timestamps into text through `Fmt`, so a language change re-renders without the
 * view model knowing.
 *
 * A workout in progress lives in the mini bar above the tab bar; Start while one runs asks first
 * ([blockedStart]), because only one workout runs at a time. The "Up next" card leads the tab with
 * the routine the Today card suggests (`DashboardViewModel.suggestedRoutine`), and turns into
 * Resume while a workout is in progress; the routine list below holds the others.
 *
 * The routine menu's Duplicate, Move up / down and Delete write through [RoutineStore]; the
 * editor itself has its own model ([RoutineEditorViewModel]).
 */
class TrainViewModel internal constructor(
    private val stores: WorkoutStarter.Stores,
    private val session: WorkoutSessionController,
    /** `RestTimer.skip()` — a discarded workout takes its rest with it. */
    private val skipRest: () -> Unit,
) : ViewModel() {

    constructor(container: AppContainer) : this(
        stores = WorkoutStarter.Stores.of(container),
        session = container.workoutSession,
        skipRest = { container.restTimer.skip() },
    )

    private val workoutDao = stores.workoutDao
    private val routineDao = stores.routineDao
    private val profileDao = stores.profileDao
    private val routineStore = RoutineStore(stores.routineDao, stores.exerciseDao)

    val state: StateFlow<TrainUiState> = combine(
        routineDao.observeRoutinesWithItems(),
        // `@Query(filter: endedAt != nil, sort: startedAt, .reverse)` — iOS has no limit.
        workoutDao.observeFinishedWorkoutsWithExercises(Int.MAX_VALUE),
        profileDao.observeProfile(),
        session.isWorkoutInProgress,
    ) { routines, history, profile, inProgress ->
        // `TrainView.upNextRoutine`: the routine after the last one done, as on Today.
        val upNextId = DashboardViewModel.suggestedRoutine(
            routines = routines.map { it.routine },
            recentWorkoutNames = history.map { it.workout.name },
        )?.id
        TrainUiState(
            upNext = routines.firstOrNull { it.routine.id == upNextId }?.let(::upNextItem),
            routines = routines.filter { it.routine.id != upNextId }.map(::rowItem),
            hasRoutines = routines.isNotEmpty(),
            routineOrder = routines.map { it.routine.id },
            history = history,
            unit = profile?.units ?: WeightUnit.Kg,
            isWorkoutInProgress = inProgress,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainUiState())

    private val _blockedStart = MutableStateFlow<BlockedStart?>(null)

    /** `blockedStart` — a Start tapped while another workout is in progress; drives the dialog. */
    val blockedStart: StateFlow<BlockedStart?> = _blockedStart.asStateFlow()

    /** A start is being resolved: a second tap in that window is dropped, never a second workout. */
    private var starting = false

    // MARK: - Actions

    /** The play button and the Up next card's Start: build the workout from the routine and present it. */
    fun start(routineId: String) = requestStart(WorkoutStarter.Request.Routine(routineId))

    /**
     * `workout.startEmpty`. [name] is `workout.defaultName`, resolved by the screen — the view model
     * never touches resources.
     */
    fun startEmpty(name: String) = requestStart(WorkoutStarter.Request.Empty(name))

    /** `requestStart(_:)` — starts right away, or asks when a workout is already in progress. */
    private fun requestStart(request: WorkoutStarter.Request) {
        if (starting) return
        starting = true
        viewModelScope.launch {
            try {
                when (val gate = WorkoutStarter.gate(workoutDao, session)) {
                    WorkoutStarter.Gate.Clear -> WorkoutStarter.start(request, stores, session)
                    is WorkoutStarter.Gate.Blocked -> _blockedStart.value = BlockedStart(
                        activeId = gate.active.id,
                        activeName = gate.active.name,
                        canDiscard = gate.canDiscard,
                        request = request,
                    )
                }
            } finally {
                starting = false
            }
        }
    }

    /**
     * "Resume" in the dialog, and on the Up next card while a workout is in progress: the running
     * workout comes back full screen, as a mini bar tap brings it.
     */
    fun resumeActive() {
        _blockedStart.value = null
        session.expand()
    }

    /**
     * "Discard it and start new" — skips the rest, drops the empty workout and starts the request.
     * [WorkoutStarter.discardAndStart] refuses (and resumes) if a set was completed meanwhile.
     *
     * Takes the [blocked] start the dialog showed rather than reading [blockedStart]: the action
     * sheet dismisses itself ([dismissBlockedStart]) *before* it runs the tapped action, so by now
     * the flow is already `null` — reading it made this button do nothing.
     */
    fun discardActiveAndStart(blocked: BlockedStart) {
        _blockedStart.value = null
        if (starting) return
        starting = true
        skipRest()
        viewModelScope.launch {
            try {
                val active = workoutDao.workout(blocked.activeId)
                if (active == null || active.endedAt != null) {
                    WorkoutStarter.start(blocked.request, stores, session)
                } else {
                    WorkoutStarter.discardAndStart(active, blocked.request, stores, session)
                }
            } finally {
                starting = false
            }
        }
    }

    /** Cancel, or a tap outside the dialog. */
    fun dismissBlockedStart() {
        _blockedStart.value = null
    }

    // MARK: - Routine menu

    /** Duplicate: a copy right after the original, named "Push A 2". */
    fun duplicateRoutine(routineId: String) {
        viewModelScope.launch { runCatching { routineStore.duplicate(routineId, LocaleProvider.current()) } }
    }

    /** Move up (-1) / Move down (+1) in the list. */
    fun moveRoutine(routineId: String, offset: Int) {
        viewModelScope.launch { runCatching { routineStore.move(routineId, offset) } }
    }

    /** Delete, once confirmed. */
    fun deleteRoutine(routineId: String) {
        viewModelScope.launch { runCatching { routineStore.delete(routineId) } }
    }

    // MARK: - Programs

    /**
     * "Add N routines" in the program browser. [localize] turns a routine's catalog key into its
     * name — the screen's resources, so the view model never touches them.
     */
    fun addProgram(program: TrainingProgram, localize: (String) -> String) {
        viewModelScope.launch { runCatching { routineStore.addProgram(program, localize, LocaleProvider.current()) } }
    }

    // MARK: - Sharing

    /** "Share routine": the routine as text ([RoutineShare]); `null` when it is gone. */
    suspend fun shareText(routineId: String, labels: RoutineShare.Labels): String? =
        runCatching { routineStore.shareText(routineId, labels, LocaleProvider.current()) }.getOrNull()

    /** The user's exercises for matching an imported routine. */
    suspend fun shareCatalog(): RoutineShare.Catalog =
        runCatching { routineStore.shareCatalog(LocaleProvider.current()) }.getOrElse { RoutineShare.Catalog() }

    /** "Add routine" in the import sheet. */
    fun addShared(name: String, items: List<RoutineShare.Planned>, fallbackName: String) {
        viewModelScope.launch { runCatching { routineStore.addShared(name, items, fallbackName) } }
    }

    /** Library id → exercise, for the program browser's lines. */
    suspend fun programExercises(ids: Collection<String>): Map<String, ExerciseEntity> =
        runCatching { stores.exerciseDao.byIds(ids.toList()).associateBy { it.id } }.getOrElse { emptyMap() }

    private companion object {
        /**
         * `RoutineRow.subtitle` inputs: only items whose exercise still exists are counted, and the
         * preview is the first three of them (`sortedItems.compactMap { $0.exercise }`).
         */
        fun rowItem(routine: RoutineWithItems): RoutineRowItem {
            val exercises = routine.sortedItems.mapNotNull { it.exercise }
            return RoutineRowItem(
                id = routine.routine.id,
                name = routine.routine.name,
                exerciseCount = exercises.size,
                preview = exercises.take(PREVIEW_COUNT),
            )
        }

        /**
         * The Up next card's lines: `routine.sortedItems` whose exercise still exists, with the
         * targets `WorkoutStarter` builds the rows from.
         */
        fun upNextItem(routine: RoutineWithItems): UpNextRoutine = UpNextRoutine(
            id = routine.routine.id,
            name = routine.routine.name,
            items = routine.sortedItems.mapNotNull { item ->
                item.exercise?.let { UpNextItem(it, item.item.targetSets, item.item.targetReps) }
            },
        )

        const val PREVIEW_COUNT = 3
    }
}

/** One immutable snapshot of the Train tab. */
data class TrainUiState(
    /** The routine the Today card suggests; `null` when there are no routines. */
    val upNext: UpNextRoutine? = null,
    /** Every routine but [upNext]. */
    val routines: List<RoutineRowItem> = emptyList(),
    /** Any routine at all, the up-next one included (`workout.noRoutines` otherwise). */
    val hasRoutines: Boolean = false,
    /** Every routine id in list order, the up-next one included: the menu's Move up / down bounds. */
    val routineOrder: List<String> = emptyList(),
    /** Finished workouts, newest first, with the graph the detail sheet and the row totals need. */
    val history: List<WorkoutWithExercises> = emptyList(),
    val unit: WeightUnit = WeightUnit.Kg,
    /** `session.isWorkoutInProgress`: the Up next card offers Resume instead of Start. */
    val isWorkoutInProgress: Boolean = false,
)

/** The Up next card (`TrainView.upNextCard`): the routine and one line per exercise. */
data class UpNextRoutine(
    val id: String,
    val name: String,
    val items: List<UpNextItem>,
)

/**
 * One line of the Up next card: the exercise (localized at render time) and its target sets ×
 * reps (`RoutineItem.targetSets` / `targetReps`).
 */
data class UpNextItem(
    val exercise: ExerciseEntity,
    val targetSets: Int,
    val targetReps: Int,
)

/**
 * `HistoryWeek` (`TrainView.swift`): the history group of a finished workout — this ISO week
 * (Monday first), the one before, or earlier.
 */
enum class HistoryWeek {
    ThisWeek,
    LastWeek,
    Earlier,
    ;

    companion object {
        fun of(day: LocalDate, today: LocalDate): HistoryWeek {
            val thisWeek = Fmt.startOfIsoWeek(today)
            return when {
                !day.isBefore(thisWeek) -> ThisWeek
                !day.isBefore(thisWeek.minusWeeks(1)) -> LastWeek
                else -> Earlier
            }
        }

        /** [items] split by week, newest group first, each keeping the order it came in; empty weeks are left out. */
        fun <T> grouped(items: List<T>, today: LocalDate, day: (T) -> LocalDate): List<Pair<HistoryWeek, List<T>>> {
            val byWeek = items.groupBy { of(day(it), today) }
            return entries.mapNotNull { week -> byWeek[week]?.let { week to it } }
        }
    }
}

/** `BlockedStart` — the running workout a Start ran into, and what the Start asked for. */
data class BlockedStart(
    val activeId: String,
    val activeName: String,
    /** No completed sets, so it may be discarded (the Finish dialog's rule). */
    val canDiscard: Boolean,
    val request: WorkoutStarter.Request,
)

/**
 * One routine row. [preview] carries the `ExerciseEntity`s rather than their names so the subtitle
 * can be localized at render time (`Exercise.localizedName` reads the **app** locale).
 */
data class RoutineRowItem(
    val id: String,
    val name: String,
    val exerciseCount: Int,
    val preview: List<ExerciseEntity>,
)
