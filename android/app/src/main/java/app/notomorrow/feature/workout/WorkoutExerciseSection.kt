package app.notomorrow.feature.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
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
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * One exercise of the active workout — 1:1 port of `WorkoutExerciseSection.swift`.
 * Expanded it is header + column header + the set table + "Add set"; collapsed it is a
 * 60 dp row with the set count and the last time it was done.
 */
@Composable
fun WorkoutExerciseSection(
    exercise: WorkoutExerciseUi,
    isExpanded: Boolean,
    hintSetId: Long?,
    hintBest: SetValue?,
    focus: SetFieldFocus,
    modifier: Modifier = Modifier,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    onAddSet: () -> Unit,
    onKind: (Long, SetKind) -> Unit,
    onWeight: (Long, Double) -> Unit,
    onReps: (Long, Int) -> Unit,
    onToggleSet: (SetRowUi) -> Unit,
) {
    if (isExpanded) {
        Expanded(
            exercise = exercise,
            hintSetId = hintSetId,
            hintBest = hintBest,
            focus = focus,
            modifier = modifier,
            onToggleExpanded = onToggleExpanded,
            onRemove = onRemove,
            onAddSet = onAddSet,
            onKind = onKind,
            onWeight = onWeight,
            onReps = onReps,
            onToggleSet = onToggleSet,
        )
    } else {
        Collapsed(exercise = exercise, modifier = modifier, onClick = onToggleExpanded)
    }
}

// MARK: - Collapsed

@Composable
private fun Collapsed(
    exercise: WorkoutExerciseUi,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val subtitle = listOfNotNull(
        workoutSetCount(exercise.setCount),
        lastLine(exercise),
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
    modifier: Modifier,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    onAddSet: () -> Unit,
    onKind: (Long, SetKind) -> Unit,
    onWeight: (Long, Double) -> Unit,
    onReps: (Long, Int) -> Unit,
    onToggleSet: (SetRowUi) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Header(exercise = exercise, onToggleExpanded = onToggleExpanded, onRemove = onRemove)
        ColumnHeader()
        for (row in exercise.sets) {
            SetRow(
                row = row,
                focus = focus,
                modifier = Modifier.fillMaxWidth(),
                onKind = { kind -> onKind(row.id, kind) },
                onWeight = { value -> onWeight(row.id, value) },
                onReps = { value -> onReps(row.id, value) },
                onToggle = { onToggleSet(row) },
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
                shown.value?.let { (hintRow, best) -> BeatsBestHint(row = hintRow, best = best) }
            }
        }
        AddSetRow(onClick = onAddSet)
    }
}

@Composable
private fun Header(
    exercise: WorkoutExerciseUi,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val subtitle = listOfNotNull(
        exercise.primaryMuscle?.let { workoutMuscleName(it) },
        lastLine(exercise),
    ).joinToString(SEPARATOR)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).ntPlainClickable(onClick = onToggleExpanded),
            // `VStack(spacing: 2)`: Compose already spends that gap on the 17/22 and 13/18 line
            // boxes, so stacking 2 dp on top makes the block 2 dp taller than iOS.
            verticalArrangement = Arrangement.spacedBy(0.dp),
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
                items = listOf(
                    NtMenuItem(
                        title = stringResource(S.workout_removeExercise),
                        onClick = onRemove,
                        destructive = true,
                    ),
                ),
            )
        }
    }
}

/** Set 36 · Previous flexible · kg 60 · Reps 60 · check 48 — the widths are the contract. */
@Composable
private fun ColumnHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(SetTable.spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell(stringResource(S.workout_set), Modifier.width(SetTable.setColumn))
        HeaderCell(stringResource(S.workout_previous), Modifier.weight(1f))
        HeaderCell(UNIT_KG, Modifier.width(SetTable.cell))
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
private fun BeatsBestHint(row: SetRowUi, best: SetValue) {
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
                Fmt.set(row.weightKg, row.reps),
                Fmt.set(best.weightKg, best.reps),
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

/** "Last: 80 kg × 8" — `null` when the exercise has never been done. */
@Composable
private fun lastLine(exercise: WorkoutExerciseUi): String? {
    val last = exercise.last ?: return null
    return stringResource(S.workout_last) + ": " + Fmt.weight(last.weightKg) + " " + Fmt.TIMES + " " + last.reps
}

/** The dot the two subtitle halves are joined with. */
private const val SEPARATOR = " · "

/** `Text(verbatim: "kg")` — the column header is not localized on iOS. */
private const val UNIT_KG = "kg"

/** `spring(response: 0.35, dampingFraction: 0.7)` — the PR hint. */
private val HINT_SPRING_FLOAT = spring<Float>(dampingRatio = 0.7f, stiffness = 322.3f)

/** `.scale(0.9, anchor: .leading)`. */
private val LEADING = TransformOrigin(0f, 0.5f)

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
)
