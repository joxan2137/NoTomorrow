package app.notomorrow.feature.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NtTimeWheel
import app.notomorrow.designsystem.NtWheelPicker
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.feature.settings.StActionRow
import app.notomorrow.feature.settings.StGroup
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Edit mode of the workout detail sheet — `WorkoutEditView.swift`: name, date, start time,
 * duration and notes, then every exercise laid out like the active table (the Previous column left
 * blank, rows never dimmed or locked), Add exercise, and Delete workout at the bottom. Everything
 * edits the model's draft; the sheet's Save writes it.
 *
 * iOS's compact date and time pickers become value pills here: a tap opens the matching wheel
 * inline, under its row, in the details card (the Settings schedule wheel).
 */
@Composable
internal fun WorkoutEditContent(
    edit: WorkoutEditState,
    unit: WeightUnit,
    model: WorkoutEditViewModel,
    focus: SetFieldFocus,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = edit.draft
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    var showsPicker by remember { mutableStateOf(false) }
    var showsDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .dismissKeyboardOnDrag(focusManager) { true }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NT.Spacing.screenH)
            .padding(bottom = NT.Spacing.section),
    ) {
        EditDetails(edit = edit, model = model)
        if (!draft.isTimeValid(System.currentTimeMillis())) {
            NtText(
                text = stringResource(S.workout_edit_endsInFuture),
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                style = NT.Fonts.footnote,
                color = NT.Colors.ember,
            )
        }
        EditNotes(notes = draft.notes, onNotes = model::setNotes, modifier = Modifier.padding(top = 18.dp))

        Spacer(Modifier.height(NT.Spacing.section))
        SectionHeader(title = stringResource(S.workout_exercises))
        Spacer(Modifier.height(4.dp))

        draft.exercises.forEachIndexed { index, exercise ->
            key(exercise.id) {
                WorkoutExerciseSection(
                    exercise = exercise.toSectionUi(),
                    isExpanded = true,
                    hintSetId = null,
                    hintBest = null,
                    focus = focus,
                    unit = unit,
                    modifier = Modifier.animateContentSize(EDIT_SPEC),
                    editing = true,
                    onToggleExpanded = {},
                    // Remove needs no confirmation: Cancel reverts it.
                    onRemove = {
                        focusManager.clearFocus()
                        model.removeExercise(exercise.id)
                    },
                    onAddSet = { model.addSet(exercise.id) },
                    onKind = { setId, kind -> model.setKind(exercise.id, setId, kind) },
                    onWeight = { setId, kg -> model.setWeight(exercise.id, setId, kg) },
                    onReps = { setId, reps -> model.setReps(exercise.id, setId, reps) },
                    onToggleSet = { row ->
                        if (model.toggleDone(exercise.id, row.id)) {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        } else {
                            // Nothing to log without reps: send the user to the reps cell, with the
                            // keyboard up even when that cell kept the focus after back hid it.
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                            focus.request(SetField(row.id, isReps = true), keyboard)
                        }
                    },
                    onDeleteSet = { setId ->
                        focusManager.clearFocus()
                        model.deleteSet(exercise.id, setId)
                    },
                    onMoveUp = if (index > 0) {
                        {
                            focusManager.clearFocus()
                            model.moveExercise(exercise.id, -1)
                        }
                    } else {
                        null
                    },
                    onMoveDown = if (index < draft.exercises.lastIndex) {
                        {
                            focusManager.clearFocus()
                            model.moveExercise(exercise.id, 1)
                        }
                    } else {
                        null
                    },
                )
                Hairline(Modifier.padding(top = 8.dp))
            }
        }

        GhostButton(
            title = stringResource(S.workout_addExercise),
            modifier = Modifier.padding(top = 14.dp),
            icon = NtIcons.Plus,
            onClick = {
                focusManager.clearFocus()
                showsPicker = true
            },
        )

        // A red row in its own card, like Settings' destructive rows.
        StGroup(modifier = Modifier.padding(top = 28.dp)) {
            row {
                StActionRow(
                    label = stringResource(S.workout_edit_delete),
                    color = NT.Colors.bad,
                    onClick = {
                        focusManager.clearFocus()
                        showsDeleteConfirm = true
                    },
                )
            }
        }
    }

    if (showsPicker) {
        // The plain picker: it only reports the ids, the draft adds them (nothing is saved yet).
        ExercisePickerSheet(
            onDismiss = { showsPicker = false },
            alreadyIn = draft.exerciseIds,
            onAdd = model::append,
        )
    }

    if (showsDeleteConfirm) {
        NtActionSheet(
            actions = listOf(
                NtAlertAction(
                    title = stringResource(S.workout_edit_delete),
                    role = NtAlertRole.Destructive,
                    onClick = onDelete,
                ),
            ),
            cancel = stringResource(S.common_cancel),
            onDismiss = { showsDeleteConfirm = false },
            title = stringResource(S.workout_edit_deleteConfirm),
        )
    }
}

/** Cancel · "Edit workout" (centred) · Save — Save is ink when it can save, ink3 and inert otherwise. */
@Composable
internal fun WorkoutEditHeader(canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 20.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        NtText(
            text = stringResource(S.workout_edit_title),
            style = NT.Fonts.headline,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.heightIn(min = NT.Size.control).ntPlainClickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                NtText(text = stringResource(S.common_cancel), style = NT.Fonts.body, color = NT.Colors.ink2)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .heightIn(min = NT.Size.control)
                    .ntPlainClickable(enabled = canSave, onClick = onSave),
                contentAlignment = Alignment.Center,
            ) {
                NtText(
                    text = stringResource(S.common_save),
                    style = NT.Fonts.headline,
                    color = if (canSave) NT.Colors.ink else NT.Colors.ink3,
                )
            }
        }
    }
}

// MARK: - Details

/** Which inline wheel is open under its row. */
private enum class EditWheel { Date, Time }

/** The Settings card: Name, Date, Start time, Duration. */
@Composable
private fun EditDetails(edit: WorkoutEditState, model: WorkoutEditViewModel) {
    val draft = edit.draft
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(draft.startedAt).atZone(zone)
    var open by remember { mutableStateOf<EditWheel?>(null) }
    val focusManager = LocalFocusManager.current
    fun toggle(wheel: EditWheel) {
        focusManager.clearFocus()
        open = if (open == wheel) null else wheel
    }

    StGroup {
        row {
            NameRow(name = draft.name, placeholder = edit.original.name, onName = model::setName)
        }
        row {
            Column {
                PickerRow(
                    label = stringResource(S.workout_edit_date),
                    action = stringResource(S.workout_edit_date_action),
                    value = Fmt.mediumDate(start.toLocalDate()),
                    active = open == EditWheel.Date,
                    onClick = { toggle(EditWheel.Date) },
                )
                InlineWheel(visible = open == EditWheel.Date) {
                    DateWheel(
                        day = start.toLocalDate(),
                        originalDay = Instant.ofEpochMilli(edit.original.startedAt).atZone(zone).toLocalDate(),
                        onDay = model::setDay,
                    )
                }
            }
        }
        row {
            Column {
                PickerRow(
                    label = stringResource(S.workout_edit_startTime),
                    action = stringResource(S.workout_edit_startTime_action),
                    value = Fmt.time(start.toInstant()),
                    active = open == EditWheel.Time,
                    onClick = { toggle(EditWheel.Time) },
                )
                InlineWheel(visible = open == EditWheel.Time) {
                    NtTimeWheel(hour = start.hour, minute = start.minute, onChange = model::setTime)
                }
            }
        }
        row {
            DurationRow(draft = draft, onStep = model::stepDuration)
        }
    }
}

/** "Name" and a trailing field; its placeholder is the saved name, and an empty name keeps it. */
@Composable
private fun NameRow(name: String, placeholder: String, onName: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = NT.Size.control),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(stringResource(S.workout_edit_name), style = NT.Fonts.body, color = NT.Colors.ink)
        BasicTextField(
            value = name,
            onValueChange = onName,
            modifier = Modifier.weight(1f),
            textStyle = NT.Fonts.body.copy(color = NT.Colors.ink, textAlign = TextAlign.End),
            cursorBrush = SolidColor(NT.Colors.ink),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (name.isEmpty()) {
                        NtText(
                            text = placeholder,
                            style = NT.Fonts.body,
                            color = NT.Colors.ink3,
                            maxLines = 1,
                            textAlign = TextAlign.End,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * A label and the value in a compact-picker pill; ember while its wheel is open. [action] is the
 * TalkBack click action, read as "Double-tap to <action>" — an infinitive phrase, not the label.
 */
@Composable
private fun PickerRow(label: String, action: String, value: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = PICKER_ROW_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(label, style = NT.Fonts.body, color = NT.Colors.ink)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        Box(
            modifier = Modifier
                .heightIn(min = NT.Size.control)
                .ntPlainClickable(onClickLabel = action, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            TabularText(
                text = value,
                modifier = Modifier
                    .background(NT.Colors.surface2, NtShapes.cell)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                style = NT.Fonts.body,
                color = if (active) NT.Colors.ember else NT.Colors.ink,
            )
        }
    }
}

/** The wheel under a picker row, cropped to the schedule editor's 180 dp. */
@Composable
private fun InlineWheel(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(200, easing = NT.Ease.inOut)) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(200, easing = NT.Ease.inOut)) + fadeOut(tween(200)),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(WHEEL_HEIGHT).clipToBounds(),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * `DatePicker(in: ...Date.now)` as a wheel: the last two years up to today (never later), plus the
 * saved day when it is older. The time of day stays when the day changes.
 */
@Composable
private fun DateWheel(day: LocalDate, originalDay: LocalDate, onDay: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val first = minOf(originalDay, day, today.minusDays(DATE_WHEEL_DAYS - 1))
    val days = remember(first, today) {
        generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
    }
    NtWheelPicker(
        items = days,
        selectedIndex = days.indexOf(day).coerceAtLeast(0),
        onSelect = { onDay(days[it]) },
        style = NT.Fonts.body,
        label = { Fmt.weekdayShort(it) + ", " + Fmt.mediumDate(it) },
    )
}

/**
 * Duration: − / value / + in 5-minute steps that snap to the grid, 5 min … 12 h. TalkBack reads
 * it as one adjustable control (`.accessibilityAdjustableAction`).
 */
@Composable
private fun DurationRow(draft: WorkoutDraft, onStep: (Int) -> Unit) {
    val strings = rememberNtStrings()
    val label = stringResource(S.workout_edit_duration)
    val value = Fmt.duration(draft.durationMs / 1000.0, strings)
    val minutes = draft.durationMs / WorkoutDraft.MINUTE_MS.toFloat()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PICKER_ROW_HEIGHT)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = value
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = minutes,
                    range = WorkoutDraft.DURATION_MIN_MINUTES.toFloat()..WorkoutDraft.DURATION_MAX_MINUTES.toFloat(),
                )
                setProgress { target ->
                    when {
                        target > minutes && draft.canLengthen -> onStep(1)
                        target < minutes && draft.canShorten -> onStep(-1)
                        else -> return@setProgress false
                    }
                    true
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(label, style = NT.Fonts.body, color = NT.Colors.ink)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        StepButton(icon = NtIcons.Minus, enabled = draft.canShorten) { onStep(-1) }
        NtText(
            text = value,
            modifier = Modifier.widthIn(min = 84.dp),
            style = NT.Fonts.body.tabular(),
            color = NT.Colors.ink,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        StepButton(icon = NtIcons.Plus, enabled = draft.canLengthen, modifier = Modifier.offset(x = 4.dp)) { onStep(1) }
    }
}

/** A 36 dp `surface2` circle in a 44 dp hit area; the glyph greys out at the end of the range. */
@Composable
private fun StepButton(icon: NtIcons, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(NT.Size.control)
            .pressScale(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(STEP_CIRCLE).background(NT.Colors.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(icon, size = sfIconSize(12.5f), tint = if (enabled) NT.Colors.ink else NT.Colors.ink3)
        }
    }
}

// MARK: - Notes

/** "NOTES" and a 3…8 line field on `surface`, placeholder "How did it go?". */
@Composable
private fun EditNotes(notes: String, onNotes: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(stringResource(S.workout_edit_notes), Modifier.padding(start = 16.dp))
        BasicTextField(
            value = notes,
            onValueChange = onNotes,
            modifier = Modifier
                .fillMaxWidth()
                .background(NT.Colors.surface, NtShapes.tile)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = NT.Fonts.body.copy(color = NT.Colors.ink),
            cursorBrush = SolidColor(NT.Colors.ink),
            minLines = 3,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (notes.isEmpty()) {
                        NtText(
                            text = stringResource(S.workout_edit_notesPlaceholder),
                            style = NT.Fonts.body,
                            color = NT.Colors.ink3,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** `.frame(minHeight: 52)` on the picker and duration rows. */
private val PICKER_ROW_HEIGHT = 52.dp

/** The schedule editor's crop of the wheel. */
private val WHEEL_HEIGHT = 180.dp

/** The step buttons' circle. */
private val STEP_CIRCLE = 36.dp

/** How far back the date wheel reaches (the saved day is always in it). */
private const val DATE_WHEEL_DAYS = 730L

/** `.animation(.easeInOut(duration: 0.2))` on the exercise list. */
private val EDIT_SPEC = tween<androidx.compose.ui.unit.IntSize>(200, easing = NT.Ease.inOut)
