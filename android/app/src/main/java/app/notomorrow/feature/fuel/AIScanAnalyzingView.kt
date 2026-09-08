package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtSpinner
import app.notomorrow.designsystem.NtText
import app.notomorrow.util.S

/**
 * `AIScanAnalyzingView` (`AIScanView.swift:120`) — the photo dimmed under a spinner and the
 * "looking at your plate" line while the estimate is in flight.
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
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .padding(horizontal = NT.Spacing.screenH)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AIScanPhoto(photo = photo, tags = emptyList())
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(NT.Colors.ground.copy(alpha = 0.35f), NtShapes.card),
                contentAlignment = Alignment.Center,
            ) {
                // `ProgressView().controlSize(.large)` — UIKit's large activity indicator, 37 pt.
                NtSpinner(color = NT.Colors.ink, size = 37.dp, strokeWidth = 3.dp)
            }
        }
        NtText(
            text = stringResource(S.fuel_ai_analyzing),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )
    }
}
