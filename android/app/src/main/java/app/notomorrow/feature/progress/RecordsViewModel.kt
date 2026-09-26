package app.notomorrow.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.Days
import app.notomorrow.service.localizedName
import app.notomorrow.util.LocaleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Progress > Records — the port of `RecordsView`'s own `ProgressModel`: every lift with a PR, most
 * recent PR first, with its [LiftRecords] ([ProgressDerivations.buildLifts] then
 * [LiftRecords.withPRs], the lifts the lift pages are built from).
 */
class RecordsViewModel(
    private val profileDao: ProfileDao,
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: () -> Locale = { LocaleProvider.current() },
) : ViewModel() {

    /** `.onAppear` — the day the last-PR phrasing is measured against. */
    private val today = MutableStateFlow(LocalDate.now(zone))

    val uiState: StateFlow<RecordsUiState> =
        combine(
            profileDao.observeProfile(),
            workoutDao.observeCompletedSets(),
            exerciseDao.observeAllByName(),
            today,
        ) { profile, sets, exercises, day ->
            val currentLocale = locale()
            val names = exercises.associate { it.id to it.localizedName(currentLocale) }
            val lifts = ProgressDerivations.buildLifts(sets) { names[it] }
            RecordsUiState(
                unit = profile?.units ?: WeightUnit.Kg,
                rows = LiftRecords.withPRs(lifts).map { (lift, records) ->
                    RecordsRowState(
                        exerciseId = lift.exerciseId,
                        name = lift.name,
                        lastPR = ProgressPhrase.lastPR(lift.lastPR, day, zone, currentLocale),
                        isHot = lift.prInLast30Days(day, zone),
                        bestE1RMKg = records.bestE1RMKg,
                        bestE1RMDay = Days.date(records.bestE1RM.completedAt, zone),
                        heaviest = records.heaviest.toRecord(),
                        bestVolume = records.bestVolume.toRecord(),
                    )
                },
                loaded = true,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = RecordsUiState(),
            )

    /** `RecordsView.onAppear` — re-read "now" so yesterday's phrasing never sticks. */
    fun refresh() {
        val day = LocalDate.now(zone)
        if (today.value != day) today.value = day
    }

    private fun CompletedSetRow.toRecord(): RecordSet =
        RecordSet(weightKg = weightKg, reps = reps, day = Days.date(completedAt, zone))
}
