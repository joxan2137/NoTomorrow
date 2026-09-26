package app.notomorrow.feature.workout

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntOnUserScroll
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * The set table half of `ActiveWorkoutView.swift` — `content(_:)`, `header(_:)` and the
 * `.confirmationDialog`. Split out of `ActiveWorkoutScreen.kt` to keep both files short; the
 * screen owns the state, the transitions and the pill.
 */

/** `content(_:)` — the header row and the exercise rail over the scrolling exercise sections. */
@Composable
internal fun WorkoutTable(
    state: ActiveWorkoutUiState,
    restRunning: Boolean,
    focus: SetFieldFocus,
    scroll: ScrollState,
    onMinimize: () -> Unit,
    onFinish: () -> Unit,
    onAddExercise: () -> Unit,
    onToggleExpanded: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onAddSet: (Long) -> Unit,
    onKind: (Long, app.notomorrow.model.SetKind) -> Unit,
    onWeight: (Long, Double) -> Unit,
    onReps: (Long, Int) -> Unit,
    onToggleSet: (SetRowUi) -> Unit,
    onDeleteSet: (Long) -> Unit,
    onRowAppear: (Long) -> Unit,
    onUseSuggestion: (Long) -> Unit,
    onAddWarmups: (Long) -> Unit,
    onNote: (Long, String) -> Unit,
    onRpe: (Long, Double?) -> Unit,
    onLinkNext: (Long) -> Unit,
    onUnlinkSuperset: (Long) -> Unit,
    onHistory: (Long) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        Header(
            state = state,
            modifier = Modifier
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 8.dp),
            onMinimize = onMinimize,
            onFinish = onFinish,
        )
        if (state.exercises.isNotEmpty()) {
            ExerciseRail(
                state = state,
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 6.dp),
                onSelect = onToggleExpanded,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .dismissKeyboardOnDrag(focusManager) { focus.focused != null }
                // Hoisted by the shell: expanding from the mini bar lands where the user left off.
                .verticalScroll(scroll)
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
                    unit = state.unit,
                    onToggleExpanded = { onToggleExpanded(exercise.id) },
                    onRemove = { onRemove(exercise.id) },
                    onAddSet = { onAddSet(exercise.id) },
                    onKind = onKind,
                    onWeight = onWeight,
                    onReps = onReps,
                    onToggleSet = onToggleSet,
                    onDeleteSet = onDeleteSet,
                    onRowAppear = onRowAppear,
                    onUseSuggestion = { onUseSuggestion(exercise.id) },
                    onAddWarmups = { onAddWarmups(exercise.id) },
                    onNote = { text -> onNote(exercise.id, text) },
                    onRpe = onRpe,
                    onLinkNext = { onLinkNext(exercise.id) },
                    onUnlinkSuperset = { onUnlinkSuperset(exercise.id) },
                    onHistory = { onHistory(exercise.id) },
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

/**
 * The collapse chevron, routine name, ember dot + elapsed clock + "Exercise i of n", and the
 * Finish capsule.
 */
@Composable
private fun Header(
    state: ActiveWorkoutUiState,
    modifier: Modifier,
    onMinimize: () -> Unit,
    onFinish: () -> Unit,
) {
    val now by rememberSecondTicker()
    val elapsed = ((state.endedAt ?: now) - state.startedAt) / 1000.0
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinimizeButton(onClick = onMinimize)
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
                    text = Fmt.elapsed(elapsed),
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
        // `Spacer(minLength: 12)` between the `HStack(spacing: 8)` gaps.
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
 * `rail(_:)` — one 4 dp capsule per exercise (4 dp gaps), filled in ember by its completed share of
 * sets; the current exercise's track is brighter. A tap (16 dp tall target) opens that exercise, as
 * its collapsed row does; the open one stays open.
 */
@Composable
private fun ExerciseRail(
    state: ActiveWorkoutUiState,
    modifier: Modifier,
    onSelect: (Long) -> Unit,
) {
    // "Exercise i of n" is 1-based.
    val current = state.currentExerciseIndex - 1
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.exercises.forEachIndexed { index, exercise ->
            key(exercise.id) {
                val done = exercise.sets.count { it.isCompleted }
                val target = if (exercise.sets.isEmpty()) 0f else done.toFloat() / exercise.sets.size
                // `.animation(.easeOut(duration: 0.3), value: completedSetCount)`.
                val fraction by animateFloatAsState(target, NT.Anim.easeOut30, label = "railFill")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(RAIL_TARGET)
                        .ntPlainClickable(role = Role.Button) {
                            if (exercise.id != state.expandedExerciseId) onSelect(exercise.id)
                        }
                        .semantics { contentDescription = exercise.name },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(RAIL_HEIGHT)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (index == current) 0.24f else 0.10f)),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(NT.Colors.ember))
                    }
                }
            }
        }
    }
}

private val RAIL_HEIGHT = 4.dp
private val RAIL_TARGET = 16.dp

/**
 * The leading `chevron.down`: a 36 dp `surface2` circle in a 44 dp hit area — the rest sheet's
 * close button — pulled 4 dp into the margin so the circle, not the hit area, lines up with the
 * 20 dp screen edge (`.padding(.leading, -(NT.Size.control - 36) / 2)`).
 */
@Composable
private fun MinimizeButton(onClick: () -> Unit) {
    val label = stringResource(S.workout_minimize)
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val inset = ((NT.Size.control - MINIMIZE_CIRCLE) / 2).roundToPx()
                val placeable = measurable.measure(constraints)
                layout(placeable.width - inset, placeable.height) { placeable.place(-inset, 0) }
            }
            .size(NT.Size.control)
            // Named by the chevron's description; TalkBack's default "double-tap to activate"
            // follows it (the title is not an action phrase).
            .pressScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(MINIMIZE_CIRCLE).background(NT.Colors.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(
                icon = NtIcons.ChevronDown,
                size = sfIconSize(15f),
                tint = NT.Colors.ink,
                contentDescription = label,
            )
        }
    }
}

private val MINIMIZE_CIRCLE = 36.dp

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
 * closest behaviour is putting the keyboard down on the first drag of the table (the workout
 * editor's list too).
 *
 * Only a finger's drag counts ([ntOnUserScroll]): the scroll that lifts a low set cell above the
 * opening keyboard reaches the nested-scroll chain as `UserInput` too, and used to clear the focus
 * it was scrolling for — the keyboard closed within a second and kg / reps could not be typed.
 */
@Composable
internal fun Modifier.dismissKeyboardOnDrag(
    focusManager: FocusManager,
    isFocused: () -> Boolean,
): Modifier = ntOnUserScroll { if (isFocused()) focusManager.clearFocus() }
