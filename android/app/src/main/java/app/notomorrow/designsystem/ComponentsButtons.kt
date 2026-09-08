package app.notomorrow.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * White pill, black label. One per screen — the thing the user came to do.
 *
 * `Components.swift:7-30`. The horizontal padding sits *inside* the capsule
 * (SwiftUI proposes the width down through `.padding` into
 * `.frame(maxWidth: .infinity)`), and the 0.4 opacity when disabled covers the
 * background too.
 */
@Composable
fun PrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: NtIcons? = null,
    height: Dp = NT.Size.primaryButton,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PillButton(
        title = title,
        modifier = modifier,
        icon = icon,
        iconFontPt = 16f,
        height = height,
        background = NT.Colors.ink,
        tint = NT.Colors.onPrimary,
        titleStyle = NT.Fonts.headline,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * Surface-2 pill, ink label. The *other* thing.
 *
 * `Components.swift:33-53`. iOS has no disabled state here.
 */
@Composable
fun SecondaryButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: NtIcons? = null,
    height: Dp = NT.Size.primaryButton,
    tint: Color = NT.Colors.ink,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PillButton(
        title = title,
        modifier = modifier,
        icon = icon,
        iconFontPt = 16f,
        height = height,
        background = NT.Colors.surface2,
        tint = tint,
        titleStyle = NT.Fonts.headline,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * Hairline-outlined pill for tertiary actions ("Add exercise").
 *
 * `Components.swift:56-73`. 44 dp, no fill, 1 dp `NT.Colors.border` capsule,
 * 14 pt **bold** icon and a `subheadlineBold` label — and, unlike the two pills
 * above, no inner horizontal padding.
 */
@Composable
fun GhostButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: NtIcons? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(NT.Size.control)
            .pressScale(enabled = enabled, onClick = onClick)
            .border(1.dp, NT.Colors.border, CircleShape),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NtIcon(icon, size = sfIconSize(14f), tint = NT.Colors.ink)
        }
        NtText(
            text = title,
            style = NT.Fonts.subheadlineBold,
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}

/** Shared body of [PrimaryButton] and [SecondaryButton]. */
@Composable
private fun PillButton(
    title: String,
    modifier: Modifier,
    icon: NtIcons?,
    iconFontPt: Float,
    height: Dp,
    background: Color,
    tint: Color,
    titleStyle: androidx.compose.ui.text.TextStyle,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            // The indication must wrap the background, because SwiftUI scales the
            // whole button *label* — capsule included.
            .pressScale(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .background(background, CircleShape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NtIcon(icon, size = sfIconSize(iconFontPt), tint = tint)
        }
        NtText(text = title, style = titleStyle, color = tint, maxLines = 1)
    }
}

/**
 * Filter / selector chip. 40 dp, ink when selected, surface when not.
 *
 * `Components.swift:126-152`. The optional [tint] both colours the icon and
 * paints a 1 dp capsule stroke at 40 % of itself.
 */
@Composable
fun Chip(
    title: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: NtIcons? = null,
    tint: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val content = if (selected) NT.Colors.onPrimary else NT.Colors.ink
    Row(
        modifier = modifier
            .height(NT.Size.chip)
            .pressScale(enabled = enabled, onClick = onClick)
            .background(if (selected) NT.Colors.ink else NT.Colors.surface, CircleShape)
            .then(
                if (tint != null) {
                    Modifier.border(1.dp, tint.copy(alpha = 0.4f), CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NtIcon(icon, size = sfIconSize(14f), tint = tint ?: content)
        }
        NtText(
            text = title,
            style = if (selected) NT.Fonts.subheadlineBold else NT.Fonts.subheadline,
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * `Chip` for `surface`-backed sheets, where `surface` on `surface` would vanish:
 * the unselected fill is `surface2` and the label is tabular.
 * (Contract row `SheetChip` — same geometry as [Chip].)
 */
@Composable
fun SheetChip(
    title: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val content = if (selected) NT.Colors.onPrimary else NT.Colors.ink
    Row(
        modifier = modifier
            .height(NT.Size.chip)
            .pressScale(enabled = enabled, onClick = onClick)
            .background(if (selected) NT.Colors.ink else NT.Colors.surface2, CircleShape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabularText(
            text = title,
            style = if (selected) NT.Fonts.subheadlineBold else NT.Fonts.subheadline,
            color = content,
        )
    }
}

/**
 * Bro heads-up chip. 44 dp (it is a real hit target, not a filter), `surface`
 * fill and a 1 dp stroke in [borderTint]; the icon is 15 pt SemiBold.
 * (Contract row `HeadsUpChip`.)
 *
 * `BroHeadsUpRow.swift:78-101`:
 *
 * ```swift
 * var tint: Color = NT.Colors.ink            // the SF Symbol only
 * var borderTint: Color = NT.Colors.hairline
 * Text(title).foregroundStyle(NT.Colors.ink) // the label is ALWAYS ink
 * .padding(.horizontal, 14)
 * ```
 *
 * So [iconTint] colours the glyph and nothing else — the "can't make it" chip is
 * the only one that uses it, and its label stays `ink` beside a `bad` icon.
 */
@Composable
fun HeadsUpChip(
    title: String,
    modifier: Modifier = Modifier,
    icon: NtIcons? = null,
    borderTint: Color = NT.Colors.hairline,
    iconTint: Color = NT.Colors.ink,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(NT.Size.control)
            .pressScale(enabled = enabled, role = Role.Button, onClick = onClick)
            .background(NT.Colors.surface, CircleShape)
            .border(1.dp, borderTint, CircleShape)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NtIcon(icon, size = sfIconSize(15f), tint = iconTint)
        }
        NtText(text = title, style = NT.Fonts.subheadline, color = NT.Colors.ink, maxLines = 1)
    }
}
