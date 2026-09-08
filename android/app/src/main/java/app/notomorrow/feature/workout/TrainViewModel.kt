package app.notomorrow.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.relation.RoutineWithItems
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.di.AppContainer
import app.notomorrow.model.WeightUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Train tab's state — the port of the four `@Query`s in `TrainView.swift`.
 *
 * iOS reads routines, finished workouts (newest first), unfinished workouts (newest first) and the
 * profile straight from SwiftData; here the same four Room `Flow`s are combined into one immutable
 * [TrainUiState]. Nothing is formatted: the screen turns timestamps into text through `Fmt`, so a
 * language change re-renders without the view model knowing.
 */
class TrainViewModel(private val container: AppContainer) : ViewModel() {

    private val workoutDao = container.db.workoutDao()
    private val routineDao = container.db.routineDao()
    private val profileDao = container.db.profileDao()

    val state: StateFlow<TrainUiState> = combine(
        routineDao.observeRoutinesWithItems(),
        // `@Query(filter: endedAt != nil, sort: startedAt, .reverse)` — iOS has no limit.
        workoutDao.observeFinishedWorkoutsWithExercises(Int.MAX_VALUE),
        workoutDao.observeActiveWorkouts(),
        profileDao.observeProfile(),
    ) { routines, history, unfinished, profile ->
        TrainUiState(
            routines = routines.map(::rowItem),
            history = history,
            active = unfinished.firstOrNull()?.let { ActiveWorkoutSummary(it.id, it.name, it.startedAt) },
            unit = profile?.units ?: WeightUnit.Kg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainUiState())

    // MARK: - Actions

    /** The white Start pill: build the workout from the routine and present it. */
    fun start(routineId: String) {
        viewModelScope.launch { WorkoutStarter.start(container, routineId) }
    }

    /**
     * `workout.startEmpty`. [name] is `workout.defaultName`, resolved by the screen — the view model
     * never touches resources.
     */
    fun startEmpty(name: String) {
        viewModelScope.launch { WorkoutStarter.startEmpty(container, name) }
    }

    /** The resume banner: `session.begin(active)` — the workout already exists. */
    fun resume(workoutId: String) {
        container.workoutSession.begin(workoutId)
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
    val active: ActiveWorkoutSummary? = null,
    val unit: WeightUnit = WeightUnit.Kg,
)

/** The unfinished workout behind `ResumeWorkoutBanner`. */
data class ActiveWorkoutSummary(
    val id: String,
    val name: String,
    val startedAt: Long,
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
