package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.effects.BorderBeam
import app.notomorrow.designsystem.effects.OrbSize
import app.notomorrow.designsystem.effects.OrbState
import app.notomorrow.designsystem.effects.ThinkingOrb
import app.notomorrow.util.S

/**
 * `AIScanAnalyzingView` (`AIScanView.swift:120`) — the photo dimmed under a thinking orb and the
 * "looking at your plate" line while the estimate is in flight, with a sunset border beam running
 * round the photo (libraries.dev effects, `designsystem/effects`).
 */
@Composable
fun AIScanAnalyzingView(
    photo: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BorderBeam(
            cornerRadius = NT.Radius.card,
            modifier = Modifier
                .padding(top = 12.dp)
                .padding(horizontal = NT.Spacing.screenH)
                .fillMaxWidth(),
        ) {
            AIScanPhoto(photo = photo, tags = emptyList())
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(NT.Colors.ground.copy(alpha = 0.35f), NtShapes.card),
                contentAlignment = Alignment.Center,
            ) {
                // The 64 pt preset drawn at 80 dp, on a dark disc so the dots read over a bright plate.
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .background(NT.Colors.ground.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ThinkingOrb(state = OrbState.Searching, size = OrbSize.Px64, displaySize = 80.dp)
                }
            }
        }
        NtText(
            text = stringResource(S.fuel_ai_analyzing),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )
    }
}
