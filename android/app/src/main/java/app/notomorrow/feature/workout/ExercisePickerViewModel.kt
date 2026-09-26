package app.notomorrow.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.di.AppContainer
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.ExerciseLibrary
import app.notomorrow.service.RecordService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Search + muscle filter over the exercise library — 1:1 port of
 * `NoTomorrow/Features/Workout/ExercisePickerViewModel.swift`.
 *
 * Typing is debounced by **200 ms** before the list is refiltered (the field itself updates at
 * once); a chip tap refilters immediately. The matching rules — recently-used-first ordering, token
 * search over the folded English *and* Polish names, the muscle sets behind each chip — live in
 * `ExerciseLibrary`, so this class only wires flows together.
 *
 * The "Last: 80 × 8" column is resolved from one flat projection of every completed set rather than
 * a per-row lookup: SwiftData can walk `exercise.usages` lazily, Room would issue 876 queries.
 */
@OptIn(FlowPreview::class)
class ExercisePickerViewModel(
    private val container: AppContainer,
    /** When set, chosen exercises are appended to this workout before the caller's `onAdd` runs. */
    private val workoutId: String? = null,
    initialAlreadyIn: Set<String> = emptySet(),
) : ViewModel() {

    private val workoutDao = container.db.workoutDao()
    private val exerciseDao = container.db.exerciseDao()

    private val queryInput = MutableStateFlow("")
    private val groupInput = MutableStateFlow(ExerciseLibrary.MuscleGroup.All)
    private val muscleInput = MutableStateFlow<String?>(null)
    /** The chip row's filter: a group, or one muscle from the body map that replaces it. */
    private val filterInput = combine(groupInput, muscleInput) { group, muscle -> group to muscle }
    private val selectedInput = MutableStateFlow<List<String>>(emptyList())

    /** The `alreadyIn` of the plain `init(alreadyIn:onAdd:)`; re-set by [reset] on every presentation. */
    private val alreadyInInput = MutableStateFlow(initialAlreadyIn)

    /** Recently used first, then alphabetical — `load(context:)`. */
    private val library: Flow<List<ExerciseEntity>> =
        exerciseDao.observeAllByName().map { ExerciseLibrary.sorted(it) }

    /** Most recent completed working set per exercise (`RecordService.lastSet(for:)`). */
    private val lastSets: Flow<Map<String, CompletedSetRow>> =
        workoutDao.observeCompletedSets().map { rows ->
            RecordService.completedSets(rows)
                .groupBy { it.exerciseId }
                .mapNotNull { (id, group) ->
                    if (id == null) null else id to group.maxWith(RecordService.COMPLETED_EARLIER)
                }
                .toMap()
        }

    /** `init(workout:)` hides what is already in the workout; the plain init takes the set. */
    private val alreadyIn: Flow<Set<String>> =
        if (workoutId == null) {
            alreadyInInput
        } else {
            workoutDao.observeWorkoutWithExercises(workoutId).map { workout ->
                workout?.exercises?.mapNotNull { it.workoutExercise.exerciseId }?.toSet().orEmpty()
            }
        }

    private val results: Flow<List<ExerciseEntity>> = combine(
        library,
        queryInput.debounce { if (it.isEmpty()) 0L else FILTER_DEBOUNCE_MS },
        filterInput,
    ) { all, query, filter -> ExerciseLibrary.filter(all, query, filter.first, filter.second) }

    /** Library exercises per primary muscle, for the body-map filter's "Show n exercises". */
    val muscleCounts: StateFlow<Map<String, Int>> = library.map { all ->
        all.flatMap { it.primaryMuscles.distinct() }.groupingBy { it }.eachCount()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val state: StateFlow<ExercisePickerUiState> = combine(
        results,
        lastSets,
        queryInput,
        filterInput,
        combine(selectedInput, alreadyIn, container.db.profileDao().observeProfile(), library) { selected, already, profile, all ->
            PickerContext(selected, already, profile?.units ?: WeightUnit.Kg, all)
        },
    ) { rows, last, query, filter, context ->
        val trimmed = query.trim()
        ExercisePickerUiState(
            query = query,
            trimmedQuery = trimmed,
            group = filter.first,
            muscle = filter.second,
            results = rows.map { ExercisePickerEntry(it, last[it.id]) },
            selectedIds = context.selected,
            alreadyIn = context.alreadyIn,
            unit = context.unit,
            // `showsCreateRow` — a non-empty query offers "Create «…»" unless the library already has that name.
            showsCreateRow = trimmed.isNotEmpty() && !ExerciseLibrary.hasName(context.library, trimmed),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExercisePickerUiState())

    // MARK: - Input

    /**
     * iOS holds the picker in `@State private var model = ExercisePickerViewModel()`, so every
     * `.sheet` presentation gets a brand-new model: empty query, the All chip, nothing selected.
     * Android keeps the view model in the host's `ViewModelStore`, so the sheet calls this once on
     * presentation to get the same clean slate — and to refresh the `alreadyIn` set the plain init
     * would otherwise freeze at first construction.
     */
    fun reset(alreadyIn: Set<String> = emptySet()) {
        queryInput.value = ""
        groupInput.value = ExerciseLibrary.MuscleGroup.All
        muscleInput.value = null
        selectedInput.value = emptyList()
        alreadyInInput.value = alreadyIn
    }

    fun setQuery(value: String) {
        // Starting a search drops the body-map muscle, so a name typed in full isn't hidden by it.
        if (queryInput.value.isBlank() && value.isNotBlank()) muscleInput.value = null
        queryInput.value = value
    }

    fun setGroup(group: ExerciseLibrary.MuscleGroup) {
        muscleInput.value = null
        groupInput.value = group
    }

    /** The body map's pick; null clears it back to the All chip. */
    fun setMuscle(muscle: String?) {
        if (muscle == null) groupInput.value = ExerciseLibrary.MuscleGroup.All
        muscleInput.value = muscle
    }

    /** `toggle(_:)` — selection is kept in **tap order**, which is the order they are added in. */
    fun toggle(id: String) {
        val current = selectedInput.value
        selectedInput.value = if (current.contains(id)) current - id else current + id
    }

    /**
     * `createExercise(context:)` — inserts a custom exercise named after the query, tagged with one
     * representative muscle of the selected chip, selects it and clears the search so it sorts to
     * the top.
     */
    fun createExercise() {
        val name = queryInput.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val exercise = container.exerciseLibrary.createCustom(name, groupInput.value) ?: return@launch
            selectedInput.value = selectedInput.value + exercise.id
            queryInput.value = ""
        }
    }

    /**
     * The bottom "Add n" bar. Stamps `lastUsedAt` on every chosen exercise, appends them (with
     * prefilled rows) to the target workout when there is one, and reports the ids in tap order.
     */
    fun add(onCommitted: (List<String>) -> Unit) {
        val ids = selectedInput.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            ids.forEach { exerciseDao.markUsed(it, now) }
            if (workoutId != null) {
                var order = (workoutDao.workoutExercises(workoutId).maxOfOrNull { it.order } ?: -1) + 1
                for (id in ids) {
                    WorkoutStarter.append(
                        container = container,
                        workoutId = workoutId,
                        exerciseId = id,
                        order = order,
                        now = now,
                    )
                    order += 1
                }
            }
            selectedInput.value = emptyList()
            onCommitted(ids)
        }
    }

    private data class PickerContext(
        val selected: List<String>,
        val alreadyIn: Set<String>,
        val unit: WeightUnit,
        val library: List<ExerciseEntity>,
    )

    private companion object {
        /** `try? await Task.sleep(for: .milliseconds(200))`. */
        const val FILTER_DEBOUNCE_MS = 200L
    }
}

/** One immutable snapshot of the picker sheet. */
data class ExercisePickerUiState(
    val query: String = "",
    val trimmedQuery: String = "",
    val group: ExerciseLibrary.MuscleGroup = ExerciseLibrary.MuscleGroup.All,
    /** One muscle picked on the body map; the group chips show unselected while it is set. */
    val muscle: String? = null,
    val results: List<ExercisePickerEntry> = emptyList(),
    /** Selected ids in tap order. */
    val selectedIds: List<String> = emptyList(),
    val alreadyIn: Set<String> = emptySet(),
    val unit: WeightUnit = WeightUnit.Kg,
    val showsCreateRow: Boolean = false,
) {
    val selectedCount: Int get() = selectedIds.size

    fun isSelected(id: String): Boolean = selectedIds.contains(id)

    /** `ExercisePickerRow.State` for one row. */
    fun rowState(id: String): ExercisePickerRowState = when {
        alreadyIn.contains(id) -> ExercisePickerRowState.AlreadyIn
        isSelected(id) -> ExercisePickerRowState.Selected
        else -> ExercisePickerRowState.Available
    }
}

/** One result: the exercise and its most recent completed working set, if any. */
data class ExercisePickerEntry(
    val exercise: ExerciseEntity,
    val lastSet: CompletedSetRow?,
)

/** `ExercisePickerRow.State`. */
enum class ExercisePickerRowState { Available, Selected, AlreadyIn }
