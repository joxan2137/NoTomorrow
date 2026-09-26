package app.notomorrow.feature.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtMenu
import app.notomorrow.designsystem.NtMenuItem
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * One exercise of the active workout — 1:1 port of `WorkoutExerciseSection.swift`.
 * Expanded it is header + suggested weight + column header + the set table + "Add set"; collapsed
 * it is a 60 dp row with the set count and the last time it was done. [onUseSuggestion] is the
 * suggestion's Use.
 *
 * [editing] is the workout editor's section (`WorkoutEditExerciseSection`): always expanded, the
 * header shows the muscle only and its menu offers [onMoveUp] / [onMoveDown] (hidden when null,
 * at the ends) before Remove; rows are never dimmed or locked and the Previous column is blank.
 * Weights are shown in [unit]. [onDeleteSet] adds "Delete set" to every row's kind menu.
 * [onAddWarmups] (the active workout only) adds "Add warm-up sets" to the header menu, [onNote]
 * "Add note" and the note field under the header, [onRpe] the RPE submenu on every set.
 */
@Composable
fun WorkoutExerciseSection(
    exercise: WorkoutExerciseUi,
    isExpanded: Boolean,
    hintSetId: Long?,
    hintBest: SetValue?,
    focus: SetFieldFocus,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    onAddSet: () -> Unit,
    onKind: (Long, SetKind) -> Unit,
    onWeight: (Long, Double) -> Unit,
    onReps: (Long, Int) -> Unit,
    onToggleSet: (SetRowUi) -> Unit,
    onDeleteSet: ((Long) -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onRowAppear: (Long) -> Unit = {},
    onUseSuggestion: () -> Unit = {},
    onAddWarmups: (() -> Unit)? = null,
    onNote: ((String) -> Unit)? = null,
    onRpe: ((Long, Double?) -> Unit)? = null,
) {
    if (isExpanded || editing) {
        Expanded(
            exercise = exercise,
            hintSetId = hintSetId,
            hintBest = hintBest,
            focus = focus,
            unit = unit,
            editing = editing,
            modifier = modifier,
            onToggleExpanded = onToggleExpanded,
            onRemove = onRemove,
            onAddSet = onAddSet,
            onKind = onKind,
            onWeight = onWeight,
            onReps = onReps,
            onToggleSet = onToggleSet,
            onDeleteSet = onDeleteSet,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onRowAppear = onRowAppear,
            onUseSuggestion = onUseSuggestion,
            onAddWarmups = onAddWarmups,
            onNote = onNote,
            onRpe = onRpe,
        )
    } else {
        Collapsed(exercise = exercise, unit = unit, modifier = modifier, onClick = onToggleExpanded)
    }
}

// MARK: - Collapsed

@Composable
private fun Collapsed(
    exercise: WorkoutExerciseUi,
    unit: WeightUnit,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val subtitle = listOfNotNull(
        workoutSetCount(exercise.setCount),
        lastLine(exercise, unit),
    ).joinToString(SEPARATOR)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .ntPlainClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NtText(exercise.name, style = NT.Fonts.headline, color = NT.Colors.ink, maxLines = 1)
            NtText(
                text = subtitle,
                style = NT.Fonts.footnote.tabular(),
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        NtIcon(
            icon = if (exercise.isDone) NtIcons.Checkmark else NtIcons.ChevronRight,
            // The 15 pt SF-symbol box over-pads a chevron, which is far narrower than it is tall;
            // iOS sits the glyph flush with the 20 dp content inset, so shift it back out.
            modifier = Modifier.offset(x = 4.dp),
            size = sfIconSize(15f),
            tint = if (exercise.isDone) NT.Colors.ember else NT.Colors.ink3,
        )
    }
}

// MARK: - Expanded

@Composable
private fun Expanded(
    exercise: WorkoutExerciseUi,
    hintSetId: Long?,
    hintBest: SetValue?,
    focus: SetFieldFocus,
    unit: WeightUnit,
    editing: Boolean,
    modifier: Modifier,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    onAddSet: () -> Unit,
    onKind: (Long, SetKind) -> Unit,
    onWeight: (Long, Double) -> Unit,
    onReps: (Long, Int) -> Unit,
    onToggleSet: (SetRowUi) -> Unit,
    onDeleteSet: ((Long) -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRowAppear: (Long) -> Unit,
    onUseSuggestion: () -> Unit,
    onAddWarmups: (() -> Unit)?,
    onNote: ((String) -> Unit)?,
    onRpe: ((Long, Double?) -> Unit)?,
) {
    // `@State private var showsNote` — "Add note" opens the field before anything is typed.
    var showsNote by remember(exercise.id) { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Header(
            exercise = exercise,
            unit = unit,
            editing = editing,
            onToggleExpanded = onToggleExpanded,
            onRemove = onRemove,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onAddWarmups = onAddWarmups,
            onAddNote = if (onNote != null && exercise.notes.isEmpty() && !showsNote) {
                { showsNote = true }
            } else {
                null
            },
        )
        if (onNote != null) {
            AnimatedVisibility(
                visible = showsNote || exercise.notes.isNotEmpty(),
                enter = fadeIn(SUGGESTION_FADE),
                exit = fadeOut(SUGGESTION_FADE),
            ) {
                // Typing keeps the field up, so clearing a note does not pull it away mid-edit.
                ExerciseNoteField(exercise = exercise, onNote = { text ->
                    showsNote = true
                    onNote(text)
                })
            }
        }
        if (!editing) {
            // `.transition(.opacity)`: Use fades the line out; latch the last suggestion so the
            // exit has something to fade, as SwiftUI keeps the removed view alive.
            val suggestion = exercise.suggestion
            val shown = remember { mutableStateOf(suggestion) }
            if (suggestion != null) shown.value = suggestion
            AnimatedVisibility(
                visible = suggestion != null,
                enter = fadeIn(SUGGESTION_FADE),
                exit = fadeOut(SUGGESTION_FADE),
            ) {
                shown.value?.let { SuggestionRow(suggestion = it, unit = unit, onUse = onUseSuggestion) }
            }
        }
        ColumnHeader(unit = unit, showsPrevious = !editing)
        for (row in exercise.sets) {
            key(row.id) {
                SetRow(
                    row = row,
                    focus = focus,
                    unit = unit,
                    modifier = Modifier.fillMaxWidth(),
                    editing = editing,
                    onKind = { kind -> onKind(row.id, kind) },
                    onWeight = { value -> onWeight(row.id, value) },
                    onReps = { value -> onReps(row.id, value) },
                    onToggle = { onToggleSet(row) },
                    onDelete = onDeleteSet?.let { delete -> { delete(row.id) } },
                    onRpe = onRpe?.let { rpe -> { value -> rpe(row.id, value) } },
                    onAppear = { onRowAppear(row.id) },
                )
                val visible = hintSetId == row.id && hintBest != null
                // `hintSetID` and `hintBest` go nil in the same emission, so the exit would play over
                // an empty box; latch the last pair the hint showed and keep rendering it while the
                // scale/fade runs out, exactly as SwiftUI's transition keeps the removed view alive.
                val shown = remember(row.id) { mutableStateOf<Pair<SetRowUi, SetValue>?>(null) }
                if (visible && hintBest != null) shown.value = row to hintBest
                AnimatedVisibility(
                    visible = visible,
                    enter = scaleIn(HINT_SPRING_FLOAT, initialScale = 0.9f, transformOrigin = LEADING) +
                        fadeIn(HINT_SPRING_FLOAT),
                    exit = scaleOut(HINT_SPRING_FLOAT, targetScale = 0.9f, transformOrigin = LEADING) +
                        fadeOut(HINT_SPRING_FLOAT),
                ) {
                    shown.value?.let { (hintRow, best) -> BeatsBestHint(row = hintRow, best = best, unit = unit) }
                }
            }
        }
        AddSetRow(onClick = onAddSet)
    }
}

/**
 * Name over "muscle · Last: 80 kg × 8" (tap toggles the section) and the "…" menu. The editor's
 * header shows the muscle alone, does not toggle, and its menu also moves the exercise.
 */
@Composable
private fun Header(
    exercise: WorkoutExerciseUi,
    unit: WeightUnit,
    editing: Boolean,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onAddWarmups: (() -> Unit)?,
    onAddNote: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val muscle = exercise.primaryMuscle?.let { workoutMuscleName(it) }
    val subtitle = if (editing) {
        muscle
    } else {
        listOfNotNull(muscle, lastLine(exercise, unit)).joinToString(SEPARATOR)
    }
    val items = buildList {
        onMoveUp?.let { add(NtMenuItem(title = stringResource(S.workout_edit_moveUp), onClick = it)) }
        onMoveDown?.let { add(NtMenuItem(title = stringResource(S.workout_edit_moveDown), onClick = it)) }
        onAddWarmups?.let {
            add(
                NtMenuItem(
                    title = stringResource(S.warmup_add),
                    onClick = it,
                    icon = NtIcons.Flame,
                    enabled = exercise.warmupSteps.isNotEmpty(),
                ),
            )
        }
        onAddNote?.let { add(NtMenuItem(title = stringResource(S.note_add), onClick = it, icon = NtIcons.Pencil)) }
        add(
            NtMenuItem(
                title = stringResource(S.workout_removeExercise),
                onClick = onRemove,
                destructive = true,
            ),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (editing) Modifier else Modifier.ntPlainClickable(onClick = onToggleExpanded)),
            // `VStack(spacing: 2)`: Compose already spends that gap on the 17/22 and 13/18 line
            // boxes, so stacking 2 dp on top makes the block 2 dp taller than iOS.
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            NtText(exercise.name, style = NT.Fonts.headline, color = NT.Colors.ink, maxLines = 1)
            if (subtitle != null) {
                NtText(
                    text = subtitle,
                    style = NT.Fonts.footnote.tabular(),
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(NT.Size.control)
                .ntPlainClickable { menuExpanded = true },
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(NtIcons.Ellipsis, size = sfIconSize(18f), tint = NT.Colors.ink2)
            NtMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                items = items,
            )
        }
    }
}

/**
 * `ExerciseNoteField` — the exercise's note for this workout, saved as it is typed. Its placeholder
 * is the note from last time, so a seat height or grip written once shows up again next session.
 * The text is the field's own from the first frame (`.onAppear { text = exercise.notes }`).
 */
@Composable
private fun ExerciseNoteField(exercise: WorkoutExerciseUi, onNote: (String) -> Unit) {
    var text by remember(exercise.id) { mutableStateOf(exercise.notes) }
    val placeholder = exercise.previousNote?.let { stringResource(S.note_last_s, it) }
        ?: stringResource(S.note_placeholder)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NT.Colors.surface, NtShapes.cell)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NtIcon(NtIcons.Pencil, modifier = Modifier.padding(top = 2.dp), size = sfIconSize(13f), tint = NT.Colors.ink3)
        BasicTextField(
            value = text,
            onValueChange = { new ->
                text = new
                if (new != exercise.notes) onNote(new)
            },
            modifier = Modifier.weight(1f),
            textStyle = NT.Fonts.subheadline.copy(color = NT.Colors.ink),
            cursorBrush = SolidColor(NT.Colors.ink),
            maxLines = 4,
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        NtText(placeholder, style = NT.Fonts.subheadline, color = NT.Colors.ink3, maxLines = 4)
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * `suggestionRow(_:)` — "↑ Try 82.5 kg today" over "8 · 8 · 8 at 80 kg last time", and Use, which
 * puts that weight in the open sets.
 */
@Composable
private fun SuggestionRow(suggestion: WeightSuggestion, unit: WeightUnit, onUse: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(NtIcons.ArrowUp, size = sfIconSize(13f), tint = NT.Colors.ember)
        Column(
            modifier = Modifier.weight(1f),
            // `VStack(spacing: 1)` — the 13/18 line boxes already carry that leading.
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            NtText(
                text = stringResource(S.workout_suggest_try_s, Fmt.weight(suggestion.toKg, unit)),
                style = NT.Fonts.footnoteBold.tabular(),
                color = NT.Colors.ink,
            )
            NtText(
                text = stringResource(
                    S.workout_suggest_last_s_s,
                    suggestion.reps.joinToString(SEPARATOR),
                    Fmt.weight(suggestion.fromKg, unit),
                ),
                style = NT.Fonts.footnote.tabular(),
                color = NT.Colors.ink2,
            )
        }
        // `Spacer(minLength: 8)` between the `HStack(spacing: 10)` gaps.
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .widthIn(min = NT.Size.control)
                .heightIn(min = 36.dp)
                .ntPlainClickable(role = Role.Button, onClick = onUse),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.workout_suggest_use),
                style = NT.Fonts.subheadlineBold,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
    }
}

/**
 * Set 36 · Previous flexible · kg (or lb) 60 · Reps 60 · check 48 — the widths are the contract.
 * The editor leaves the Previous column blank.
 */
@Composable
private fun ColumnHeader(unit: WeightUnit, showsPrevious: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(SetTable.spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell(stringResource(S.workout_set), Modifier.width(SetTable.setColumn))
        if (showsPrevious) {
            HeaderCell(stringResource(S.workout_previous), Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f).height(1.dp))
        }
        // `Text(verbatim: unit.rawValue)` — the column header is not localized on iOS.
        HeaderCell(unit.raw, Modifier.width(SetTable.cell))
        HeaderCell(stringResource(S.workout_reps), Modifier.width(SetTable.cell))
        Spacer(Modifier.width(SetTable.check).height(1.dp))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    NtText(
        text = text,
        modifier = modifier,
        style = NT.Fonts.caption,
        color = NT.Colors.ink2,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
}

/** "82,5 × 9 beats your best set (80 × 8)" on an ember tint. */
@Composable
private fun BeatsBestHint(row: SetRowUi, best: SetValue, unit: WeightUnit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(NT.Colors.emberTint, NtShapes.cell)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(NtIcons.Trophy, size = sfIconSize(12f), tint = NT.Colors.ember)
        NtText(
            text = stringResource(
                S.workout_beatsBest_s_s,
                Fmt.set(row.weightKg, row.reps, unit),
                Fmt.set(best.weightKg, best.reps, unit),
            ),
            style = NT.Fonts.footnoteBold.tabular(),
            color = NT.Colors.ember,
            maxLines = 1,
        )
    }
}

@Composable
private fun AddSetRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = NT.Size.control)
            .ntPlainClickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `sfIconSize`'s 1.25 multiplier renders this plus ~12 % larger than the 14 pt SF symbol.
        NtIcon(NtIcons.Plus, size = sfIconSize(12.5f), tint = NT.Colors.ink2)
        NtText(
            text = stringResource(S.workout_addSet),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
    }
}

// MARK: - Copy

/** "Last: 80 kg × 8" in [unit] — `null` when the exercise has never been done. */
@Composable
private fun lastLine(exercise: WorkoutExerciseUi, unit: WeightUnit): String? {
    val last = exercise.last ?: return null
    return stringResource(S.workout_last) + ": " + Fmt.weight(last.weightKg, unit) + " " + Fmt.TIMES + " " + last.reps
}

/** The dot the two subtitle halves are joined with. */
private const val SEPARATOR = " · "

/** `spring(response: 0.35, dampingFraction: 0.7)` — the PR hint. */
private val HINT_SPRING_FLOAT = spring<Float>(dampingRatio = 0.7f, stiffness = 322.3f)

/** `.scale(0.9, anchor: .leading)`. */
private val LEADING = TransformOrigin(0f, 0.5f)

/** `withAnimation(.easeInOut(duration: 0.2))` around Use — the suggestion fades out. */
private val SUGGESTION_FADE = tween<Float>(200, easing = NT.Ease.inOut)

// MARK: - Section state

/** One exercise section of the active workout. */
data class WorkoutExerciseUi(
    val id: Long,
    val exerciseId: String?,
    val name: String,
    /** Raw free-exercise-db value; localized by the view through `WorkoutStrings.muscle`. */
    val primaryMuscle: String?,
    val restSeconds: Int,
    val setCount: Int,
    val isDone: Boolean,
    /** "Last: 80 kg × 8". */
    val last: SetValue?,
    val sets: List<SetRowUi>,
    /** The suggested weight while it applies (`ActiveWorkoutModel.suggestion(for:)`); never in the editor. */
    val suggestion: WeightSuggestion? = null,
    /** The ramp "Add warm-up sets" would add (`warmupSteps(for:)`); empty disables it. Never in the editor. */
    val warmupSteps: List<WarmupPlan.Step> = emptyList(),
    /** This workout's note on the exercise. */
    val notes: String = "",
    /** Last session's note (`previousNote(for:)`), the note field's placeholder. */
    val previousNote: String? = null,
)
