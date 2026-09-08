package app.notomorrow.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Avatar
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.TabularText
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// Bro row pieces
// ─────────────────────────────────────────────────────────────────────────────

/** `NextSessionCard.broLine` — the `(isIn, partnerIsIn)` 2×2 state machine. */
@Composable
internal fun broLine(
    state: DashboardUiState,
    isIn: Boolean,
    isOut: Boolean,
    partnerIsIn: Boolean,
): String {
    val name = state.partnerName.orEmpty()
    return when {
        isIn && partnerIsIn -> stringResource(S.dashboard_bothIn)
        !isIn && partnerIsIn -> stringResource(S.dashboard_broIsIn, name)
        isIn -> if (state.partnerState.isMissedOrCancelled) {
            stringResource(S.dashboard_broOut, name)
        } else {
            stringResource(S.dashboard_youreIn)
        }

        isOut -> stringResource(S.dashboard_youreOut)
        state.partnerState.isMissedOrCancelled -> stringResource(S.dashboard_broOut, name)
        else -> stringResource(S.dashboard_broNotYet, name)
    }
}

@Composable
internal fun BroTimes(state: DashboardUiState, isIn: Boolean, partnerIsIn: Boolean) {
    when {
        isIn && partnerIsIn -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(NT.Colors.good)
            TabularText(
                text = listOfNotNull(state.myTime, state.partnerTime)
                    .joinToString(" · ") { Fmt.time(it) },
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }

        !isIn && partnerIsIn -> state.partnerTime?.let { TimeStamp(it, NT.Colors.good) }
        isIn -> state.myTime?.let { TimeStamp(it, NT.Colors.good) }
        else -> Unit
    }
}

@Composable
internal fun TimeStamp(at: Instant, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color)
        TabularText(text = Fmt.time(at), style = NT.Fonts.footnote, color = NT.Colors.ink2)
    }
}

@Composable
internal fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
internal fun AvatarStack(
    state: DashboardUiState,
    isIn: Boolean,
    isOut: Boolean,
    partnerIsIn: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        ParticipantAvatar(
            initial = " ",
            background = NT.Colors.surface2,
            isIn = isIn,
            isOut = isOut,
            dimmed = !isIn,
        )
        if (state.isPaired) {
            ParticipantAvatar(
                initial = state.partnerName?.takeIf { it.isNotEmpty() } ?: " ",
                background = NT.Colors.surface3,
                isIn = partnerIsIn,
                isOut = state.partnerState.isMissedOrCancelled,
                dimmed = !partnerIsIn,
            )
        }
    }
}

/** 28 dp avatar with a 2 dp `good` / `bad` / `surface` ring drawn **over** the fill. */
@Composable
internal fun ParticipantAvatar(
    initial: String,
    background: Color,
    isIn: Boolean,
    isOut: Boolean,
    dimmed: Boolean,
) {
    Box {
        Avatar(initial = initial, size = 28.dp, background = background, dimmed = dimmed)
        Box(
            Modifier
                .matchParentSize()
                .border(
                    width = 2.dp,
                    color = if (isIn) NT.Colors.good else if (isOut) NT.Colors.bad else NT.Colors.surface,
                    shape = CircleShape,
                )
        )
    }
}
