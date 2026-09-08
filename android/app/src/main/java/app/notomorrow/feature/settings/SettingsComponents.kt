package app.notomorrow.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NtToggle
import app.notomorrow.designsystem.StatusDot
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize

// Building blocks for the Settings screens — the port of
// `Features/Settings/SettingsComponents.swift`. Prefixed `St`, exactly like iOS's `ST`,
// so they stay out of the design system's way.

/** A row with a `detail` line is 56 pt tall with 4 pt of vertical padding. */
private val StDetailRowHeight: Dp = 56.dp

/**
 * The disclosure chevron's layout slot.
 *
 * `Image(systemName: "chevron.right")` is only as wide as the glyph plus SF Pro's own bearings —
 * 10.7 pt, measured on `11-settings.png` — while `ic_chevron_right` centres its 6.3 pt glyph in a
 * 16.25 pt (`sfIconSize(13f)`) box, so laying the icon out at its natural width would push the
 * whole trailing column 7 pt to the left. Reserving iOS's slot and nudging the glyph to the
 * trailing edge lands the arrow 0.7 pt inside the card's 16 pt inset (x 365.0 pt) and the value
 * column's right edge back on x 343.0 pt.
 */
private val StChevronSlot: Dp = 10.7.dp

/** Trailing nudge that takes the dead space out of [StChevronSlot]'s centred glyph. */
private val StChevronNudge: Dp = 4.2.dp

/** iOS's disclosure chevron: `.font(.system(size: 13, weight: .semibold))`. */
@Composable
private fun StChevron() {
    NtIcon(
        NtIcons.ChevronRight,
        modifier = Modifier
            // Take up iOS's slot, but let the wider vector box overhang it rather than being
            // squeezed by it, then slide the glyph onto the slot's trailing edge.
            .width(StChevronSlot)
            .wrapContentSize(Alignment.CenterEnd, unbounded = true)
            .offset(x = StChevronNudge),
        size = sfIconSize(13f),
        tint = NT.Colors.ink3,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Group
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Collects a group's rows so [StGroup] can put a [Hairline] between them and **not** after the
 * last one — the Compose stand-in for `_VariadicView.Tree(STGroupLayout())`.
 */
class StGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    /** One row of the card. */
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * Eyebrow header (16 pt inset) + surface card, 16 pt radius, rows separated by hairlines,
 * plus the optional `.stFootnote` line underneath.
 */
@Composable
fun StGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footnote: String? = null,
    content: StGroupScope.() -> Unit,
) {
    val scope = StGroupScope().apply(content)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Eyebrow(title, Modifier.padding(start = 16.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(NT.Colors.surface, NtShapes.tile)
                .padding(horizontal = 16.dp)
        ) {
            scope.rows.forEachIndexed { index, row ->
                row()
                if (index != scope.rows.lastIndex) Hairline()
            }
        }
        if (footnote != null) {
            NtText(
                text = footnote,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rows
// ─────────────────────────────────────────────────────────────────────────────

/** 44 pt row that pushes an editor. */
@Composable
fun StLinkRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    labelColor: Color = NT.Colors.ink,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = NT.Size.control)
            .ntPlainClickable(onClickLabel = label, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(NT.Spacing.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(label, style = NT.Fonts.body, color = labelColor)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        if (dot != null) StatusDot(dot)
        TabularText(value, style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
        StChevron()
    }
}

/** 44 pt row that runs an action (sign out, delete). */
@Composable
fun StActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    color: Color = NT.Colors.ink,
    isBusy: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = NT.Size.control)
            .ntPlainClickable(enabled = !isBusy, onClickLabel = label, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(NT.Spacing.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(label, style = NT.Fonts.body, color = color)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        if (isBusy) {
            StSpinner()
        } else if (value != null) {
            NtText(value, style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
        }
    }
}

/** 44 pt row with a trailing toggle (56 pt with a detail line). */
@Composable
fun StToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // SwiftUI applies `.padding(.vertical, 4)` OUTSIDE `.frame(minHeight: 56)`, so a
            // detail row's floor is 64 pt, not 56. Keep the padding node outside the min-size one.
            .padding(vertical = if (detail == null) 0.dp else 4.dp)
            .defaultMinSize(minHeight = if (detail == null) NT.Size.control else StDetailRowHeight),
        horizontalArrangement = Arrangement.spacedBy(NT.Spacing.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StRowLabel(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        NtToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 44 pt row with a trailing checkmark; tapping selects. */
@Composable
fun StCheckRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // `.contentShape(Rectangle())` sits outside the padding on iOS, and the padding
            // itself outside `.frame(minHeight:)` — so the hit area is the full 64 pt row.
            .ntPlainClickable(onClickLabel = title, onClick = onClick)
            .padding(vertical = if (detail == null) 0.dp else 4.dp)
            .defaultMinSize(minHeight = if (detail == null) NT.Size.control else StDetailRowHeight),
        horizontalArrangement = Arrangement.spacedBy(NT.Spacing.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StRowLabel(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        // iOS keeps the checkmark in the layout at zero opacity, so the rows never shift.
        NtIcon(
            icon = NtIcons.Checkmark,
            modifier = Modifier.alphaLayer(if (selected) 1f else 0f),
            size = sfIconSize(15f),
            tint = NT.Colors.ink,
        )
    }
}

/** Plain label · value row (no chevron). */
@Composable
fun StInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = NT.Size.control),
        horizontalArrangement = Arrangement.spacedBy(NT.Spacing.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(label, style = NT.Fonts.body, color = NT.Colors.ink)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        if (dot != null) StatusDot(dot)
        TabularText(value, style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
    }
}

@Composable
private fun StRowLabel(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        NtText(title, style = NT.Fonts.body, color = NT.Colors.ink)
        if (detail != null) {
            NtText(detail, style = NT.Fonts.footnote, color = NT.Colors.ink2)
        }
    }
}
