package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ProgressRing
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.designsystem.tabular
import app.notomorrow.rest.RestTimerState
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * Floating rest pill above the bottom edge of the active workout — 1:1 port of
 * `RestPillView.swift`: draining ring, mm:ss, +15, Skip.
 *
 * Time is derived from [RestTimerState.endAt] on the shared 1 s ticker; the pill runs no
 * timer of its own. This is the app's **only** shadow.
 */
@Composable
fun RestPill(
    state: RestTimerState,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onPlus15: () -> Unit,
    onSkip: () -> Unit,
) {
    val now by rememberSecondTicker()
    val remaining = state.remaining(now)
    val fraction = state.fractionRemaining(now)

    Row(
        modifier = modifier
            .height(56.dp)
            .dropShadow(CircleShape, PILL_SHADOW)
            .background(NT.Colors.surface, CircleShape)
            .border(1.dp, NT.Colors.hairline, CircleShape)
            .padding(start = 10.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).ntPlainClickable(onClick = onTap),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(progress = fraction, modifier = Modifier.size(36.dp), lineWidth = 3.dp)
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Eyebrow(stringResource(S.timer_rest))
                TabularText(Fmt.clock(remaining), style = NT.Fonts.headline, color = NT.Colors.ink)
            }
            Spacer(Modifier.weight(1f))
        }

        PillAction(
            title = PLUS_15,
            background = NT.Colors.surface2,
            tint = NT.Colors.ink,
            tabular = true,
            onClick = onPlus15,
        )
        PillAction(
            title = stringResource(S.common_skip),
            background = NT.Colors.ink,
            tint = NT.Colors.onPrimary,
            tabular = false,
            onClick = onSkip,
        )
    }
}

/** 36 dp capsule action inside the pill: "+15" and "Skip". */
@Composable
private fun PillAction(
    title: String,
    background: Color,
    tint: Color,
    tabular: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .pressScale(onClick = onClick)
            .background(background, CircleShape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        NtText(
            text = title,
            style = if (tabular) NT.Fonts.subheadlineBold.tabular() else NT.Fonts.subheadlineBold,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * `.shadow(color: .black.opacity(0.5), radius: 12, y: 8)`.
 *
 * SwiftUI's `radius` is a Gaussian sigma; Skia turns a `BlurMaskFilter` radius into
 * `0.57735 * r + 0.5`, so 12 becomes ≈ 20 dp here (research §5.3).
 */
private val PILL_SHADOW = Shadow(
    radius = 20.dp,
    color = Color.Black.copy(alpha = 0.5f),
    offset = DpOffset(0.dp, 8.dp),
)

/** `Text(verbatim: "+15")` — never localized. */
private const val PLUS_15 = "+15"
