package app.notomorrow.feature.workout

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.service.localizedName
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * One routine: name, "5 exercises · Bench, Press, Raise", and a round play button
 * (`NoTomorrow/Features/Workout/RoutineRow.swift`).
 */
@Composable
fun RoutineRow(
    routine: RoutineRowItem,
    onStart: () -> Unit,
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
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
        // `Spacer(minLength: 8)` between two 12 pt `HStack` gaps — 32 pt of clearance in total.
        Spacer(Modifier.width(8.dp))
        StartPlayButton(routineName = routine.name, onClick = onStart)
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
