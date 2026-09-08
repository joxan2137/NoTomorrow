package app.notomorrow.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * A navigation-bar / toolbar button on a Liquid Glass platter — iOS 26's `Done`, `Close` and the
 * back chevron (`docs/android-glass.md` §1.9).
 *
 * Geometry re-measured on `design/ios26-reference/zoom-navbar-done-glass.png` and
 * `zoom-navbar-back-glass.png` at 4 px/pt:
 *
 * | | Measured | Here |
 * |---|---|---|
 * | Lone icon button | 177 × 177 px = **44.25 × 44.25 pt**, a circle | 44 × 44, `CapsuleShape` |
 * | "Done" platter | 296 × 177 px = **74.0 × 44.25 pt**, r 22 | 44 tall, 16 dp h-padding |
 * | Body over `NT.Colors.ground` | **#1C1C1E** — the tab bar's exact value | the §1.1 equation |
 * | Rim | 59 / 51 / 42 inward, identical on all four edges | `GlassStyle.Regular` |
 *
 * (The 82 × 56 / 56 dp numbers that circulated in the wave brief are not what the captures show;
 * these are the pixel measurements.)
 *
 * Blast radius in this app is deliberately tiny: there are two `NavigationStack`s and Settings
 * forces an opaque bar, so this exists for the handful of `Done`/back affordances that keep the
 * system look. Note that a button hosted **inside a sheet** is outside the tab backdrop capture and
 * will fall back to the flat fill — which is the same #1C1C1E, so it still reads correctly.
 */
@Composable
fun NtGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: NtIcons? = null,
    title: String? = null,
    /**
     * The screen-reader name. **Every call site must pass this or [title]** — the icon-only case
     * (the back chevron) is precisely what this component exists for, and iOS gets a system
     * accessibility label for it for free; with both null the control is announced as nothing at
     * all. It is nullable only so the parameter could be added without breaking the two existing
     * call sites in the same wave (`SettingsScaffold.kt:120` still needs
     * `contentDescription = stringResource(S.common_back)`).
     */
    contentDescription: String? = null,
) {
    val hasTitle = !title.isNullOrEmpty()
    val label = contentDescription ?: title
    Row(
        modifier = modifier
            .height(NtGlassButtonTokens.height)
            .defaultMinSize(minWidth = NtGlassButtonTokens.height)
            .liquidGlass(CapsuleShape, GlassStyle.Regular)
            .ntClickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = if (hasTitle) NtGlassButtonTokens.labelPadding else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NtIcon(
                icon = icon,
                size = NtGlassButtonTokens.iconSize,
                tint = NT.Colors.ink,
                // The label is on the text when there is one; otherwise the icon carries it.
                contentDescription = if (hasTitle) null else label,
            )
        }
        if (hasTitle) {
            NtText(text = title!!, style = NT.Fonts.headline, color = NT.Colors.ink, maxLines = 1)
        }
    }
}

/** One place for the §1.9 measurements, so a re-measure is a one-line change. */
object NtGlassButtonTokens {
    /** 177 px / 4 = 44.25 pt. */
    val height = 44.dp

    /** "Done" is 74 pt wide for a 42 pt label -> 16 pt each side. */
    val labelPadding = 16.dp

    /**
     * The back chevron measures 17 pt tall, i.e. a ~22 pt SF Symbol box — but the Android vector
     * does not fill its box the way `chevron.left` fills SF's.
     *
     * Measured on the two `12-settings-resttimer.png` parity captures inside an identically sized
     * 44 dp glass circle (both 132 px): iOS draws the glyph 33 × 56 px = 11.0 × 18.67 dp, Android
     * 26 × 46 px = 8.67 × 15.33 dp — 18 % short in both axes because `ic_chevron_left.xml` has
     * padding baked into its path. Scaling the box by 18.67/15.33 restores the rendered glyph.
     *
     * This is the second-choice fix and it is here because it is the one this file owns: the right
     * one is to redraw the vector so its extent fills the 22 dp box, at which point this returns to
     * 22 and every other glass button (which today has no icon) is unaffected either way.
     */
    val iconSize = 26.5.dp

    /** Leading margin of the bar item row. */
    val leadingMargin = 16.dp

    /** The bar item row itself, starting at the top safe-area inset. */
    val barHeight = 54.dp
}
