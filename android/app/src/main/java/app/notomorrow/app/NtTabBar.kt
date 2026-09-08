package app.notomorrow.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notomorrow.BuildConfig
import app.notomorrow.R
import app.notomorrow.designsystem.CapsuleShape
import app.notomorrow.designsystem.GlassDebug
import app.notomorrow.designsystem.GlassLensFrame
import app.notomorrow.designsystem.GlassStyle
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtBackdrop
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.liquidGlass
import app.notomorrow.designsystem.liquidGlassLens
import app.notomorrow.designsystem.ntBackdropSource
import app.notomorrow.designsystem.ntGlassLensAvailable
import app.notomorrow.designsystem.rememberNtBackdrop
import app.notomorrow.model.AppTab
import kotlin.math.abs

/**
 * The iOS 26 tab bar: a **floating Liquid Glass capsule**, not a bar.
 *
 * Measured on `design/ios26-reference/01-tabbar-dashboard.png` (402 × 874 pt @3x) and cross-checked
 * against the UIKit view-tree dumps (`docs/android-glass.md` §1.2):
 *
 * ```
 * UITabBar               (0, 791, 402, 83)      = 49 + bottomSafeArea(34)
 *   _UITabBarPlatterView (21,   0, 360, 62)     the visible capsule, pinned to the bar's TOP
 *     _UITabButton       (4, 4, 77, 54) ×5, pitch 68.75      items OVERLAP by 8.25
 *     _UILiquidLensView  (4, 4, 77, 54)         the selection pill
 * ```
 *
 * Re-measured here from the PNG: platter rows 2373-2558 = 62.00 pt tall, columns 63-1142 = 360.0 pt
 * wide at x 21.0, body `#1C1C1E` (28,28,30), rim 59/51/42 inward on all four edges, pill 78.0 pt
 * wide at a hard 28 -> 61 step. Nothing is a hairline, nothing casts a shadow, `NT.Colors.tabBar`
 * (#161618) is a dead token on iOS 26 (§1.11), and there is **no minimise-on-scroll** — Apple:
 * "On iOS, iPadOS, tvOS, and watchOS, the tab bar does not minimize."
 *
 * Content scrolls *under* the capsule: `LocalTabBarHeight` is [NtTabBarTokens.barHeight] — the very
 * expression this bar lays itself out with — and every screen adds it as bottom `contentPadding`.
 *
 * **Frame budget** (`docs/android-glass.md` §2.3). Three graphics layers keep a tab switch cheap:
 * the bar, the bar's own surface, and the pill. The pill's spring invalidates only the pill's draw;
 * the surface re-records its backdrop only when an icon tint tweens; and neither can reach the tab
 * shell's `NavHost` entry, which used to be the nearest layer above all of this — so every spring
 * frame rebuilt the whole page and its backdrop capture. The pill itself is a [liquidGlassLens]:
 * one fixed-size effect layer that moves, driven entirely in the draw phase.
 */
@Composable
fun NtTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val gap = NtTabBarTokens.bottomGap(navInset)
    // The same expression `LocalTabBarHeight` hands every screen, so the padding they apply and the
    // bar drawn over it are the same number by construction.
    val barHeight = NtTabBarTokens.barHeight(navInset)

    // Layer 1 of 3: the bar. Nothing drawn below this line may invalidate the page behind it.
    BoxWithConstraints(modifier.fillMaxWidth().height(barHeight).graphicsLayer()) {
        val platterWidth = (maxWidth - NtTabBarTokens.sideInset * 2).coerceAtLeast(0.dp)
        val count = AppTab.entries.size
        // pitch = (platterWidth - 16.25) / n ; itemWidth = pitch + 8.25. Verified against three
        // UIKit configurations to <= 0.17 pt: 402/5 -> 68.75/77.00, 440/5 -> 76.35/84.60,
        // 402/3 -> 86.00/94.00. Never hard-code 77: it is 12 % too wide on a 360 dp phone.
        val pitch = (platterWidth - NtTabBarTokens.pitchSlack) / count
        val itemWidth = pitch + NtTabBarTokens.itemOverhang

        val selectedIndex = AppTab.entries.indexOf(selected).coerceAtLeast(0)
        // The pill slides and morphs between items on the iOS spring; it never cross-fades.
        // `Animatable` rather than `animateFloatAsState` for one reason: the lens needs the
        // spring's live **velocity**, which is what drives `motion` and the stretch below.
        val pillT = remember { Animatable(selectedIndex.toFloat()) }
        LaunchedEffect(selectedIndex) { pillT.animateTo(selectedIndex.toFloat(), NT.Anim.spring070) }

        // One source for all five items: `indication` is null, so it is only ever read for the
        // press envelope, and iOS grows the blob on touch-down of any item, not just the selected.
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val motion = remember { mutableFloatStateOf(0f) }
        val stretch = remember { mutableFloatStateOf(0f) }
        PillMotionDriver(pillT, pressed, selectedIndex, motion, stretch)

        // The bar composes its own surface — platter glass, icons, labels — into a local backdrop
        // so the pill can be a lens *over* it. It is a second `NtBackdrop`, never a nesting of the
        // app's: `NtTabBar` is already outside that recording, and the pill is a sibling of this
        // one rather than a child, so nothing samples a layer it is inside of.
        val barBackdrop = rememberNtBackdrop()
        val lensOk = ntGlassLensAvailable(barBackdrop)
        val margin = NtTabBarTokens.lensMargin

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // What `BottomCenter` positions is exactly the capsule plus its gap — 62 + 21 dp,
                // never more than [NtTabBarTokens.barHeight] — so nothing here overflows the bar's
                // own container and the alignment has one unambiguous answer.
                //
                // It used to be an over-tall `requiredHeight` box pulled back by `offset(margin -
                // gap)`. A `Box` **grows to fit an oversized child** (`boxHeight = max(minHeight,
                // child)`), so that box moved the alignment origin by the same amount it overhung
                // by, and the capsule landed 29 px low — on top of the gesture handle, the exact
                // regression [NtTabBarTokens.bottomGap]'s doc says the 21 dp floor had fixed.
                .padding(bottom = gap)
                .size(platterWidth, NtTabBarTokens.platterHeight),
        ) {
            Box(
                modifier = Modifier
                    // The overhang, made invisible to the layout above: `wrapContentSize` reports
                    // the platter's own footprint (its size is coerced back into the incoming
                    // fixed constraints) while `unbounded = true` lets the content measure
                    // `margin` larger on every side, centred on it. The blob is 2.5 dp proud of
                    // the capsule mid-travel and its lens reaches `refractionAmount` past its own
                    // rim, so both the recorded surface and the node drawing it have to reach
                    // past the capsule — but neither may move it.
                    .wrapContentSize(align = Alignment.Center, unbounded = true)
                    .requiredSize(
                        platterWidth + margin * 2,
                        NtTabBarTokens.platterHeight + margin * 2,
                    ),
            ) {
                // Layer 2 of 3: the bar's surface. It repaints — and re-records `barBackdrop` —
                // when an icon's tint tweens, never because the pill moved over it.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer()
                        .then(if (lensOk) Modifier.ntBackdropSource(barBackdrop) else Modifier),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(platterWidth)
                            .height(NtTabBarTokens.platterHeight)
                            .liquidGlass(CapsuleShape, GlassStyle.Regular)
                            .then(
                                // Tiers with no AGSL lens keep the pill exactly as it was: a uniform
                                // additive lift under the icons, with no rim and no lens. Measured
                                // 28 -> 61 (+33) over `ground`, +32/+31/+29 over brighter backdrops —
                                // additive, not a composite (§1.2 "Pill optics").
                                if (lensOk) {
                                    Modifier
                                } else {
                                    Modifier.drawBehind {
                                        val left = (NtTabBarTokens.itemPadding + pitch * pillT.value)
                                            .toPx()
                                        drawRoundRect(
                                            color = Color(
                                                NtTabBarTokens.pillLift,
                                                NtTabBarTokens.pillLift,
                                                NtTabBarTokens.pillLift,
                                                1f,
                                            ),
                                            topLeft = Offset(left, NtTabBarTokens.itemPadding.toPx()),
                                            size = Size(
                                                itemWidth.toPx(),
                                                NtTabBarTokens.itemHeight.toPx(),
                                            ),
                                            cornerRadius = CornerRadius(
                                                NtTabBarTokens.pillRadius.toPx(),
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                },
                            ),
                    ) {
                        AppTab.entries.forEachIndexed { index, tab ->
                            TabItem(
                                tab = tab,
                                selected = tab == selected,
                                left = NtTabBarTokens.itemPadding + pitch * index,
                                width = itemWidth,
                                interaction = interaction,
                                onClick = { onSelect(tab) },
                            )
                        }
                    }
                }

                if (lensOk) {
                    // Drawn after — and outside — the recording, so it is a lens over the icons.
                    TabPillLens(
                        backdrop = barBackdrop,
                        margin = margin,
                        pitch = pitch,
                        restWidth = itemWidth,
                        index = pillT,
                        motion = motion,
                        stretch = stretch,
                    )
                }
            }
        }

        if (BuildConfig.DEBUG) {
            // The only way into GlassProbeScreen: a long press on the dead strip beside the
            // capsule. Debug builds only, and outside every tab's hit box.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(NtTabBarTokens.sideInset, 44.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { GlassDebug.showProbe = true })
                    },
            )
        }
    }
}

/**
 * Turns the pill spring into the lens's 0..1 `motion`, and the same signal into the stretch.
 *
 * The reference burst is unambiguous about the shape of this envelope: the blob is *fully* liquid
 * for the whole of the travel (`burst-04`, `burst-05`, `burst-10`: interior 33/30, big rim, big
 * fringes) and is already the flat additive capsule two to three frames after it lands
 * (33 -> 35 -> 51 -> 60 -> 61). So `motion` is the spring's own normalised speed, peak-held and
 * then bled off over [NtTabBarTokens.pillRelease] — the hold is what keeps the blob liquid through
 * the spring's velocity zero-crossing at the overshoot instead of flickering flat and back.
 *
 * The loop only exists while there is something to animate; at rest it is not scheduled at all, so
 * neither this nor the bar backdrop's re-record costs anything between selections. Its writes are
 * read by the pill's **draw** alone ([TabPillLens]), so a frame of it costs one draw invalidation
 * of one small layer — never a recomposition or a layout.
 */
@Composable
private fun PillMotionDriver(
    pill: Animatable<Float, AnimationVector1D>,
    pressed: Boolean,
    selectedIndex: Int,
    motion: MutableFloatState,
    stretch: MutableFloatState,
) {
    LaunchedEffect(pressed, selectedIndex) {
        val floor = if (pressed) NtTabBarTokens.pillPressMotion else 0f
        // `Animatable.velocity` is the spring's own velocity in animation time, so it is unchanged
        // by the system's animator duration scale — but this bleed is in wall-clock seconds. Both
        // have to live on the same clock or the envelope collapses long before the travel ends
        // under Developer Options' 10x, which is exactly the setting the parity captures use.
        val release = NtTabBarTokens.pillRelease *
            (coroutineContext[MotionDurationScale]?.scaleFactor?.takeIf { it > 0f } ?: 1f)
        var last = 0L
        while (pill.isRunning || motion.floatValue > 0.001f || floor > motion.floatValue) {
            withFrameNanos { now ->
                // Clamped so a dropped frame cannot bleed the whole envelope off at once.
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 1f / 20f)
                last = now
                // Index units per second; the pitch is uniform, so this is speed in item widths.
                val speed = (abs(pill.velocity) / NtTabBarTokens.pillVelocityMax).coerceAtMost(1f)
                val bled = (motion.floatValue - dt / release).coerceAtLeast(0f)
                motion.floatValue = maxOf(speed, bled, floor)
                stretch.floatValue = speed
            }
        }
        motion.floatValue = floor
        stretch.floatValue = 0f
    }
}

/**
 * The pill as a Liquid Glass **lens** over the bar's own surface: a [liquidGlassLens] node sampling
 * [backdrop] (the platter, the icons and the labels) and drawn above them.
 *
 * Geometry, measured on the burst (3 px = 1 dp): the rest capsule is 78 x 54 with r = h/2, and
 * mid-travel the blob is 91 x 67 with r = h/2 still — a uniform +13 dp, 6.5 dp on every side,
 * which is why one [NtTabBarTokens.pillGrow] drives both axes. On top of that it stretches along
 * the travel while it is fast, and there is only one travel axis in this bar.
 *
 * Nothing about the animation is read in composition. The node covers the whole overhang box and
 * never changes size; its effect layer is allocated once for the blob at full motion; and the
 * spring, `motion` and `stretch` are read inside [GlassLensFrame]'s producer, in the draw phase.
 * Layer 3 of 3: the `graphicsLayer` in front of it is what keeps that per-frame draw its own.
 */
@Composable
private fun BoxScope.TabPillLens(
    backdrop: NtBackdrop,
    margin: Dp,
    pitch: Dp,
    restWidth: Dp,
    index: Animatable<Float, AnimationVector1D>,
    motion: MutableFloatState,
    stretch: MutableFloatState,
) {
    val maxSize = DpSize(
        restWidth + NtTabBarTokens.pillGrow + NtTabBarTokens.pillStretch,
        NtTabBarTokens.itemHeight + NtTabBarTokens.pillGrow,
    )
    val maxStyle = remember { GlassStyle.pillLens(1f) }
    val frame = remember<Density.() -> GlassLensFrame>(margin, pitch, restWidth) {
        {
            val m = motion.floatValue
            val s = stretch.floatValue
            val grow = NtTabBarTokens.pillGrow * m
            val width = restWidth + grow + NtTabBarTokens.pillStretch * s
            val height = (NtTabBarTokens.itemHeight + grow).coerceAtLeast(1.dp)
            val left = margin + NtTabBarTokens.itemPadding + pitch * index.value +
                (restWidth - width) / 2
            val top = margin + (NtTabBarTokens.platterHeight - height) / 2
            GlassLensFrame(
                rect = Rect(Offset(left.toPx(), top.toPx()), Size(width.toPx(), height.toPx())),
                // CapsuleShape resolves to min(w, h) / 2 — r = h / 2 at both ends of the morph,
                // which is what the frames measure (27 at rest, 33.5 mid-travel).
                style = GlassStyle.pillLens(m),
            )
        }
    }
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer()
            .liquidGlassLens(
                backdrop = backdrop,
                shape = CapsuleShape,
                maxSize = maxSize,
                maxStyle = maxStyle,
                frame = frame,
            ),
    )
}

@Composable
private fun TabItem(
    tab: AppTab,
    selected: Boolean,
    left: Dp,
    width: Dp,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
) {
    val title = stringResource(tab.titleRes)
    // [measured/app] The icon/label asymmetry is the single most likely thing to get wrong from
    // reading RootView.swift: selected icon AND label are pure white, but an unselected icon is
    // near-white (#F4F4F6) while its label is a full two stops darker (#A0A0A8).
    val iconTint by animateColorAsState(
        targetValue = if (selected) TAB_ICON_SELECTED else TAB_ICON_UNSELECTED,
        animationSpec = tween(durationMillis = 150, easing = NT.Ease.out),
        label = "tabIconTint",
    )
    val labelTint by animateColorAsState(
        targetValue = if (selected) TAB_LABEL_SELECTED else TAB_LABEL_UNSELECTED,
        animationSpec = tween(durationMillis = 150, easing = NT.Ease.out),
        label = "tabLabelTint",
    )
    Box(
        modifier = Modifier
            .offset(x = left, y = NtTabBarTokens.itemPadding)
            .size(width, NtTabBarTokens.itemHeight)
            // iOS's `TabView` publishes the selected trait for free (`Label(tab.titleKey,
            // systemImage:)` inside it, `RootView.swift:53`); without `selected` TalkBack announces
            // all five items identically and there is no way to tell which tab is active.
            .selectable(
                selected = selected,
                enabled = true,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        NtIcon(
            icon = tab.icon,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = NtTabBarTokens.iconTop),
            size = NtTabBarTokens.iconSize,
            tint = iconTint,
            contentDescription = null,
        )
        NtText(
            text = title,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = NtTabBarTokens.labelTop),
            style = TAB_LABEL,
            color = labelTint,
            maxLines = 1,
        )
    }
}

/**
 * Every measured tab-bar number, in one place, so a re-measure is a one-line change.
 * All values are pt -> dp 1:1.
 */
object NtTabBarTokens {
    /** [view tree] The platter is inset 21 pt on each side; there is no 360 pt cap. */
    val sideInset = 21.dp

    /** [view tree] + a 7-depth chord fit: 62 pt tall, r = 31 = h/2, a true capsule. */
    val platterHeight = 62.dp

    /** [view tree] `_UITabButton` is 54 pt tall with 4 pt of padding all round. */
    val itemHeight = 54.dp
    val itemPadding = 4.dp

    /** `pitch = (platterWidth - pitchSlack) / n`. */
    val pitchSlack = 16.25.dp

    /** `itemWidth = pitch + itemOverhang` — items deliberately overlap; they do not tile. */
    val itemOverhang = 8.25.dp

    /** [view tree] 28 pt image box, top at item-y 6. The glyphs measure 24.0-26.7 pt tall. */
    val iconSize = 28.dp
    val iconTop = 6.dp

    /** [view tree] 12 pt line box at item-y 35 -> a 10 pt font. */
    val labelTop = 35.dp

    /** [measured/app] 234 px = 78.0 pt wide, r 27; the frame is 77, the SDF edge adds ~1. */
    val pillRadius = 27.dp

    /** [measured/app] Bar 28 -> pill 61. Additive, backdrop-independent, no rim. */
    const val pillLift = GlassStyle.PILL_REST_LIFT

    // ---- the moving lens -------------------------------------------------------------------
    //
    // MEASURED on `design/ios26-reference/pill/burst-*.png`. The optics live in
    // `GlassStyle.pillLens`; what follows is only the geometry and the timing.

    /**
     * How much the blob grows on every side while it travels, x2 per axis.
     *
     * Measured by masking off every pixel the rest frame does not render as flat platter (28,28,30)
     * or flat ground (10,10,11) — so no glyph edge can be mistaken for the blob — and taking the
     * bounding box of what the moving frame changes. The identical routine over both platforms:
     *
     * ```
     *              iOS burst-04   burst-09   burst-16     Android (was)
     *   width       87.0 dp       84.0        93.7         93.0
     *   height      70.3 dp       69.0        68.0         67.0
     * ```
     *
     * iOS's **height is the reliable half** — the blob's top and bottom extremes always fall on
     * flat platter or flat ground — and it is 69.1 +/- 1.2 dp across the three, i.e. +15 dp on the
     * 54 dp rest capsule. The widths are a floor, not a measurement: a blob whose left or right
     * extreme happens to sit over a label loses those columns to the mask.
     */
    val pillGrow = 15.dp

    /**
     * Extra length along the travel at full speed. **Measured zero**, kept as the one place a
     * re-measure would go.
     *
     * The burst does not resolve a stretch. `burst-04` is 2/3 of the way through a three-tab move,
     * where the spring is still running at 20 item widths per second — four times
     * [pillVelocityMax] — and it is the *narrowest* of the three (87.0 dp) while `burst-16`, near
     * the end of a one-tab move, is the widest (93.7). Height is flat at 68-70 dp however fast the
     * blob is going. So iOS's blob grows isotropically to within what these frames can measure,
     * and [pillGrow] alone puts the widest of them (93.7) and this bar's blob (92.0) inside a
     * dp and a half of each other. Any positive value here pushes the width past every frame
     * measured.
     */
    val pillStretch = 0.dp

    /**
     * Speed, in item widths per second, at which `motion` saturates.
     *
     * `NT.Anim.spring070` is `spring(response 0.35, damping 0.70)`; its step response peaks at
     * `(w0^2 / wd) * e^(-z*w0*t) * sin(wd*t)` = **8.2** item widths per second for a one-tab move
     * (w0 = 17.95, wd = 12.82, at t = 62 ms). At 5.0 a single-tab move is fully liquid across the
     * middle of its travel and the three-tab moves in the burst saturate for nearly all of theirs,
     * which is what those frames show.
     */
    const val pillVelocityMax = 5.0f

    /**
     * Seconds for the held envelope to bleed from 1 to 0 once the spring is slow.
     *
     * The burst walks the interior back 33 -> 35 -> 51 -> 60 -> 61 over three ~50 ms frames after
     * the blob lands, so the lens is gone about 150 ms after the travel ends.
     */
    const val pillRelease = 0.15f

    /** Touch-down grows the blob before the selection moves; the frames put it around a third. */
    const val pillPressMotion = 0.35f

    /**
     * How far the pill's surface and its recording overhang the capsule.
     *
     * The blob is 3.5 dp proud of the 62 dp platter at full motion ([pillGrow] / 2 over the 54 dp
     * item), and its lens samples up to `GlassStyle.pillLens`'s 11.5 dp refraction beyond its own
     * rim, plus the blur kernel. Under that the outer ring of the lens reads past the recorded
     * layer and comes back transparent — a black bite out of the blob's edge.
     *
     * It is free of the bar's own layout: `NtTabBar` hangs the overhang off a
     * `wrapContentSize(unbounded = true)`, which reports the capsule's footprint whatever this is,
     * so raising it can never move the capsule the way it could when the overhang was an
     * oversized `requiredHeight` box.
     */
    val lensMargin = 20.dp

    /**
     * Override for the gap between the capsule's bottom and the screen's bottom edge.
     *
     * iOS derives it: the bar is `49 + bottomSafeArea` and the 62 pt platter is pinned to its top,
     * so the gap is `bottomSafeArea - 13` (21 pt on every iPhone measured). Both available
     * simulators report a 34 pt inset, so the data cannot separate "inset - 13" from a constant 21
     * (§5.2) — hence one token, one place.
     *
     * The **parity captures settle it**: on Android's gesture navigation `navigationBars` reports
     * ~24 dp, `24 - 13` gives 11, and every capture 06-20 shows the capsule 10 dp lower than iOS
     * with the white gesture handle drawn across its bottom rim (measured iOS y 2373-2558 with 63 px
     * = 21 dp clear; Android y 2403-2588 with 33 px = 11 dp, handle at y 2580-2591). iOS never
     * overlaps. So 21 dp is the design constant and the derivation is the floor's *other* half:
     *
     *  - `maxOf(inset - 13, 21)` reproduces iOS exactly under gesture nav (21) and on every iPhone
     *    (34 - 13 = 21);
     *  - it keeps the derived value where the system bar is taller than iOS's home indicator —
     *    3-button navigation reports a 48 dp inset and still gets 35, clearing the strip;
     *  - and it subsumes the old 8 dp floor for a device that reports no bottom inset at all (a
     *    tablet, a desktop window), where the derived gap would be negative.
     *
     * There is deliberately **no upper bound**. An earlier `coerceIn(8.dp, 24.dp)` capped the gap at
     * 24, so with 3-button navigation the capsule's lower 11 dp sat *inside* the Back/Home/Recents
     * strip and shared its hit area.
     */
    var bottomGapOverride: Dp? = null

    /** The gap iOS lands on for every device measured, and the floor under the derivation. */
    val bottomGapMin = 21.dp

    fun bottomGap(navInset: Dp): Dp =
        bottomGapOverride ?: maxOf(navInset - 13.dp, bottomGapMin)

    /**
     * The container height: iOS's `49 + bottomSafeArea`, widened when the 8 dp floor above makes the
     * platter and its gap taller than that (`navInset < 21`).
     *
     * `MainTabScaffold` provides this as `LocalTabBarHeight` and [NtTabBar] lays itself out with it,
     * so the bottom `contentPadding` every scrolling screen applies is the bar's real height. They
     * used to be computed separately and disagreed by up to 21 dp.
     */
    fun barHeight(navInset: Dp): Dp =
        maxOf(NT.Size.tabBar + navInset, platterHeight + bottomGap(navInset))
}

// [measured/app] on 01-tabbar-dashboard.png at native 3x.
private val TAB_ICON_SELECTED = Color(0xFFFFFFFF)
private val TAB_ICON_UNSELECTED = Color(0xFFF4F4F6)
private val TAB_LABEL_SELECTED = Color(0xFFFFFFFF)
private val TAB_LABEL_UNSELECTED = Color(0xFFA0A0A8)

/** UIKit's tab-item label: 10 pt in a 12 pt line box. Smaller than any entry in `NT.Fonts`. */
private val TAB_LABEL = NT.Fonts.caption.copy(fontSize = 10.sp, lineHeight = 12.sp)

/** `AppTab.titleKey` — `tab.today` … `tab.bro`. */
private val AppTab.titleRes: Int
    get() = when (this) {
        AppTab.Today -> R.string.tab_today
        AppTab.Train -> R.string.tab_train
        AppTab.Fuel -> R.string.tab_fuel
        AppTab.Progress -> R.string.tab_progress
        AppTab.Bro -> R.string.tab_bro
    }

/** `AppTab.symbol` — `house`, `dumbbell`, `fork.knife`, `chart.line.uptrend.xyaxis`, `person.2`. */
private val AppTab.icon: NtIcons
    get() = when (this) {
        AppTab.Today -> NtIcons.House
        AppTab.Train -> NtIcons.Dumbbell
        AppTab.Fuel -> NtIcons.ForkKnife
        AppTab.Progress -> NtIcons.ChartLineUptrend
        AppTab.Bro -> NtIcons.Person2
    }
