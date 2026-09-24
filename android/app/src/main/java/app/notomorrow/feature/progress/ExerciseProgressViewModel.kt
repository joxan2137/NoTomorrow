package app.notomorrow.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.designsystem.WeekVolumeBar
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.Days
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
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
 * One lift over time — the port of `ExerciseProgressView`'s own `ProgressModel` plus its
 * `@State private var range` (`Features/Progress/ExerciseProgressView.swift`).
 *
 * The weekly-volume section deliberately covers **every** lift, not just this one, exactly
 * as iOS reads `model.weekly` there.
 */
class ExerciseProgressViewModel(
    private val exerciseId: String,
    private val profileDao: ProfileDao,
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: () -> Locale = { LocaleProvider.current() },
) : ViewModel() {

    private val range = MutableStateFlow(ProgressRange.M3)

    /** `.onAppear` — the day every relative phrase is measured against. */
    private val today = MutableStateFlow(LocalDate.now(zone))

    private val store = combine(
        profileDao.observeProfile(),
        workoutDao.observeCompletedSets(),
        exerciseDao.observeAllByName(),
    ) { profile, sets, exercises ->
        Store(profile?.units ?: WeightUnit.Kg, sets, exercises)
    }

    /**
     * Everything that does **not** depend on the selected range: the full pass over every
     * completed set (this screen rebuilds every lift and keeps one, as iOS does) runs off the
     * main thread, and the cheap per-range slice below stays on the caller's dispatcher so the
     * 1M/3M/1Y/All switch lands in the same frame.
     */
    private val derived = combine(store, today) { data, day -> derive(data, day) }
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<ExerciseProgressUiState> =
        combine(derived, range) { data, selected -> state(data, selected) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ExerciseProgressUiState(),
            )

    fun select(range: ProgressRange) {
        this.range.value = range
    }

    /** `ExerciseProgressView.onAppear` — re-read "now" so yesterday's phrasing never sticks. */
    fun refresh() {
        val day = LocalDate.now(zone)
        if (today.value != day) today.value = day
    }

    // MARK: - Derivation

    private fun derive(data: Store, today: LocalDate): Derived {
        val currentLocale = locale()
        val names = data.exercises.associate { it.id to it.localizedName(currentLocale) }
        val weekly = ProgressDerivations.buildWeekly(data.sets, today, zone)
        val lift = ProgressDerivations.buildLifts(data.sets) { names[it] }
            .firstOrNull { it.exerciseId == exerciseId }
        val weekStart = Fmt.startOfIsoWeek(today).atStartOfDay(zone).toInstant()
        return Derived(
            name = names[exerciseId].orEmpty(),
            unit = data.unit,
            today = today,
            lift = lift,
            weekly = weekly.map { it.toBar() },
            allThisWeekVolume = ProgressDerivations.thisWeekVolume(weekly),
            weekOverWeek = ProgressDerivations.weekOverWeek(weekly),
            lastPR = lift?.lastPR?.let { ProgressPhrase.ago(it, today, zone, currentLocale) },
            thisWeekVolume = lift?.thisWeekVolume(weekStart) ?: 0.0,
            heaviest = lift?.heaviest?.toRecord(),
            mostReps = lift?.mostReps?.toRecord(),
        )
    }

    /** The range-dependent slice: the points inside the window, the delta and the sessions. */
    private fun state(data: Derived, selected: ProgressRange): ExerciseProgressUiState {
        val base = ExerciseProgressUiState(
            name = data.name,
            unit = data.unit,
            range = selected,
            weekly = data.weekly,
            allThisWeekVolume = data.allThisWeekVolume,
            weekOverWeek = data.weekOverWeek,
            loaded = true,
        )
        val lift = data.lift ?: return base

        val start = selected.start(data.today, zone)
        return base.copy(
            hasLift = true,
            current = lift.current,
            delta = lift.delta(start),
            points = lift.points(start).map { it.toChartPoint(zone) },
            lastPR = data.lastPR,
            thisWeekVolume = data.thisWeekVolume,
            sessions = lift.sessions(start),
            heaviest = data.heaviest,
            mostReps = data.mostReps,
        )
    }

    private fun CompletedSetRow.toRecord(): RecordSet =
        RecordSet(weightKg = weightKg, reps = reps, day = Days.date(completedAt, zone))

    private data class Store(
        val unit: WeightUnit,
        val sets: List<CompletedSetRow>,
        val exercises: List<ExerciseEntity>,
    )

    /** Everything the range does not change, derived once per store emission. */
    private data class Derived(
        val name: String,
        val unit: WeightUnit,
        val today: LocalDate,
        val lift: LiftSummary?,
        val weekly: List<WeekVolumeBar>,
        val allThisWeekVolume: Double,
        val weekOverWeek: Double?,
        val lastPR: ProgressPhraseRef?,
        val thisWeekVolume: Double,
        val heaviest: RecordSet?,
        val mostReps: RecordSet?,
    )
}
