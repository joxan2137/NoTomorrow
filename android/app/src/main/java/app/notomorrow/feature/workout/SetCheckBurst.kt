package app.notomorrow.feature.workout

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.effects.rememberReduceMotion
import app.notomorrow.designsystem.ntPlainClickable
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * `SetCheckButton` (`SetCheckButton.swift`): a 36 dp rounded square (radius 10) in a 48 × 44 hit
 * column — ember with a dark check when done, `surface2` with a faint check when not.
 *
 * Ticking a set sets off a short ember splash built from the libraries.dev effects the app uses
 * elsewhere: droplets that squeeze out of the box and pinch off like goo (Gooey), a comet lap round
 * its edge (Border Beam), a metal glint across the fill (Liquid Metal) and a spray of dots
 * (Thinking Orbs) — over a spring squash, a fill that floods out from the centre and a check that
 * draws itself, with a haptic pop. Unticking is instant; "Remove animations" skips the splash.
 * Same timeline and numbers as iOS.
 */
@Composable
internal fun SetCheckButton(isCompleted: Boolean, onToggle: () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalHapticFeedback.current
    val gooLayer = rememberGraphicsLayer()
    val bloomLayer = rememberGraphicsLayer()
    var burstT by remember { mutableFloatStateOf(-1f) }
    var wasOn by remember { mutableStateOf(isCompleted) }

    LaunchedEffect(isCompleted) {
        val burst = isCompleted && !wasOn && !reduceMotion
        wasOn = isCompleted
        if (!burst) {
            burstT = -1f
            return@LaunchedEffect
        }
        var t0 = -1L
        var popped = false
        while (true) {
            val t = withFrameNanos { now ->
                if (t0 < 0) t0 = now
                (now - t0) / 1_000_000_000f
            }
            if (!popped && t >= POP_T) {
                // the pop lands when the fill has flooded the box
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                popped = true
            }
            if (t >= DURATION_S) break
            burstT = t
        }
        burstT = -1f
    }

    Box(
        modifier = Modifier
            .size(width = SetTable.check, height = NT.Size.control)
            .ntPlainClickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        // Room round the 36 dp box for the droplets, sparks and shockwave; it overflows the column.
        Canvas(Modifier.requiredSize(CANVAS_SIDE.dp)) {
            val t = burstT
            if (t >= 0f && isCompleted) drawBurst(t.toDouble(), gooLayer, bloomLayer) else drawStatic(isCompleted)
        }
    }
}

private const val DURATION_S = 0.85f
private const val CANVAS_SIDE = 140f
private const val BOX = 36f
private const val RADIUS = 10f

private val EmberLight = Color(0xFFFF9A5C)
private val Spark = Color(0xFFFFE3D1)

// Eight droplets between the box's corners and edges, each with its own reach, size and delay so the
// splash is uneven — but fixed, so every tick throws the same splash.
private val DropAngles = floatArrayOf(22.5f, 64f, 115f, 157f, 203f, 246f, 292f, 338f)
private val DropReach = floatArrayOf(1.0f, 0.65f, 0.95f, 0.55f, 1.0f, 0.8f, 0.6f, 0.9f)
private val DropSize = floatArrayOf(1.0f, 0.6f, 0.85f, 0.5f, 0.95f, 0.7f, 0.55f, 0.8f)
private val DropDelay = floatArrayOf(0f, 0.03f, 0.01f, 0.05f, 0.02f, 0.04f, 0f, 0.03f)

/** The pop: the fill has flooded the box and the splash is thrown. */
private const val POP_T = 0.08
private val SparkAngles = floatArrayOf(0f, 58f, 122f, 180f, 236f, 301f)

private fun clamp01(x: Double) = min(1.0, max(0.0, x))
private fun smooth(e0: Double, e1: Double, x: Double): Double {
    val t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3 - 2 * t)
}
private fun easeOutCubic(x: Double) = 1 - (1 - x).pow(3)
private fun easeInOut(x: Double) = if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

private fun DrawScope.drawStatic(isOn: Boolean) {
    drawBox(scale = 1f, fill = if (isOn) 1f else 0f, check = 1f, isOn = isOn, glint = null)
}

private fun DrawScope.drawBurst(t: Double, gooLayer: GraphicsLayer, bloomLayer: GraphicsLayer) {
    val d = density
    val c = center
    // spring squash: pressed in to 84 %, a small overshoot, settled by ~0.5 s
    val squash = (1 - 0.16 * exp(-8 * t) * cos(20 * t)).toFloat()

    // warm glow behind everything
    val glowA = (0.55 * smooth(0.06, 0.12, t) * (1 - smooth(0.16, 0.75, t))).toFloat()
    if (glowA > 0.01f) {
        drawCircle(
            Brush.radialGradient(listOf(NT.Colors.ember.copy(alpha = glowA), Color.Transparent), c, 38 * d),
            radius = 38 * d,
            center = c,
        )
    }

    // shockwave: the box's outline expanding and thinning out
    val wave = easeOutCubic(clamp01((t - POP_T) / 0.5)).toFloat()
    if (t >= POP_T && wave < 1f) {
        val side = (BOX + 34 * wave) * d
        drawRoundRect(
            color = EmberLight.copy(alpha = 0.7f * (1 - wave)),
            topLeft = Offset(c.x - side / 2, c.y - side / 2),
            size = Size(side, side),
            cornerRadius = CornerRadius((RADIUS + 8 * wave) * d),
            style = Stroke(width = (2.5f * (1 - wave) + 0.5f) * d),
        )
    }

    // goo: the box and eight droplets, blurred and thresholded so they stay joined by liquid
    // bridges until they pinch off (API 31+; plain droplets before that)
    val gooA = (1 - smooth(0.5, 0.66, t)).toFloat()
    if (gooA > 0.01f) {
        val shapes: DrawScope.(Color) -> Unit = { color ->
            val s = BOX * squash * d
            drawRoundRect(color, Offset(c.x - s / 2, c.y - s / 2), Size(s, s), CornerRadius(RADIUS * squash * d))
            for (i in DropAngles.indices) {
                val tt = t - DropDelay[i]
                if (tt < 0) continue
                val a = Math.toRadians(DropAngles[i].toDouble())
                val travel = easeOutCubic(clamp01(tt / 0.45))
                val dist = (10 + (20 + 16 * DropReach[i]) * travel) * d
                val r = ((4.2 + 2.0 * DropSize[i]) * (1 - clamp01((tt - 0.12) / 0.42)).pow(0.9) * d).toFloat()
                if (r > 0.2f * d) {
                    drawCircle(color, r, Offset(c.x + (cos(a) * dist).toFloat(), c.y + (sin(a) * dist).toFloat()))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            gooLayer.renderEffect = gooEffect(3.5f * d)
            gooLayer.alpha = gooA
            gooLayer.record(size = IntSize(size.width.toInt(), size.height.toInt())) { shapes(Color.Black) }
            drawLayer(gooLayer)
        } else {
            shapes(NT.Colors.ember.copy(alpha = gooA))
        }
    }

    // sparks: small bright dots flying past the droplets
    val sparkP = easeOutCubic(clamp01((t - POP_T) / 0.55))
    val sparkFade = (1 - clamp01((t - POP_T) / 0.62)).toFloat()
    val sparkR = 1.8f * sparkFade
    if (t >= POP_T && sparkR > 0.05f) {
        for (deg in SparkAngles) {
            val a = Math.toRadians(deg.toDouble())
            val dist = (20 + 36 * sparkP) * d
            drawCircle(
                Spark.copy(alpha = sparkFade),
                sparkR * d,
                Offset(c.x + (cos(a) * dist).toFloat(), c.y + (sin(a) * dist).toFloat()),
            )
        }
    }

    // the box: the fill floods out from the centre, the check draws in, a glint sweeps across
    val fill = easeOutCubic(clamp01(t / 0.14)).toFloat()
    val check = easeOutCubic(clamp01((t - 0.1) / 0.24)).toFloat()
    val glint = if (t > 0.2 && t < 0.55) easeInOut((t - 0.2) / 0.35).toFloat() else null
    drawBox(squash, fill, check, isOn = true, glint = glint)

    // beam: a comet running once round the box's edge
    val lap = clamp01((t - 0.06) / 0.5)
    val beamA = (smooth(0.06, 0.12, t) * (1 - smooth(0.45, 0.6, t))).toFloat()
    if (beamA > 0.01f) {
        val side = (BOX + 6) * d
        val head = (-90 + 360 * easeInOut(lap)).toFloat()
        val comet = Brush.sweepGradient(
            0f to Color.Transparent,
            0.62f to Color.Transparent,
            0.86f to EmberLight.copy(alpha = 0.6f * beamA),
            0.985f to Color.White.copy(alpha = beamA),
            1f to Color.Transparent,
            center = c,
        )
        val topLeft = Offset(c.x - side / 2, c.y - side / 2)
        val corner = CornerRadius((RADIUS + 3) * d)
        // a sweep gradient starts at 3 o'clock; turning the canvas puts its end on the head
        val bloom: DrawScope.() -> Unit = {
            rotate(head, pivot = c) { drawRoundRect(comet, topLeft, Size(side, side), corner, style = Stroke(4 * d)) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // the comet's bloom: a 4 dp stroke blurred by 3 dp, as on iOS
            val radius = max(0.5f, (3 * d - 0.5f) / 0.57735f)
            bloomLayer.renderEffect = BlurEffect(radius, radius, TileMode.Decal)
            bloomLayer.record(size = IntSize(size.width.toInt(), size.height.toInt())) { bloom() }
            drawLayer(bloomLayer)
        } else {
            // no blur before API 31: the wide stroke goes on faint instead
            drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = 0.35f }) }
            bloom()
            drawIntoCanvas { it.restore() }
        }
        rotate(head, pivot = c) { drawRoundRect(comet, topLeft, Size(side, side), corner, style = Stroke(1.5f * d)) }
    }
}

private fun DrawScope.drawBox(scale: Float, fill: Float, check: Float, isOn: Boolean, glint: Float?) {
    val d = density
    val s = BOX * scale * d
    val topLeft = Offset(center.x - s / 2, center.y - s / 2)
    val box = boxPath(topLeft, s, RADIUS * scale * d)
    drawPath(box, if (isOn && fill >= 1f) NT.Colors.ember else NT.Colors.surface2)
    if (isOn && fill < 1f) {
        clipPath(box) { drawCircle(NT.Colors.ember, 27 * fill * scale * d, center) }
    }
    if (glint != null) {
        val f = glint
        clipPath(box) {
            drawRect(
                Brush.linearGradient(
                    max(0f, f - 0.16f) to Color.Transparent,
                    min(1f, max(0.001f, f)) to Color.White.copy(alpha = 0.55f),
                    min(1f, f + 0.16f) to Color.Transparent,
                    start = Offset(topLeft.x - 8 * d, topLeft.y - 8 * d),
                    end = Offset(topLeft.x + s + 8 * d, topLeft.y + s + 8 * d),
                ),
            )
        }
    }
    if (check > 0f) {
        // the SF "checkmark" at 15 pt bold, as a stroke so it can draw itself in
        val tick = Path().apply {
            moveTo(center.x - 6.8f * scale * d, center.y + 0.4f * scale * d)
            lineTo(center.x - 2.2f * scale * d, center.y + 5.2f * scale * d)
            lineTo(center.x + 7.2f * scale * d, center.y - 5.4f * scale * d)
        }
        val drawn = if (check >= 1f) {
            tick
        } else {
            Path().also { dst ->
                val measure = PathMeasure().apply { setPath(tick, false) }
                measure.getSegment(0f, measure.length * check, dst, true)
            }
        }
        drawPath(
            drawn,
            if (isOn) NT.Colors.onPrimary else NT.Colors.ink3,
            style = Stroke(width = 2.6f * scale * d, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** `NtShapes.cell` (continuous corners, like iOS) at this size, as a path. */
private fun DrawScope.boxPath(topLeft: Offset, side: Float, radius: Float): Path {
    val outline = NtShapes.cell.createOutline(Size(side, side), layoutDirection, this)
    val path = when (outline) {
        is Outline.Generic -> Path().apply { addPath(outline.path) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRoundRect(RoundRect(outline.rect, CornerRadius(radius))) }
    }
    path.translate(topLeft)
    return path
}

/** Blur, then an alpha threshold that paints everything past half coverage ember: the goo. */
private fun gooEffect(blurSigmaPx: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val ember = NT.Colors.ember.toArgb()
    val threshold = ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, android.graphics.Color.red(ember).toFloat(),
            0f, 0f, 0f, 0f, android.graphics.Color.green(ember).toFloat(),
            0f, 0f, 0f, 0f, android.graphics.Color.blue(ember).toFloat(),
            0f, 0f, 0f, 20f, -10f * 255f,
        ),
    )
    // RenderEffect blur radii become a Gaussian sigma of 0.57735·r + 0.5 (HWUI); invert that.
    val radius = max(0.5f, (blurSigmaPx - 0.5f) / 0.57735f)
    return RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(threshold),
        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL),
    ).asComposeRenderEffect()
}
