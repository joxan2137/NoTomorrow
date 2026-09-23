package app.notomorrow.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

// ─────────────────────────────────────────────────────────────────────────────
// Measured geometry — docs/android-glass.md §1.8, re-measured here at native 3x
// on design/ios26-reference/16-menu-setkind.png (4-item, text-only set-kind menu).
//
//   panel      x 20.0 → 270.0 (250.0 pt)   y 208.5 → 395.9 (187.4 pt)
//   corner     circular fit r = 31 at seven depths (≤0.4 pt)  ⇒ 33 continuous
//   item pitch 42.0 / 41.4 / 42.3          ⇒ 42 pt, v-padding 9.7 each end
//   label      cap height 12.6 pt ⇒ 17 pt; ink starts x 48.67 ⇒ 28 pt inset
//   body       (20,20,21) over `ground` — the menu DARKENS its backdrop
//   separators none.  scrim none.
// ─────────────────────────────────────────────────────────────────────────────

/** One row of an [NtMenu] — SwiftUI's `Button` inside a `Menu { }`. */
@Immutable
data class NtMenuItem(
    val title: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val icon: NtIcons? = null,
    /**
     * A `Divider()` above this row. §1.8 measures **no** separators on the set-kind menu, and that
     * is right — SwiftUI only draws one where the source declares it; the only menu that declares
     * any is the AI-scan grams menu (`AIScanFoodRow.swift`), around its count/edit group.
     */
    val separatorBefore: Boolean = false,
    /** SwiftUI's `.disabled(…)` on a menu button: drawn in `ink3`, not tappable. */
    val enabled: Boolean = true,
)

/** Measured 250 pt on the set-kind menu; long labels grow the panel to [NtMenuMaxWidth]. */
val NtMenuWidth: Dp = 250.dp
private val NtMenuMaxWidth: Dp = 320.dp
private val NtMenuItemHeight: Dp = 42.dp
private val NtMenuVerticalPadding: Dp = 10.dp

/** The leading gutter the label starts after; the optional icon lives inside it. */
private val NtMenuLeadingGutter: Dp = 28.dp
private val NtMenuTrailingPadding: Dp = 16.dp
private val NtMenuRadius: Dp = 33.dp

/** docs/android-glass.md §3.6: `alpha tween(180, CubicBezier(0.32, 0.72, 0, 1))`. */
private val NtMenuEase = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * iOS 26 `Menu { … }`: a glass platter that pops open from the control that
 * owns it, with no scrim and no separators, dismissed by a tap anywhere else.
 *
 * Place it **inside the `Box` that holds the anchor**, the way Material's
 * `DropdownMenu` is placed — the panel is positioned from that `Box`'s bounds
 * and scales out of the corner nearest it:
 *
 * ```
 * Box(Modifier.ntPlainClickable { expanded = true }) {
 *     Ellipsis()
 *     NtMenu(expanded, onDismiss = { expanded = false }, items = items)
 * }
 * ```
 *
 * **Where it is drawn matters.** `docs/android-glass.md` §3.6: *"Material 3's `DropdownMenu` renders
 * in a `Popup`, i.e. a separate window with its own `GraphicsContext`, so it cannot sample our
 * `GraphicsLayer`. Render the menu inside the root `Box` instead."* So the panel is registered with
 * the app's [NtOverlayHost] and composed there — over the recorded backdrop, in the same window —
 * and it darkens whatever is actually behind it (89 → 48), instead of painting a constant `#141416`
 * that is only right over `NT.Colors.ground`. All four call sites open the menu **over content**:
 * the set-kind menu sits on the ActiveWorkout set rows, the log-to-meal menu over the AI-scan sheet.
 *
 * A caller inside a sheet has no reachable host (that sheet is its own window, stacked above the
 * host), and keeps the `Popup` path — same geometry, same flat fill.
 *
 * Five call sites: the set-kind menu (`SetRowView.swift:52`), the remove-exercise
 * menu (`WorkoutExerciseSection.swift:78`), the grams-rescale menu
 * (`AIScanFoodRow.swift:58`), the log-to-meal menu (`AIScanResultView.swift:139`) and the
 * Fuel entry row's long-press menu (iOS `.contextMenu` on `FuelEntryRow`).
 */
@Composable
fun NtMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<NtMenuItem>,
    modifier: Modifier = Modifier,
) {
    val host = currentNtOverlayHost()
    if (host == null) {
        NtMenuPopup(expanded, onDismiss, items, modifier)
        return
    }

    // A zero-size probe that reports the ANCHOR's bounds: `NtMenu` is placed inside the anchor's
    // Box, so the parent's coordinates are the anchor's, exactly what `Popup` gets for free.
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Box(
        Modifier.size(0.dp).onGloballyPositioned { coords ->
            anchor = (coords.parentLayoutCoordinates ?: coords).boundsInWindow()
        },
    )

    val transition = remember { MutableTransitionState(false) }
    transition.targetState = expanded
    if (!transition.currentState && !transition.targetState) return
    val bounds = anchor ?: return

    NtOverlayContent(host) {
        // The overlay is a plain `Box` in the app's own window, so unlike the `Popup` fallback
        // nothing gives back-dismissal for free. iOS closes a menu on the interactive-pop gesture
        // exactly as it does on an outside tap; `enabled` keeps the handler off the stack while the
        // exit animation drains, so a second back reaches the screen underneath.
        BackHandler(enabled = expanded, onBack = onDismiss)
        val density = LocalDensity.current
        // The panel's size is known before it is measured — every row is exactly 42 dp and the
        // panel is 250 dp wide — so the first frame is already in the right place; `onSizeChanged`
        // only corrects a label that grew the panel past 250.
        val estimated = remember(items.size, density) {
            with(density) {
                IntSize(
                    NtMenuWidth.roundToPx(),
                    (NtMenuItemHeight * items.size + NtMenuVerticalPadding * 2).roundToPx(),
                )
            }
        }
        var panelSize by remember { mutableStateOf(estimated) }
        val placement = remember(bounds, panelSize, host.origin, host.size) {
            menuPlacement(bounds, panelSize, host.origin, host.size)
        }

        Box(Modifier.fillMaxSize()) {
            // No scrim (§1.8) — but an outside tap dismisses, and must not also reach the control
            // underneath, exactly like a UIKit menu.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
            )
            Box(Modifier.offset { placement.offset }) {
                AnimatedVisibility(
                    visibleState = transition,
                    enter = scaleIn(
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                        initialScale = 0.86f,
                        transformOrigin = placement.origin,
                    ) + fadeIn(tween(180, easing = NtMenuEase)),
                    exit = scaleOut(
                        animationSpec = tween(140, easing = NT.Ease.out),
                        targetScale = 0.90f,
                        transformOrigin = placement.origin,
                    ) + fadeOut(tween(140, easing = NT.Ease.out)),
                ) {
                    NtMenuPanel(
                        items = items,
                        onDismiss = onDismiss,
                        modifier = modifier.onSizeChanged { panelSize = it },
                        glass = true,
                    )
                }
            }
        }
    }
}

/** The fallback for a caller with no reachable overlay host — a sheet, or a preview. */
@Composable
private fun NtMenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<NtMenuItem>,
    modifier: Modifier,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = expanded
    if (!transition.currentState && !transition.targetState) return

    var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val provider = remember { NtMenuPositionProvider { origin = it } }

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = transition,
            enter = scaleIn(
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                initialScale = 0.86f,
                transformOrigin = origin,
            ) + fadeIn(tween(180, easing = NtMenuEase)),
            exit = scaleOut(
                animationSpec = tween(140, easing = NT.Ease.out),
                targetScale = 0.90f,
                transformOrigin = origin,
            ) + fadeOut(tween(140, easing = NT.Ease.out)),
        ) {
            NtMenuPanel(items = items, onDismiss = onDismiss, modifier = modifier, glass = false)
        }
    }
}

@Composable
private fun NtMenuPanel(
    items: List<NtMenuItem>,
    onDismiss: () -> Unit,
    modifier: Modifier,
    glass: Boolean,
) {
    val shape = NtShapes.rounded(NtMenuRadius)
    Column(
        modifier = modifier
            .widthIn(min = NtMenuWidth, max = NtMenuMaxWidth)
            // Measure to the widest LABEL, then clamp. Without this the Column
            // inherits the overlay host's full-width max constraint, `widthIn`
            // resolves it to the 320 dp cap and every `fillMaxWidth()` row
            // expands to it — the set-kind menu came out 319.3 dp against
            // iOS's 249, a platter reaching almost to the screen edge.
            .width(IntrinsicSize.Max)
            .then(
                if (glass) {
                    Modifier.liquidGlass(shape, GlassStyle.Menu)
                } else {
                    Modifier.clip(shape).ntGlassPanel(shape, GlassStyle.Menu)
                },
            )
            .padding(vertical = NtMenuVerticalPadding),
    ) {
        items.forEachIndexed { index, item ->
            if (item.separatorBefore && index > 0) {
                Hairline(Modifier.padding(vertical = 4.dp))
            }
            NtMenuRow(item) {
                if (item.enabled) {
                    onDismiss()
                    item.onClick()
                }
            }
        }
    }
}

@Composable
private fun NtMenuRow(item: NtMenuItem, onClick: () -> Unit) {
    val color = when {
        !item.enabled -> NT.Colors.ink3
        item.destructive -> NT.Colors.bad
        else -> NT.Colors.ink
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NtMenuItemHeight)
            .ntClickable(enabled = item.enabled, onClick = onClick)
            .padding(end = NtMenuTrailingPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(NtMenuLeadingGutter),
            contentAlignment = Alignment.Center,
        ) {
            if (item.icon != null) NtIcon(item.icon, size = 17.dp, tint = color)
        }
        NtText(
            text = item.title,
            style = NT.Fonts.body,
            color = color,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Placement
// ─────────────────────────────────────────────────────────────────────────────

private class NtMenuPlacement(val offset: IntOffset, val origin: TransformOrigin)

/**
 * SwiftUI `Menu`'s placement, in overlay-local coordinates: the platter's **top edge aligned with
 * the anchor's top** and its leading edge, flipped above when there is no room, clamped inside the
 * host. [TransformOrigin] is the anchor's centre expressed within the panel, so the pop-open scales
 * out of the control.
 *
 * Not Material's `DropdownMenu` rule (`y = anchor.bottom`): iOS opens the platter **over** the row
 * it belongs to. On the set menu that is the difference between covering sets 1-3 as iOS does
 * (panel top 208.3 dp for a row whose top is 208.7) and hanging 43 dp lower, past the exercise
 * divider and over the next exercise's title.
 */
private fun menuPlacement(
    anchor: Rect,
    panel: IntSize,
    hostOrigin: androidx.compose.ui.geometry.Offset,
    hostSize: IntSize,
): NtMenuPlacement {
    val left = anchor.left - hostOrigin.x
    val top = anchor.top - hostOrigin.y

    val maxX = (hostSize.width - panel.width).coerceAtLeast(0)
    val x = left.toInt().coerceIn(0, maxX)

    val above = anchor.bottom.toInt() - hostOrigin.y.toInt() - panel.height
    val fitsBelow = top.toInt() + panel.height <= hostSize.height
    val y = when {
        fitsBelow -> top.toInt().coerceAtLeast(0)
        above >= 0 -> above
        else -> (hostSize.height - panel.height).coerceAtLeast(0)
    }

    val cx = if (panel.width == 0) 0f else {
        ((anchor.center.x - hostOrigin.x - x) / panel.width).coerceIn(0f, 1f)
    }
    val cy = if (panel.height == 0) 0f else {
        ((anchor.center.y - hostOrigin.y - y) / panel.height).coerceIn(0f, 1f)
    }
    return NtMenuPlacement(IntOffset(x, y), TransformOrigin(cx, cy))
}

/** The same rules for the `Popup` fallback, which works in window coordinates. */
private class NtMenuPositionProvider(
    private val onOrigin: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val preferredX = if (rtl) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val x = preferredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))

        // Mirrors `menuPlacement`: the platter's TOP aligns with the anchor's top.
        val below = anchorBounds.top
        val above = anchorBounds.bottom - popupContentSize.height
        val fitsBelow = below + popupContentSize.height <= windowSize.height
        val y = when {
            fitsBelow -> below.coerceAtLeast(0)
            above >= 0 -> above
            else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }

        val cx = if (popupContentSize.width == 0) 0f else {
            ((anchorBounds.center.x - x).toFloat() / popupContentSize.width).coerceIn(0f, 1f)
        }
        val cy = if (popupContentSize.height == 0) 0f else {
            ((anchorBounds.center.y - y).toFloat() / popupContentSize.height).coerceIn(0f, 1f)
        }
        onOrigin(TransformOrigin(cx, cy))
        return IntOffset(x, y)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared chrome painting
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The Liquid Glass **body + specular rim** for a chrome platter that is rendered in its own window
 * (a `Popup` or a `Dialog`) and therefore cannot sample the app's [NtBackdrop] —
 * `Modifier.liquidGlass` needs a `GraphicsLayer` recorded in the same `GraphicsContext`, and a
 * popup window has its own.
 *
 * The body is [GlassStyle.flatFill], which for each preset is the value the measured transfer
 * function yields over this app's own `ground`, so over the backdrops these panels actually appear
 * on the result matches the iOS capture.
 *
 * The rim is derived from [GlassStyle.rimWidth] / [GlassStyle.rimAlpha], **not** hard-coded: §1.1's
 * re-fit is `a(x) = 0.165 · (1 − x / 3.667 px)`, i.e. 1.22 pt starting at 0.165, and the 1 pt / 0.14
 * this used to paint renders 54 / 44 / 33 against a measured 59 / 51 / 42 — "visibly thin". Three
 * concentric sub-dp strokes reproduce the ramp, and a re-measure of the two tokens now moves every
 * panel at once.
 */
internal fun Modifier.ntGlassPanel(
    shape: Shape,
    style: GlassStyle,
    fill: Color? = null,
): Modifier = this.drawWithCache {
    val path = Path().apply {
        addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
    }
    val body = fill ?: style.flatFill
    val rim = ntGlassRimStrokes(style, this)
    onDrawBehind {
        drawPath(path, color = body)
        if (rim.isNotEmpty()) {
            clipPath(path) {
                rim.forEach { (width, alpha) ->
                    drawPath(path, color = Color.White.copy(alpha = alpha), style = Stroke(width))
                }
            }
        }
    }
}

/**
 * The measured rim as three concentric strokes, `(strokeWidth, alpha)` each.
 *
 * §1.1's re-fit is a linear ramp `a(x) = rimAlpha · (1 − x / rimWidth)` — the four solved samples
 * are 0.1366 / 0.1013 / 0.0617 / 0 at x = 0.5 / 1.5 / 2.5 / 3.5 px. Reproducing it with strokes
 * needs two things right:
 *
 *  * a `Stroke` is **centred** on the path, so one of width `2·d` clipped to the shape paints
 *    exactly the outermost `d` of the interior. The three widths are therefore `2·w/3`, `4·w/3`
 *    and `2·w` — nested, not adjacent;
 *  * because they are nested they **composite**, so the stroke alphas are not the ramp's values.
 *    Solve inward-out for a white-over-white alpha composite (which is order-independent, so the
 *    draw order below is free) such that the *rendered* alpha at each band's midpoint is the ramp's.
 *
 * At the default 1.22 dp / 0.165 the rendered ramp is 0.1375 / 0.0825 / 0.0275 across the three
 * bands, which is the measured 59 / 51 / 42 over a `#0A0A0B` body — inside §4 row 4's ±4. The flat
 * `rimAlpha · 0.5` this replaced rendered a constant 0.0825 and could not pass it.
 */
internal fun ntGlassRimStrokes(
    style: GlassStyle,
    density: androidx.compose.ui.unit.Density,
): List<Pair<Float, Float>> {
    val rimPx = with(density) { style.rimWidth.toPx() }
    if (rimPx <= 0f || style.rimAlpha <= 0f) return emptyList()
    // Target composite alpha at the midpoint of each third, outermost first.
    val target = FloatArray(3) { i -> style.rimAlpha * (1f - (i + 0.5f) / 3f) }
    var covered = 0f
    val strokes = ArrayList<Pair<Float, Float>>(3)
    for (i in 2 downTo 0) {
        // What this stroke must add on top of everything already covering this band.
        val alpha = if (covered >= 1f) 0f else (target[i] - covered) / (1f - covered)
        covered = target[i]
        strokes += (rimPx * (i + 1) / 3f * 2f) to alpha.coerceIn(0f, 1f)
    }
    return strokes
}
