package app.notomorrow.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.di.AppContainer
import app.notomorrow.service.localizedName
import app.notomorrow.util.LocaleProvider
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the routine editor opens on (`RoutineEditRequest` in `RoutineEditorSheet.swift`): an
 * existing routine, a blank new one, or a new one from a finished workout ("Save as routine").
 * iOS builds the workout's draft before presenting; here the view model reads it, so the request
 * carries only the id.
 */
sealed interface RoutineEditRequest {
    data class Edit(val routineId: String) : RoutineEditRequest

    data object New : RoutineEditRequest

    data class FromWorkout(val workoutId: String) : RoutineEditRequest
}

/**
 * The routine editor's model — the `@State` half of `RoutineEditorSheet.swift`: the draft being
 * edited, the untouched original, and the names Save must not reuse. Nothing reaches Room before
 * [save] (or [delete]), so Cancel is free.
 *
 * The view model outlives the sheet (it is scoped to the host), so every [open] rebuilds the draft
 * from the store, and a load still running for a sheet that has closed since is dropped.
 */
class RoutineEditorViewModel(
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
    private val workoutDao: WorkoutDao,
    private val profileDao: ProfileDao,
    private val locale: () -> Locale = { LocaleProvider.current() },
) : ViewModel() {

    constructor(container: AppContainer) : this(
        routineDao = container.db.routineDao(),
        exerciseDao = container.db.exerciseDao(),
        workoutDao = container.db.workoutDao(),
        profileDao = container.db.profileDao(),
    )

    private val store = RoutineStore(routineDao, exerciseDao)

    private val _state = MutableStateFlow<RoutineEditState?>(null)

    /** The editor on show; `null` while nothing is (or while its draft loads). */
    val state: StateFlow<RoutineEditState?> = _state.asStateFlow()

    private var request: RoutineEditRequest? = null

    /** Bumped by every [open] that changes the request, so a stale load is dropped. */
    private var presentation = 0L

    /** Presents [next] (`null` closes). The same request again (the sheet recomposed) keeps its draft. */
    fun open(next: RoutineEditRequest?) {
        if (next == request && (next == null || _state.value != null)) return
        presentation += 1
        request = next
        _state.value = null
        next ?: return
        val shownAs = presentation
        viewModelScope.launch {
            val loaded = load(next) ?: return@launch
            if (presentation == shownAs) _state.value = loaded
        }
    }

    private suspend fun load(request: RoutineEditRequest): RoutineEditState? = when (request) {
        is RoutineEditRequest.Edit -> routineDao.routineWithItems(request.routineId)?.let { routine ->
            val draft = RoutineStore.draft(of = routine, locale = locale())
            RoutineEditState(request, request.routineId, draft, draft, store.names(excludingId = request.routineId))
        }
        RoutineEditRequest.New -> RoutineEditState(request, null, RoutineDraft(), RoutineDraft(), store.names())
        is RoutineEditRequest.FromWorkout -> workoutDao.workoutWithExercises(request.workoutId)?.let { workout ->
            val names = store.names()
            val draft = RoutineStore.draft(
                from = workout,
                defaultRest = WorkoutStarter.defaultRestSeconds(profileDao),
                takenNames = names,
                locale = locale(),
            )
            RoutineEditState(request, null, draft, draft, names)
        }
    }

    private inline fun update(change: (RoutineDraft) -> RoutineDraft) {
        val current = _state.value ?: return
        _state.value = current.copy(draft = change(current.draft))
    }

    fun setName(name: String) = update { it.copy(name = name) }

    fun remove(itemId: String) = update { it.removing(itemId) }

    fun move(itemId: String, offset: Int) = update { it.moving(itemId, offset) }

    fun stepSets(itemId: String, delta: Int) = update { it.steppingSets(itemId, delta) }

    fun stepReps(itemId: String, delta: Int) = update { it.steppingReps(itemId, delta) }

    fun setRest(itemId: String, seconds: Int) = update { it.settingRest(itemId, seconds) }

    /** The exercises picked in the exercise picker, each a 3 × 8 line with the default rest. */
    fun append(exerciseIds: List<String>) {
        val shownAs = presentation
        viewModelScope.launch {
            val found = exerciseDao.byIds(exerciseIds).associateBy { it.id }
            if (presentation != shownAs) return@launch
            for (id in exerciseIds) {
                val exercise = found[id] ?: continue
                update {
                    it.appending(
                        RoutineItemDraft.of(
                            exerciseId = exercise.id,
                            name = exercise.localizedName(locale()),
                            primaryMuscle = exercise.primaryMuscles.firstOrNull(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Save: writes the draft ([RoutineStore.save]) and closes. Refused while the name is empty or
     * taken, or the routine has no exercise. [onSaved] runs once the write landed; a failed write
     * leaves the draft up to try again or cancel.
     */
    fun save(onSaved: () -> Unit = {}) {
        val current = _state.value ?: return
        if (current.saving || !current.canSave) return
        _state.value = current.copy(saving = true)
        val shownAs = presentation
        viewModelScope.launch {
            val written = runCatching { store.save(current.draft, current.routineId) }
            if (presentation != shownAs) return@launch
            if (written.isFailure) {
                _state.value = _state.value?.copy(saving = false)
                return@launch
            }
            onSaved()
        }
    }

    /** Delete routine, confirmed: the sheet has already closed ([open] with `null`). */
    fun delete(routineId: String) {
        viewModelScope.launch { runCatching { store.delete(routineId) } }
    }
}

/** The editor of one routine: the untouched original, the draft, and the other routines' names. */
data class RoutineEditState(
    val request: RoutineEditRequest,
    /** `null` for a new routine. */
    val routineId: String?,
    val original: RoutineDraft,
    val draft: RoutineDraft,
    val otherNames: List<String>,
    /** A save is being written; a second tap is ignored. */
    val saving: Boolean = false,
) {
    val isNew: Boolean get() = routineId == null

    /** A new routine with exercises counts as changed even when it came prefilled from a workout. */
    val isDirty: Boolean get() = draft != original || isNew && draft.items.isNotEmpty()

    val isNameTaken: Boolean get() = draft.isNameTaken(otherNames)

    val canSave: Boolean get() = draft.canSave(otherNames)
}
