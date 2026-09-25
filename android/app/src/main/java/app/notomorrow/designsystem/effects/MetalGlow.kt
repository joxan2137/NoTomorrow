package app.notomorrow.designsystem.effects

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/*
 * The wandering halo of a MetalFx ring — upstream `metal-fx-native/src/glow.ts` and
 * `geometry.ts` (libraries.dev `metal-fx`, MIT) in Kotlin: a luminance hunt over 16 points of the
 * perimeter picks where the metal is brightest, a soft halo dwells there, wanders a little, fades
 * out and reappears at the next bright spot, tinted by the material underneath.
 */

internal enum class MetalShapeKind { Pill, Circle }

internal fun metalShapeKind(w: Float, h: Float, radius: Float): MetalShapeKind {
    val m = min(w, h)
    return if (kotlin.math.abs(w - h) < 0.5f && radius >= m / 2 - 0.5f) MetalShapeKind.Circle else MetalShapeKind.Pill
}

internal fun rrPerim(w: Float, h: Float, r: Float): Float {
    val rr = max(0f, min(r, min(w, h) / 2))
    return 2 * max(0f, w - 2 * rr) + 2 * max(0f, h - 2 * rr) + 2 * PI.toFloat() * rr
}

internal fun shapePerim(w: Float, h: Float, r: Float, kind: MetalShapeKind): Float =
    if (kind == MetalShapeKind.Circle) 2 * PI.toFloat() * max(0f, min(r, min(w, h) / 2)) else rrPerim(w, h, r)

/** The point [sIn] pt along the outline (clockwise from the top edge's start), [inset] in / [outward] out. */
internal fun sampleAtArc(
    sIn: Float, w: Float, h: Float, r: Float, inset: Float, outward: Float, kind: MetalShapeKind,
): FloatArray {
    val rr = max(0f, min(r, min(w, h) / 2))
    val pi = PI.toFloat()
    if (kind == MetalShapeKind.Circle) {
        val perim = 2 * pi * rr
        if (perim <= 0.0001f) return floatArrayOf(w / 2, h / 2)
        val s = ((sIn % perim) + perim) % perim
        val theta = -pi / 2 + (s / perim) * pi * 2
        val rad = max(0f, rr - inset + outward)
        return floatArrayOf(w / 2 + rad * cos(theta), h / 2 + rad * sin(theta))
    }
    val topLen = max(0f, w - 2 * rr)
    val sideLen = max(0f, h - 2 * rr)
    val arcLen = pi * rr / 2
    val perim = 2 * (topLen + sideLen) + 4 * arcLen
    var d = ((sIn % perim) + perim) % perim
    val rad = max(0f, rr - inset + outward)
    fun f(a: Float) = if (arcLen > 0) a / arcLen else 0f
    if (d < topLen) return floatArrayOf(rr + d, inset - outward)
    d -= topLen
    if (d < arcLen) {
        val th = -pi / 2 + f(d) * (pi / 2)
        return floatArrayOf((w - rr) + rad * cos(th), rr + rad * sin(th))
    }
    d -= arcLen
    if (d < sideLen) return floatArrayOf(w - inset + outward, rr + d)
    d -= sideLen
    if (d < arcLen) {
        val th = f(d) * (pi / 2)
        return floatArrayOf((w - rr) + rad * cos(th), (h - rr) + rad * sin(th))
    }
    d -= arcLen
    if (d < topLen) return floatArrayOf(w - rr - d, h - inset + outward)
    d -= topLen
    if (d < arcLen) {
        val th = pi / 2 + f(d) * (pi / 2)
        return floatArrayOf(rr + rad * cos(th), (h - rr) + rad * sin(th))
    }
    d -= arcLen
    if (d < sideLen) return floatArrayOf(inset - outward, h - rr - d)
    d -= sideLen
    val th = pi + f(d) * (pi / 2)
    return floatArrayOf(rr + rad * cos(th), rr + rad * sin(th))
}

private fun tangentAngleAtArc(s: Float, w: Float, h: Float, r: Float, inset: Float, kind: MetalShapeKind): Float {
    val a = sampleAtArc(s - 0.5f, w, h, r, inset, 0f, kind)
    val b = sampleAtArc(s + 0.5f, w, h, r, inset, 0f, kind)
    return atan2(b[1] - a[1], b[0] - a[0])
}

// ── GLOW_DEFAULTS ──

private const val HALO_OP_MUL = 2.0f
private const val EXTRA_INTENSITY = 3.51f
private const val PEAK_OP = 0.85f
private const val BASE_OP = 0.34f
private const val INSET = 1.5f
private const val EXTRA_OUTWARD = 1.0f
private const val WANDER_RANGE = 15f
private const val WANDER_LERP = 0.0075f
private const val FADE_RATE = 0.00875f
private const val LUM_LO = 0.08f
private const val LUM_HI = 0.32f
private const val MIN_DWELL_MS = 1500f
private const val RELOC_FADE_MS = 300f
private const val RELOC_FADE_OUT_MS = 450f
internal const val HALO_HALF_LEN = 7.8f
internal const val EXTRA_HALF_LEN = 9.13952f / 3

private const val RELOCATE_DELTA = 0.05f
private const val WANDER_RETARGET_MS = 120 * (1000f / 15)
private const val RATE_TICK_MS = 1000f / 15
private const val HUNT_MS = 66f
private const val TINT_HOLD_MS = 2000f
private const val TINT_FADE_MS = 400f
private const val ENV_MAX_STEP_MS = 34f

private fun ssf(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / max(1e-9f, e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

private class Tween(val from: Float, val to: Float, val dur: Float, val start: Float) {
    var value = from
    var done = false
    fun tick(clock: Float): Float {
        val t = ((clock - start) / max(1f, dur)).coerceIn(0f, 1f)
        val e = t * t * (3 - 2 * t)
        value = if (t >= 1f) to else from + (to - from) * e
        done = t >= 1f
        return value
    }
}

/** One frame of the halo, in the box's points. */
internal class GlowFrame(
    val x: Float, val y: Float, val ex: Float, val ey: Float, val tangent: Float,
    val haloOp: Float, val extraOp: Float, val env: Float, val tint: FloatArray,
)

/** The glow state machine (`glowTick`), stepped once per drawn frame. */
internal class MetalGlowState {
    private var perimX = FloatArray(0)
    private var perimY = FloatArray(0)
    private var perimArc = FloatArray(0)
    private var lum = FloatArray(0)
    private var w = 0f
    private var h = 0f
    private var r = 0f
    private var kind = MetalShapeKind.Pill
    private var currentIdx = 0
    private var glowOpacity = 0f
    private var appearedAt = 0f
    private var relocNextIdx = 0
    private var tween: Tween? = null
    private var envClock = 0f
    private var lastTickMs = 0f
    private var lastHuntMs = 0f
    private var wanderS = 0f
    private var wanderTargetS = 0f
    private var wanderMs = 0f
    private var tintFrom = floatArrayOf(1f, 1f, 1f)
    private var tintTarget = floatArrayOf(1f, 1f, 1f)
    private var tintTween: Tween? = null
    private var tintHoldUntil = 0f

    fun configure(w: Float, h: Float, r: Float, kind: MetalShapeKind) {
        if (this.w == w && this.h == h && this.r == r && this.kind == kind && perimArc.isNotEmpty()) return
        this.w = w; this.h = h; this.r = r; this.kind = kind
        val n = 16
        val total = shapePerim(w, h, r, kind)
        perimX = FloatArray(n)
        perimY = FloatArray(n)
        perimArc = FloatArray(n)
        for (i in 0 until n) {
            val arc = total * i / n
            val p = sampleAtArc(arc, w, h, r, INSET, 0f, kind)
            perimX[i] = p[0]; perimY[i] = p[1]; perimArc[i] = arc
        }
        lum = FloatArray(n)
        if (currentIdx >= n) currentIdx = 0
    }

    fun tick(nowMs: Float, sample: (x: Float, y: Float) -> DoubleArray, strength: Float): GlowFrame? {
        val n = perimArc.size
        if (n == 0) return null
        val dtMs = if (lastTickMs > 0) (nowMs - lastTickMs).coerceIn(0.5f, 200f) else RATE_TICK_MS
        lastTickMs = nowMs
        envClock += min(dtMs, ENV_MAX_STEP_MS)
        fun rate(perTick: Float) = 1 - (1 - perTick).pow(dtMs / RATE_TICK_MS)

        if (nowMs - lastHuntMs >= HUNT_MS || lastHuntMs == 0f) {
            lastHuntMs = nowMs
            for (i in 0 until n) {
                val c = sample(perimX[i], perimY[i])
                lum[i] = (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]).toFloat()
            }
        }
        var maxLum = -1f
        var maxIdx = currentIdx
        for (i in 0 until n) if (lum[i] > maxLum) { maxLum = lum[i]; maxIdx = i }
        val curLum = lum[currentIdx]

        val dwellActive = appearedAt > 0 && nowMs - appearedAt < MIN_DWELL_MS
        val targetOp = BASE_OP + (PEAK_OP - BASE_OP) * ssf(LUM_LO, LUM_HI, curLum)
        val rivalDominates = !dwellActive && maxLum - curLum > RELOCATE_DELTA
        fun fadeIn() {
            appearedAt = nowMs; wanderS = 0f; wanderTargetS = 0f; wanderMs = 0f
            tween = Tween(0f, 1f, RELOC_FADE_MS, envClock)
        }
        tween?.let { tw ->
            if (tw.done && tw.to == 0f) {
                currentIdx = relocNextIdx
                glowOpacity = BASE_OP + (PEAK_OP - BASE_OP) * ssf(LUM_LO, LUM_HI, lum[currentIdx])
                fadeIn()
            }
        }
        val tw = tween
        if (tw == null || tw.done) {
            if (appearedAt == 0f) {
                currentIdx = maxIdx; glowOpacity = targetOp; fadeIn()
            } else if (rivalDominates) {
                relocNextIdx = maxIdx
                tween = Tween(1f, 0f, RELOC_FADE_OUT_MS, envClock)
            }
        }
        glowOpacity = (glowOpacity + (targetOp - glowOpacity) * rate(FADE_RATE)).coerceIn(0f, 1f)
        val relocMul = tween?.tick(envClock) ?: 1f

        val ratio = shapePerim(w, h, r, kind) / rrPerim(140f, 40f, 20f)
        wanderMs += dtMs
        if (wanderMs >= WANDER_RETARGET_MS) {
            wanderTargetS = (Random.nextFloat() * 2 - 1) * WANDER_RANGE * ratio
            wanderMs = 0f
        }
        wanderS += (wanderTargetS - wanderS) * rate(WANDER_LERP)

        val arc = perimArc[currentIdx] + wanderS
        val b = sampleAtArc(arc, w, h, r, INSET, 0f, kind)
        val tangent = tangentAngleAtArc(arc, w, h, r, INSET, kind)
        val e = sampleAtArc(arc, w, h, r, INSET, EXTRA_OUTWARD * ratio, kind)

        val c = sample(b[0], b[1])
        val samp = floatArrayOf(c[0].toFloat(), c[1].toFloat(), c[2].toFloat())
        val tt = tintTween
        if (tt == null) {
            tintFrom = samp; tintTarget = samp
            tintTween = Tween(0f, 1f, TINT_FADE_MS, nowMs)
            tintHoldUntil = nowMs + TINT_HOLD_MS
        } else if (tt.done && nowMs >= tintHoldUntil) {
            tintFrom = tintTarget; tintTarget = samp
            tintTween = Tween(0f, 1f, TINT_FADE_MS, nowMs)
            tintHoldUntil = nowMs + TINT_HOLD_MS
        }
        val ft = tintTween!!.tick(nowMs)
        var tint = FloatArray(3) { i -> tintFrom[i] + (tintTarget[i] - tintFrom[i]) * ft }
        // dark theme: the halo takes the tint's hue at full brightness
        val peak = max(tint[0], max(tint[1], tint[2]))
        if (peak > 0) tint = FloatArray(3) { tint[it] / peak }
        val m = strength.coerceIn(0f, 1f)
        return GlowFrame(
            x = b[0], y = b[1], ex = e[0], ey = e[1], tangent = tangent,
            haloOp = min(1f, glowOpacity * HALO_OP_MUL * m),
            extraOp = min(1f, glowOpacity * EXTRA_INTENSITY * m),
            env = relocMul, tint = tint,
        )
    }
}

// ── Sprites: white, alpha-only, symmetric; the segment's midpoint is the bitmap centre ──

internal class GlowSprite(val bitmap: Bitmap, val widthPt: Float, val heightPt: Float)

private class SpriteLayer(val stroke: Float, val blur: Float, val opacity: Float)

private val spriteCache = HashMap<String, GlowSprite>()

private fun composeSprite(layers: List<SpriteLayer>, halfLen: Float, scale: Float, fade: Float): GlowSprite {
    var padMax = 0f
    for (l in layers) padMax = max(padMax, l.stroke / 2 + 3 * l.blur)
    val pad = ceil(padMax) + 1
    val cw = 2 * halfLen + 2 * pad
    val ch = 2 * pad
    val bitmap = Bitmap.createBitmap(ceil(cw * scale).toInt(), ceil(ch * scale).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(scale, scale)
    for (l in layers) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = android.graphics.Color.argb((l.opacity * 255).toInt(), 255, 255, 255)
            // Skia's blur is a Gaussian of sigma = 0.57735·radius + 0.5; the web's blur(px) is the sigma.
            val radius = (l.blur * scale - 0.5f) / 0.57735f
            if (radius > 0.1f) maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        // mask filters are applied in device pixels, so draw this layer unscaled
        canvas.save()
        canvas.scale(1 / scale, 1 / scale)
        paint.strokeWidth = l.stroke * scale
        canvas.drawLine(pad * scale, pad * scale, (pad + 2 * halfLen) * scale, pad * scale, paint)
        canvas.restore()
    }
    if (fade > 0) {
        // radial end fade: opaque to 30 %, 25 % at 65 %, gone at the radius
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = RadialGradient(
                pad + halfLen, pad, fade + halfLen,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x40FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.3f, 0.65f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, cw, ch, mask)
    }
    return GlowSprite(bitmap, cw, ch)
}

internal fun haloSprite(halfLen: Float, scale: Float): GlowSprite = synchronized(spriteCache) {
    spriteCache.getOrPut("h|${"%.2f".format(halfLen)}|$scale") {
        composeSprite(
            listOf(
                SpriteLayer(26.4f, 8.4f, 0.385f),
                SpriteLayer(15.6f, 4.8f, 0.595f),
                SpriteLayer(7.2f, 2.1f, 0.70f),
                SpriteLayer(3.0f, 0.9f, 0.70f),
            ),
            halfLen, scale, 0f,
        )
    }
}

internal fun extraSprite(halfLen: Float, scale: Float): GlowSprite = synchronized(spriteCache) {
    spriteCache.getOrPut("e|${"%.2f".format(halfLen)}|$scale") {
        composeSprite(
            listOf(SpriteLayer(4f / 3, 2f / 3, 0.85f), SpriteLayer(2f / 3, 1.35f / 3, 1f)),
            halfLen, scale, 13f / 3,
        )
    }
}
