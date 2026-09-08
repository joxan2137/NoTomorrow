package app.notomorrow.feature.bro

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.util.S
import kotlinx.coroutines.delay

/**
 * Unpaired Bro tab — the port of `BroUnpairedView` (`Features/Bro/BroUnpairedView.swift`):
 * the code card (share + copy) and the "enter your bro's code" field.
 */
@Composable
fun BroUnpairedView(
    state: BroUiState,
    onCodeEntry: (String) -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        NtText(
            text = stringResource(S.bro_unpaired_subtitle),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )

        BroCodeCard(myCode = state.myCode, modifier = Modifier.padding(top = 28.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
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

        BroEnterRow(
            state = state,
            onCodeEntry = onCodeEntry,
            onPair = {
                focus.clearFocus()
                onPair()
            },
            modifier = Modifier.padding(top = 18.dp),
        )

        if (state.pairFailed) {
            NtText(
                text = stringResource(S.bro_invalidCode),
                modifier = Modifier.padding(top = 10.dp),
                style = NT.Fonts.footnote,
                color = NT.Colors.bad,
            )
        }

        NtText(
            text = stringResource(S.onboarding_pair_helper),
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Code card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BroCodeCard(myCode: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val shareText = stringResource(S.bro_shareText, myCode.orEmpty())

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_VISIBLE_MILLIS)
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
            BroAutoSizeText(
                text = myCode ?: stringResource(S.bro_codePlaceholder),
                // `.lineLimit(1).minimumScaleFactor(0.6)` — a long code shrinks to 34 pt.
                minScale = 0.6f,
                // iOS `.tracking(2)` at 56 pt.
                style = NT.Fonts.display(56).copy(letterSpacing = (2f / 56f).em),
                color = if (myCode == null) NT.Colors.ink3 else NT.Colors.ink,
                tabular = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .then(
                            if (myCode != null) {
                                Modifier.pressScale {
                                    ShareCompat.IntentBuilder(context)
                                        .setType("text/plain")
                                        .setText(shareText)
                                        .startChooser()
                                }
                            } else {
                                Modifier.alpha(0.4f)
                            }
                        )
                        .background(NT.Colors.ink, CircleShape),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NtIcon(NtIcons.SquareAndArrowUp, size = sfIconSize(16f), tint = NT.Colors.onPrimary)
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
                        .pressScale(enabled = myCode != null) {
                            clipboard.setText(AnnotatedString(myCode.orEmpty()))
                            copied = true
                        }
                        .background(NT.Colors.surface2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // `.contentTransition(.symbolEffect(.replace))`.
                    AnimatedContent(
                        targetState = copied,
                        transitionSpec = {
                            (scaleIn(NT.Anim.easeOut20, initialScale = 0.7f) + fadeIn(NT.Anim.easeOut20)) togetherWith
                                (scaleOut(NT.Anim.easeOut30, targetScale = 0.7f) + fadeOut(NT.Anim.easeOut30))
                        },
                        label = "broCopyGlyph",
                    ) { isCopied ->
                        NtIcon(
                            icon = if (isCopied) NtIcons.Checkmark else NtIcons.DocOnDoc,
                            size = sfIconSize(16f),
                            tint = if (isCopied) NT.Colors.good else NT.Colors.ink,
                            // `.accessibilityLabel(Text(copied ? "bro.copied" : "bro.copy"))`
                            // labels the element; Android's `onClickLabel` names the action.
                            contentDescription = stringResource(
                                if (isCopied) S.bro_copied else S.bro_copy
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Enter code
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BroEnterRow(
    state: BroUiState,
    onCodeEntry: (String) -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.isPairing || state.isLoading
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fieldStyle = NT.Fonts.display(26).copy(letterSpacing = (1f / 26f).em)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .background(NT.Colors.surface, NtShapes.rounded(14.dp))
                .border(1.dp, NT.Colors.hairline, NtShapes.rounded(14.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = state.codeEntry,
                onValueChange = onCodeEntry,
                modifier = Modifier.fillMaxWidth(),
                textStyle = fieldStyle.copy(color = NT.Colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(NT.Colors.ink),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    // iOS shows the default keyboard; several IMEs drop auto-capitalization on
                    // `Ascii`, and `BroViewModel.setCodeEntry` uppercases regardless.
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onPair() }),
            )
            if (state.codeEntry.isEmpty()) {
                NtText(
                    text = stringResource(S.bro_codePlaceholder),
                    style = fieldStyle,
                    color = NT.Colors.ink3,
                    maxLines = 1,
                )
            }
        }

        Box(
            modifier = Modifier
                .height(52.dp)
                .pressScale(enabled = state.canPair && !state.isPairing, onClick = onPair)
                .background(NT.Colors.surface2, NtShapes.rounded(14.dp))
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.onboarding_pair_pair),
                modifier = Modifier.graphicsLayer { alpha = if (busy) 0f else 1f },
                style = NT.Fonts.headline,
                color = if (state.canPair) NT.Colors.ink else NT.Colors.ink2,
                maxLines = 1,
            )
            if (busy) BroSpinner()
        }
    }
}

/** "Copied" reverts after 1.5 s. */
private const val COPIED_VISIBLE_MILLIS: Long = 1_500L
