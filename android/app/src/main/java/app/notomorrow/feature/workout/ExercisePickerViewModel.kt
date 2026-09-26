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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Search + muscle and equipment filters over the exercise library — 1:1 port of
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
    private val selectedInput = MutableStateFlow<List<String>>(emptyList())

    /** The `alreadyIn` of the plain `init(alreadyIn:onAdd:)`; re-set by [reset] on every presentation. */
    private val alreadyInInput = MutableStateFlow(initialAlreadyIn)

    private val equipmentInput = MutableStateFlow(ExerciseEquipment.All)

    /** Starred exercises (`nt.favoriteExercises`). */
    private val favorites = FavoriteExercises.Store.prefs(container.appPrefs)

    /** The equipment chip row; combines with the muscle chips and the search. */
    val equipment: StateFlow<ExerciseEquipment> = equipmentInput.asStateFlow()

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
        groupInput,
    ) { all, query, group -> ExerciseLibrary.filter(all, query, group) }

    /** [results] narrowed by the equipment chip; a chip tap refilters immediately. */
    private val filtered: Flow<List<ExerciseEntity>> =
        combine(results, equipmentInput) { rows, equipment -> ExerciseEquipment.filter(rows, equipment) }

    val state: StateFlow<ExercisePickerUiState> = combine(
        filtered,
        lastSets,
        queryInput,
        groupInput,
        combine(
            selectedInput,
            alreadyIn,
            container.db.profileDao().observeProfile(),
            workoutDao.observeUsedExerciseIds(),
            favorites.ids,
        ) { selected, already, profile, used, favoriteIds ->
            PickerContext(selected, already, profile?.units ?: WeightUnit.Kg, used.toSet(), favoriteIds)
        },
    ) { rows, last, query, group, context ->
        val trimmed = query.trim()
        ExercisePickerUiState(
            query = query,
            trimmedQuery = trimmed,
            group = group,
            results = rows.map { ExercisePickerEntry(it, last[it.id]) },
            selectedIds = context.selected,
            alreadyIn = context.alreadyIn,
            unit = context.unit,
            usedIds = context.used,
            favoriteIds = context.favorites,
            // `showsCreateRow` — any non-empty query offers "Create «…»".
            showsCreateRow = trimmed.isNotEmpty(),
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
        selectedInput.value = emptyList()
        alreadyInInput.value = alreadyIn
        equipmentInput.value = ExerciseEquipment.All
    }

    fun setQuery(value: String) {
        queryInput.value = value
    }

    fun setGroup(group: ExerciseLibrary.MuscleGroup) {
        groupInput.value = group
    }

    /** `toggle(_:)` — selection is kept in **tap order**, which is the order they are added in. */
    fun toggle(id: String) {
        val current = selectedInput.value
        selectedInput.value = if (current.contains(id)) current - id else current + id
    }

    /** The long-press "Add to favorites" / "Remove from favorites". */
    fun toggleFavorite(id: String) {
        viewModelScope.launch { FavoriteExercises.toggle(favorites, id) }
    }

    fun setEquipment(equipment: ExerciseEquipment) {
        equipmentInput.value = equipment
    }

    /**
     * `createExercise(context:)` — inserts a custom exercise named after the query, tagged with one
     * representative muscle of the selected chip and the equipment chip's equipment, selects it
     * and clears the search so it sorts to the top.
     */
    fun createExercise(onCreated: ((String) -> Unit)? = null) {
        val name = queryInput.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val exercise = container.exerciseLibrary.createCustom(
                name,
                groupInput.value,
                equipment = equipmentInput.value.representative,
            ) ?: return@launch
            queryInput.value = ""
            if (onCreated != null) {
                onCreated(exercise.id)
            } else {
                selectedInput.value = selectedInput.value + exercise.id
            }
        }
    }

    /**
     * Single-select ("Replace exercise"): stamps `lastUsedAt` on the tapped exercise and hands it
     * back; nothing is appended to a workout (the caller swaps it in).
     */
    fun pick(id: String, onPicked: (String) -> Unit) {
        viewModelScope.launch {
            exerciseDao.markUsed(id, System.currentTimeMillis())
            onPicked(id)
        }
    }

    /**
     * The long-press Delete exercise: a custom exercise no workout uses leaves the selection and
     * the library (`model.deselect(id)` + `modelContext.delete`).
     */
    fun deleteCustom(exercise: ExerciseEntity) {
        selectedInput.value = selectedInput.value - exercise.id
        viewModelScope.launch { CustomExercises.delete(exerciseDao, workoutDao, exercise) }
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
        val used: Set<String>,
        val favorites: Set<String>,
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
    val results: List<ExercisePickerEntry> = emptyList(),
    /** Selected ids in tap order. */
    val selectedIds: List<String> = emptyList(),
    val alreadyIn: Set<String> = emptySet(),
    val unit: WeightUnit = WeightUnit.Kg,
    val showsCreateRow: Boolean = false,
    /** Exercises in any workout (`exercise.usages`) — a custom one outside them can be deleted. */
    val usedIds: Set<String> = emptySet(),
    /** Starred exercise ids (`FavoriteExercises`). */
    val favoriteIds: Set<String> = emptySet(),
) {
    val selectedCount: Int get() = selectedIds.size

    /** With no search text the favorites among the results sit in their own section on top. */
    val sections: FavoriteExercises.Sections<ExercisePickerEntry>
        get() = FavoriteExercises.sections(results, favoriteIds, trimmedQuery.isNotEmpty()) { it.exercise.id }

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
