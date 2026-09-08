package app.notomorrow.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.app.ShareCompat
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.util.S
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// Pieces
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ObPairAvatars(myName: String, partnerName: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy((-14).dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ObBigAvatar(initial = myName, background = NT.Colors.surface2)
        // `.animation(.easeOut(duration: 0.2), value: model.partnerName)`
        // (`SetupPairView.swift:81`): the plus circle crossfades into the partner's avatar.
        AnimatedContent(
            targetState = partnerName,
            transitionSpec = {
                fadeIn(tween(200, easing = NT.Ease.out)) togetherWith
                    fadeOut(tween(200, easing = NT.Ease.out))
            },
            label = "obPairPartnerSlot",
        ) { partner ->
            if (partner != null) {
                ObBigAvatar(initial = partner, background = NT.Colors.surface)
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(NT.Colors.surface, CircleShape)
                        .border(3.dp, NT.Colors.ground, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    NtIcon(NtIcons.Plus, size = sfIconSize(24f), tint = NT.Colors.ink3)
                }
            }
        }
    }
}

@Composable
internal fun ObCodeCard(code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val shareText = stringResource(S.onboarding_pair_shareText, code)

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    NTCard(modifier = modifier, padding = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Eyebrow(stringResource(S.onboarding_pair_yourCode))
            // `.contentTransition(.numericText())` (`SetupPairView.swift:93`): the locally
            // generated code fades into the backend's once `loadCode()` returns.
            AnimatedContent(
                targetState = code,
                transitionSpec = {
                    fadeIn(tween(200, easing = NT.Ease.out)) togetherWith
                        fadeOut(tween(200, easing = NT.Ease.out))
                },
                label = "obPairCode",
            ) { value ->
                TabularText(
                    text = value,
                    // iOS `.tracking(2.2)` at 56 pt.
                    style = NT.Fonts.display(56).copy(letterSpacing = (2.2f / 56f).em),
                    color = NT.Colors.ink,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .pressScale {
                            ShareCompat.IntentBuilder(context)
                                .setType("text/plain")
                                .setText(shareText)
                                .startChooser()
                        }
                        .background(NT.Colors.ink, CircleShape),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NtIcon(
                        NtIcons.SquareAndArrowUp,
                        size = sfIconSize(16f),
                        tint = NT.Colors.onPrimary,
                    )
                    NtText(
                        text = stringResource(S.onboarding_pair_share),
                        style = NT.Fonts.headline,
                        color = NT.Colors.onPrimary,
                        maxLines = 1,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pressScale(
                            onClickLabel = stringResource(
                                if (copied) S.common_copied else S.common_copy
                            ),
                        ) {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        }
                        .background(NT.Colors.surface2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // `.contentTransition(.symbolEffect(.replace))` — research §5.2 row 6.
                    AnimatedContent(
                        targetState = copied,
                        transitionSpec = {
                            (scaleIn(tween(200, easing = NT.Ease.out), initialScale = 0.7f) +
                                fadeIn(tween(200, easing = NT.Ease.out))) togetherWith
                                (scaleOut(tween(200, easing = NT.Ease.out), targetScale = 0.7f) +
                                    fadeOut(tween(200, easing = NT.Ease.out)))
                        },
                        label = "obCopyGlyph",
                    ) { isCopied ->
                        NtIcon(
                            icon = if (isCopied) NtIcons.Checkmark else NtIcons.DocOnDoc,
                            size = sfIconSize(16f),
                            tint = if (isCopied) NT.Colors.good else NT.Colors.ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ObOrDivider(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Hairline(Modifier.weight(1f))
        NtText(
            text = stringResource(S.onboarding_pair_orEnter),
            style = NT.Fonts.caption,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
        Hairline(Modifier.weight(1f))
    }
}

@Composable
internal fun ObCodeEntryRow(
    state: OnboardingUiState,
    onCodeEntry: (String) -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ObTextField(
            value = state.codeEntry,
            onValueChange = onCodeEntry,
            placeholder = stringResource(S.onboarding_pair_codePlaceholder),
            modifier = Modifier.weight(1f),
            // iOS `.tracking(1)` at 26 pt.
            textStyle = NT.Fonts.display(26).copy(letterSpacing = (1f / 26f).em),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onPair() }),
        )

        Box(
            modifier = Modifier
                .height(52.dp)
                .pressScale(enabled = state.canPair, onClick = onPair)
                .background(NT.Colors.surface2, NtShapes.rounded(14.dp))
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.onboarding_pair_pair),
                modifier = Modifier.graphicsLayer { alpha = if (state.isPairing) 0f else 1f },
                style = NT.Fonts.headline,
                color = if (state.canPair) NT.Colors.ink else NT.Colors.ink2,
                maxLines = 1,
            )
            if (state.isPairing) ObSpinner()
        }
    }
}
