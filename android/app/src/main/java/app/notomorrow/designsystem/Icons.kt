package app.notomorrow.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.R

/**
 * The whole icon vocabulary of the app, one entry per distinct SF Symbol used by
 * the iOS source (`Image(systemName:)`, `Label(systemImage:)`, `AppTab.symbol`).
 *
 * Features never touch `R.drawable.*` — they name an entry here, so the icon set
 * can be re-cut in one place.
 *
 * ⛔ The drawables are **hand-authored Material-Symbols-style geometry**, never
 * traced from SF Symbols: Apple's licence limits SF Symbols to Apple operating
 * systems (research §9.3). The six glyphs Material has no honest equivalent for
 * (`flame`, `target`, `barcode.viewfinder`, `calendar.badge.minus`, `trophy`,
 * `trophy.fill`) plus `person.crop.circle.badge.exclamationmark` and `moon.zzz`
 * are original vectors drawn to read like the SF glyph.
 */
enum class NtIcons(
    @param:DrawableRes val res: Int,
    /**
     * How much of the nominal box the glyph is *allowed* to overflow, so its painted
     * bounding box matches the SF Symbol's inside the same point box.
     *
     * An SF Symbol's ink is not confined to the point size it is asked for — `person.2`
     * at 28 pt paints 31 pt wide — while a 24-unit Material-style viewport is by
     * construction inside its box. Drawn at 1:1 the hand-cut vectors therefore came out
     * 4–21 % smaller than Apple's, measured on `design/parity/{ios,android-r1}/06-dashboard.png`
     * at 3 px/dp inside the identical 28 dp `NtTabBarTokens.iconSize`:
     *
     * | glyph | iOS px | Android px (1.0) | factor |
     * |---|---|---|---|
     * | `house` | 82 × 72 | 68.6 × 61.6 | 1.18 |
     * | `dumbbell` | 87 × 51 | 81.9 × 49.0 | 1.05 |
     * | `fork.knife` | 47 × 80 | 41.3 × 69.6 | 1.145 |
     * | `chart.line.uptrend.xyaxis` | 75 × 64 | 74 × 63 | 1.0 (already matched) |
     * | `person.2` | 93 × 62 | 77 × 50.9 | 1.21 |
     *
     * [NtIcon] keeps the **layout** size at the caller's `size` and only draws the
     * painter into a box this many times larger, centred — so nothing moves, the glyph
     * just grows into the ink area SF Symbols occupy. It is a property of the symbol,
     * not of one call site, which is why it lives on the enum: the ratio is the same at
     * every size.
     */
    val glyphScale: Float = 1f,
) {
    /** SF `house` — Today tab. */
    House(R.drawable.ic_home, glyphScale = 1.18f),

    /** SF `dumbbell` — Train tab. */
    Dumbbell(R.drawable.ic_fitness_center, glyphScale = 1.05f),

    /** SF `fork.knife` — Fuel tab. */
    ForkKnife(R.drawable.ic_restaurant, glyphScale = 1.145f),

    /** SF `chart.line.uptrend.xyaxis` — Progress tab. Measured 1 px off; left at 1:1. */
    ChartLineUptrend(R.drawable.ic_trending_up),

    /**
     * SF `person.2` as the **Bro tab item** renders: SwiftUI substitutes the
     * filled variant for tab items, so the tab bar glyph is solid.
     */
    Person2(R.drawable.ic_group, glyphScale = 1.21f),

    /**
     * SF `person.2` written literally (`Image(systemName: "person.2")`), i.e. the
     * outlined weight — the only place iOS shows it is the Bro signed-out card.
     */
    Person2Outline(R.drawable.ic_group_outline, glyphScale = 1.19f),

    /** SF `chevron.right`. */
    ChevronRight(R.drawable.ic_chevron_right),

    /** SF `chevron.left`. */
    ChevronLeft(R.drawable.ic_chevron_left),

    /** SF `chevron.down`. */
    ChevronDown(R.drawable.ic_chevron_down),

    /** SF `arrow.left`. */
    ArrowLeft(R.drawable.ic_arrow_left),

    /** SF `arrow.up`. */
    ArrowUp(R.drawable.ic_arrow_up),

    /** SF `arrow.down`. */
    ArrowDown(R.drawable.ic_arrow_down),

    /** SF `plus`. */
    Plus(R.drawable.ic_plus),

    /** SF `minus`. */
    Minus(R.drawable.ic_minus),

    /** SF `checkmark`. */
    Checkmark(R.drawable.ic_checkmark),

    /** SF `xmark`. */
    Xmark(R.drawable.ic_xmark),

    /** SF `magnifyingglass`. */
    MagnifyingGlass(R.drawable.ic_magnifyingglass),

    /** SF `ellipsis` — horizontal, not the Android vertical overflow. */
    Ellipsis(R.drawable.ic_ellipsis),

    /** SF `square.and.arrow.up` — the iOS share glyph, kept for parity. */
    SquareAndArrowUp(R.drawable.ic_share),

    /** SF `doc.on.doc`. */
    DocOnDoc(R.drawable.ic_copy),

    /** SF `lock`. */
    Lock(R.drawable.ic_lock),

    /** SF `info.circle`. */
    InfoCircle(R.drawable.ic_info),

    /** SF `timer`. */
    Timer(R.drawable.ic_timer),

    /** SF `clock`. */
    Clock(R.drawable.ic_clock),

    /** SF `bolt`. */
    Bolt(R.drawable.ic_bolt),

    /** SF `bubble.left`. */
    BubbleLeft(R.drawable.ic_bubble_left),

    /** SF `pencil`. */
    Pencil(R.drawable.ic_pencil),

    /** SF `trash`. */
    Trash(R.drawable.ic_trash),

    /** SF `camera`. */
    Camera(R.drawable.ic_camera),

    /** SF `photo.on.rectangle`. */
    PhotoOnRectangle(R.drawable.ic_photo_library),

    /** SF `eye.slash`. */
    EyeSlash(R.drawable.ic_eye_slash),

    /** SF `forward.end.fill` — skip rest. */
    ForwardEndFill(R.drawable.ic_forward_end_fill),

    /** SF `medal`. */
    Medal(R.drawable.ic_medal),

    /** SF `person.crop.circle.badge.exclamationmark` — custom vector. */
    PersonBadgeExclamation(R.drawable.ic_person_badge_exclamation),

    /** SF `moon.zzz` — custom vector (Material `bedtime` has no "zzz"). */
    MoonZzz(R.drawable.ic_moon_zzz),

    /** SF `flame` — custom vector, the streak icon. */
    Flame(R.drawable.ic_flame),

    /** Filled companion of [Flame]; not in the iOS source, kept for the ember dot states. */
    FlameFill(R.drawable.ic_flame_fill),

    /**
     * SF `target` — custom vector, a bullseye: two concentric rings and a solid
     * centre dot, no ticks.
     *
     * SF renders it tighter than the 1.25× rule of thumb: at 13 pt semibold the
     * ink box is 40 px = 13.33 pt, i.e. 1.0256 × the font size, where
     * [sfIconSize] hands the painter 1.25 ×. The vector's ink fills its
     * viewport, so the scale is the ratio of the two: 1.0256 / 1.25.
     */
    Target(R.drawable.ic_target, glyphScale = 0.8205f),

    /** SF `barcode.viewfinder` — custom vector, bars inside corner brackets. */
    BarcodeViewfinder(R.drawable.ic_barcode_viewfinder),

    /** SF `calendar.badge.minus` — custom vector; the badge is a −, not an ✕. */
    CalendarBadgeMinus(R.drawable.ic_calendar_badge_minus),

    /** SF `trophy` — custom vector, outline. */
    Trophy(R.drawable.ic_trophy),

    /** SF `trophy.fill` — custom vector, filled. */
    TrophyFill(R.drawable.ic_trophy_fill),
}

/**
 * Alias so both spellings in the specs resolve. `NtIcon.Plus` and `NtIcons.Plus`
 * are the same constant; the composable below is a *function* named `NtIcon`,
 * which lives in a different namespace and does not clash.
 */
typealias NtIcon = NtIcons

/**
 * The one way to draw an icon.
 *
 * SF Symbols are sized by the *font size* they inherit; a symbol at `size: 16`
 * renders a glyph roughly 20 pt tall. Call sites therefore pass either an
 * explicit [size] or, better, [sfIconSize] of the iOS font size at that site.
 *
 * @param contentDescription `null` marks the icon decorative (the label beside it
 *   already carries the meaning) — same rule as SwiftUI's `.accessibilityHidden`.
 */
@Composable
fun NtIcon(
    icon: NtIcons,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    if (icon.glyphScale == 1f) {
        NtIcon(
            painter = painterResource(icon.res),
            modifier = modifier,
            size = size,
            tint = tint,
            contentDescription = contentDescription,
        )
        return
    }
    // [NtIcons.glyphScale] The node still measures `size`, so every call site keeps the box it
    // laid out and the glyph keeps its centre; `requiredSize` inside a centred `Box` lets the
    // painter draw into a larger square that overflows symmetrically, which is exactly how an
    // SF Symbol's ink leaves its point box. Scaling the box (not a `graphicsLayer`) keeps the
    // vector rasterising at its drawn size, so nothing is resampled.
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        NtIcon(
            painter = painterResource(icon.res),
            modifier = Modifier.requiredSize(size * icon.glyphScale),
            size = size * icon.glyphScale,
            tint = tint,
            contentDescription = contentDescription,
        )
    }
}

/** [NtIcon] for a painter that is not part of [NtIcons] (a remote or branded asset). */
@Composable
fun NtIcon(
    painter: Painter,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val semantics = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            this.role = Role.Image
        }
    } else {
        Modifier
    }
    androidx.compose.foundation.Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.then(semantics).size(size),
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * Point size of an SF Symbol rendered inside text of `fontPt` points.
 *
 * Compose's icons are nominally 24 dp while SF Symbols at 17 pt render nearer
 * 20–22 pt (research §9.3), so the iOS `.font(.system(size: n))` on an
 * `Image(systemName:)` maps to `n × 1.25` dp here.
 */
fun sfIconSize(fontPt: Float): Dp = (fontPt * 1.25f).dp
