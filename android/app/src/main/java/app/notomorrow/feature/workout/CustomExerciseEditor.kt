package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.designsystem.Chip
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtFlowLayout
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.appLocale
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import kotlinx.coroutines.launch

/**
 * The picker's custom-exercise actions (long press › Edit exercise / Delete exercise), kept off the
 * composables so they are testable on in-memory tables.
 */
internal object CustomExercises {

    /** `CustomExerciseEditor.muscles` — the main-muscle choices, free-exercise-db values. */
    val muscles = listOf(
        "chest", "lats", "middle back", "lower back", "traps", "shoulders", "biceps", "triceps",
        "forearms", "abdominals", "quadriceps", "hamstrings", "glutes", "calves", "adductors",
        "abductors", "neck",
    )

    /** `CustomExerciseEditor.equipmentOptions`. */
    val equipment = listOf(
        "barbell", "dumbbell", "cable", "machine", "body only", "kettlebells", "bands", "e-z curl bar", "other",
    )

    /** Delete is offered only for a custom exercise that no workout has ever used. */
    fun canDelete(exercise: ExerciseEntity, usedIds: Set<String>): Boolean =
        exercise.isCustom && exercise.id !in usedIds

    /** The edited row — `null` while the name is blank, as Save is disabled then. */
    fun edited(exercise: ExerciseEntity, name: String, muscle: String?, equipment: String?): ExerciseEntity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return exercise.copy(name = trimmed, primaryMuscles = listOfNotNull(muscle), equipment = equipment)
    }

    /** `save()` — the Room flows (picker, history, Progress) pick the change up on their own. */
    suspend fun save(dao: ExerciseDao, exercise: ExerciseEntity, name: String, muscle: String?, equipment: String?): Boolean {
        val row = edited(exercise, name, muscle, equipment) ?: return false
        dao.update(row)
        return true
    }

    /** Deletes [exercise] when it is custom and still in no workout; `false` otherwise. */
    suspend fun delete(exerciseDao: ExerciseDao, workoutDao: WorkoutDao, exercise: ExerciseEntity): Boolean {
        if (!exercise.isCustom || workoutDao.usageCount(exercise.id) > 0) return false
        exerciseDao.deleteCustomById(exercise.id)
        return true
    }
}

/**
 * Edit a custom exercise (long press in the exercise picker) — port of `CustomExerciseEditor.swift`:
 * its name, main muscle and equipment. The equipment matters beyond the label: a barbell exercise's
 * warm-up ramp starts with the empty bar.
 */
@Composable
fun CustomExerciseEditor(exercise: ExerciseEntity, onDismiss: () -> Unit) {
    val dao = LocalAppContainer.current.db.exerciseDao()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val strings = rememberNtStrings()
    val locale = appLocale()
    var name by remember(exercise.id) { mutableStateOf(exercise.name) }
    var muscle by remember(exercise.id) { mutableStateOf(exercise.primaryMuscles.firstOrNull()) }
    var equipment by remember(exercise.id) { mutableStateOf(exercise.equipment) }
    val canSave = name.trim().isNotEmpty()

    NtSheet(onDismiss = onDismiss, showsHandle = true, containerColor = NT.Colors.ground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.heightIn(min = NT.Size.control).ntPlainClickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                NtText(stringResource(S.common_cancel), style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
            }
            NtText(
                text = stringResource(S.customExercise_edit),
                modifier = Modifier.weight(1f),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .heightIn(min = NT.Size.control)
                    .ntPlainClickable(enabled = canSave) {
                        scope.launch {
                            if (CustomExercises.save(dao, exercise, name, muscle, equipment)) onDismiss()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                NtText(
                    text = stringResource(S.common_save),
                    style = NT.Fonts.headline,
                    color = if (canSave) NT.Colors.ember else NT.Colors.ink3,
                    maxLines = 1,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .ntDismissKeyboardOnScroll()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 12.dp, bottom = NT.Spacing.section),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NT.Colors.surface, NtShapes.tile)
                    .padding(horizontal = 16.dp)
                    .heightIn(min = NT.Size.control),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtText(stringResource(S.workout_edit_name), style = NT.Fonts.body, color = NT.Colors.ink, maxLines = 1)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    textStyle = NT.Fonts.body.copy(color = NT.Colors.ink, textAlign = TextAlign.End),
                    singleLine = true,
                    cursorBrush = SolidColor(NT.Colors.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
            }
            Choices(
                title = stringResource(S.customExercise_muscle),
                options = CustomExercises.muscles,
                selection = muscle,
                label = { WorkoutStrings.muscle(it, strings, locale) },
                onSelect = { muscle = it },
            )
            Choices(
                title = stringResource(S.customExercise_equipment),
                options = CustomExercises.equipment,
                selection = equipment,
                label = { WorkoutStrings.equipment(it, strings, locale) },
                onSelect = { equipment = it },
            )
        }
    }
}

/** An eyebrow over a wrap of chips; tapping the selected chip clears it. */
@Composable
private fun Choices(
    title: String,
    options: List<String>,
    selection: String?,
    label: (String) -> String,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(title, Modifier.padding(start = 16.dp))
        NtFlowLayout(spacing = 8.dp) {
            for (option in options) {
                Chip(title = label(option), selected = selection == option) {
                    onSelect(if (selection == option) null else option)
                }
            }
        }
    }
}
