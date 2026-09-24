package app.notomorrow.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Shapes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The app's corner shapes. SwiftUI uses `RoundedRectangle(cornerRadius:, style:
 * .continuous)` at 49 sites — a superellipse, not a circular-arc round-rect —
 * so these are `androidx.graphics.shapes` polygons with `smoothing = 0.6`.
 *
 * Nothing outside `designsystem/` may construct a corner shape.
 */
object NtShapes {
    /** `NT.Radius.card` — [NTCard]. */
    val card: Shape = ContinuousCornerShape(NT.Radius.card)

    /** `NT.Radius.tile` — [StatTile], grouped rows. */
    val tile: Shape = ContinuousCornerShape(NT.Radius.tile)

    /** `NT.Radius.field` — text fields. */
    val field: Shape = ContinuousCornerShape(NT.Radius.field)

    /** `NT.Radius.cell` — small cells, calendar days. */
    val cell: Shape = ContinuousCornerShape(NT.Radius.cell)

    /** SwiftUI `Capsule()`. */
    val capsule: Shape = CircleShape

    /** Any other `RoundedRectangle(cornerRadius:, style: .continuous)`. */
    fun rounded(radius: Dp): Shape = ContinuousCornerShape(radius)
}

/** SwiftUI's `.continuous` corner style. */
private data class ContinuousCornerShape(
    val radius: Dp,
    val smoothing: Float = 0.6f,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.minDimension <= 0f) return Outline.Rectangle(size.toRect())
        val r = with(density) { radius.toPx() }.coerceIn(0f, size.minDimension / 2f)
        val polygon = RoundedPolygon.rectangle(
            width = size.width,
            height = size.height,
            rounding = CornerRounding(radius = r, smoothing = smoothing),
            centerX = size.width / 2f,
            centerY = size.height / 2f,
        )
        return Outline.Generic(polygon.toPath().asComposePath())
    }
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)

// ─────────────────────────────────────────────────────────────────────────────
// Text helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The locale the app is actually running in (AppCompat per-app locales, so not
 * necessarily `Locale.getDefault()` at process start). Uppercasing goes through
 * it — Turkish dotless-i and Polish both depend on it.
 */
@Composable
internal fun appLocale(): Locale {
    val config = LocalContext.current.resources.configuration
    return remember(config) { config.locales[0] }
}

/**
 * 11 pt semibold uppercase tracked label — `Theme.swift`'s `.eyebrow()`.
 * Section labels, "NEXT SESSION", stat-tile captions.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NT.Colors.ink2,
    maxLines: Int = 1,
) {
    NtText(
        text = text.uppercase(appLocale()),
        modifier = modifier,
        style = NT.Fonts.eyebrow,
        color = color,
        maxLines = maxLines,
    )
}

/**
 * The app's `Text`. Exists so no feature has to remember to switch off Material's
 * `LocalTextStyle` inheritance or repeat `overflow = Ellipsis`.
 */
@Composable
fun NtText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = NT.Fonts.body,
    color: Color = NT.Colors.ink,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    /** Shrinks the text to fit instead of cutting it off — SwiftUI's `minimumScaleFactor`. */
    autoSize: TextAutoSize? = null,
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign ?: style.textAlign),
        maxLines = maxLines,
        overflow = overflow,
        autoSize = autoSize,
    )
}

/** `NtText` with tabular figures — every number in a column or that ticks. */
@Composable
fun TabularText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = NT.Fonts.body,
    color: Color = NT.Colors.ink,
    maxLines: Int = 1,
    autoSize: TextAutoSize? = null,
) = NtText(
    text = text,
    modifier = modifier,
    style = style.tabular(),
    color = color,
    maxLines = maxLines,
    autoSize = autoSize,
)

// ─────────────────────────────────────────────────────────────────────────────
// Containers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Elevated surface card, 22 dp continuous radius. Reserve for the one object on
 * the screen that needs lifting — sections are separated by whitespace, not by
 * cards.
 */
@Composable
fun NTCard(
    modifier: Modifier = Modifier,
    padding: Dp = NT.Spacing.cardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NT.Colors.surface, NtShapes.card)
            .padding(padding),
        horizontalAlignment = Alignment.Start,
        content = content,
    )
}

/**
 * `Components.swift` `FocalCard` (v2). The one card per screen that holds what the user came to do:
 * the surface with a faint ember wash from the top-trailing corner (13 % ember fading out over
 * 320 dp) and a 1 dp ember hairline at 12 %. Everything else stays on plain [NTCard]s or none.
 */
@Composable
fun FocalCard(
    modifier: Modifier = Modifier,
    padding: Dp = NT.Spacing.cardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NtShapes.card)
            .background(NT.Colors.surface)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(NT.Colors.ember.copy(alpha = 0.13f), NT.Colors.ember.copy(alpha = 0f)),
                        center = Offset(size.width, 0f),
                        radius = 320.dp.toPx(),
                    )
                )
            }
            .border(1.dp, NT.Colors.ember.copy(alpha = 0.12f), NtShapes.card)
            .padding(padding),
        horizontalAlignment = Alignment.Start,
        content = content,
    )
}

/**
 * Small stat tile: eyebrow label over a headline value. The value shrinks to fit, down to
 * [STAT_TILE_MIN_SCALE] of the headline size, instead of truncating — three tiles in a row leave a
 * value about 72 dp on a 360 dp phone, and "1 h 12 min" or "12 500 kg" must stay whole
 * (`StatTile`'s `minimumScaleFactor(0.6)` on iOS).
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = NT.Colors.ink,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NT.Colors.surface, NtShapes.tile)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Eyebrow(label)
        TabularText(
            value,
            style = NT.Fonts.headline,
            color = valueColor,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = NT.Fonts.headline.fontSize * STAT_TILE_MIN_SCALE,
                maxFontSize = NT.Fonts.headline.fontSize,
            ),
        )
    }
}

/** The smallest a [StatTile] value shrinks to, as a share of the headline size. */
private const val STAT_TILE_MIN_SCALE = 0.6f

/**
 * 1 dp horizontal rule. Not `Dp.Hairline` — that is `Dp(0f)` and draws nothing;
 * matching iOS's 1 pt matters more here than physical thinness.
 */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = NT.Colors.hairline) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Vertical companion of [Hairline] (alert action splits, segmented dividers). */
@Composable
fun VHairline(modifier: Modifier = Modifier, color: Color = NT.Colors.hairline) {
    Box(modifier.fillMaxHeight().width(1.dp).background(color))
}

/** Section header row: title left, optional trailing text right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(title, style = NT.Fonts.headline, color = NT.Colors.ink, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            // The trailing slot is only ever a count in this app, and `TrainView.swift:88`
            // renders it `.monospacedDigit()` — so it gets tabular figures.
            TabularText(
                text = trailing,
                modifier = Modifier.padding(start = 8.dp),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        }
    }
}

/** Small tinted badge chip — "PR", "guess". */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NT.Colors.ember,
) {
    Box(
        modifier = modifier
            .height(22.dp)
            .background(color.copy(alpha = 0.12f), NtShapes.rounded(6.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        NtText(text, style = NT.Fonts.caption, color = color, maxLines = 1)
    }
}

/** Circular initial avatar. `headline` at 36 dp and up, `caption` below. */
@Composable
fun Avatar(
    initial: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    background: Color = NT.Colors.surface2,
    dimmed: Boolean = false,
) {
    Box(
        modifier = modifier.size(size).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        NtText(
            text = initial.take(1).uppercase(appLocale()),
            style = if (size >= 36.dp) NT.Fonts.headline else NT.Fonts.caption,
            color = if (dimmed) NT.Colors.ink3 else NT.Colors.ink,
            maxLines = 1,
        )
    }
}

/** Sheet grabber: 36 × 5 dp capsule, 8 dp above it. */
@Composable
fun Grabber(modifier: Modifier = Modifier) {
    Box(modifier.padding(top = 8.dp)) {
        Box(Modifier.size(width = 36.dp, height = 5.dp).background(NT.Colors.ink3, CircleShape))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Numbers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `KcalLabel` (`FuelSupport.swift:140`): the small "N kcal" pair used in rows — the number in
 * `ink`, tabular, and the unit one step down in `ink2`.
 *
 * SwiftUI aligns the two on `.firstTextBaseline`; in a `Row` that is
 * [androidx.compose.foundation.layout.RowScope.alignByBaseline] on both children.
 */
@Composable
fun KcalLabel(
    kcal: Double,
    modifier: Modifier = Modifier,
    numberStyle: androidx.compose.ui.text.TextStyle = NT.Fonts.subheadline,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TabularText(
            text = Fmt.kcal(kcal, withUnit = false),
            modifier = Modifier.alignByBaseline(),
            style = numberStyle,
            color = NT.Colors.ink,
        )
        NtText(
            text = stringResource(S.unit_kcal),
            modifier = Modifier.alignByBaseline(),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
    }
}

/**
 * `.contentTransition(.numericText())` — one `AnimatedContent` per character slot, the new glyph
 * sliding up as the old one leaves (research §5.2 row 5).
 *
 * ⚠️ Big Shoulders has no `tnum` feature, so a display-face counter reflows horizontally as
 * digits change; iOS's `.monospacedDigit()` is equally inert there (research §5.4).
 */
@Composable
fun NumericText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    durationMs: Int = 150,
) {
    Row(modifier) {
        text.forEachIndexed { index, character ->
            key(index) {
                AnimatedContent(
                    targetState = character,
                    transitionSpec = {
                        (
                            slideInVertically(tween(durationMs, easing = NT.Ease.out)) { it } +
                                fadeIn(tween(durationMs, easing = NT.Ease.out))
                            ) togetherWith (
                            slideOutVertically(tween(durationMs, easing = NT.Ease.out)) { -it } +
                                fadeOut(tween(durationMs, easing = NT.Ease.out))
                            )
                    },
                    label = "numericText",
                ) { glyph ->
                    NtText(glyph.toString(), style = style, color = color, maxLines = 1)
                }
            }
        }
    }
}
