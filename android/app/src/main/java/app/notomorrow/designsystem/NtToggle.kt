package app.notomorrow.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Measured geometry — docs/android-glass.md §1.3, re-measured here at native 3x
// on design/ios26-reference/13c-toggle-t1.png (OFF) and 08-toggle-…png (ON).
//
//   track  x 305.0 → 368.0  (63.0 pt)   y 227.0 → 255.0  (28.0 pt)   r = 14
//   knob   OFF x 307.0 → 344.0          ON x 329.0 → 366.0           travel 22
//          y 229.0 → 253.0  (24.0 pt)   inset 2 on all four sides    r = 12
//   OFF track  (90,90,94) over `surface` (28,28,30)  ⇒  #EBEBF5 @ 30 %
//   ON  track  (242,242,244)                          ⇒  NT.Colors.ink
//   knob       (254,254,254) in BOTH states           ⇒  pure white, flat
//
// The iOS 26 knob is a `_UILiquidLensView` — real glass, sampled from the TRACK
// and not from the page (§3.6 `NtToggle`). It is drawn that way here: the track
// Canvas wears its own small `ntBackdropSource` and the knob is a sibling with
// `Modifier.liquidGlass(RoundedCornerShape(12.dp), GlassStyle.Toggle, trackBackdrop)`.
//
// `GlassStyle.Toggle` is fitted to both captures — a flat #FFFFFF knob with no
// rim over both track colours — so the resting pixels are exactly what the hard-
// coded white capsule produced, while the lens band and the press response now
// come from the material. §5.1 is still open (no mid-drag frame was ever
// captured); when one exists the fix is a re-fit of the preset, not of this file.
// ─────────────────────────────────────────────────────────────────────────────

/** `UISwitch.bounds.width` on iOS 26.1 — was 51 on iOS 18. */
val NtToggleWidth: Dp = 63.dp

/** `UISwitch.bounds.height` — was 31 on iOS 18. */
val NtToggleHeight: Dp = 28.dp

private val KnobWidth = 37.dp
private val KnobHeight = 24.dp
private val KnobInset = 2.dp

/** x 2 → 24. */
private val KnobTravel = 22.dp

/** The extra length the knob gains while it is held — [estimated], §5 open question 1. */
private val KnobStretch = 4.dp

/** `_UILiquidLensView` is a 37 × 24 capsule: r 12 = h/2. */
private val KnobShape = RoundedCornerShape(12.dp)

/** `tertiaryLabelColor` = `#EBEBF5 @ 30 %`. Composited, never pre-flattened. */
private val OffTrack = Color(0xFFEBEBF5).copy(alpha = 0.30f)

/** iOS `spring(dampingFraction: 0.8)` on the switch — docs/android-glass.md §3.6 [estimated]. */
private val ToggleSpring: AnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)

/**
 * The iOS 26 `Toggle` (`UISwitch`), rebuilt because Material 3's `Switch` cannot
 * be restyled to it: its thumb is a circle with a fixed elevation shadow, its
 * track is 52 × 32, and there is no hook for the sweep-in fill.
 *
 * Three iOS behaviours that change the silhouette and are all reproduced here:
 *
 * 1. the knob is a **capsule** (37 × 24), not a circle;
 * 2. the ON fill **sweeps in from the left** under the knob — it does not
 *    cross-fade between two track colours;
 * 3. the knob **stretches** while it is held or dragged, growing away from the
 *    end it is resting against.
 *
 * The knob can be dragged as well as tapped; releasing past the half-way point
 * commits, exactly like `UISwitch`.
 *
 * All three iOS call sites (`SettingsComponents.swift:105`,
 * `OnboardingComponents.swift:149`, `OBDayToggle`) tint `ink`, so the tint is
 * not a parameter.
 */
@Composable
fun NtToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    val travelPx = with(density) { KnobTravel.toPx() }
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val onChange by rememberUpdatedState(onCheckedChange)

    /** 0 = knob left (OFF), 1 = knob right (ON). Continuous, so a drag scrubs it. */
    val progress = remember { Animatable(if (checked) 1f else 0f) }

    /** 0 = released, 1 = held. Drives the knob stretch. */
    val press = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    // Follow the state when it changes from the outside (or from a tap).
    LaunchedEffect(checked) {
        if (!dragging) progress.animateTo(if (checked) 1f else 0f, ToggleSpring)
    }
    LaunchedEffect(pressed, dragging) {
        press.animateTo(if (pressed || dragging) 1f else 0f, NT.Anim.easeOut15)
    }

    Box(
        modifier = modifier
            .size(width = NtToggleWidth, height = NtToggleHeight)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = { onChange(it) },
            )
            .pointerInput(enabled, travelPx) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragCancel = {
                        dragging = false
                        scope.launch { progress.animateTo(if (checked) 1f else 0f, ToggleSpring) }
                    },
                    onDragEnd = {
                        dragging = false
                        val target = progress.value >= 0.5f
                        scope.launch { progress.animateTo(if (target) 1f else 0f, ToggleSpring) }
                        if (target != checked) onChange(target)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            progress.snapTo((progress.value + dragAmount / travelPx).coerceIn(0f, 1f))
                        }
                    },
                )
            },
    ) {
        // The knob samples the TRACK, never the page: one tiny backdrop per toggle, recorded by the
        // track Canvas and consumed by its sibling. It is created here rather than taken from
        // `LocalNtBackdrop` so the knob still refracts the track inside a sheet, where the app-wide
        // backdrop belongs to another window.
        val trackBackdrop = rememberNtBackdrop()

        Canvas(Modifier.fillMaxSize().ntBackdropSource(trackBackdrop)) {
            val p = progress.value
            val trackRadius = size.height / 2f
            val capsule = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        cornerRadius = CornerRadius(trackRadius),
                    ),
                )
            }

            // Track — the OFF colour is always there; the ON fill sweeps over it.
            drawPath(capsule, OffTrack)
            if (p > 0f) {
                clipPath(capsule) {
                    drawRect(
                        color = NT.Colors.ink,
                        size = Size(size.width * p, size.height),
                    )
                }
            }
        }

        // Knob — 37 × 24 capsule inset 2, stretching away from the end it rests on. Position and
        // width are read in the LAYOUT phase, so a drag never recomposes anything.
        Box(
            Modifier
                .layout { measurable, _ ->
                    val knobW = (KnobWidth.toPx() + KnobStretch.toPx() * press.value).roundToInt()
                    val knobH = KnobHeight.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(knobW, knobH))
                    val inset = KnobInset.roundToPx()
                    val free = NtToggleWidth.roundToPx() - 2 * inset - knobW
                    layout(placeable.width, placeable.height) {
                        placeable.place(inset + (free * progress.value).roundToInt(), inset)
                    }
                }
                .liquidGlass(KnobShape, GlassStyle.Toggle, backdrop = trackBackdrop),
        )
    }
}
