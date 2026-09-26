package app.notomorrow.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's hand-built segmented control — never
 * `SingleChoiceSegmentedButtonRow` (outlined pills, leading check, 40 dp).
 *
 * `surface` track at `radius + inset`, a `surface3` thumb at `radius` that
 * slides under the selected label, labels `ink2` -> `ink` and SemiBold when
 * selected. Defaults are `ProgressSegmented` (Progress home / range picker);
 * the onboarding + settings variant (`OBSegmented` / `STSegmented`, which are
 * byte-identical to each other) is 44 dp segments, inset 3, thumb 11, track 14,
 * `subheadline` and `easeOut18`.
 */
@Composable
fun <T> NtSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    segmentHeight: Dp = 32.dp,
    inset: Dp = 2.dp,
    radius: Dp = 8.dp,
    style: TextStyle = NT.Fonts.caption,
    width: Dp? = null,
    animationSpec: AnimationSpec<Dp> = tween(150, easing = NT.Ease.out),
    label: (T) -> String,
) {
    if (options.isEmpty()) return
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .background(NT.Colors.surface, NtShapes.rounded(radius + inset))
            .padding(inset),
    ) {
        val segmentWidth: Dp = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = animationSpec,
            label = "ntSegmentedThumb",
        )
        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .height(segmentHeight)
                .background(NT.Colors.surface3, NtShapes.rounded(radius)),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val isSelected = option == selected
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .width(segmentWidth)
                        .height(segmentHeight)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(option) },
                        )
                        .semantics { this.selected = isSelected },
                    contentAlignment = Alignment.Center,
                ) {
                    NtText(
                        text = label(option),
                        style = style.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else style.fontWeight,
                        ),
                        color = if (isSelected) NT.Colors.ink else NT.Colors.ink2,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * `OBSegmented` / `STSegmented`: the 44 dp onboarding + settings configuration
 * of [NtSegmented], so those two call sites cannot drift apart.
 */
@Composable
fun <T> NtSegmentedLarge(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** `.frame(width:)` — the kg/lb control is 120 pt (`SetupYouView.swift:78`). */
    width: Dp? = null,
    label: (T) -> String,
) {
    NtSegmented(
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        width = width,
        segmentHeight = NT.Size.control,
        inset = 3.dp,
        radius = 11.dp,
        style = NT.Fonts.subheadline,
        animationSpec = tween(180, easing = NT.Ease.out),
        label = label,
    )
}

