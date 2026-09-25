package app.notomorrow.designsystem.effects

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * The liquid-metal material: presets, the sheet mapping and a CPU port of the shader for point
 * sampling (the glow's luminance hunt and halo tint) — a transcription of upstream
 * `metal-fx-native/src/material.ts` (libraries.dev `metal-fx`, MIT; the material itself is
 * Paper Shaders' `liquidMetal`, Apache-2.0). Dark theme only: the app is dark-only.
 */

/**
 * The dark-theme presets (`MetalMaterial.preset`): one silver material, told apart by the
 * colour-burn tint (whose alpha is the burn amount) and the channel dispersion.
 */
enum class LiquidMetalPreset(
    internal val tint: FloatArray,
    internal val speed: Float = 1f,
    internal val repetition: Float = 1.5f,
    internal val softness: Float = 0.05f,
    internal val shiftRed: Float = 0.3f,
    internal val shiftBlue: Float = 0.3f,
    internal val shaderOpacity: Float = 1f,
) {
    /** Iridescent: a cool blue burn and a wide R/B split. `#88ccff2e`. */
    Chromatic(rgba(0x88ccff2e), repetition = 2f, softness = 0.09f, shiftRed = 0.75f, shiftBlue = 0.75f),

    /** Cool steel: Paper's material nearly untouched. `#ffffff66`. */
    Silver(rgba(0xffffff66), shaderOpacity = 0.88f),

    /** Warm gold. `#ffcc55cc`. */
    Gold(rgba(0xffcc55cc), speed = 0.85f, shaderOpacity = 0.92f),
    ;

    // Paper `fullScreenPreset` values shared by every preset.
    internal val distortion get() = 0.1f
    internal val contour get() = 0.4f
    internal val angle get() = 90f
}

private fun rgba(v: Long): FloatArray = floatArrayOf(
    ((v ushr 24) and 0xff) / 255f, ((v ushr 16) and 0xff) / 255f, ((v ushr 8) and 0xff) / 255f, (v and 0xff) / 255f,
)

/** `uv = origin + pos * scale` (pos in points): the web's per-instance window onto the material sheet. */
internal class SheetMapping(val ox: Float, val oy: Float, val sx: Float, val sy: Float)

private const val CANONICAL_W = 140f
private const val CANONICAL_H = 40f

/**
 * The window never spans more than 72 % × 85 % of the sheet: the material fades out toward the
 * sheet's edges, and upstream lets a shape bigger than the canonical 140 × 40 pt reach them, so a
 * full-width field or card lost its ring at the ends. Canonical-size shapes are unaffected. The
 * vendored iOS package carries the same cap (`MetalSheetMapping.maxWindowX` / `maxWindowY`).
 */
internal fun sheetMapping(width: Float, height: Float, shaderScale: Float): SheetMapping {
    val w = max(1f, width)
    val h = max(1f, height)
    val fx = min(MAX_WINDOW_X, w / (CANONICAL_W * shaderScale))
    val fy = min(MAX_WINDOW_Y, h / (CANONICAL_H * shaderScale))
    return SheetMapping(0.5f - 0.5f * fx, 0.5f - 0.5f * fy, fx / w, fy / h)
}

private const val MAX_WINDOW_X = 0.72f
private const val MAX_WINDOW_Y = 0.85f

// ── CPU sampler (mirrors the AGSL in LiquidMetal.kt) ──

private fun ss(e0: Double, e1: Double, x: Double): Double {
    val e = max(e1, e0 + 1e-6)
    val t = ((x - e0) / (e - e0)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}

private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t
private fun fract(x: Double) = x - floor(x)
private fun gmod(x: Double, y: Double) = x - y * floor(x / y)
private fun permute(x: Double) = gmod(((x * 34) + 1) * x, 289.0)

internal fun snoise(vx: Double, vy: Double): Double {
    val cx = 0.211324865405187
    val cy = 0.366025403784439
    val cz = -0.577350269189626
    val cw = 0.024390243902439
    val s = (vx + vy) * cy
    var ix = floor(vx + s)
    var iy = floor(vy + s)
    val tt = (ix + iy) * cx
    val x0x = vx - ix + tt
    val x0y = vy - iy + tt
    val i1x = if (x0x > x0y) 1.0 else 0.0
    val i1y = if (x0x > x0y) 0.0 else 1.0
    val x1x = x0x + cx - i1x
    val x1y = x0y + cx - i1y
    val x2x = x0x + cz
    val x2y = x0y + cz
    ix = gmod(ix, 289.0)
    iy = gmod(iy, 289.0)
    val p0 = permute(permute(iy) + ix)
    val p1 = permute(permute(iy + i1y) + ix + i1x)
    val p2 = permute(permute(iy + 1) + ix + 1)
    var m0 = max(0.5 - (x0x * x0x + x0y * x0y), 0.0)
    var m1 = max(0.5 - (x1x * x1x + x1y * x1y), 0.0)
    var m2 = max(0.5 - (x2x * x2x + x2y * x2y), 0.0)
    m0 *= m0; m0 *= m0; m1 *= m1; m1 *= m1; m2 *= m2; m2 *= m2
    val xx0 = 2 * fract(p0 * cw) - 1
    val xx1 = 2 * fract(p1 * cw) - 1
    val xx2 = 2 * fract(p2 * cw) - 1
    val h0 = abs(xx0) - 0.5
    val h1 = abs(xx1) - 0.5
    val h2 = abs(xx2) - 0.5
    val a0 = xx0 - floor(xx0 + 0.5)
    val a1 = xx1 - floor(xx1 + 0.5)
    val a2 = xx2 - floor(xx2 + 0.5)
    m0 *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h0 * h0)
    m1 *= 1.79284291400159 - 0.85373472095314 * (a1 * a1 + h1 * h1)
    m2 *= 1.79284291400159 - 0.85373472095314 * (a2 * a2 + h2 * h2)
    val g0 = a0 * x0x + h0 * x0y
    val g1 = a1 * x1x + h1 * x1y
    val g2 = a2 * x2x + h2 * x2y
    return 130 * (m0 * g0 + m1 * g1 + m2 * g2)
}

/** direction, bump, edge, diagBL, diagTL — `fieldA` in the shader. */
private fun field(ux: Double, uy: Double, t: Double, noise: Double, cw: Double, m: LiquidMetalPreset): DoubleArray {
    val contour = m.contour.toDouble()
    val cx = ux.coerceIn(0.0, 1.0)
    val cy = uy.coerceIn(0.0, 1.0)
    val mx = min(cx, 1 - cx)
    val my = min(cy, 1 - cy)
    val maskX = ss(0.0, 0.5, mx).pow(0.25)
    val maskY = ss(0.0, 0.5, my).pow(0.25)
    var edge = (1 - maskX * maskY).coerceIn(0.0, 1.0)
    val fwE = 0.002
    edge = mix(ss(0.9 - 2 * fwE, 0.9, edge), edge, ss(0.0, 0.4, contour))
    edge *= 1.2
    val a = (-m.angle + 70) * Math.PI / 180
    val ca = cos(a)
    val sa = sin(a)
    val rx = ux - 0.5
    val ry = uy - 0.5
    val rotx = rx * ca - ry * sa + 0.5
    val roty = rx * sa + ry * ca + 0.5
    val diagBL = rotx - roty
    val diagTL = rotx + roty
    val gx = ux - 0.5
    val gy = uy - 0.5
    val dist = hypot(gx, gy + 0.2 * diagBL)
    val th = (0.25 - 0.2 * diagBL) * Math.PI
    var direction = cos(th) * gx - sin(th) * gy
    val uyc = max(cy, 0.0)
    var bump = 1 - (1.8 * dist).pow(1.2)
    bump *= uyc.pow(0.3)
    edge += (1 - edge) * m.distortion * noise
    direction += diagBL
    val sE = ss(0.0, 1.0, edge)
    direction -= 2 * noise * diagBL * (sE * (1 - sE))
    val c51 = ss(0.5, 1.0, contour)
    direction *= mix(1.0, 1 - edge, c51)
    direction -= 1.7 * edge * c51
    direction += 0.2 * contour.pow(4.0) * (1 - sE)
    bump *= uyc.pow(0.1).coerceIn(0.3, 1.0)
    direction *= (0.1 + (1.1 - edge) * bump)
    direction *= (0.4 + 0.6 * (1 - ss(0.5, 1.0, edge)))
    direction += 0.18 * (ss(0.1, 0.2, uy) * (1 - ss(0.2, 0.4, uy)))
    direction += 0.03 * (ss(0.1, 0.2, 1 - uy) * (1 - ss(0.2, 0.4, 1 - uy)))
    direction *= (0.5 + 0.5 * uy * uy)
    direction *= cw
    direction -= t
    return doubleArrayOf(direction, bump, edge, diagBL, diagTL)
}

private fun colorChanges(
    c1: Double, c2: Double, p: Double, w0: Double, w1: Double, w2: Double,
    blurIn: Double, bump: Double, tint: Double, tintA: Double,
): Double {
    val blur = max(blurIn, 1e-5)
    var ch = mix(c2, c1, ss(0.0, 2 * blur, p))
    var border = w0
    ch = mix(ch, c2, ss(border, border + 2 * blur, p))
    border = w0 + 0.4 * (1 - bump) * w1
    ch = mix(ch, c1, ss(border, border + 2 * blur, p))
    border = w0 + 0.5 * (1 - bump) * w1
    ch = mix(ch, c2, ss(border, border + 2 * blur, p))
    border = w0 + w1
    ch = mix(ch, c1, ss(border, border + 2 * blur, p))
    val gt = (p - w0 - w1) / w2
    val gradient = mix(c1, c2, ss(0.0, 1.0, gt))
    ch = mix(ch, gradient, ss(border, border + 0.5 * blur, p))
    ch = mix(ch, 1 - min(1.0, (1 - ch) / max(tint, 0.0001)), tintA)
    return ch
}

/** The material at sheet uv, straight RGB 0…1 (before the strength multiplier). */
internal fun sampleMetal(m: LiquidMetalPreset, ux: Double, uy: Double, time: Double): DoubleArray {
    val t = 0.3 * (time * m.speed + 2.8)
    val cw = m.repetition * 2.0
    val noise = snoise(ux - t, uy - t)
    val f = field(ux, uy, t, noise, cw, m)
    val fx = field(ux + 1.0 / 192, uy, t, noise, cw, m)
    val fy = field(ux, uy + 1.0 / 192, t, noise, cw, m)
    val fw = abs(fx[0] - f[0]) + abs(fy[0] - f[0])
    val direction = f[0]
    val bump = f[1]
    val edge = f[2]
    val diagBL = f[3]
    val diagTL = f[4]
    val cx = ux.coerceIn(0.0, 1.0)
    val cy = uy.coerceIn(0.0, 1.0)
    val mx = min(cx, 1 - cx)
    val my = min(cy, 1 - cy)
    val rawEdge = (1 - ss(0.0, 0.5, mx).pow(0.25) * ss(0.0, 0.5, my).pow(0.25)).coerceIn(0.0, 1.0)
    val opacity = 1 - ss(0.9 - 0.004, 0.9, rawEdge)
    val c2b = 0.1 + 0.1 * ss(0.7, 1.3, diagTL)
    val thin1 = 0.12 / cw * (1 - 0.4 * bump)
    val thin2 = 0.07 / cw * (1 + 0.4 * bump)
    val w0 = cw * thin1
    val w2 = 1 - thin1 - thin2
    var w1 = cw * thin2
    val cd = (1 - bump).coerceIn(0.0, 1.0)
    var dR = cd + 0.03 * bump * noise
    dR += 5 * (ss(-0.1, 0.2, uy) * (1 - ss(0.1, 0.5, uy))) * (ss(0.4, 0.6, bump) * (1 - ss(0.4, 1.0, bump)))
    dR -= diagBL
    var dB = cd * 1.3
    dB += (ss(0.0, 0.4, uy) * (1 - ss(0.1, 0.8, uy))) * (ss(0.4, 0.6, bump) * (1 - ss(0.4, 0.8, bump)))
    dB -= 0.2 * edge
    dR *= m.shiftRed / 20.0
    dB *= m.shiftBlue / 20.0
    val blur = m.softness / 15.0
    w1 -= 0.02 * ss(0.0, 1.0, edge + bump)
    val tintA = m.tint[3].toDouble()
    val r = colorChanges(0.98, 0.1, fract(direction + dR), w0, w1, w2, blur + fw, bump, m.tint[0].toDouble(), tintA)
    val g = colorChanges(0.98, 0.1, fract(direction), w0, w1, w2, blur + fw, bump, m.tint[1].toDouble(), tintA)
    val b = colorChanges(1.0, c2b, fract(direction - dB), w0, w1, w2, blur + fw, bump, m.tint[2].toDouble(), tintA)
    // colorBack is transparent in every preset, so only the material's own opacity applies
    return doubleArrayOf(r * opacity, g * opacity, b * opacity)
}

internal fun metalLuminance(m: LiquidMetalPreset, ux: Double, uy: Double, time: Double): Double {
    val c = sampleMetal(m, ux, uy, time)
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}
