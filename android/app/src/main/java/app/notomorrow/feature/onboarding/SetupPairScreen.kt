package app.notomorrow.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSpinner
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.pressScale
import app.notomorrow.util.S

/** Step "Pair": your code (share / copy), enter your bro's code, Continue or Not now. */
@Composable
fun SetupPairScreen(
    state: OnboardingUiState,
    onCodeEntry: (String) -> Unit,
    onPair: () -> Unit,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current

    ObStepScaffold(
        index = state.stepIndex ?: 2,
        count = state.stepCount,
        onBack = onBack,
        modifier = modifier,
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ObHelperLine(state)
                PrimaryButton(
                    title = stringResource(S.common_continue),
                    enabled = !state.isFinishing,
                ) {
                    focus.clearFocus()
                    onAdvance()
                }
                if (!state.isPaired) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(NT.Size.control)
                            .obNegativeBottomPadding(10.dp)
                            .pressScale(enabled = !state.isFinishing) {
                                focus.clearFocus()
                                onAdvance()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        NtText(
                            text = stringResource(S.onboarding_pair_notNow),
                            style = NT.Fonts.subheadline,
                            color = NT.Colors.ink2,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    ) {
        ObStepTitle(
            title = stringResource(S.onboarding_pair_title),
            subtitle = stringResource(S.onboarding_pair_subtitle),
        )

        ObPairAvatars(
            myName = state.trimmedName,
            partnerName = state.partnerName,
            modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
        )

        ObCodeCard(
            code = state.myCode,
            modifier = Modifier
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 32.dp),
        )

        if (!state.isPaired) {
            ObOrDivider(
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 24.dp)
            )
            ObCodeEntryRow(
                state = state,
                onCodeEntry = onCodeEntry,
                onPair = {
                    focus.clearFocus()
                    onPair()
                },
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 18.dp),
            )
            if (state.pairFailed) {
                NtText(
                    text = stringResource(S.onboarding_pair_invalid),
                    modifier = Modifier
                        .padding(horizontal = NT.Spacing.screenH)
                        .padding(top = 10.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.bad,
                )
            }
        }
    }
}


@Composable
private fun ObHelperLine(state: OnboardingUiState) {
    val partner = state.partnerName
    val text = when {
        partner != null -> stringResource(S.onboarding_pair_paired_s, partner)
        state.needsSignIn -> stringResource(S.onboarding_pair_signInLater)
        else -> stringResource(S.onboarding_pair_helper)
    }
    NtText(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = NT.Fonts.footnote,
        color = NT.Colors.ink2,
        textAlign = TextAlign.Center,
    )
}

/**
 * SwiftUI's `ProgressView()` — drawn rather than imported, so no Material indicator (and no
 * Material colour) leaks into the app.
 */
@Composable
internal fun ObSpinner(modifier: Modifier = Modifier) =
    NtSpinner(modifier = modifier, color = NT.Colors.ink, size = 20.dp)
