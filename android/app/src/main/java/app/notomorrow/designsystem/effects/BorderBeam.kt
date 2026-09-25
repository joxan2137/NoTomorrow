package app.notomorrow.designsystem.effects

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * A soft glow that rides the border — `BorderBeam(size: .md, colorVariant: .sunset, theme: .dark)`
 * from BorderBeamKit (libraries.dev `border-beam`, MIT, © Jakub Antalik), the one configuration
 * the app uses.
 *
 * Three layers drawn over [content], as on iOS and the web: an inner glow, the stroke ring (both
 * window-masked by a rotating conic gradient) and a blurred bloom ring. The shader is the upstream
 * React Native port's SkSL (`rotateShader.ts`), which AGSL accepts as is; every number below comes
 * from upstream's `beam-spec.json`, given here in CSS px (= dp) and scaled to pixels.
 *
 * AGSL needs API 33. Below that, or if the shader fails to compile, the content draws on its own.
 * The web's rotate family does not honour reduced motion, and neither does this.
 */
@Composable
fun BorderBeam(
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val fade by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(if (active) 600 else 500, easing = CssEase),
        label = "borderBeamFade",
    )
    val beam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) rememberBeamShaders() else null
    val bloomLayer = rememberGraphicsLayer()
    val time = rememberEffectTime(running = beam != null && fade > 0f)

    Box(
        modifier.drawWithContent {
            drawContent()
            if (beam != null && fade > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                drawBeam(beam, bloomLayer, time.value, fade, cornerRadius.toPx())
            }
        },
        content = content,
    )
}

/** CSS `ease`, the web library's fade curve. */
private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

// ── beam-spec.json: defaults, sizePresets.md, sizeThemePresets.md.dark, rotate.*, palettes.border.sunset ──

private const val DURATION_S = 1.96
private const val HUE_RANGE_DEG = 30.0
private const val HUE_SHIFT_PERIOD_S = 12.0
private const val BRIGHTNESS = 1.3 // brightnessFallback: md/dark has no own brightness
private const val SATURATION = 1.2
private const val BORDER_WIDTH = 1f
private const val STROKE_OPACITY = 0.26f
private const val INNER_OPACITY = 0.42f
private const val BLOOM_OPACITY = 0.24f
private const val INNER_EDGE_MASK = 28f
private const val INNER_SHADOW_BLUR = 9f
private val INNER_SHADOW = floatArrayOf(1f, 1f, 1f, 0.27f)
private const val BLOOM_BLUR_SIGMA = 8f

/** Sunset border palette: rgb, position in % of the box, radii in px. */
private class Blob(val r: Int, val g: Int, val b: Int, val x: Float, val y: Float, val rx: Float, val ry: Float)

private val SunsetBlobs = listOf(
    Blob(255, 80, 50, 33f, -7.4f, 70f, 40f),
    Blob(255, 160, 40, 12f, -5f, 60f, 35f),
    Blob(255, 120, 60, 2.1f, 68.3f, 40f, 70f),
    Blob(255, 200, 50, 2.1f, 68.3f, 20f, 35f),
    Blob(255, 100, 80, 74.4f, 100f, 180f, 32f),
    Blob(255, 180, 60, 55f, 100f, 85f, 26f),
    Blob(255, 60, 60, 93.9f, 0f, 74f, 32f),
    Blob(255, 140, 50, 100f, 27.1f, 26f, 42f),
    Blob(255, 90, 70, 100f, 27.1f, 52f, 48f),
)

/** md inner glow: the border palette at 0.9× size (rounded to whole px) and alpha 0.45. */
private const val INNER_SIZE_SCALE = 0.9f
private const val INNER_ALPHA = 0.45f

private val WhiteStopsDark = floatArrayOf(
    0f, 0f, 54f, 0f, 57f, 0.1f, 60f, 0.3f, 63f, 0.6f, 66f, 0.75f, 69f, 0.6f, 72f, 0.3f, 75f, 0.1f, 78f, 0f, 100f, 0f,
)
private val BloomStopsDark = floatArrayOf(
    0f, 0f, 58f, 0f, 62f, 0.03f, 65f, 0.08f, 67f, 0.2f, 69f, 0.45f, 70f, 0.85f, 70.5f, 0.85f, 71.5f, 0.45f,
    73f, 0.2f, 75f, 0.08f, 78f, 0.03f, 82f, 0f,
)
private val BeamMaskStops = floatArrayOf(
    0f, 0f, 30f, 0f, 36f, 0.1f, 44f, 0.35f, 52f, 1f, 80f, 1f, 86f, 0.35f, 92f, 0.1f, 95f, 0f, 100f, 0f,
)

private const val MAX_BLOBS = 12
private const val MAX_BG_STOPS = 16
private const val MAX_MASK_STOPS = 12

/** Stop pairs with the position turned from % into 0…1, padded to the uniform length. */
private fun stops(pairs: FloatArray, max: Int): FloatArray =
    FloatArray(max * 2).also { out -> for (i in pairs.indices) out[i] = if (i % 2 == 0) pairs[i] / 100f else pairs[i] }

private fun blobFloats(sizeScale: Float, alpha: Float, density: Float): FloatArray {
    val out = FloatArray(MAX_BLOBS * 8)
    SunsetBlobs.forEachIndexed { i, b ->
        val rx = if (sizeScale == 1f) b.rx else round(b.rx * sizeScale)
        val ry = if (sizeScale == 1f) b.ry else round(b.ry * sizeScale)
        val values = floatArrayOf(
            rx * density, ry * density, b.x / 100f, b.y / 100f, b.r / 255f, b.g / 255f, b.b / 255f, alpha,
        )
        values.copyInto(out, i * 8)
    }
    return out
}

// ── the CSS filter chain (BeamColorMatrix.swift): hue-rotate(θ) brightness(b) saturate(s) ──

private fun hueRotate(degrees: Double): DoubleArray {
    val rad = degrees * Math.PI / 180
    val c = cos(rad)
    val s = sin(rad)
    return doubleArrayOf(
        0.213 + c * 0.787 - s * 0.213, 0.715 - c * 0.715 - s * 0.715, 0.072 - c * 0.072 + s * 0.928,
        0.213 - c * 0.213 + s * 0.143, 0.715 + c * 0.285 + s * 0.140, 0.072 - c * 0.072 - s * 0.283,
        0.213 - c * 0.213 - s * 0.787, 0.715 - c * 0.715 + s * 0.715, 0.072 + c * 0.928 + s * 0.072,
    )
}

private fun saturate(s: Double): DoubleArray = doubleArrayOf(
    0.213 + 0.787 * s, 0.715 - 0.715 * s, 0.072 - 0.072 * s,
    0.213 - 0.213 * s, 0.715 + 0.285 * s, 0.072 - 0.072 * s,
    0.213 - 0.213 * s, 0.715 - 0.715 * s, 0.072 + 0.928 * s,
)

internal fun beamFilterMatrix(hueDegrees: Double, brightness: Double, saturation: Double): FloatArray {
    val a = saturate(saturation)
    val b = hueRotate(hueDegrees).map { it * brightness }
    return FloatArray(9) { i ->
        val row = i / 3
        val col = i % 3
        (0 until 3).sumOf { k -> a[row * 3 + k] * b[k * 3 + col] }.toFloat()
    }
}

/** Rotate-family hue shift: ±30° ping-pong over 12 s (`PulseDriver.pingPong`). */
private fun hueShift(t: Double): Double {
    val pingPong = (1 - cos(2 * Math.PI * (t / HUE_SHIFT_PERIOD_S))) / 2
    return -HUE_RANGE_DEG + 2 * HUE_RANGE_DEG * pingPong
}

// ── drawing ──

private class BeamShaders(val inner: RuntimeShader, val stroke: RuntimeShader, val bloom: RuntimeShader) {
    val innerBrush = ShaderBrush(inner)
    val strokeBrush = ShaderBrush(stroke)
    val bloomBrush = ShaderBrush(bloom)
    var density = 0f
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberBeamShaders(): BeamShaders? = remember {
    runCatching {
        // One shader per layer: each keeps its own uniforms between frames.
        BeamShaders(RuntimeShader(BEAM_ROTATE_AGSL), RuntimeShader(BEAM_ROTATE_AGSL), RuntimeShader(BEAM_ROTATE_AGSL))
    }.getOrNull()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawBeam(beam: BeamShaders, bloomLayer: GraphicsLayer, t: Double, fade: Float, radiusPx: Float) {
    if (size.width <= 0f || size.height <= 0f) return
    if (beam.density != density) {
        beam.density = density
        configureStatic(beam, density)
    }
    val angle = ((t / DURATION_S) % 1.0).toFloat()
    val matrix = beamFilterMatrix(hueShift(t), BRIGHTNESS, SATURATION)
    for (s in listOf(beam.inner, beam.stroke, beam.bloom)) {
        s.setFloatUniform("uSize", size.width, size.height)
        s.setFloatUniform("uAngle", angle)
        s.setFloatUniform("uRadius", radiusPx)
        s.setFloatUniform("uBorderWidth", BORDER_WIDTH * density)
    }
    beam.inner.setFloatUniform("uCM", matrix)
    beam.inner.setFloatUniform("uOpacity", fade * INNER_OPACITY)
    beam.stroke.setFloatUniform("uCM", matrix)
    beam.stroke.setFloatUniform("uOpacity", fade * STROKE_OPACITY)
    beam.bloom.setFloatUniform("uOpacity", fade * BLOOM_OPACITY)

    // z-order as on the web: inner glow (1), stroke ring (2), blurred bloom ring (3)
    drawRect(beam.innerBrush)
    drawRect(beam.strokeBrush)

    // The bloom's blur spreads past the box, so the layer is padded by three sigmas each side.
    val pad = ceil(BLOOM_BLUR_SIGMA * density * 3)
    val box = size
    // RenderEffect blur radii are converted to sigma as 0.57735·r + 0.5 (HWUI); invert that.
    val blurRadius = ((BLOOM_BLUR_SIGMA * density) - 0.5f) / 0.57735f
    bloomLayer.renderEffect = BlurEffect(blurRadius, blurRadius, TileMode.Decal)
    bloomLayer.record(size = IntSize((box.width + 2 * pad).toInt(), (box.height + 2 * pad).toInt())) {
        translate(pad, pad) { drawRect(beam.bloomBrush, size = Size(box.width, box.height)) }
    }
    translate(-pad, -pad) { drawLayer(bloomLayer) }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun configureStatic(beam: BeamShaders, density: Float) {
    val none = FloatArray(MAX_BLOBS * 8)
    val noBg = FloatArray(MAX_BG_STOPS * 2)
    val noMask = FloatArray(MAX_MASK_STOPS * 2)
    val mask = stops(BeamMaskStops, MAX_MASK_STOPS)
    val noShadow = floatArrayOf(0f, 0f, 0f, 0f)

    beam.inner.apply {
        setFloatUniform("uKind", 1f)
        setFloatUniform("uEdgeMaskPx", INNER_EDGE_MASK * density)
        setFloatUniform("uBlobs", blobFloats(INNER_SIZE_SCALE, INNER_ALPHA, density))
        setFloatUniform("uBlobCount", SunsetBlobs.size.toFloat())
        setFloatUniform("uBg", noBg)
        setFloatUniform("uBgCount", 0f)
        setFloatUniform("uBgIsBlack", 0f)
        setFloatUniform("uMask", mask)
        setFloatUniform("uMaskCount", BeamMaskStops.size.toFloat())
        setFloatUniform("uShadowColor", INNER_SHADOW)
        setFloatUniform("uShadowBlur", INNER_SHADOW_BLUR * density)
    }
    beam.stroke.apply {
        setFloatUniform("uKind", 0f)
        setFloatUniform("uEdgeMaskPx", 0f)
        setFloatUniform("uBlobs", blobFloats(1f, 1f, density))
        setFloatUniform("uBlobCount", SunsetBlobs.size.toFloat())
        setFloatUniform("uBg", stops(WhiteStopsDark, MAX_BG_STOPS))
        setFloatUniform("uBgCount", WhiteStopsDark.size.toFloat())
        setFloatUniform("uBgIsBlack", 0f)
        setFloatUniform("uMask", mask)
        setFloatUniform("uMaskCount", BeamMaskStops.size.toFloat())
        setFloatUniform("uShadowColor", noShadow)
        setFloatUniform("uShadowBlur", 0f)
    }
    beam.bloom.apply {
        setFloatUniform("uKind", 2f)
        setFloatUniform("uEdgeMaskPx", 0f)
        setFloatUniform("uBlobs", none)
        setFloatUniform("uBlobCount", 0f)
        setFloatUniform("uBg", stops(BloomStopsDark, MAX_BG_STOPS))
        setFloatUniform("uBgCount", BloomStopsDark.size.toFloat())
        setFloatUniform("uBgIsBlack", 0f)
        setFloatUniform("uMask", noMask)
        setFloatUniform("uMaskCount", 0f)
        // the bloom has no hue-rotate on the web, only the static brightness/saturate
        setFloatUniform("uCM", beamFilterMatrix(0.0, BRIGHTNESS, SATURATION))
        setFloatUniform("uShadowColor", noShadow)
        setFloatUniform("uShadowBlur", 0f)
    }
}

/**
 * The rotate-family layer shader: upstream `border-beam-native/src/rotateShader.ts` (a translation
 * of BorderBeamKit's `BeamShaders.metal`), unchanged apart from the array sizes being spelled out.
 * `uKind`: 0 stroke ring, 1 inner glow, 2 bloom ring. Blobs are 8 floats each
 * (rx, ry, cx, cy as a fraction of the box, r, g, b, a); stop arrays are (position 0…1, alpha).
 */
private const val BEAM_ROTATE_AGSL = """
const float PI = 3.14159265358979;

uniform float2 uSize;
uniform float uAngle;
uniform float uRadius;
uniform float uBorderWidth;
uniform float uKind;
uniform float uEdgeMaskPx;
uniform float uBlobs[96];
uniform float uBlobCount;
uniform float uBg[32];
uniform float uBgCount;
uniform float uBgIsBlack;
uniform float uMask[24];
uniform float uMaskCount;
uniform float uCM[9];
uniform float uOpacity;
uniform float4 uShadowColor;
uniform float uShadowBlur;

float roundedRectSDF(float2 p, float2 halfSize, float radius) {
  float2 q = abs(p) - halfSize + radius;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float conicFraction(float2 p, float2 center) {
  float2 d = p - center;
  float theta = atan(d.x, -d.y);
  return fract(theta / (2.0 * PI) + 1.0);
}

float bgStopsAlpha(float t) {
  int n = int(uBgCount) / 2;
  if (n == 0) { return 1.0; }
  float result = uBg[1];
  for (int i = 1; i < 16; i++) {
    if (i < n) {
      float p0 = uBg[2 * (i - 1)];
      float a0 = uBg[2 * (i - 1) + 1];
      float p1 = uBg[2 * i];
      float a1 = uBg[2 * i + 1];
      if (t > p0) {
        float f = p1 > p0 ? clamp((t - p0) / (p1 - p0), 0.0, 1.0) : 1.0;
        result = mix(a0, a1, f);
      }
    }
  }
  return result;
}

float maskStopsAlpha(float t) {
  int n = int(uMaskCount) / 2;
  if (n == 0) { return 1.0; }
  float result = uMask[1];
  for (int i = 1; i < 12; i++) {
    if (i < n) {
      float p0 = uMask[2 * (i - 1)];
      float a0 = uMask[2 * (i - 1) + 1];
      float p1 = uMask[2 * i];
      float a1 = uMask[2 * i + 1];
      if (t > p0) {
        float f = p1 > p0 ? clamp((t - p0) / (p1 - p0), 0.0, 1.0) : 1.0;
        result = mix(a0, a1, f);
      }
    }
  }
  return result;
}

float4 srcOver(float4 src, float4 dst) {
  return src + dst * (1.0 - src.a);
}

half4 main(float2 position) {
  float2 center = uSize * 0.5;
  float2 rel = position - center;
  int kind = int(uKind);

  float outerSDF = roundedRectSDF(rel, center, uRadius);
  float aa = 1.0;
  float outerCov = 1.0 - smoothstep(-aa, 0.0, outerSDF);
  float geom;
  if (kind == 1) {
    geom = outerCov;
  } else {
    float innerRadius = max(uRadius - uBorderWidth, 0.0);
    float innerSDF = roundedRectSDF(rel, center - uBorderWidth, innerRadius);
    float innerCov = 1.0 - smoothstep(-aa, 0.0, innerSDF);
    geom = outerCov - innerCov;
  }
  if (geom <= 0.0) { return half4(0.0); }

  float mask = 1.0;
  if (uMaskCount > 0.5) {
    float t = fract(conicFraction(position, center) - uAngle);
    mask = maskStopsAlpha(t);
  }

  if (kind == 1 && uEdgeMaskPx > 0.0) {
    float ev = max(1.0 - position.y / uEdgeMaskPx, 1.0 - (uSize.y - position.y) / uEdgeMaskPx);
    float eh = max(1.0 - position.x / uEdgeMaskPx, 1.0 - (uSize.x - position.x) / uEdgeMaskPx);
    float edge = clamp(max(ev, 0.0) + max(eh, 0.0), 0.0, 1.0);
    mask *= edge;
  }
  if (mask <= 0.001) { return half4(0.0); }

  float4 acc = float4(0.0);
  int nBlobs = int(uBlobCount);
  for (int i = 11; i >= 0; i--) {
    if (i < nBlobs) {
      float rx = max(uBlobs[i * 8 + 0], 0.001);
      float ry = max(uBlobs[i * 8 + 1], 0.001);
      float2 c = float2(uBlobs[i * 8 + 2], uBlobs[i * 8 + 3]) * uSize;
      float d = length((position - c) / float2(rx, ry));
      float alpha = uBlobs[i * 8 + 7] * clamp(1.0 - d, 0.0, 1.0);
      if (alpha > 0.0) {
        float3 rgb = float3(uBlobs[i * 8 + 4], uBlobs[i * 8 + 5], uBlobs[i * 8 + 6]);
        acc = srcOver(float4(rgb * alpha, alpha), acc);
      }
    }
  }

  if (uBgCount > 0.5) {
    float t = fract(conicFraction(position, center) - uAngle);
    float a = bgStopsAlpha(t);
    float3 rgb = uBgIsBlack > 0.5 ? float3(0.0) : float3(1.0);
    acc = srcOver(float4(rgb * a, a), acc);
  }

  if (kind == 1 && uShadowColor.a > 0.0 && uShadowBlur > 0.0) {
    float depth = -outerSDF;
    float fall = clamp(1.0 - depth / (uShadowBlur + 1.0), 0.0, 1.0);
    float a = uShadowColor.a * fall * fall;
    acc = srcOver(float4(uShadowColor.rgb * a, a), acc);
  }

  if (acc.a > 0.0001) {
    float3 rgb = acc.rgb / acc.a;
    float3 outRGB = float3(
      dot(float3(uCM[0], uCM[1], uCM[2]), rgb),
      dot(float3(uCM[3], uCM[4], uCM[5]), rgb),
      dot(float3(uCM[6], uCM[7], uCM[8]), rgb)
    );
    acc.rgb = clamp(outRGB, 0.0, 1.0) * acc.a;
  }

  float finalA = acc.a * geom * mask * clamp(uOpacity, 0.0, 1.0);
  if (acc.a > 0.0001) {
    return half4(half3(acc.rgb / acc.a * finalA), half(finalA));
  }
  return half4(0.0);
}
"""
