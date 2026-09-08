package app.notomorrow.feature.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.util.S

/**
 * `AIScanFailedView` (`AIScanView.swift:145`) — the half-faded photo (when there is one), the
 * error line, and Retake pinned to the bottom.
 */
@Composable
fun AIScanFailedView(
    photo: ImageBitmap?,
    messageRes: Int,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (photo != null) {
            AIScanPhoto(
                photo = photo,
                tags = emptyList(),
                modifier = Modifier
                    .padding(top = 12.dp)
                    .padding(horizontal = NT.Spacing.screenH)
                    .alpha(0.5f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (photo == null) 40.dp else 0.dp)
                .padding(horizontal = NT.Spacing.screenH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NtIcon(NtIcons.EyeSlash, size = sfIconSize(28f), tint = NT.Colors.ink2)
            NtText(
                text = stringResource(messageRes),
                style = NT.Fonts.body,
                color = NT.Colors.ink,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.weight(1f))

        PrimaryButton(
            title = stringResource(S.fuel_ai_retake),
            modifier = Modifier
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = 12.dp),
            icon = NtIcons.Camera,
            onClick = onRetake,
        )
    }
}
