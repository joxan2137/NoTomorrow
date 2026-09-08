package app.notomorrow.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.notomorrow.R

/**
 * Design tokens for No Tomorrow (direction A, dark-only).
 * 1:1 port of `NoTomorrow/DesignSystem/Theme.swift`; 1 pt -> 1 dp / 1 sp.
 * Nothing here may be replaced by `MaterialTheme.colorScheme`.
 */
object NT {

    object Colors {
        val ground = Color(0xFF0A0A0B)
        val surface = Color(0xFF1C1C1E)
        val surface2 = Color(0xFF2A2A2E)
        val surface3 = Color(0xFF38383C)
        val tabBar = Color(0xFF161618)

        val ink = Color(0xFFF2F2F4)
        val ink2 = Color(0xFFEBEBF5).copy(alpha = 0.60f)
        val ink3 = Color(0xFFEBEBF5).copy(alpha = 0.42f)
        val onPrimary = ground

        val ember = Color(0xFFFF6A2B)
        val emberTint = ember.copy(alpha = 0.12f)
        val good = Color(0xFF30D158)
        val bad = Color(0xFFFF375F)
        val badTint = bad.copy(alpha = 0.12f)

        val hairline = Color.White.copy(alpha = 0.12f)
        val border = Color.White.copy(alpha = 0.20f)
    }

    object Spacing {
        val screenH = 20.dp
        val cardPadding = 18.dp
        val section = 22.dp
        val row = 12.dp
    }

    object Radius {
        val card = 22.dp
        val tile = 16.dp
        val field = 12.dp
        val cell = 10.dp
        val pill = 28.dp
    }

    object Size {
        val primaryButton = 56.dp
        val cardButton = 52.dp
        val control = 44.dp
        val chip = 40.dp
        val tabBar = 49.dp
    }

    /**
     * iOS easing curves. **Never** write a bare `tween(n)` — Compose's default
     * easing is Material's `FastOutSlowIn`, which is visibly different.
     */
    object Ease {
        val out: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
        val inOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    }

    object Anim {
        val easeOut12 = tween<Float>(120, easing = Ease.out)   // PressScale
        val easeOut15 = tween<Float>(150, easing = Ease.out)   // field focus, chips, segmented
        val easeOut18 = tween<Float>(180, easing = Ease.out)   // OB/ST segmented
        val easeOut20 = tween<Float>(200, easing = Ease.out)   // most common
        val easeOut25 = tween<Float>(250, easing = Ease.out)
        val easeOut30 = tween<Float>(300, easing = Ease.out)
        val easeOut60 = tween<Float>(600, easing = Ease.out)   // ProgressRing trim

        val easeInOut20 = tween<Float>(200, easing = Ease.inOut)
        val easeInOut25 = tween<Float>(250, easing = Ease.inOut)
        val easeInOut28 = tween<Float>(280, easing = Ease.inOut)
        val easeInOut30 = tween<Float>(300, easing = Ease.inOut)

        /** iOS `spring(response: 0.35, dampingFraction: 0.70)`. */
        val spring070 = spring<Float>(dampingRatio = 0.70f, stiffness = 322.3f)

        /** iOS `spring(response: 0.35, dampingFraction: 0.85)`. */
        val spring085 = spring<Float>(dampingRatio = 0.85f, stiffness = 322.3f)
    }

    object Fonts {
        /**
         * Big Shoulders Display ExtraBold — hero numbers and the wordmark only.
         * The repo ships the same static face as iOS; line height stays the
         * font's natural one, exactly as SwiftUI's `.custom(_:size:)`.
         */
        fun display(size: Int, letterSpacing: TextUnit = TextUnit.Unspecified): TextStyle =
            TextStyle(
                fontFamily = displayFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = size.sp,
                letterSpacing = letterSpacing,
                fontSynthesis = FontSynthesis.None,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )

        // ─────────────────────────────────────────────────────────────────────
        // Leading is SF Pro's RENDERED line height, **not** the HIG text-style
        // table. `Theme.swift` builds every entry with `Font.system(size:)`, so
        // SwiftUI stacks lines at the face's own ascent+descent — a flat
        // 1.1932 x the point size — while the HIG table (13/18, 15/20, 17/22)
        // describes `.footnote`/`.subheadline`/`.body` *text styles*, which
        // carry extra leading this app never asks for.
        //
        // Measured on design/parity/ios at 3x: subheadline pitch 53 px
        // (17.67 pt), footnote 47 px (15.67 pt), title2 81 px (27 pt). Using
        // the HIG values made every stacked block 10-15 % too tall and the
        // error compounded down each screen (+26 px from the Fuel heading to
        // the dashboard hairline).
        // ─────────────────────────────────────────────────────────────────────
        val largeTitle = sans(34, 40.5, FontWeight.Bold, 0.0118f, 96.2f, 34f)
        val title1 = sans(28, 33.5, FontWeight.Bold, 0.0136f, 99.4f, 28f)
        val title2 = sans(22, 26.5, FontWeight.Bold, -0.0118f, 104.2f, 22f)
        val title3 = sans(20, 24.0, FontWeight.SemiBold, -0.0225f, 105.7f, 20f)
        val headline = sans(17, 20.5, FontWeight.SemiBold, -0.0253f, 119.2f)
        val body = sans(17, 20.5, FontWeight.Normal, -0.0253f, 120.8f)
        val callout = sans(16, 19.0, FontWeight.Normal, -0.0194f, 118.5f)
        val subheadline = sans(15, 18.0, FontWeight.Normal, -0.0153f, 116.2f)
        val subheadlineBold = sans(15, 18.0, FontWeight.SemiBold, -0.0153f, 124.8f)
        val footnote = sans(13, 15.5, FontWeight.Normal, -0.0062f, 124.0f)
        val footnoteBold = sans(13, 15.5, FontWeight.SemiBold, -0.0062f, 124.0f)
        val caption = sans(12, 14.5, FontWeight.Medium, 0f, 117.0f)

        /** 11/13 SemiBold. Tracking is the explicit `Theme.swift:81` 0.9 pt. */
        val eyebrow = sans(11, 13.0, FontWeight.SemiBold, 0.0818f, 125.2f)

        /**
         * `UIDatePicker`'s wheel digit. Measured on
         * design/ios26-reference/11-wheel-schedule-editor.png at native 3x: the
         * selected two-digit group is 75 x 51 px, i.e. a 17 dp digit height,
         * which is ~23 pt **regular** — not the 20 pt semibold [title3] the
         * wheel used to default to. Width stays neutral: the digits are already
         * tabular and the 75 px group matches Roboto Flex's own advance — so
         * this is the one entry the width fit below deliberately does **not**
         * touch. It keeps `wdth 100` at the text optical size; the group was
         * measured against SF, not derived from the ramp.
         */
        val wheelDigit = sans(23, 28.0, FontWeight.Normal, 0f, 100f)
    }
}

// MARK: - Font families

// ─────────────────────────────────────────────────────────────────────────────
// FITTED WIDTH RAMP — `scripts/fit_font_width.py`
//
// Roboto Flex is the SF Pro stand-in (research §6.10) and it does **not** set to
// the same measure. Round 1 shipped three hand-picked `wdth` buckets (106 / 100
// / 97) on the theory that "+6 wdth ≈ +6 % advance". The axis is an order of
// magnitude weaker than that — 6 units buy ~1.3 % — so the round-1 captures
// still ran 3-9 % NARROW at text sizes and ~5 % WIDE at 34 pt
// (`android-status.md` §3: "Start workout" 273 vs 288 px, "over 4 weeks ·
// logged 1 of 28 days" 574 vs 624, "Training solo" 625 vs 596).
//
// The fit is measured, not guessed. `scripts/fit_font_width.py`
//   1. threshold-scans the ink extent of 73 reference strings out of
//      design/parity/{ios,android-r1}/*.png (both 402 dp at 3x — 12-20 samples
//      for the styles that carry the screens, 3-5 for the rest),
//   2. instantiates this variable font with fontTools at candidate
//      (wdth, XTRA, opsz, wght) locations and measures each string with PIL at
//      the exact pixel size (3 px per pt) plus letterSpacing × (len − 1),
//   3. uses the model only as a RATIO against the same string's measured
//      round-1 width, so PIL hinting, the missing HarfBuzz kerning and the scan
//      threshold all cancel, and
//   4. least-squares fits `wdth` on the relative error against SF Pro.
//
// `opsz` mirrors SF Pro's own optical split — SF Pro **Text** below 20 pt, SF
// Pro **Display** from 20 pt up. Under the hinge the axis stays at Roboto
// Flex's text default 14: it is strongly non-linear below 14, and driving it
// per point size makes the fitted `wdth` jump around (105 at 12 pt against 117
// at both 11 and 13). Above the hinge `opsz` == the point size, and that is
// what turns the 34 pt title from 5 % too wide into a match *without*
// condensing the letterforms — wdth 99 rather than the 83 an opsz-14 fit
// demands. `XTRA` stays at Roboto Flex's default 468: it opens counters rather
// than advances, and a wdth-112 + XTRA-540 trial read visibly rounder than SF.
//
// | style           |  pt/wt | wdth  | opsz | n  | SF ÷ r1 | RMS r1 → fit  |
// |-----------------|--------|-------|------|----|---------|---------------|
// | largeTitle      | 34/700 |  96.2 |   34 |  5 |  0.973  | 3.19 → 1.47 % |
// | title1          | 28/700 |  99.4 |   28 |  – | interpolated 22↔34 pt   |
// | title2          | 22/700 | 104.2 |   22 |  3 |  1.012  | 2.29 → 2.05 % |
// | title3          | 20/600 | 105.7 |   20 |  – | extrapolated 22↔34 pt   |
// | headline        | 17/600 | 119.2 |   14 | 11 |  1.033  | 3.77 → 2.25 % |
// | body            | 17/400 | 120.8 |   14 | 20 |  1.037  | 3.89 → 1.89 % |
// | callout         | 16/400 | 118.5 |   14 |  – | interpolated 15↔17 pt   |
// | subheadline     | 15/400 | 116.2 |   14 |  8 |  1.031  | 3.36 → 1.71 % |
// | subheadlineBold | 15/600 | 124.8 |   14 |  4 |  1.048  | 5.06 → 1.79 % |
// | footnote        | 13/400 | 124.0 |   14 |  5 |  1.046  | 4.79 → 1.87 % |
// | footnoteBold    | 13/600 | 124.0 |   14 |  – | = footnote              |
// | caption         | 12/500 | 117.0 |   14 |  5 |  1.032  | 3.59 → 1.27 % |
// | eyebrow         | 11/600 | 125.2 |   14 | 12 |  1.038  | 3.92 → 1.88 % |
// | wheelDigit      | 23/400 | 100.0 |   14 |  – | measured directly       |
//
// The four display rows carry a −3.0 … −1.3 correction on top of the fit. It
// comes from re-measuring 42 of these strings on the emulator after the first
// pass: at `opsz 14` the model tracked the device to −0.1 % (n = 19 body rows),
// but at `opsz 34` the device rendered a consistent +1.2 % wider than predicted
// (Train, Progress and Settings all within ±0.3 % of each other), so the
// display rows are pulled back by that bias, pro-rated by how far each sits
// above the 14 pt default. Round-2 device RMS over those 42 strings: 4.12 % →
// 1.85 %, with the mean bias at ±0.4 % for headline, body and largeTitle.
//
// "SF ÷ r1" is the mean per-string width ratio the fit had to close; "RMS" is
// the root-mean-square relative width error over that style's samples, before
// and after. The residual 1.3-2.3 % is NOT removable by any axis: two different
// typefaces disagree per *glyph*, so e.g. "PROPOSE" matches SF to the pixel
// while "TRAINING" is 4 % short at the same setting. The tracking column is
// untouched — those are `Theme.swift`'s own values and re-fitting them would
// change the letter rhythm to buy less than the glyph noise.
// ─────────────────────────────────────────────────────────────────────────────

/** Roboto Flex's own `XTRA` default. Widening counters is not the lever here. */
private const val NtSansXtra = 468f

/** Roboto Flex's own `opsz` default — the SF Pro **Text** stand-in, below 20 pt. */
private const val NtSansTextOpsz = 14f

/** Roboto Flex, the SF Pro stand-in (research §6.10), at one axis location. */
private fun robotoFlex(weight: FontWeight, width: Float, opsz: Float): Font = Font(
    R.font.robotoflex_variable,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(
        weight,
        FontStyle.Normal,
        FontVariation.width(width),
        FontVariation.Setting("opsz", opsz),
        FontVariation.Setting("XTRA", NtSansXtra),
    ),
)

private fun buildNtSansFamily(width: Float, opsz: Float): FontFamily = FontFamily(
    robotoFlex(FontWeight.Normal, width, opsz),
    robotoFlex(FontWeight.Medium, width, opsz),
    robotoFlex(FontWeight.SemiBold, width, opsz),
    robotoFlex(FontWeight.Bold, width, opsz),
)

/**
 * One `FontFamily` per fitted (wdth, opsz) pair. Every entry is created while
 * [NT.Fonts] initialises — a `Font` is only a descriptor, and Android resolves
 * all of them off the one mmapped `robotoflex_variable.ttf` — so the map is
 * never written from more than one thread.
 */
private val ntSansFamilies = HashMap<Pair<Float, Float>, FontFamily>()

private fun ntSansFamilyFor(width: Float, opsz: Float): FontFamily =
    ntSansFamilies.getOrPut(width to opsz) { buildNtSansFamily(width, opsz) }

/**
 * The small-text family, for the handful of sizes with no ramp entry (the chart
 * axis at 12, the fuel ring's 14 and 8). `internal` for the probe screens.
 * Pinned to the [NT.Fonts.caption] location — 12 pt is what most of them are.
 */
internal val NtSansFamily: FontFamily = ntSansFamilyFor(117.0f, NtSansTextOpsz)

internal val displayFamily: FontFamily = FontFamily(
    Font(R.font.bigshouldersdisplay_extrabold, FontWeight.ExtraBold),
)

/**
 * One ramp entry. The weight is declared on the `Font` **and** synthesis is off,
 * or Roboto Flex gets synthetically double-bolded (research §6.10).
 */
private fun sans(
    size: Int,
    leading: Double,
    weight: FontWeight,
    trackingEm: Float,
    width: Float,
    opsz: Float = NtSansTextOpsz,
): TextStyle =
    TextStyle(
        fontFamily = ntSansFamilyFor(width, opsz),
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = leading.sp,
        letterSpacing = trackingEm.em,
        fontSynthesis = FontSynthesis.None,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

// MARK: - Text style helpers (Theme.swift's View modifiers)

/**
 * Tabular numerals for every number that sits in a column or ticks.
 * `Theme.swift`'s `.tabular()` == `.monospacedDigit()`.
 *
 * ⚠️ `tnum` is a no-op on the Big Shoulders display face — display-face counters
 * need fixed-width digit slots instead (research §5.4).
 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * 11 pt semibold tracked label (section labels, "NEXT SESSION").
 * The uppercasing is **not** here: it must run under the app locale, so call
 * sites pass an already-uppercased string (`util/NtStrings`).
 */
fun eyebrowStyle(color: Color = NT.Colors.ink2): TextStyle =
    NT.Fonts.eyebrow.copy(color = color)
