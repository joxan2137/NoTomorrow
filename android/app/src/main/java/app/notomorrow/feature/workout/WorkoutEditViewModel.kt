package app.notomorrow.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.di.AppContainer
import app.notomorrow.model.SetKind
import app.notomorrow.service.RoutineSeeder
import app.notomorrow.service.localizedName
import app.notomorrow.util.LocaleProvider
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The finished-workout detail sheet's model — the workout on show, and the editor's draft
 * (`WorkoutEditModel.swift` plus the presenter half of `WorkoutDetailSheet.swift`).
 *
 * The workout is observed by id, so the sheet follows a save (a moved date, a rename) and closes
 * by itself if the row goes. Edit mode is [edit]: the untouched original and the draft, and
 * nothing reaches Room before [save] — Cancel is free. The view model outlives the sheet (it is
 * scoped to the tab shell), so [beginEditing] always rebuilds the draft from the stored workout.
 *
 * Weights reach it in kg: the set cells parse the user's unit before calling in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutEditViewModel(
    private val stores: WorkoutEditor.Stores,
    private val profileDao: ProfileDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: () -> Locale = { LocaleProvider.current() },
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    constructor(container: AppContainer) : this(
        stores = WorkoutEditor.Stores.of(container),
        profileDao = container.db.profileDao(),
    )

    private val workoutDao = stores.workoutDao
    private val shownId = MutableStateFlow<String?>(null)

    /** The finished workout on show, re-read after every write; `null` once it is gone. */
    val shown: StateFlow<WorkoutWithExercises?> = shownId
        .flatMapLatest { id -> if (id == null) flowOf(null) else workoutDao.observeWorkoutWithExercises(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _edit = MutableStateFlow<WorkoutEditState?>(null)

    /** Edit mode: `null` while reading. */
    val edit: StateFlow<WorkoutEditState?> = _edit.asStateFlow()

    /** Ids for rows added in the editor: negative, so they never meet a saved row's id. */
    private var nextTempId = -1L

    private fun tempId(): Long = nextTempId--

    /**
     * Bumped by every [show] that changes the workout on show (closing included), so a draft still
     * loading for a sheet that has closed since is dropped instead of reappearing when the sheet
     * opens again.
     */
    private var presentation = 0L

    /**
     * Presents [workoutId] (`null` closes). A fresh presentation — another workout, or the sheet
     * opened again after it closed — starts in read mode; the same workout shown again while it is
     * up (the sheet recomposed) keeps its edit.
     */
    fun show(workoutId: String?) {
        if (shownId.value != workoutId) {
            presentation += 1
            _edit.value = null
        } else if (_edit.value?.workoutId != workoutId) {
            _edit.value = null
        }
        shownId.value = workoutId
    }

    // MARK: - Edit mode

    /**
     * Edit: the draft starts as a copy of the stored workout. The copy is dropped when the sheet
     * closed or moved to another workout while it was being read.
     */
    fun beginEditing() {
        val id = shownId.value ?: return
        val shownAs = presentation
        viewModelScope.launch {
            val graph = workoutDao.workoutWithExercises(id) ?: return@launch
            if (presentation != shownAs || shownId.value != id) return@launch
            val draft = graph.toDraft(locale())
            nextTempId = minOf(-1L, draft.lowestId - 1)
            _edit.value = WorkoutEditState(workoutId = id, original = draft, draft = draft)
        }
    }

    /** Cancel (or a discard): back to read mode, the draft dropped. */
    fun endEditing() {
        _edit.value = null
    }

    private inline fun update(change: (WorkoutDraft) -> WorkoutDraft) {
        val current = _edit.value ?: return
        _edit.value = current.copy(draft = change(current.draft))
    }

    fun setName(name: String) = update { it.copy(name = name) }

    fun setNotes(notes: String) = update { it.copy(notes = notes) }

    fun setDay(day: LocalDate) = update { it.withDay(day, zone) }

    fun setTime(hour: Int, minute: Int) = update { it.withTime(hour, minute, zone) }

    fun stepDuration(direction: Int) = update { it.steppingDuration(direction) }

    fun setKind(exerciseId: Long, setId: Long, kind: SetKind) =
        update { draft -> draft.updatingSet(setId, exerciseId) { it.copy(kind = kind) } }

    fun setWeight(exerciseId: Long, setId: Long, weightKg: Double) =
        update { draft -> draft.updatingSet(setId, exerciseId) { it.copy(weightKg = weightKg) } }

    fun setReps(exerciseId: Long, setId: Long, reps: Int) =
        update { draft -> draft.updatingSet(setId, exerciseId) { it.copy(reps = reps) } }

    /** Ticks or unticks a row. `false` = refused (no reps): the sheet sends the user to the reps cell. */
    fun toggleDone(exerciseId: Long, setId: Long): Boolean {
        val current = _edit.value ?: return true
        val toggled = current.draft.togglingDone(setId, exerciseId) ?: return false
        _edit.value = current.copy(draft = toggled)
        return true
    }

    fun addSet(exerciseId: Long) = update { it.addingSet(exerciseId, tempId()) }

    fun deleteSet(exerciseId: Long, setId: Long) = update { it.deletingSet(setId, exerciseId) }

    fun removeExercise(exerciseId: Long) = update { it.removingExercise(exerciseId) }

    fun moveExercise(exerciseId: Long, offset: Int) = update { it.movingExercise(exerciseId, offset) }

    /**
     * `append(_:editing:context:)` — the exercises picked in the exercise picker, each with one
     * ticked row from the first working set of its last finished session (else an empty row) and
     * the user's default rest (heavy compounds a little longer).
     */
    fun append(exerciseIds: List<String>) {
        val workoutId = _edit.value?.workoutId ?: return
        viewModelScope.launch {
            val defaultRest = WorkoutStarter.defaultRestSeconds(profileDao)
            for (exerciseId in exerciseIds) {
                val exercise = stores.exerciseDao.byId(exerciseId) ?: continue
                val template = WorkoutStarter.lastCompletedSets(workoutDao, exerciseId, excludingWorkoutId = workoutId)
                    .firstOrNull()
                    ?.let { SetValue(it.weightKg, it.reps) }
                update {
                    it.appendingExercise(
                        id = tempId(),
                        setId = tempId(),
                        exerciseId = exerciseId,
                        name = exercise.localizedName(locale()),
                        primaryMuscle = exercise.primaryMuscles.firstOrNull(),
                        restSeconds = RoutineSeeder.restSeconds(exerciseId, defaultRest),
                        template = template,
                    )
                }
            }
        }
    }

    /**
     * Save: writes the draft (`WorkoutEditor.save`) and returns to read mode. Refused while nothing
     * changed or the workout would end in the future. [onSaved] runs once the write landed; a write
     * that fails (as `try? context.save()` would) leaves the draft up to try again or cancel.
     */
    fun save(onSaved: () -> Unit = {}) {
        val current = _edit.value ?: return
        if (current.saving || !current.canSave(clock())) return
        _edit.value = current.copy(saving = true)
        viewModelScope.launch {
            val written = runCatching {
                WorkoutEditor.save(current.workoutId, current.draft, stores, zone, LocalDate.now(zone))
            }
            if (written.isFailure) {
                if (_edit.value?.workoutId == current.workoutId) _edit.value = _edit.value?.copy(saving = false)
                return@launch
            }
            if (_edit.value?.workoutId == current.workoutId) _edit.value = null
            onSaved()
        }
    }

    /**
     * Delete workout, confirmed: the sheet has already closed ([show] with `null`), so nothing
     * renders the workout while it goes.
     */
    fun delete(workoutId: String) {
        if (_edit.value?.workoutId == workoutId) _edit.value = null
        viewModelScope.launch { runCatching { WorkoutEditor.delete(workoutId, stores, zone, LocalDate.now(zone)) } }
    }
}

/** Edit mode of one workout: the untouched original and the draft being edited. */
data class WorkoutEditState(
    val workoutId: String,
    val original: WorkoutDraft,
    val draft: WorkoutDraft,
    /** A save is being written; a second tap is ignored. */
    val saving: Boolean = false,
) {
    val isDirty: Boolean get() = draft != original

    fun canSave(now: Long): Boolean = isDirty && draft.isTimeValid(now)
}
