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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.feature.auth.SignInSheet
import app.notomorrow.model.MealSlot
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * `AIScanSignedOutView` (`Features/Fuel/AIScanSignedOutView.swift`) — the backend path needs an
 * account and there is none: no photo is taken and nothing leaves the device until the user
 * signs in (or switches to their own Claude key in Settings).
 */
@Composable
fun AIScanSignedOutView(
    meal: MealSlot,
    modifier: Modifier = Modifier,
    onSignedIn: () -> Unit = {},
) {
    var showsSignIn by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(stringResource(NtKeys.meal(meal)))
            NtText(
                text = stringResource(S.fuel_ai_signedOut_title),
                style = NT.Fonts.title3,
                color = NT.Colors.ink,
            )
            NtText(
                text = stringResource(S.fuel_ai_signedOut_subtitle),
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
                NtIcons.PersonBadgeExclamation,
                size = sfIconSize(34f),
                tint = NT.Colors.ink3,
            )
        }

        PrimaryButton(title = stringResource(S.auth_signIn)) { showsSignIn = true }
    }

    if (showsSignIn) {
        SignInSheet(
            onDismiss = { showsSignIn = false },
            onSignedIn = {
                showsSignIn = false
                onSignedIn()
            },
        )
    }
}
