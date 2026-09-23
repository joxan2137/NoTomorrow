package app.notomorrow.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Which way round the material reads. iOS 26 flips small chrome to a light appearance over bright
 * backdrops (`whiteTabs.png`: 254 -> 253 with inverted glyphs); No Tomorrow is dark-only and no
 * bright content ever sits under chrome, so [Light] is a declared hook, not a wired behaviour
 * (`docs/android-glass.md` §5.6).
 */
enum class GlassAppearance { Dark, Light }

/**
 * Every measured constant of Apple's Liquid Glass, in one immutable bag.
 *
 * The material is **not** an alpha composite over a blurred backdrop — it is a dynamic-range
 * compression (`docs/android-glass.md` §1.1):
 *
 * ```
 * out = contrast * blur(backdrop) + tint * L(aggregate)
 * L(agg) = liftLo + (liftHi - liftLo) * smoothstep(liftK0, liftK1, agg)
 * ```
 *
 * With the [Regular] numbers that is `0.294 * bd + L(agg)`, which reproduces four independent
 * iOS 26.1 captures to ±1/255: agg 0.039 -> 28 (`#1C1C1E`, the real app over `NT.Colors.ground`),
 * agg 0.50 local 0.0 -> 51, local 0.5 -> 89, local 1.0 -> 126.
 *
 * Only [Regular] is measured at four points. [Alert]/[Panel], [Popover], [Menu] and [Toggle]/[Knob]
 * are fits to one or two points each and are expected to move during the side-by-side pass
 * (§5.7). There is deliberately no preset for the wheel picker's selection band: §1.4 measures it
 * as a **flat** `#EBEBF5 @ 8.5 %` capsule — no rim, no lens, no blur — and `NtWheelBand` paints it
 * with a plain `background`. A `GlassStyle` for it only ever existed in `GlassProbeScreen`, which
 * therefore verified a path the app never executed.
 */
@Immutable
data class GlassStyle(
    // ---- blur -----------------------------------------------------------------------------
    /** MEASURED: Gaussian sigma 2.8 pt (10-90 % edge width 7.0-7.5 pt). Never raise it. */
    val blurSigma: Dp = 2.8.dp,

    // ---- rim refraction (the lens band) ---------------------------------------------------
    /** Band that bends, measured 4-5 pt at the capsule caps. Must stay <= the smallest radius. */
    val refractionHeight: Dp = 4.5.dp,
    /** Peak lateral displacement at the extreme edge. There is **no** interior displacement. */
    val refractionAmount: Dp = 3.0.dp,

    /**
     * Shape of the bend across [refractionHeight]. `0` (every measured preset) keeps the
     * `circleMap` bevel — a displacement that is essentially zero until the last fraction of a dp.
     * A positive value swaps in `pow(1 - depth/height, refractionPower)`, a broad ramp across the
     * whole band, which is the *lens* the moving tab pill is (see [PillLens]).
     */
    val refractionPower: Float = 0f,

    /**
     * Uniform magnification of everything under the shape, about its centre — the flat top of a
     * thick lens, as opposed to [refractionAmount]'s bevel. `M - 1`, so `0.128` renders the
     * backdrop 12.8 % larger. 0 for every static preset: iOS's chrome does not magnify at rest.
     */
    val lensMagnification: Float = 0f,

    // ---- chromatic dispersion -------------------------------------------------------------
    //
    // Seven spectral bands, each sampled at `refraction offset + t * spread` with t running from
    // +1 (red) to -1 (violet). The spread points outward, so a feature's violet copy always lands
    // outside its red one. It is its own field — the sum of the three terms below — rather than
    // a fraction of the bend, because iOS keeps the colour after the bend has relaxed. All three
    // at 0 (every static preset) skip the six extra taps entirely.

    /** Radial spread, px per px of distance from the shape's centre. */
    val dispersion: Float = 0f,
    /** Spread at the rim along the outward normal, fading linearly to 0 across [dispersionBand]. */
    val dispersionRim: Dp = 0.dp,
    val dispersionBand: Dp = 0.dp,
    /** Spread along the lens's travel: violet leads, red trails. */
    val dispersionDrift: DpOffset = DpOffset.Zero,

    // ---- specular rim ---------------------------------------------------------------------
    /**
     * MEASURED, re-fit. Walking inward from the capsule boundary of `01-tabbar-dashboard.png` over
     * a uniform `#0A0A0B` gives 59 / 51 / 42 / 28 (the body) at 1/3 pt steps, identically on all
     * four edges. Solving each for the white alpha over a 0.1098 body yields
     * 0.1366 / 0.1013 / 0.0617 / 0 at x = 0.5 / 1.5 / 2.5 / 3.5 px; least squares through those
     * four points is `a(x) = 0.165 * (1 - x / 3.667px)`, i.e. **1.22 pt** wide starting at
     * **0.165**, not the 1 pt / 0.14 the spec rounds it to. Reproduces 60 / 50 / 40 / 30 — every
     * sample inside §4's ±4 acceptance, where 1 pt / 0.14 renders 54 / 44 / 33 and is visibly thin.
     */
    val rimWidth: Dp = 1.22.dp,
    val rimAlpha: Float = 0.165f,
    /** Degrees, 0 = from above. Only read when [rimAniso] > 0. */
    val rimAngle: Float = 0f,
    /** MEASURED: 0. Over a uniform backdrop the rim is identical on all four edges. */
    val rimAniso: Float = 0f,

    // ---- transfer -------------------------------------------------------------------------
    val contrast: Float = 0.294f,
    val liftLo: Float = 0.098f,
    val liftHi: Float = 0.200f,
    val liftK0: Float = 0.19f,
    val liftK1: Float = 0.50f,
    /** The hue of the lift — a multiplier on the lift term, **not** an alpha-over colour. */
    val tint: Color = Color.White,
    /** 9-tap aggregate for the lift. `false` pins it to [liftLo] and saves 9 texture reads. */
    val luminanceAdaptive: Boolean = true,
    val appearance: GlassAppearance = GlassAppearance.Dark,

    // ---- extras ---------------------------------------------------------------------------
    /** Flat additive lift on top of everything. 0 for every NT component; see `NtTabBar` pill. */
    val overlay: Float = 0f,
    /** Grain amplitude. 0 everywhere today — iOS 26.1 shows no measurable grain. */
    val noise: Float = 0f,
    /**
     * Tier `Tint` fill, and the fallback whenever no backdrop is reachable. `NT.Colors.surface`
     * (#1C1C1E) is *exactly* what the equation yields over `NT.Colors.ground`, so the cheapest
     * tier is off by less than 1/255 on the one backdrop this app actually has.
     */
    val flatFill: Color = NT.Colors.surface,
) {
    companion object {
        /**
         * Tab bar and nav-bar buttons — small chrome that adapts to its backdrop.
         * MEASURED to ±1/255 at four points; the only preset to trust blindly.
         *
         * The four points are *luminance* points, and with a pure-white lift they render the
         * capsule over `ground` as (28, 28, 28). iOS renders (28, 28, 30) = `#1C1C1E` — the same
         * 2-count blue bias [flatFill] (`NT.Colors.surface`) already carries, which is why the
         * cheap tier looked right in the parity captures and the real-glass tier looked neutral.
         *
         * The transfer function cannot produce that bias with a white [tint]: blue needs a lift of
         * 0.1058 where red and green need 0.098. So the *lift* carries the blue and the tint scales
         * red and green back down. `tint * lift` is unchanged on red/green (0.9255 * 0.1058 = 0.098,
         * 0.9255 * 0.2159 = 0.200), so all four measured points still reproduce to ±1/255:
         *
         * ```
         * R,G = 0.294 * 0.0392 + 0.9255 * 0.1058 = 27.9/255 -> 28
         * B   = 0.294 * 0.0431 + 1.0000 * 0.1058 = 30.2/255 -> 30
         * ```
         *
         * Only [Regular] is re-fit this way: `Alert`/`Popover`/`Menu` carry the same +2 blue in
         * their measured [flatFill]s, but their transfer terms are one- or two-point fits and there
         * is no side-by-side real-glass capture of them yet to fit the hue against (§5.7).
         */
        val Regular = GlassStyle(
            liftLo = 0.1058f,
            liftHi = 0.2159f,
            // 236/255 = 0.9255 on red and green, 1.0 on blue.
            tint = Color(0xFFECECFF),
        )

        /** `docs/android-glass.md` spells this one `Chrome`. Same object. */
        val Chrome = Regular

        /**
         * Sheets and alerts — large chrome. Apple: bigger surfaces are more opaque, and they never
         * flip. Fitted to the sheet's measured `out = 0.233 * dimmed + 30/255`.
         */
        val Alert = GlassStyle(
            contrast = 0.233f,
            liftLo = 0.118f,
            liftHi = 0.118f,
            luminanceAdaptive = false,
            refractionHeight = 8.dp,
            flatFill = Color(0xFF1F1F21),
        )

        /** `docs/android-glass.md` spells this one `Panel`. Same object. */
        val Panel = Alert

        /**
         * Action sheets — an anchored popover, measurably clearer than a menu (89 -> 66).
         *
         * [flatFill] is **measured, not derived**: the confirmation dialog's body on
         * `17-confirmationdialog-finish.png` is (19, 21, 20) over this app's own `ground`, so the
         * fallback is `#141416` — what `NtActionSheet` paints through `ntGlassPanel` on the one
         * path that still cannot sample `NtBackdrop`: a caller inside a sheet, which is its own
         * window. Everywhere else the panel is composed in the app's `NtOverlayHost` and the
         * transfer above is really evaluated.
         *
         * **Re-fit against the real capture (2026-09-05).** The `0.233 / 0.178` this used to carry
         * came from the
         * sheet's transfer plus the "89 -> 66" popover reading on an engineered grey backdrop, and
         * over this app's own `ground` it renders **48/255** — the `#303030` body
         * `docs/android-status.md` §3 caught on parity screen 16, 28 counts brighter than iOS. With
         * the backdrop actually sampled (see `NtBackdrop`) the constants are now solvable from
         * `17-confirmationdialog-finish.png` itself: 161 k interior pixels outside the two capsules,
         * paired against the same screen blurred at sigma 2.8 dp, give
         *
         * ```
         * bd 10.3 -> (19, 21, 20)      bd 58.3 -> (25, 27, 26)      slope 6/48 = 0.125
         * ```
         *
         * so `contrast = 0.115`, `lift = 0.0738`, which renders (20, 20, 20) over `ground` against
         * the measured (19, 21, 20) — every channel inside 1/255. The card is *much* flatter than a
         * menu, which is the "larger surfaces are more opaque" rule continuing past it.
         */
        val Popover = GlassStyle(
            contrast = 0.115f,
            liftLo = 0.0738f,
            liftHi = 0.0738f,
            luminanceAdaptive = false,
            refractionHeight = 8.dp,
            flatFill = Color(0xFF141416),
        )

        /**
         * Menus — larger still, and they *darken* their backdrop. [flatFill] measured the same way:
         * the set-kind menu's body on `16-menu-setkind.png` is (21, 21, 22) over `ground`, matching
         * `NtMenu`'s own `#141416`.
         *
         * **Fitted against the real capture (2026-09-05), not against the grey-backdrop reading.**
         * The `0.20 / 0.118` this used to carry came from `docs/android-glass.md` §1.8's single
         * "89 -> 48" sample on a 35 % grey; over this app's own `ground` it renders **32/255**, the
         * dead-flat `#202020` `docs/android-status.md` §3 caught on parity screen 15. With the
         * backdrop actually sampled (see `NtBackdrop`) the menu's own capture supplies both ends:
         * 364 k interior pixels of `16-menu-setkind.png`, paired against the same screen blurred at
         * sigma 2.8 dp, split into two dense clusters —
         *
         * ```
         * bd 10.3 (ground, n=241k) -> (21, 21, 22)
         * bd 43.3 (a set field,  n=50k) -> (28, 28, 30)      slope 7/33 = 0.212
         * ```
         *
         * — and a least-squares line through all of them independently returns
         * `contrast = 0.198, lift = 0.076` (rms 2.1/255, which is the alignment error between the
         * two devices' content, not the model). The blue bias rides on [tint] the way [Regular]'s
         * does, so `lift` is the blue lift and red/green are scaled by 236/255:
         *
         * ```
         * R,G = 0.212 * 0.0392 + 0.9255 * 0.0765 = 20.2/255   (iOS 21)
         * B   = 0.212 * 0.0431 + 1.0000 * 0.0765 = 21.8/255   (iOS 22)
         * over a set field: (27.2, 27.2, 29.2)                (iOS 28, 28, 30)
         * ```
         *
         * The §1.8 "89 -> 48" reading is *not* reproduced by this fit (it predicts 38) and is not
         * reconcilable with it: one of the two captures has a lift that depends on the aggregate,
         * and only this one is the app. Parity follows the app.
         */
        val Menu = GlassStyle(
            contrast = 0.212f,
            liftLo = 0.0765f,
            liftHi = 0.0765f,
            luminanceAdaptive = false,
            refractionHeight = 8.dp,
            // 236/255 = 0.9255 on red and green, 1.0 on blue — see [Regular].
            tint = Color(0xFFECECFF),
            flatFill = Color(0xFF141416),
        )

        /**
         * The `NtToggle` knob: real glass over a solid track, sampled through a per-toggle
         * [NtBackdrop] of the **track**, never the page.
         *
         * `docs/android-glass.md` §3.2 fits this to a single data point — over the ON track
         * (#F2F2F4 = 0.949) the knob must saturate, `0.55 * 0.949 + 0.48 = 1.00`. There are
         * actually **two**, and the second kills that fit: `13c-toggle-t1.png` reads the OFF knob
         * at (254,254,254) over a (90,90,94) track, where `0.55 * 0.353 + 0.48` renders 172/255 —
         * a visibly grey thumb on every settings row. Both captures also show a knob with **no
         * rim** and no interior gradient: it is a flat #FFFFFF disc in both states.
         *
         * So the fit is degenerate — `lift = 1.0` saturates over every sample the track can
         * present, including the transparent margin the blur and the lens reach into just outside
         * the 63 × 28 recording, which is what a smaller lift turns into a grey halo at the knob's
         * edge. That is not a fudge, it is what the two captures say; and the *mechanism* (the
         * track backdrop, the lens band, the press response) is now in place, so when §5.1's
         * missing mid-drag frame is finally captured the correction is three numbers here rather
         * than a rewrite of `NtToggle`.
         */
        val Toggle = GlassStyle(
            blurSigma = 2.0.dp,
            refractionHeight = 5.dp,
            refractionAmount = 4.dp,
            contrast = 0.55f,
            liftLo = 1.0f,
            liftHi = 1.0f,
            luminanceAdaptive = false,
            // MEASURED: the knob has no specular rim — 254/255 flat to its antialiased edge.
            rimAlpha = 0f,
            flatFill = Color.White,
        )

        /** `docs/android-glass.md` spells this one `Knob`. Same object. */
        val Knob = Toggle

        // ---- the tab bar's selection pill --------------------------------------------------
        //
        // MEASURED on `design/ios26-reference/pill/burst-*.png` (iPhone 17 Pro, 1206 x 2622,
        // Simulator slow-mo, 3 px = 1 dp). At rest the pill is the flat additive capsule §1.2
        // measured; *while it travels* it is a `_UILiquidLensView` — a different material — and
        // [pillLens] is the one-parameter path between the two.
        //
        // Rest, `burst-30`:   platter (28,28,30) -> pill (61,61,63), a hard +33 step, no rim.
        // Travel:             **three** flat-backdrop points, none of them a glyph edge, read down
        //                     the blob's own centre column in `burst-04`:
        //
        //                       platter 28 -> 33        (the blob's interior)
        //                       ground  10 -> 15/17     (the band the rim's bend samples from
        //                                                outside the capsule, y 2370-2382 and
        //                                                2558-2564, a *constant* 13-px plateau)
        //                       label  160 -> 163       (`burst-17`: the only frame in the whole
        //                                                burst where the blob covers a glyph that
        //                                                is neither the source nor the destination
        //                                                of the move, so its tint is not animating)
        //
        //                     One line fits all three: `out = src + 5.4/255`. The lens does **not**
        //                     change contrast — it only trades its rest lift of +33 for +5, and
        //                     everything else you see in the blob is geometry (bend, magnification,
        //                     dispersion, rim).
        //
        //                     An earlier fit read `out = 1.57 * src - 0.043` off the Train label
        //                     going 163 -> 245 in `burst-04`. That was the **selection tint**, not
        //                     the lens: `burst-04`'s Train is the tab being selected, so its label
        //                     is animating #A0A0A8 -> white on the 150 ms `tween` underneath, and
        //                     every other frame in the burst has the same confound (the item inside
        //                     the blob is always the move's source or destination — `burst-17` is
        //                     the sole exception, and it says +3, not +82). The 1.57 boost also
        //                     rendered the ground under the bend at `1.57 * 10 - 11` = **5**, a
        //                     black crescent across the top and bottom of the blob where iOS shows
        //                     a flat 15-17.
        //                     `burst-04..12` walk the interior back 33 -> 35 -> 51 -> 60 -> 61 over
        //                     three ~50 ms frames.
        // Rim, `burst-04`:    the centre column climbs 17 -> 111 -> 86 -> 60 -> 39 inward from the
        //                     blob's top edge (and 15 -> 110 -> 86 -> 60 -> 49 at the bottom).
        //                     `mix(base, white, alpha * (1 - depth / width))` reproduces all four
        //                     samples to <= 3/255 at **alpha 0.44 over a 1.5 dp band** (109/85/62/38)
        //                     — wider and brighter than [Regular]'s 1.22 dp at 0.165, which renders
        //                     78/55/32/8 and reads as a thin scratch rather than a lit edge.
        // Magnification:      the Train glyphs sit 12.8 % further from the blob centre than they
        //                     do in `burst-30` (icon box 87 x 51 px -> 98 x 58, label half-width
        //                     34 -> 40.5), with no compression until the rim band.
        // Bend:               the platter's own top rim (true y 2373, 8 px inside the blob) is
        //                     displayed at y 2384, 19 px inside — a 19.3 px outward sample where
        //                     the magnification alone would give -9.5. It is back to zero by
        //                     ~40 px depth. A linear ramp over a 13 dp band peaking at 11.5 dp
        //                     reproduces both ends; `circleMap` cannot (it would need a 48 dp peak).
        // Dispersion:         re-measured 2026-09-23 per channel, at every edge a frame has. The old
        //                     three-tap fit (12 % of the bend) rendered 0.7-1.4 px fringes mid-lens
        //                     and none once the bend relaxed; iOS's are rainbows, and they sit in
        //                     three places, none of which scales with the bend:
        //                     - the rim band. Glyph edges near the blob's edge split up to 11-13 px
        //                       violet-to-red by `key-05`'s left rim (90th percentile), and 4-8 px
        //                       in the top and bottom ~12 dp of `burst-18`, where the pill has
        //                       already shrunk back to its rest size;
        //                     - the travel. `key-04`'s dumbbell trails a faint (+20-40/255) violet
        //                       copy ~3 dp ahead of each plate and a red one behind, however far the
        //                       plate is from the centre;
        //                     - the interior. Only 0.3-0.7 px (`key-04`, `burst-11`).
        //                     Hues: `key-05`'s saturated pixels run orange -> yellow -> green ->
        //                     azure -> violet, not just red and blue, hence seven spectral bands.
        //                     Sign: the violet copy is always the outer one (the blue edge above
        //                     the warm platter-rim streak in `key-05`'s top crescent, the violet
        //                     ghost above the roof in `burst-18`).

        /**
         * Peak blur while the lens is fully liquid; the rest state has none, the icon is crisp.
         *
         * Tiny on purpose. The label strokes under the lens in `burst-04` go from a 1 px edge ramp
         * (29/96/162/163/116/48) to a 2 px one — most of the softening the frames show is the
         * magnification's resampling and the three-way colour split, not a blur. 2.8 dp, or even
         * 1.6, turns the blob into frosted glass and loses the glyphs it is supposed to magnify.
         */
        private val PILL_BLUR = 0.4.dp
        private val PILL_LENS_BAND = 13.dp
        private val PILL_LENS_AMOUNT = 11.5.dp
        private const val PILL_MAGNIFICATION = 0.128f
        private const val PILL_RIM_ALPHA = 0.44f

        /**
         * The three dispersion terms at full colour (see the block above), fitted by rendering
         * this shader over the burst's own rest frame and counting the pixels whose chroma
         * (max - min) passes 12 % and 25 %, the same count run on the iOS frame:
         *
         * ```
         *                    iOS          three-tap (old)   this
         *   key-04  travel   0 / 1542     365 / 1615        306 / 1422
         *   key-05  rim      3781 / 6200  740 / 1914        2840 / 5145
         *   burst-18 settle  1233 / 1525  0 / 10            880 / 1481
         *   burst-19 parked  0 / 66       0 / 0             0 / 0
         * ```
         *
         * The old split was only ever right mid-travel: it died with the bend, so the settle —
         * where iOS flashes its rainbow — came out grey. The rim band's violet-to-red centroid
         * split is 1.1x its spread: 5 dp is ~16 px at the edge, ~8 px half-way in. The drift is
         * small on purpose — `key-04`'s ghosts are faint, and a full dp of it turned a 10 pt label
         * into a smear.
         */
        private const val PILL_DISPERSION = 0.012f
        private val PILL_DISPERSION_RIM = 5.dp
        private val PILL_DISPERSION_BAND = 16.dp
        private val PILL_DISPERSION_DRIFT = 0.25.dp

        /**
         * How much of the rainbow `motion` carries: all of it from half-liquid up, none below 0.08.
         *
         * Mid-settle this is `sqrt`'s strength (0.54 at 0.3, where `burst-18` splits hardest). The
         * cut-off is for the spring's tail: its last few hundredths of speed keep `motion` above 0
         * for several hundred ms after the pill looks parked, and a `sqrt` curve turned that into
         * blue fringes on a resting icon (17 frames of the 10x emulator burst). It also sits under
         * `NtTabBarTokens.pillPressMotion`, so a held tab still shimmers.
         */
        private fun pillColour(m: Float): Float {
            val t = ((m - 0.08f) / (0.5f - 0.08f)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        private val PILL_RIM_WIDTH = 1.5.dp

        /** MEASURED: 28 -> 61 over the platter. Additive, backdrop-independent, no rim. */
        const val PILL_REST_LIFT = 0.129f

        /**
         * MEASURED: `out = src + 5.4/255` fits (10 -> 15/17), (28 -> 33) and (160 -> 163).
         *
         * The moving lens keeps the pill's contrast at 1 and only drops its lift, +33 -> +5. See
         * the block above for why the +82 the label appears to gain is the selection tween.
         */
        private const val PILL_TRAVEL_CONTRAST = 1f
        private const val PILL_TRAVEL_OFFSET = 0.021f

        /**
         * The selection pill, anywhere between at rest (`motion` 0) and fully liquid (`motion` 1).
         *
         * At 0 this is **exactly** the measured rest optics and nothing else: `contrast = 1`,
         * `lift = 0.129`, no blur, no band, no rim, no dispersion — so the shader evaluates
         * `backdrop + 33/255`, which is the `BlendMode.Plus` capsule this replaced, to the bit.
         * That is the whole reason the pill can be one node instead of a cross-fade between two.
         *
         * At 1 it is the lens: the same backdrop, lifted by +5 instead of +33 — magnified, bent
         * outward at the rim, split into a spectrum and rimmed at 0.44. The blob reads as a
         * blob because of its *geometry*, not because of a brightness step: contrast never leaves
         * 1, so a glyph that passes under it comes out the brightness it went in.
         *
         * [drift] is the lens's signed speed along x, -1..1 of `NtTabBarTokens.pillVelocityMax`.
         *
         * The colour has its own curve, [pillColour]: `burst-18` is still split 4-8 px once the
         * blob has shrunk back to its rest size, so the rainbow has to outlast the bend — and
         * `burst-19` is clean, so it must not outlast it by much. At 0 it is 0 like everything
         * else, and the rest state above still holds to the bit.
         */
        fun pillLens(motion: Float, drift: Float = 0f): GlassStyle {
            val m = motion.coerceIn(0f, 1f)
            val colour = pillColour(m)
            return GlassStyle(
                blurSigma = PILL_BLUR * m,
                // Both scale with m so the band collapses to nothing at rest and the `if` in the
                // shader is skipped entirely — no sampling difference, no cost.
                refractionHeight = PILL_LENS_BAND * m,
                refractionAmount = PILL_LENS_AMOUNT * m,
                refractionPower = 1f,
                lensMagnification = PILL_MAGNIFICATION * m,
                dispersion = PILL_DISPERSION * colour,
                dispersionRim = PILL_DISPERSION_RIM * colour,
                dispersionBand = PILL_DISPERSION_BAND,
                dispersionDrift = DpOffset(PILL_DISPERSION_DRIFT * (drift.coerceIn(-1f, 1f) * colour), 0.dp),
                // Width scales too, so at rest `depth < rimWidth` is false everywhere and the
                // shader's rim block is skipped rather than evaluated at alpha 0.
                rimWidth = PILL_RIM_WIDTH * m,
                rimAlpha = PILL_RIM_ALPHA * m,
                contrast = 1f + (PILL_TRAVEL_CONTRAST - 1f) * m,
                // Both ends are a signed *offset*, so they ride on `overlay` and the lift term is
                // switched off entirely — which also means `tint` never enters the pill's colour
                // and the additive step at rest is the measured, neutral +33/+33/+33.
                liftLo = 0f,
                liftHi = 0f,
                // A 9-tap aggregate over a rect that is half white glyph would swing the result
                // every frame; both ends of this preset are single measured numbers anyway.
                luminanceAdaptive = false,
                overlay = PILL_REST_LIFT + (PILL_TRAVEL_OFFSET - PILL_REST_LIFT) * m,
                // The pill sits *above* the icons. A tier that cannot sample must draw nothing at
                // all rather than a slab over the selected glyph — `NtTabBar` keeps the additive
                // `BlendMode.Plus` capsule for those tiers and never composes this node.
                flatFill = Color.Transparent,
            )
        }
    }
}
