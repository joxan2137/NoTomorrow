package app.notomorrow.feature.bro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.HeadsUpChip
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.util.S
import kotlinx.coroutines.delay

/**
 * "Heads-up to Tomek" + a horizontally scrolling chip row that bleeds to the screen edge —
 * the port of `BroHeadsUpRow` (`Features/Bro/BroHeadsUpRow.swift`).
 *
 * The local `HeadsUp` row and the backend call both happen in [BroViewModel.sendHeadsUp];
 * only the two-and-a-half-second "Sent to …" confirmation is view state, and its token is
 * the `LaunchedEffect` key so a later send cancels the earlier hide, exactly as iOS does.
 */
@Composable
fun BroHeadsUpRow(
    partnerName: String,
    /** `false` once the session is trained: the chip would cancel nothing and tell no one. */
    offersCantMakeIt: Boolean,
    onCantMakeIt: () -> Unit,
    onSend: (HeadsUpKind, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCustom by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    var sentToken by remember { mutableIntStateOf(0) }
    var showSent by remember { mutableStateOf(false) }

    val late15 = stringResource(S.bro_late15)
    val letsGo = stringResource(S.bro_letsGo)

    LaunchedEffect(sentToken) {
        if (sentToken == 0) return@LaunchedEffect
        showSent = true
        delay(SENT_VISIBLE_MILLIS)
        showSent = false
    }

    fun send(kind: HeadsUpKind, text: String) {
        onSend(kind, text)
        sentToken += 1
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow(stringResource(S.bro_headsUp_s, partnerName))

        Row(
            modifier = Modifier
                .bleedHorizontally(NT.Spacing.screenH)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Swift's `HeadsUpChip` tints only the SF Symbol (`tint`); the label is always `ink`.
            if (offersCantMakeIt) {
                HeadsUpChip(
                    title = stringResource(S.dashboard_cantMakeIt),
                    icon = NtIcons.CalendarBadgeMinus,
                    borderTint = NT.Colors.bad.copy(alpha = 0.4f),
                    iconTint = NT.Colors.bad,
                    onClick = onCantMakeIt,
                )
            }
            // `var borderTint: Color = NT.Colors.hairline` — the Swift default, not `border`.
            HeadsUpChip(
                title = late15,
                icon = NtIcons.Clock,
                borderTint = NT.Colors.hairline,
                onClick = { send(HeadsUpKind.RunningLate, late15) },
            )
            HeadsUpChip(
                title = letsGo,
                icon = NtIcons.Bolt,
                borderTint = NT.Colors.hairline,
                onClick = { send(HeadsUpKind.LetsGo, letsGo) },
            )
            HeadsUpChip(
                title = stringResource(S.bro_custom),
                icon = NtIcons.BubbleLeft,
                borderTint = NT.Colors.hairline,
                onClick = {
                    customText = ""
                    showCustom = true
                },
            )
        }

        AnimatedVisibility(
            visible = showSent,
            enter = fadeIn(NT.Anim.easeOut20),
            exit = fadeOut(NT.Anim.easeOut30),
        ) {
            NtText(
                text = stringResource(S.bro_sent_s, partnerName),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
    }

    if (showCustom) {
        NtAlert(
            title = stringResource(S.bro_custom),
            actions = listOf(
                NtAlertAction(title = stringResource(S.bro_sendMessage)) {
                    val text = customText.trim()
                    if (text.isNotEmpty()) {
                        send(HeadsUpKind.Custom, text.take(BroViewModel.MAX_CUSTOM_LENGTH))
                    }
                },
                NtAlertAction(title = stringResource(S.common_cancel), role = NtAlertRole.Cancel),
            ),
            onDismiss = { showCustom = false },
            field = {
                CustomMessageField(
                    value = customText,
                    onValueChange = {
                        customText = if (it.length > BroViewModel.MAX_CUSTOM_LENGTH) {
                            it.take(BroViewModel.MAX_CUSTOM_LENGTH)
                        } else {
                            it
                        }
                    },
                )
            },
        )
    }
}

/** The alert's inline `TextField("bro.customPlaceholder", text: $customText)`. */
@Composable
private fun CustomMessageField(value: String, onValueChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = NT.Fonts.body.copy(color = NT.Colors.ink),
            singleLine = true,
            cursorBrush = SolidColor(NT.Colors.ink),
        )
        if (value.isEmpty()) {
            NtText(
                text = stringResource(S.bro_customPlaceholder),
                style = NT.Fonts.body,
                color = NT.Colors.ink3,
                maxLines = 1,
            )
        }
    }
}

/** "Sent to Tomek" stays up for 2.5 s. */
private const val SENT_VISIBLE_MILLIS: Long = 2_500L

/**
 * SwiftUI's `.padding(.horizontal, -NT.Spacing.screenH)`: Compose has no negative padding,
 * so the child is measured [inset] wider on each side and drawn shifted left — the chip row
 * scrolls out to the true screen edges while the section around it keeps its 20 dp gutter.
 */
internal fun Modifier.bleedHorizontally(inset: Dp): Modifier = layout { measurable, constraints ->
    val extra = inset.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = width),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}
