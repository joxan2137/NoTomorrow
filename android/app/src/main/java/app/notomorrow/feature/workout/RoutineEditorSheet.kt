package app.notomorrow.feature.workout

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtMenu
import app.notomorrow.designsystem.NtMenuItem
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.di.ntViewModel
import app.notomorrow.feature.settings.StActionRow
import app.notomorrow.feature.settings.StGroup
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * Presents the routine editor for [request] (`null` shows nothing) — iOS's
 * `.sheet(item: $routineEdit) { RoutineEditorSheet(request: $0) }`. [host] keeps the Train tab's
 * editor and the workout detail sheet's apart (both live in the same view-model store).
 *
 * @param onDismiss the sheet closed (Cancel, Save, a swipe, back, or a delete): the host drops its request.
 */
@Composable
fun RoutineEditorPresenter(
    request: RoutineEditRequest?,
    host: String,
    onDismiss: () -> Unit,
) {
    val model = ntViewModel(key = "routineEditor/$host") { container -> RoutineEditorViewModel(container) }
    LaunchedEffect(request) { model.open(request) }
    val state by model.state.collectAsStateWithLifecycle()
    val edit = state?.takeIf { it.request == request } ?: return

    val close = {
        model.open(null)
        onDismiss()
    }
    RoutineEditorSheet(
        edit = edit,
        model = model,
        onDismiss = close,
        onDelete = { routineId ->
            close()
            model.delete(routineId)
        },
    )
}

/**
 * Create or edit a routine (Cancel · New routine / Edit routine · Save): its name, then one card
 * per exercise with sets × reps steppers and a rest menu, Add exercise, and Delete routine at the
 * bottom for an existing one — `NoTomorrow/Features/Workout/RoutineEditorSheet.swift`. Everything
 * edits the model's draft; Save writes it ([RoutineStore]).
 *
 * While the draft has unsaved changes a swipe, a scrim tap or back does not close the sheet: it
 * asks "Discard this routine?", as Cancel does (`.interactiveDismissDisabled(isDirty)`).
 */
@Composable
private fun RoutineEditorSheet(
    edit: RoutineEditState,
    model: RoutineEditorViewModel,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit,
) {
    var showsDiscard by remember { mutableStateOf(false) }
    var showsPicker by remember { mutableStateOf(false) }
    var showsDeleteConfirm by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // The sheet is its own window with its own focus owner (see `WorkoutDetailSheet`).
    val sheetFocusManager = remember { arrayOfNulls<FocusManager>(1) }
    val draft = edit.draft

    NtSheet(
        onDismiss = onDismiss,
        showsHandle = true,
        containerColor = NT.Colors.ground,
        confirmDismiss = {
            if (edit.isDirty) {
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

        RoutineEditorHeader(
            isNew = edit.isNew,
            canSave = edit.canSave && !edit.saving,
            onCancel = {
                focusManager.clearFocus()
                if (edit.isDirty) showsDiscard = true else onDismiss()
            },
            onSave = {
                focusManager.clearFocus()
                model.save {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDismiss()
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .dismissKeyboardOnDrag(focusManager) { true }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = NT.Spacing.section),
        ) {
            RoutineNameField(
                name = draft.name,
                // A blank new routine starts with the keyboard up (`nameFocused = true` on appear).
                autoFocus = edit.isNew && draft.trimmedName.isEmpty(),
                onName = model::setName,
            )
            if (edit.isNameTaken) {
                NtText(
                    text = stringResource(S.routine_nameTaken),
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ember,
                )
            }

            Spacer(Modifier.height(NT.Spacing.section))
            SectionHeader(
                title = stringResource(S.workout_exercises),
                // `Text(verbatim: "\(draft.items.count)")`.
                trailing = if (draft.items.isEmpty()) null else draft.items.size.toString(),
            )
            Spacer(Modifier.height(8.dp))

            if (draft.items.isEmpty()) {
                NtText(
                    text = stringResource(S.routine_empty),
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink2,
                )
            }
            Column(
                modifier = Modifier.animateContentSize(EDITOR_SPEC),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                draft.items.forEachIndexed { index, item ->
                    key(item.id) {
                        RoutineItemCard(
                            item = item,
                            isFirst = index == 0,
                            isLast = index == draft.items.lastIndex,
                            model = model,
                        )
                    }
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

            if (edit.routineId != null) {
                // A red row in its own card, like Settings' destructive rows.
                StGroup(modifier = Modifier.padding(top = 28.dp)) {
                    row {
                        StActionRow(
                            label = stringResource(S.routine_delete),
                            color = NT.Colors.bad,
                            onClick = {
                                focusManager.clearFocus()
                                showsDeleteConfirm = true
                            },
                        )
                    }
                }
            }
        }

        // The prompts and the picker are composed INSIDE the sheet: it is its own window, and one
        // composed beside it lands in the activity's overlay host, behind it.
        if (showsPicker) {
            // The plain picker: it only reports the ids, the draft adds them (nothing is saved yet).
            ExercisePickerSheet(
                onDismiss = { showsPicker = false },
                alreadyIn = draft.exerciseIds,
                onAdd = model::append,
            )
        }

        if (showsDeleteConfirm) {
            val routineId = edit.routineId
            NtActionSheet(
                actions = listOf(
                    NtAlertAction(
                        title = stringResource(S.routine_delete),
                        role = NtAlertRole.Destructive,
                        onClick = { if (routineId != null) onDelete(routineId) },
                    ),
                ),
                cancel = stringResource(S.common_cancel),
                onDismiss = { showsDeleteConfirm = false },
                title = stringResource(S.routine_deleteConfirm),
            )
        }

        if (showsDiscard) {
            NtActionSheet(
                actions = listOf(
                    NtAlertAction(
                        title = stringResource(S.workout_edit_discard),
                        role = NtAlertRole.Destructive,
                        onClick = onDismiss,
                    ),
                ),
                cancel = stringResource(S.workout_edit_keepEditing),
                onDismiss = { showsDiscard = false },
                title = stringResource(S.routine_discardConfirm),
            )
        }
    }
}

/** Cancel · "New routine" / "Edit routine" (centred) · Save — Save is ember when it can save, ink3 and inert otherwise. */
@Composable
private fun RoutineEditorHeader(isNew: Boolean, canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 16.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        NtText(
            text = stringResource(if (isNew) S.routine_new else S.routine_edit),
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
                    color = if (canSave) NT.Colors.ember else NT.Colors.ink3,
                )
            }
        }
    }
}

/** The Settings card with one row: "Name" and a trailing field, placeholder "e.g. Push A". */
@Composable
private fun RoutineNameField(name: String, autoFocus: Boolean, onName: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocus) runCatching { requester.requestFocus() }
    }
    StGroup {
        row {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = NT.Size.control),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtText(stringResource(S.workout_edit_name), style = NT.Fonts.body, color = NT.Colors.ink)
                BasicTextField(
                    value = name,
                    onValueChange = onName,
                    modifier = Modifier.weight(1f).focusRequester(requester),
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
                                    text = stringResource(S.routine_namePlaceholder),
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
    }
}

// MARK: - Exercise card

/**
 * `RoutineItemCard`: one exercise of the routine — name and muscle with a ⋯ menu (move up / down,
 * remove), then the Sets and Reps steppers and the Rest menu.
 */
@Composable
private fun RoutineItemCard(
    item: RoutineItemDraft,
    isFirst: Boolean,
    isLast: Boolean,
    model: RoutineEditorViewModel,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val moveUp = stringResource(S.workout_edit_moveUp)
    val moveDown = stringResource(S.workout_edit_moveDown)
    val removeLabel = stringResource(S.workout_removeExercise)
    val items = buildList {
        if (!isFirst) add(NtMenuItem(title = moveUp, onClick = { model.move(item.id, -1) }, icon = NtIcons.ArrowUp))
        if (!isLast) add(NtMenuItem(title = moveDown, onClick = { model.move(item.id, 1) }, icon = NtIcons.ArrowDown))
        add(NtMenuItem(title = removeLabel, onClick = { model.remove(item.id) }, destructive = true, icon = NtIcons.Trash))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NT.Colors.surface, NtShapes.tile)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                NtText(item.name, style = NT.Fonts.headline, color = NT.Colors.ink, maxLines = 1)
                item.primaryMuscle?.let { muscle ->
                    NtText(
                        text = workoutMuscleName(muscle),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                        maxLines = 1,
                    )
                }
            }
            // `Spacer(minLength: 8)`.
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(NT.Size.control)
                    .ntPlainClickable {
                        focusManager.clearFocus()
                        menuExpanded = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                NtIcon(NtIcons.Ellipsis, size = sfIconSize(18f), tint = NT.Colors.ink2)
                NtMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }, items = items)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoutineStepper(
                label = stringResource(S.workout_sets),
                value = item.sets,
                range = RoutineDraft.SET_RANGE,
                modifier = Modifier.weight(1f),
                onStep = { model.stepSets(item.id, it) },
            )
            RoutineStepper(
                label = stringResource(S.routine_reps),
                value = item.reps,
                range = RoutineDraft.REP_RANGE,
                modifier = Modifier.weight(1f),
                onStep = { model.stepReps(item.id, it) },
            )
            RestMenu(
                seconds = item.restSeconds,
                modifier = Modifier.weight(1f),
                onRest = { model.setRest(item.id, it) },
            )
        }
    }
}

/**
 * "Sets  − 3 +": an eyebrow over a compact stepper on `surface2`. TalkBack reads it as one
 * adjustable control (`.accessibilityAdjustableAction`).
 */
@Composable
private fun RoutineStepper(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier,
    onStep: (Int) -> Unit,
) {
    val canDecrease = value > range.first
    val canIncrease = value < range.last
    Column(
        modifier = modifier
            .background(NT.Colors.surface2, NtShapes.cell)
            .padding(vertical = 6.dp)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = value.toString()
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = range.first.toFloat()..range.last.toFloat(),
                )
                setProgress { target ->
                    when {
                        target > value && canIncrease -> onStep(1)
                        target < value && canDecrease -> onStep(-1)
                        else -> return@setProgress false
                    }
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Eyebrow(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepGlyph(icon = NtIcons.Minus, enabled = canDecrease) { onStep(-1) }
            NtText(
                text = value.toString(),
                modifier = Modifier.widthIn(min = 24.dp),
                style = NT.Fonts.subheadlineBold.tabular(),
                color = NT.Colors.ink,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            StepGlyph(icon = NtIcons.Plus, enabled = canIncrease) { onStep(1) }
        }
    }
}

/** A 30 dp − / + glyph; it greys out at the end of the range. */
@Composable
private fun StepGlyph(icon: NtIcons, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(STEP_GLYPH)
            .pressScale(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(icon, size = sfIconSize(12f), tint = if (enabled) NT.Colors.ink else NT.Colors.ink3)
    }
}

/** "REST / 1:30" on `surface2`; a tap opens the rest menu, the current value ticked. */
@Composable
private fun RestMenu(seconds: Int, modifier: Modifier, onRest: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(S.routine_rest)
    val defaultLabel = stringResource(S.routine_restDefault)
    fun restLabel(value: Int) = if (value <= 0) defaultLabel else Fmt.clock(value)
    val items = RoutineDraft.REST_OPTIONS.map { option ->
        NtMenuItem(
            title = restLabel(option),
            onClick = { onRest(option) },
            icon = if (option == seconds) NtIcons.Checkmark else null,
        )
    }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NT.Colors.surface2, NtShapes.cell)
                // TalkBack reads the merged "Rest, 1:30".
                .ntPlainClickable { expanded = true }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Eyebrow(label)
            Box(Modifier.height(STEP_GLYPH), contentAlignment = Alignment.Center) {
                NtText(
                    text = restLabel(seconds),
                    style = NT.Fonts.subheadlineBold.tabular(),
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
            }
        }
        NtMenu(expanded = expanded, onDismiss = { expanded = false }, items = items)
    }
}

/** The stepper glyphs' hit area, and the rest value's line height to match. */
private val STEP_GLYPH = 30.dp

/** `.animation(.easeInOut(duration: 0.2), value: draft.items.map(\.id))`. */
private val EDITOR_SPEC = tween<androidx.compose.ui.unit.IntSize>(200, easing = NT.Ease.inOut)
