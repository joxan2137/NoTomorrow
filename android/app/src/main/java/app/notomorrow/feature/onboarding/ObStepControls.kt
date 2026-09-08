package app.notomorrow.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NtTimeWheel
import app.notomorrow.designsystem.tabular
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys

// The schedule step's own controls — `OBDayToggle`, `OBTimeWheel` and `OBBigAvatar` from
// `Features/Onboarding/OnboardingComponents.swift` / `SetupScheduleView.swift`.

/** iOS `.onLongPressGesture(minimumDuration: 0.4)`. */
private const val OB_LONG_PRESS_MS = 400L


// ─────────────────────────────────────────────────────────────────────────────
// Day toggle
// ─────────────────────────────────────────────────────────────────────────────

/** 44 dp circle with the day letter; short weekday name, or the override time in ember, below. */
@Composable
fun ObDayToggle(
    day: Int,
    isOn: Boolean,
    overrideMinute: Int?,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onHold: () -> Unit,
) {
    val label = stringResource(NtKeys.weekdayShort(day))
    Column(
        modifier = modifier
            .obTapOrHold(onTap = onTap, onHold = onHold)
            // `.accessibilityElement(children: .combine)` + `.isButton` + `.isSelected`: the
            // gesture is a raw `pointerInput`, so the node has to be declared by hand.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = isOn
                onClick(label = label) {
                    onTap()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(NT.Size.control)
                .background(if (isOn) NT.Colors.ink else NT.Colors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(NtKeys.weekday(day)),
                style = NT.Fonts.headline,
                color = if (isOn) NT.Colors.onPrimary else NT.Colors.ink2,
                maxLines = 1,
            )
        }
        // `.lineLimit(1).minimumScaleFactor(0.8)`: with seven ~44 dp columns the Polish short
        // weekday names and the ember override time shrink rather than ellipsize.
        if (overrideMinute != null) {
            BasicText(
                text = Fmt.time(overrideMinute),
                style = NT.Fonts.caption.tabular().copy(
                    color = NT.Colors.ember,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                autoSize = ObCaptionAutoSize(),
            )
        } else {
            BasicText(
                text = label,
                style = NT.Fonts.caption.copy(
                    color = NT.Colors.ink2,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                autoSize = ObCaptionAutoSize(),
            )
        }
    }
}

/** `.minimumScaleFactor(0.8)` for the caption under a day circle. */
private fun ObCaptionAutoSize(): TextAutoSize = TextAutoSize.StepBased(
    minFontSize = NT.Fonts.caption.fontSize * 0.8f,
    maxFontSize = NT.Fonts.caption.fontSize,
)

/**
 * `onTapGesture` + `onLongPressGesture(minimumDuration: 0.4)`.
 *
 * Not `combinedClickable`: that uses the platform long-press timeout (500 ms on most devices),
 * and the 400 ms is part of the spec.
 */
private fun Modifier.obTapOrHold(onTap: () -> Unit, onHold: () -> Unit): Modifier =
    pointerInput(onTap, onHold) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            try {
                withTimeout(OB_LONG_PRESS_MS) { waitForUpOrCancellation() }?.let { onTap() }
            } catch (_: PointerEventTimeoutCancellationException) {
                onHold()
                waitForUpOrCancellation()
            }
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Time wheel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Inline hour/minute wheel inside a surface card: 164 dp of wheel (iOS clips the system picker
 * to that height), 6 dp of padding above and below, `NT.Radius.card`.
 */
@Composable
fun ObTimeWheel(
    hour: Int,
    minute: Int,
    onChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(NT.Colors.surface, NtShapes.card)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(164.dp)
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            NtTimeWheel(hour = hour, minute = minute, onChange = onChange)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Avatar
// ─────────────────────────────────────────────────────────────────────────────

/** 72 dp avatar with a 3 dp ground ring so the pair overlaps cleanly. */
@Composable
fun ObBigAvatar(
    initial: String,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .background(background, CircleShape)
            .border(3.dp, NT.Colors.ground, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        NtText(
            text = initial.take(1).uppercase(),
            style = NT.Fonts.title1,
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}
