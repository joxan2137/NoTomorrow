package app.notomorrow.feature.workout

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.designsystem.Badge
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.StatTile
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.ntViewModel
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.Instant

/**
 * Presents a finished workout's detail sheet (read, edit, delete) from any screen — iOS's
 * `View.workoutDetailSheet(_:unit:)`. The Train history and the Today "Last session" row both use
 * it; [host] keeps their models apart (both tabs live in the same view-model store).
 *
 * A confirmed delete closes the sheet first and deletes once it is gone, so nothing renders the
 * workout while it leaves.
 *
 * @param workoutId the workout to show; `null` shows nothing.
 * @param onDismiss the sheet closed (Done, a swipe, back, or a delete): the host drops its id.
 */
@Composable
fun WorkoutDetailPresenter(
    workoutId: String?,
    unit: WeightUnit,
    host: String,
    onDismiss: () -> Unit,
) {
    val model = ntViewModel(key = "workoutDetail/$host") { container -> WorkoutEditViewModel(container) }
    LaunchedEffect(workoutId) { model.show(workoutId) }
    val shown by model.shown.collectAsStateWithLifecycle()
    val edit by model.edit.collectAsStateWithLifecycle()
    val workout = shown?.takeIf { it.workout.id == workoutId } ?: return

    WorkoutDetailSheet(
        workout = workout,
        unit = unit,
        edit = edit?.takeIf { it.workoutId == workout.workout.id },
        model = model,
        onDismiss = {
            model.show(null)
            onDismiss()
        },
        onDelete = {
            val id = workout.workout.id
            model.show(null)
            onDismiss()
            model.delete(id)
        },
    )
}

/**
 * Detail of a finished workout: tiles, notes, then every exercise with its completed sets and
 * record badges (`NoTomorrow/Features/Workout/WorkoutDetailSheet.swift`). **Edit** switches the same
 * sheet to the editor (Cancel · Edit workout · Save, [WorkoutEditContent]); Delete lives at the
 * editor's bottom.
 *
 * The sheet shows the drag indicator and paints itself `ground`, matching
 * `.presentationBackground(NT.Colors.ground)` + `.presentationDragIndicator(.visible)`. While the
 * draft has unsaved changes a swipe, a scrim tap or back does not close it: it asks
 * "Discard changes?", as Cancel does.
 */
@Composable
private fun WorkoutDetailSheet(
    workout: WorkoutWithExercises,
    unit: WeightUnit,
    edit: WorkoutEditState?,
    model: WorkoutEditViewModel,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    var showsDiscard by remember { mutableStateOf(false) }
    val focus = remember { SetFieldFocus() }
    // The sheet is its own window with its own focus owner: the manager read out here (the
    // activity's) cannot clear a field focused inside it, so the one the content reads is kept.
    val sheetFocusManager = remember { arrayOfNulls<FocusManager>(1) }
    val haptics = LocalHapticFeedback.current
    // The editor keeps rendering its last draft while it fades out after Save / Cancel.
    val lastEdit = remember { mutableStateOf<WorkoutEditState?>(null) }
    if (edit != null) lastEdit.value = edit

    NtSheet(
        onDismiss = onDismiss,
        showsHandle = true,
        containerColor = NT.Colors.ground,
        confirmDismiss = {
            if (edit?.isDirty == true) {
                sheetFocusManager[0]?.clearFocus()
                showsDiscard = true
                false
            } else {
                true
            }
        },
    ) {
        val focusManager = LocalFocusManager.current
        SideEffect { sheetFocusManager[0] = focusManager }
        Crossfade(
            targetState = edit != null,
            modifier = Modifier.fillMaxWidth().weight(1f),
            animationSpec = tween(200, easing = NT.Ease.inOut),
            label = "workoutDetailMode",
        ) { editing ->
            val state = lastEdit.value
            if (editing && state != null) {
                Column(Modifier.fillMaxSize()) {
                    val canSave = state.canSave(System.currentTimeMillis())
                    WorkoutEditHeader(
                        canSave = canSave,
                        onCancel = {
                            focusManager.clearFocus()
                            if (state.isDirty) showsDiscard = true else model.endEditing()
                        },
                        onSave = {
                            focusManager.clearFocus()
                            model.save { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
                        },
                    )
                    WorkoutEditContent(
                        edit = state,
                        unit = unit,
                        model = model,
                        focus = focus,
                        onDelete = onDelete,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    WorkoutDetailHeader(
                        workout = workout,
                        onEdit = model::beginEditing,
                        onDone = onDismiss,
                    )
                    WorkoutDetailBody(workout = workout, unit = unit, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }

        if (showsDiscard) {
            // `.confirmationDialog("workout.edit.discardConfirm")`: Discard (destructive), Keep
            // editing (cancel). Composed INSIDE the sheet: the sheet is its own window, and a
            // prompt composed beside it lands in the activity's overlay host — behind it, unseen.
            NtActionSheet(
                actions = listOf(
                    NtAlertAction(
                        title = stringResource(S.workout_edit_discard),
                        role = NtAlertRole.Destructive,
                        onClick = model::endEditing,
                    ),
                ),
                cancel = stringResource(S.workout_edit_keepEditing),
                onDismiss = { showsDiscard = false },
                title = stringResource(S.workout_edit_discardConfirm),
            )
        }
    }
}

// MARK: - Read

/** Workout name, then plain "Edit" and "Done"; the long day and the start time under it. */
@Composable
private fun WorkoutDetailHeader(workout: WorkoutWithExercises, onEdit: () -> Unit, onDone: () -> Unit) {
    val startedAt = Instant.ofEpochMilli(workout.workout.startedAt)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // `HStack(alignment: .firstTextBaseline, spacing: 16)` — the title2 name and the body
        // buttons sit on one baseline, not on one centre line.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NtText(
                text = workoutDisplayName(workout.workout.name),
                modifier = Modifier.weight(1f).alignByBaseline(),
                style = NT.Fonts.title2,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            HeaderButton(text = stringResource(S.common_edit), modifier = Modifier.alignByBaseline(), onClick = onEdit)
            HeaderButton(text = stringResource(S.common_done), modifier = Modifier.alignByBaseline(), onClick = onDone)
        }
        NtText(
            text = Fmt.longDay(startedAt) + " · " + Fmt.time(startedAt),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
    }
}

/** A plain body-text button with a 44 dp tall hit area. */
@Composable
private fun HeaderButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = NT.Size.control)
            .ntPlainClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NtText(text = text, style = NT.Fonts.body, color = NT.Colors.ink2)
    }
}

/** Tiles, the PR line, the notes, then every exercise. */
@Composable
private fun WorkoutDetailBody(workout: WorkoutWithExercises, unit: WeightUnit, modifier: Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NT.Spacing.screenH)
            .padding(bottom = NT.Spacing.section),
    ) {
        WorkoutDetailTiles(workout, unit)

        if (workout.prCount > 0) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtIcon(
                    icon = NtIcons.TrophyFill,
                    size = sfIconSize(12f),
                    tint = NT.Colors.ember,
                )
                TabularText(
                    text = stringResource(S.dashboard_prs, workout.prCount),
                    style = NT.Fonts.footnoteBold,
                    color = NT.Colors.ember,
                )
            }
        }

        val notes = workout.workout.notes
        if (notes.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Eyebrow(stringResource(S.workout_edit_notes))
                NtText(text = notes, style = NT.Fonts.subheadline, color = NT.Colors.ink)
            }
        }

        Spacer(Modifier.height(NT.Spacing.section))
        SectionHeader(title = stringResource(S.workout_exercises))
        Spacer(Modifier.height(4.dp))

        if (workout.exercises.isEmpty()) {
            NtText(
                text = stringResource(S.workout_noSetsLogged),
                modifier = Modifier.padding(vertical = 12.dp),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        } else {
            workout.sortedExercises.forEach { item ->
                WorkoutDetailExercise(item = item, unit = unit)
                Hairline()
            }
        }
    }
}

/** Time / Sets / Volume (in the user's unit). */
@Composable
private fun WorkoutDetailTiles(workout: WorkoutWithExercises, unit: WeightUnit) {
    val strings = rememberNtStrings()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            label = stringResource(S.workout_time),
            value = Fmt.duration(
                workoutDuration(workout.workout.startedAt, workout.workout.endedAt),
                strings,
            ),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(S.workout_sets),
            value = workout.completedSetCount.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(S.workout_volume),
            value = Fmt.volume(workout.totalVolumeKg, unit),
            modifier = Modifier.weight(1f),
        )
    }
}

/** Exercise name + one line per completed set with PR / set-record badges. */
@Composable
private fun WorkoutDetailExercise(item: WorkoutExerciseWithSets, unit: WeightUnit) {
    val ordered = item.sortedSets
    val labels = setLabels(ordered)
    val completed = ordered.filter { it.isCompleted }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NtText(
            text = item.exercise?.localizedName().orEmpty(),
            style = NT.Fonts.headline,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        if (completed.isEmpty()) {
            NtText(
                text = stringResource(S.workout_noSetsLogged),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        } else {
            completed.forEach { set -> WorkoutDetailSetLine(set, labels[set.id].orEmpty(), unit) }
        }
    }
}

/** 32 pt line: the numbered chip, "85 × 7", and the record badge. */
@Composable
private fun WorkoutDetailSetLine(set: SetEntryEntity, label: String, unit: WeightUnit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(24.dp).background(NT.Colors.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            TabularText(
                text = label,
                style = NT.Fonts.caption,
                color = NT.Colors.ink2,
            )
        }
        TabularText(
            text = Fmt.set(set.weightKg, set.reps, unit),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink,
        )
        Spacer(Modifier.weight(1f))
        when {
            set.isPR -> Badge(text = stringResource(S.workout_pr))
            set.isSetRecord -> Badge(text = stringResource(S.workout_setRecord), color = NT.Colors.ink2)
        }
    }
}

/**
 * The chip of every set, by set id: the set number for normal sets, counted like the set table
 * (warm-ups don't count); W / D / F glyphs for warm-up, drop and failure — never localized, the
 * same glyphs as the table's kind menu. [sets] is the exercise's rows in order.
 */
internal fun setLabels(sets: List<SetEntryEntity>): Map<Long, String> {
    var number = 0
    return sets.associate { set ->
        if (set.kind != SetKind.Warmup) number += 1
        set.id to if (set.kind == SetKind.Normal) number.toString() else kindLetter(set.kind)
    }
}
