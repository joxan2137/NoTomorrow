package app.notomorrow.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.relation.RoutineWithItems
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.di.AppContainer
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.WorkoutSessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Train tab's state — the port of the `@Query`s in `TrainView.swift`, plus its start guard.
 *
 * iOS reads routines, finished workouts (newest first) and the profile straight from SwiftData;
 * here the same Room `Flow`s are combined into one immutable [TrainUiState]. Nothing is formatted:
 * the screen turns timestamps into text through `Fmt`, so a language change re-renders without the
 * view model knowing.
 *
 * A workout in progress lives in the mini bar above the tab bar (the resume card is gone); Start
 * while one runs asks first ([blockedStart]), because only one workout runs at a time.
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

    val state: StateFlow<TrainUiState> = combine(
        routineDao.observeRoutinesWithItems(),
        // `@Query(filter: endedAt != nil, sort: startedAt, .reverse)` — iOS has no limit.
        workoutDao.observeFinishedWorkoutsWithExercises(Int.MAX_VALUE),
        profileDao.observeProfile(),
    ) { routines, history, profile ->
        TrainUiState(
            routines = routines.map(::rowItem),
            history = history,
            unit = profile?.units ?: WeightUnit.Kg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainUiState())

    private val _blockedStart = MutableStateFlow<BlockedStart?>(null)

    /** `blockedStart` — a Start tapped while another workout is in progress; drives the dialog. */
    val blockedStart: StateFlow<BlockedStart?> = _blockedStart.asStateFlow()

    /** A start is being resolved: a second tap in that window is dropped, never a second workout. */
    private var starting = false

    // MARK: - Actions

    /** The white Start pill: build the workout from the routine and present it. */
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

    /** "Resume" in the dialog: the running workout comes back full screen. */
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

        const val PREVIEW_COUNT = 3
    }
}

/** One immutable snapshot of the Train tab. */
data class TrainUiState(
    val routines: List<RoutineRowItem> = emptyList(),
    /** Finished workouts, newest first, with the graph the detail sheet and the row totals need. */
    val history: List<WorkoutWithExercises> = emptyList(),
    val unit: WeightUnit = WeightUnit.Kg,
)

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
