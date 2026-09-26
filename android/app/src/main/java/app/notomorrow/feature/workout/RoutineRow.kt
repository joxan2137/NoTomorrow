package app.notomorrow.feature.workout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtMenu
import app.notomorrow.designsystem.NtMenuItem
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.service.localizedName
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * One routine: name, "5 exercises · Bench, Press, Raise", and a round play button
 * (`NoTomorrow/Features/Workout/RoutineRow.swift`). Tapping the text opens the routine editor
 * ([RoutineMenuActions.onEdit]); a long press opens the routine menu (iOS's `.contextMenu`).
 */
@Composable
fun RoutineRow(
    routine: RoutineRowItem,
    onStart: () -> Unit,
    menu: RoutineMenuActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SwiftUI's `HStack` hands the fixed-size `StartPlayButton` its ideal width first and only
        // then squeezes the `VStack`; a Compose `Row` measures the unweighted text column against the
        // full width and leaves the button nothing, so the text column carries the weight instead.
        RoutineMenuBox(menu = menu, modifier = Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                NtText(
                    text = routine.name,
                    style = NT.Fonts.headline,
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
                NtText(
                    text = routineSubtitle(routine),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                    maxLines = 2,
                )
            }
        }
        // `Spacer(minLength: 8)` between two 12 pt `HStack` gaps — 32 pt of clearance in total.
        Spacer(Modifier.width(8.dp))
        StartPlayButton(routineName = routine.name, onClick = onStart)
    }
}

/**
 * The long-press actions on a routine (`TrainView.routineMenu`): Edit, Duplicate, Share (as text,
 * [RoutineShare]), Move up / down, Delete. A `null` move means the routine is already at that end
 * of the list.
 */
class RoutineMenuActions(
    val onEdit: () -> Unit,
    val onDuplicate: () -> Unit,
    val onShare: () -> Unit,
    val onMoveUp: (() -> Unit)?,
    val onMoveDown: (() -> Unit)?,
    val onDelete: () -> Unit,
)

/**
 * [content] with the routine menu: a tap edits (when [tapEdits]), a long press opens the menu over
 * it with the long-press haptic, and TalkBack gets the menu's entries as custom actions — the
 * Android reading of iOS's `.contextMenu { routineMenu(routine) }`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoutineMenuBox(
    menu: RoutineMenuActions,
    modifier: Modifier = Modifier,
    tapEdits: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val editLabel = stringResource(S.routine_edit)
    val duplicateLabel = stringResource(S.routine_duplicate)
    val shareLabel = stringResource(S.routine_share)
    val moveUpLabel = stringResource(S.workout_edit_moveUp)
    val moveDownLabel = stringResource(S.workout_edit_moveDown)
    val deleteLabel = stringResource(S.routine_delete)
    val items = listOfNotNull(
        NtMenuItem(title = editLabel, onClick = menu.onEdit, icon = NtIcons.Pencil),
        NtMenuItem(title = duplicateLabel, onClick = menu.onDuplicate, icon = NtIcons.PlusSquareOnSquare),
        NtMenuItem(title = shareLabel, onClick = menu.onShare, icon = NtIcons.SquareAndArrowUp),
        menu.onMoveUp?.let { NtMenuItem(title = moveUpLabel, onClick = it, icon = NtIcons.ArrowUp) },
        menu.onMoveDown?.let { NtMenuItem(title = moveDownLabel, onClick = it, icon = NtIcons.ArrowDown) },
        NtMenuItem(title = deleteLabel, onClick = menu.onDelete, destructive = true, icon = NtIcons.Trash),
    )
    val interaction = remember { MutableInteractionSource() }
    val openMenu = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        expanded = true
    }
    Box(modifier) {
        Box(
            modifier = Modifier
                .then(
                    if (tapEdits) {
                        Modifier.combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            // `.accessibilityHint(Text("routine.edit"))`.
                            onClickLabel = editLabel,
                            onClick = menu.onEdit,
                            onLongClick = openMenu,
                        )
                    } else {
                        // Only the long press: the card's own buttons keep their taps.
                        Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { openMenu() }) }
                    },
                )
                .semantics {
                    customActions = listOfNotNull(
                        if (tapEdits) null else CustomAccessibilityAction(editLabel) { menu.onEdit(); true },
                        CustomAccessibilityAction(duplicateLabel) { menu.onDuplicate(); true },
                        CustomAccessibilityAction(shareLabel) { menu.onShare(); true },
                        menu.onMoveUp?.let { action -> CustomAccessibilityAction(moveUpLabel) { action(); true } },
                        menu.onMoveDown?.let { action -> CustomAccessibilityAction(moveDownLabel) { action(); true } },
                        CustomAccessibilityAction(deleteLabel) { menu.onDelete(); true },
                    )
                },
        ) { content() }
        NtMenu(expanded = expanded, onDismiss = { expanded = false }, items = items)
    }
}

/** `"\(count) · \(first three names)"`, or the bare count when the routine has no exercises left. */
@Composable
private fun routineSubtitle(routine: RoutineRowItem): String {
    val count = stringResource(NtKeys.exerciseCount(routine.exerciseCount), routine.exerciseCount)
    val preview = routine.preview.joinToString(", ") { it.localizedName() }
    return if (preview.isEmpty()) count else "$count · $preview"
}

/**
 * `StartPlayButton` (`RoutineRow.swift`): a 40 dp `surface2` circle with an ink `play.fill`, starting
 * the routine beside it. TalkBack reads "Start workout, Push A".
 */
@Composable
fun StartPlayButton(
    routineName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(S.workout_start) + ", " + routineName
    Box(
        modifier = modifier
            .size(PLAY_BUTTON)
            .pressScale(onClick = onClick)
            .background(NT.Colors.surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(
            icon = NtIcons.PlayFill,
            size = sfIconSize(14f),
            tint = NT.Colors.ink,
            contentDescription = label,
        )
    }
}

private val PLAY_BUTTON = 40.dp
