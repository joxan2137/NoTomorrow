package app.notomorrow.feature.bro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.feature.auth.SignInSheet
import app.notomorrow.util.S

/**
 * Bro tab when the real backend is in use and there is no account session — the port of
 * `BroSignedOutView` (`Features/Bro/BroSignedOutView.swift`). Pairing needs an account, so
 * the code card gives way to one primary "Sign in to pair" action.
 */
@Composable
fun BroSignedOutView(
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showsSignIn by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        NtText(
            text = stringResource(S.bro_signedOut_subtitle),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )

        NTCard(modifier = Modifier.padding(top = 28.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(NT.Size.control)
                            .background(NT.Colors.surface2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        // `BroSignedOutView.swift:21` writes `person.2` literally, so this one
                        // is the outlined weight — the solid glyph is the tab item's substitution.
                        NtIcon(NtIcons.Person2Outline, size = sfIconSize(22f), tint = NT.Colors.ink2)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        NtText(
                            text = stringResource(S.bro_signedOut_title),
                            style = NT.Fonts.headline,
                            color = NT.Colors.ink,
                        )
                        NtText(
                            text = stringResource(S.bro_signedOut_body),
                            style = NT.Fonts.footnote,
                            color = NT.Colors.ink2,
                        )
                    }
                }
                PrimaryButton(
                    title = stringResource(S.bro_signInToPair),
                    height = NT.Size.cardButton,
                ) { showsSignIn = true }
            }
        }

        NtText(
            text = stringResource(S.onboarding_pair_helper),
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
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
