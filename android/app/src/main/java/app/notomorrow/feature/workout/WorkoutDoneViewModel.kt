package app.notomorrow.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.di.AppContainer
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.RecordService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the done screen renders. `previousVolumeKg` is `loadPrevious()`: the last **finished**
 * workout with the same name that started earlier (`WorkoutDoneView.swift:172`).
 *
 * [loaded] is false for the one frame before Room answers. iOS never has that state —
 * `WorkoutDoneView` is handed the `Workout` object itself — so the screen paints nothing but the
 * ground until it flips, rather than showing a 1970 date and a zero hero.
 */
data class WorkoutDoneUiState(
    val name: String = "",
    val startedAt: Long = 0L,
    val endedAt: Long? = null,
    val volumeKg: Double = 0.0,
    val completedSetCount: Int = 0,
    /** `exercises.filter { $0.sets.contains(where: \.isCompleted) }.count`. */
    val exerciseCount: Int = 0,
    val previousVolumeKg: Double? = null,
    val records: List<WorkoutRecordItem> = emptyList(),
    val partnerName: String? = null,
    /** The user's unit for the hero, the delta, the share text and the records (volumes are kg). */
    val unit: WeightUnit = WeightUnit.Kg,
    /** The share card's exercises (`WorkoutShareCard.lines(for:unit:)`). */
    val shareLines: List<WorkoutShareLine> = emptyList(),
    val loaded: Boolean = false,
)

/** One row of "Records tonight", with the numbers its detail line needs already resolved. */
data class WorkoutRecordItem(
    val setId: Long,
    val exercise: ExerciseEntity?,
    val weightKg: Double,
    val reps: Int,
    val isPR: Boolean,
    /** Epley e1RM of this set. */
    val e1RM: Double,
    /** Best e1RM of every earlier working set of the exercise — "up from 96 kg". */
    val bestBeforeE1RM: Double,
)

/**
 * Reads the finished workout back and derives the summary. The records come from
 * `RecordService.records(in:)` (PRs first, then set records, each in row order); the "up from"
 * number is `RecordService.previousSets` over the flat completed-set projection, so it costs one
 * query per record rather than a lazy graph walk.
 */
class WorkoutDoneViewModel(
    private val container: AppContainer,
    private val workoutId: String,
) : ViewModel() {

    private val workoutDao = container.db.workoutDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<WorkoutDoneUiState> = combine(
        workoutDao.observeWorkoutWithExercises(workoutId),
        container.db.broPairingDao().observePairing(),
        container.db.profileDao().observeProfile(),
    ) { workout, pairing, profile -> Triple(workout, pairing?.partnerName, profile?.units ?: WeightUnit.Kg) }
        .mapLatest { (workout, partner, unit) -> build(workout, partner, unit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutDoneUiState())

    private suspend fun build(
        workout: WorkoutWithExercises?,
        partnerName: String?,
        unit: WeightUnit,
    ): WorkoutDoneUiState {
        if (workout == null) return WorkoutDoneUiState(partnerName = partnerName, unit = unit)
        val row = workout.workout
        val byId = workout.exercises.associateBy { it.workoutExercise.id }

        val records = RecordService.records(workout).map { set ->
            val exercise = byId[set.workoutExerciseId]?.exercise
            val bestBefore = exercise?.let { ex ->
                RecordService.previousSets(workoutDao.completedSetsForExercise(ex.id), set)
                    .maxOfOrNull { it.estimatedOneRepMax } ?: 0.0
            } ?: 0.0
            WorkoutRecordItem(
                setId = set.id,
                exercise = exercise,
                weightKg = set.weightKg,
                reps = set.reps,
                isPR = set.isPR,
                e1RM = RecordService.epley(set.weightKg, set.reps),
                bestBeforeE1RM = bestBefore,
            )
        }

        return WorkoutDoneUiState(
            name = row.name,
            startedAt = row.startedAt,
            endedAt = row.endedAt,
            volumeKg = workout.totalVolumeKg,
            completedSetCount = workout.completedSetCount,
            exerciseCount = workout.exercises.count { item -> item.sets.any { it.isCompleted } },
            previousVolumeKg = workoutDao.previousWorkoutWithSameName(
                name = row.name,
                startedBefore = row.startedAt,
                excludingId = row.id,
            )?.totalVolumeKg,
            records = records,
            partnerName = partnerName,
            unit = unit,
            shareLines = workoutShareLines(workout, unit),
            loaded = true,
        )
    }
}
