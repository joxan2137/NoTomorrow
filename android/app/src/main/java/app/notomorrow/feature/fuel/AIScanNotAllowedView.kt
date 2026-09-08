package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.util.S

/**
 * `AIScanNotAllowedView` (`Features/Fuel/AIScanView.swift:193`) — the backend answered 403
 * `ai_not_allowed`: the account is not on the AI whitelist. The way out is Settings — ask the
 * owner for access, or switch to your own Gemini / Claude key.
 */
@Composable
fun AIScanNotAllowedView(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NtText(
                text = stringResource(S.fuel_ai_notAllowed_title),
                style = NT.Fonts.title3,
                color = NT.Colors.ink,
            )
            NtText(
                text = stringResource(S.fuel_ai_error_notAllowed),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(AIScanPhotoHeight)
                .background(NT.Colors.surface, NtShapes.card),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(
                NtIcons.Lock,
                size = sfIconSize(34f),
                tint = NT.Colors.ink3,
            )
        }

        PrimaryButton(
            title = stringResource(S.fuel_ai_notAllowed_openSettings),
            onClick = onOpenSettings,
        )
    }
}
