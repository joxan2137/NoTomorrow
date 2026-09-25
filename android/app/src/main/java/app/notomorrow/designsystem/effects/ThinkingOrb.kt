package app.notomorrow.designsystem.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A dotted thought-orb loading indicator — `ThinkingOrb` from ThinkingOrbsKit (libraries.dev
 * `thinking-orbs`, MIT), drawn from [orbFrame] on a [Canvas] every frame.
 *
 * Decorative: it carries no semantics, the text next to it says what is happening (the iOS call
 * sites hide it from VoiceOver the same way). With "Remove animations" on it shows one static
 * frame, the instant the web build freezes at.
 *
 * @param displaySize draws the [size] preset's geometry at another size, crisply (the drawing is
 *   scaled, not a bitmap).
 * @param dark mirrors the ink for a dark background — this app is dark-only, so it defaults on.
 */
@Composable
fun ThinkingOrb(
    state: OrbState,
    size: OrbSize,
    modifier: Modifier = Modifier,
    displaySize: Dp = size.points.dp,
    dark: Boolean = true,
    speed: Double = 1.0,
) {
    val preset = remember(state, size) { resolveOrbPreset(state, size) }
    val reduceMotion = rememberReduceMotion()
    val time = rememberEffectTime(running = !reduceMotion)
    val effSpeed = preset.speed * speed
    val points = size.points.toDouble()

    Canvas(modifier.size(displaySize)) {
        val t = if (reduceMotion) ORB_REDUCED_MOTION_T * effSpeed else time.value * effSpeed
        val frame = orbFrame(preset, points, t)
        val k = (this.size.minDimension / points).toFloat()
        // lines first, so nodes sit on top of their edges
        for (l in frame.lines) {
            drawLine(
                color = orbInk(l.white, l.a, dark),
                start = Offset((l.x1 * k).toFloat(), (l.y1 * k).toFloat()),
                end = Offset((l.x2 * k).toFloat(), (l.y2 * k).toFloat()),
                strokeWidth = (l.w * k).toFloat(),
            )
        }
        // dots arrive z-sorted into draw order
        for (d in frame.dots) {
            drawCircle(
                color = orbInk(d.white, d.a, dark),
                radius = (d.r * k).toFloat(),
                center = Offset((d.x * k).toFloat(), (d.y * k).toFloat()),
            )
        }
    }
}

/** Quantised to 8-bit exactly as the canvas painter does, so all platforms land on the same greys. */
private fun orbInk(white: Double, alpha: Double, dark: Boolean): Color {
    val w = white.coerceIn(0.0, 1.0)
    val g = roundAway((if (dark) 1 - w else w) * 255).roundToInt()
    return Color(red = g, green = g, blue = g, alpha = (alpha.coerceIn(0.0, 1.0) * 255).roundToInt())
}
