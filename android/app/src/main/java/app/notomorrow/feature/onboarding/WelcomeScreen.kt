package app.notomorrow.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notomorrow.R
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSegmentedLarge
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.pressScale
import app.notomorrow.util.S

/**
 * Wordmark, tagline, language switch, Get started — `WelcomeView.swift`. No progress header here.
 */
@Composable
fun WelcomeScreen(
    language: String,
    onLanguage: (String) -> Unit,
    onGetStarted: () -> Unit,
    onPairNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val englishLabel = stringResource(S.settings_english)
    val polishLabel = stringResource(S.settings_polish)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NT.Colors.ground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = NT.Spacing.screenH)
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NtText(
                text = stringResource(R.string.app_wordmark_no),
                style = NT.Fonts.display(132),
                color = NT.Colors.ember,
                maxLines = 1,
            )
            // `.lineLimit(1).minimumScaleFactor(0.5)` — the wordmark shrinks rather than clipping.
            BasicText(
                text = stringResource(R.string.app_wordmark_tomorrow),
                style = NT.Fonts.display(100).copy(color = NT.Colors.ink),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 50.sp, maxFontSize = 100.sp),
            )
        }

        NtText(
            text = stringResource(S.onboarding_tagline),
            modifier = Modifier.padding(top = 36.dp),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
        )
        NtText(
            text = stringResource(S.onboarding_subtitle),
            modifier = Modifier.padding(top = 10.dp),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )

        Spacer(Modifier.heightIn(min = 24.dp).weight(1f))

        ObLabeled(label = stringResource(S.onboarding_language), spacing = 10.dp) {
            NtSegmentedLarge(
                options = ObLanguages,
                selected = language,
                onSelect = onLanguage,
                label = { code -> if (code == "pl") polishLabel else englishLabel },
            )
        }

        Column(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PrimaryButton(title = stringResource(S.onboarding_getStarted), onClick = onGetStarted)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NT.Size.control)
                    .pressScale(onClick = onPairNow),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtText(
                    text = stringResource(S.onboarding_haveCode),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
                NtText(
                    text = stringResource(S.onboarding_pairNow),
                    style = NT.Fonts.subheadlineBold,
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The two languages the catalog ships — `OBL10n` has no Android analogue. */
internal val ObLanguages: List<String> = listOf("en", "pl")
