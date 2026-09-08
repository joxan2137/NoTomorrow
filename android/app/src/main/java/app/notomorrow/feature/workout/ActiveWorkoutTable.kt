package app.notomorrow.feature.workout

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * The set table half of `ActiveWorkoutView.swift` — `content(_:)`, `header(_:)` and the
 * `.confirmationDialog`. Split out of `ActiveWorkoutScreen.kt` to keep both files short; the
 * screen owns the state, the transitions and the pill.
 */

/** `content(_:)` — the header row over the scrolling exercise sections. */
@Composable
internal fun WorkoutTable(
    state: ActiveWorkoutUiState,
    restRunning: Boolean,
    focus: SetFieldFocus,
    onFinish: () -> Unit,
    onAddExercise: () -> Unit,
    onToggleExpanded: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onAddSet: (Long) -> Unit,
    onKind: (Long, app.notomorrow.model.SetKind) -> Unit,
    onWeight: (Long, Double) -> Unit,
    onReps: (Long, Int) -> Unit,
    onToggleSet: (SetRowUi) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = rememberKeyboardDismissOnDrag(focusManager) { focus.focused != null }
    Column(Modifier.fillMaxSize()) {
        Header(
            state = state,
            modifier = Modifier
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 8.dp),
            onFinish = onFinish,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(dismissKeyboard)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH),
        ) {
            for (exercise in state.exercises) {
                val expanded = exercise.id == state.expandedExerciseId
                WorkoutExerciseSection(
                    exercise = exercise,
                    modifier = Modifier.animateContentSize(EXPAND_SPEC),
                    isExpanded = expanded,
                    hintSetId = state.hintSetId,
                    hintBest = state.hintBest,
                    focus = focus,
                    onToggleExpanded = { onToggleExpanded(exercise.id) },
                    onRemove = { onRemove(exercise.id) },
                    onAddSet = { onAddSet(exercise.id) },
                    onKind = onKind,
                    onWeight = onWeight,
                    onReps = onReps,
                    onToggleSet = onToggleSet,
                )
                Hairline(Modifier.padding(top = if (expanded) 8.dp else 0.dp))
            }
            GhostButton(
                title = stringResource(S.workout_addExercise),
                modifier = Modifier.padding(top = 14.dp),
                icon = NtIcons.Plus,
                onClick = onAddExercise,
            )
            Spacer(Modifier.height(if (restRunning) 96.dp else 24.dp))
        }
    }
}

/** Routine name, ember dot + elapsed clock + "Exercise i of n", and the Finish capsule. */
@Composable
private fun Header(
    state: ActiveWorkoutUiState,
    modifier: Modifier,
    onFinish: () -> Unit,
) {
    val now by rememberSecondTicker()
    val elapsed = ((state.endedAt ?: now) - state.startedAt) / 1000.0
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            // `VStack(spacing: 2)`: the 22/28 title2 line box already carries that leading in
            // Compose, so an explicit 2 dp pushes the meta row ~3 dp below where iOS puts it.
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            NtText(state.name, style = NT.Fonts.title2, color = NT.Colors.ink, maxLines = 1)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(NT.Colors.ember, CircleShape))
                TabularText(
                    text = Fmt.clock(elapsed),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ember,
                )
                if (state.exercises.isNotEmpty()) {
                    NtText(
                        text = MIDDLE_DOT,
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                        maxLines = 1,
                    )
                    TabularText(
                        text = stringResource(
                            S.workout_exerciseOf_n_n,
                            state.currentExerciseIndex,
                            state.exercises.size,
                        ),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .height(NT.Size.control)
                .pressScale(onClick = onFinish)
                .background(NT.Colors.surface2, CircleShape)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.workout_finish),
                style = NT.Fonts.subheadlineBold,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
    }
}

/**
 * `.confirmationDialog("workout.finishConfirm")` — on iOS 26 an anchored glass card with
 * capsule rows and no scrim (`docs/android-glass.md` §1.7). Discard only shows while nothing
 * has been logged.
 */
@Composable
internal fun FinishDialog(
    canDiscard: Boolean,
    onDismiss: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
) {
    val actions = buildList {
        add(NtAlertAction(title = stringResource(S.workout_finish), onClick = onFinish))
        if (canDiscard) {
            add(
                NtAlertAction(
                    title = stringResource(S.workout_discard),
                    role = NtAlertRole.Destructive,
                    onClick = onDiscard,
                ),
            )
        }
    }
    NtActionSheet(
        actions = actions,
        cancel = stringResource(S.common_cancel),
        onDismiss = onDismiss,
        title = stringResource(S.workout_finishConfirm),
    )
}

/** `.animation(.easeInOut(duration: 0.2), value: expandedExerciseID)`. */
private val EXPAND_SPEC =
    tween<androidx.compose.ui.unit.IntSize>(200, easing = NT.Ease.inOut)

/** `Text(verbatim: "·")`. */
private const val MIDDLE_DOT = "·"

/**
 * `.scrollDismissesKeyboard(.interactively)` — Compose has no interactive variant, so the
 * closest behaviour is putting the keyboard down on the first drag of the table.
 */
@Composable
private fun rememberKeyboardDismissOnDrag(
    focusManager: FocusManager,
    isFocused: () -> Boolean,
): NestedScrollConnection = remember(focusManager) {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f && isFocused()) {
                focusManager.clearFocus()
            }
            return Offset.Zero
        }
    }
}
