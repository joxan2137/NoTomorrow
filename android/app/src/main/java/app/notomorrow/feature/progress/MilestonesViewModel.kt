package app.notomorrow.feature.progress

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.BodyWeightDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.model.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

/** Everything `MilestonesScreen` renders. */
@Immutable
data class MilestonesUiState(
    val unit: WeightUnit = WeightUnit.Kg,
    /** [Milestones.evaluate]: every tier of every track, in track order. */
    val milestones: List<Milestones.Milestone> = emptyList(),
    /** The body weight the strength track is measured against; `null` hides it. */
    val bodyWeightKg: Double? = null,
    /** `false` until the first store emission — see [ProgressHomeUiState.loaded]. */
    val loaded: Boolean = false,
)

/**
 * Progress > Milestones — the `@Query`s `MilestonesView` reads: the finished workouts (as
 * completed-set rows), the latest body weight (else the profile's) and the unit.
 */
class MilestonesViewModel(
    profileDao: ProfileDao,
    workoutDao: WorkoutDao,
    bodyWeightDao: BodyWeightDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    val uiState: StateFlow<MilestonesUiState> =
        combine(
            profileDao.observeProfile(),
            workoutDao.observeCompletedSets(),
            bodyWeightDao.observeLatest(),
        ) { profile, sets, latest ->
            val bodyWeight = Milestones.bodyWeight(latest?.kg, profile?.bodyWeightKg)
            MilestonesUiState(
                unit = profile?.units ?: WeightUnit.Kg,
                milestones = Milestones.evaluate(Milestones.sessions(sets, zone), bodyWeight),
                bodyWeightKg = bodyWeight,
                loaded = true,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = MilestonesUiState(),
            )
}
