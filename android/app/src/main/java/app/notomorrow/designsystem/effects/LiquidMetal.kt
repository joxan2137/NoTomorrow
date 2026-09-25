package app.notomorrow.designsystem.effects

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** `MetalFxVariant`: a pill with a 1 pt band at zoom 1.6, or a circle with a 2 pt band at zoom 1.3. */
enum class MetalVariant(internal val ring: Float, internal val shaderScale: Float) {
    Button(1f, 1.6f),
    Circle(2f, 1.3f),
}

/**
 * The liquid-metal ring round any content — `MetalFx` from MetalFxKit (libraries.dev `metal-fx`,
 * MIT, © Jakub Antalik), dark theme, without the tilt bend and reflections (which the iOS call
 * sites leave off too).
 *
 * Behind [content], in upstream's order: the [fill], the animated metal band along the edge, the
 * circle variant's dark hairline, the white rim, the wandering glow halo (see `MetalGlow.kt`) and,
 * with [innerShadow], the light hairline along the band's top inside edge. The halo reaches up to
 * 48 dp past the box, so give the component room.
 *
 * The material is Paper Shaders' `liquidMetal` (Apache-2.0) as AGSL — API 33+; below that the
 * band is a still silver gradient and everything else is unchanged. A shape's window onto the
 * material is capped ([sheetMapping]) so a full-width ring stays whole end to end.
 *
 * @param ringWidth the band, in dp; defaults to the variant's (pill 1, circle 2).
 * @param cornerRadius null rounds fully (a capsule or circle).
 * @param innerShadow upstream uses it on the 2 dp circle; on a 1 dp band it whitens the whole ring.
 * @param strength 0…1, multiplies the material's opacity and the glow.
 */
@Composable
fun MetalFx(
    modifier: Modifier = Modifier,
    variant: MetalVariant = MetalVariant.Button,
    preset: LiquidMetalPreset = LiquidMetalPreset.Chromatic,
    strength: Float = 1f,
    ringWidth: Dp? = null,
    cornerRadius: Dp? = null,
    innerShadow: Boolean = false,
    glow: Boolean = true,
    glowGain: Float = 1f,
    fill: Color = Color(0xFF272727),
    content: @Composable BoxScope.() -> Unit,
) {
    val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) rememberMetalShader() else null
    val glowState = remember { MetalGlowState() }
    val paints = remember { MetalPaints() }
    val time = rememberEffectTime()
    Box(
        modifier.drawBehind {
            drawMetal(
                paints, shader, glowState, time.value, variant, preset, strength.coerceIn(0f, 1f),
                ringWidth?.value, cornerRadius?.value, innerShadow, glow, glowGain, fill,
            )
        },
        content = content,
    )
}

private class MetalPaints {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val band = Paint(Paint.ANTI_ALIAS_FLAG)
    val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = android.graphics.Color.argb(115, 0, 0, 0)
    }
    val sprite = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    val dstOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    val layer = Paint()
}

private const val GLOW_MARGIN = 48f

private fun roundRectPath(path: Path, l: Float, t: Float, r: Float, b: Float, radius: Float) {
    val rr = max(0f, min(radius, min(r - l, b - t) / 2))
    path.addRoundRect(RectF(l, t, r, b), rr, rr, Path.Direction.CW)
}

/** The box minus its inset, even-odd, so only the ring is painted. */
private fun bandPath(w: Float, h: Float, radius: Float, inset: Float): Path = Path().apply {
    fillType = Path.FillType.EVEN_ODD
    roundRectPath(this, 0f, 0f, w, h, radius)
    roundRectPath(this, inset, inset, w - inset, h - inset, max(0f, radius - inset))
}

private fun DrawScope.drawMetal(
    paints: MetalPaints,
    shader: RuntimeShader?,
    glowState: MetalGlowState,
    t: Double,
    variant: MetalVariant,
    preset: LiquidMetalPreset,
    strength: Float,
    ringWidthPt: Float?,
    cornerRadiusPt: Float?,
    innerShadow: Boolean,
    glow: Boolean,
    glowGain: Float,
    fill: Color,
) {
    val d = density
    val w = size.width / d
    val h = size.height / d
    if (w < 1f || h < 1f) return
    val radius = min(cornerRadiusPt ?: Float.MAX_VALUE, min(w, h) / 2)
    val kind = metalShapeKind(w, h, radius)
    val mapping = sheetMapping(w, h, variant.shaderScale)
    val ring = ringWidthPt ?: variant.ring
    val nc = drawContext.canvas.nativeCanvas

    nc.save()
    // Everything below is in points, like the iOS package, so the material's window and the
    // glow's numbers carry over unchanged.
    nc.scale(d, d)

    val outer = Path().apply { roundRectPath(this, 0f, 0f, w, h, radius) }
    paints.fill.color = fill.toArgb()
    nc.drawPath(outer, paints.fill)

    val band = bandPath(w, h, radius, ring)
    if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        shader.setFloatUniform("uvOrigin", mapping.ox, mapping.oy)
        shader.setFloatUniform("uvScale", mapping.sx, mapping.sy)
        shader.setFloatUniform("time", (t * preset.speed).toFloat())
        shader.setFloatUniform("colorBack", 0f, 0f, 0f, 0f)
        shader.setFloatUniform("colorTint", preset.tint)
        shader.setFloatUniform("repetition", preset.repetition)
        shader.setFloatUniform("softness", preset.softness)
        shader.setFloatUniform("shiftRed", preset.shiftRed)
        shader.setFloatUniform("shiftBlue", preset.shiftBlue)
        shader.setFloatUniform("distortion", preset.distortion)
        shader.setFloatUniform("contour", preset.contour)
        shader.setFloatUniform("angle", preset.angle)
        shader.setFloatUniform("opacityMul", strength * preset.shaderOpacity)
        shader.setFloatUniform("ditherScale", d)
        paints.band.shader = shader
        paints.band.alpha = 255
    } else {
        paints.band.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFFF4F4F8.toInt(), 0xFF6E6E74.toInt(), 0xFFE4E4EA.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        paints.band.alpha = (strength * 255).toInt()
    }
    nc.drawPath(band, paints.band)

    if (variant == MetalVariant.Circle) {
        val hair = Path().apply { roundRectPath(this, -0.5f, -0.5f, w + 0.5f, h + 0.5f, radius + 0.5f) }
        nc.drawPath(hair, paints.hair)
    }
    // the white 10 % rim over the band (1 pt on a pill, 2 pt on a circle — the band's own width)
    paints.solid.color = android.graphics.Color.argb(26, 255, 255, 255)
    nc.drawPath(band, paints.solid)

    if (glow) drawGlow(nc, paints, glowState, t, d, w, h, radius, kind, mapping, preset, strength * glowGain, band)

    if (innerShadow) {
        // The band minus itself shifted down 1 pt: a light hairline on the band's top inside edge.
        paints.layer.alpha = (0.9f * 255).toInt()
        val save = nc.saveLayer(-1f, -1f, w + 1f, h + 2f, paints.layer)
        paints.solid.color = android.graphics.Color.WHITE
        nc.drawPath(band, paints.solid)
        nc.save()
        nc.translate(0f, 1f)
        nc.drawPath(band, paints.dstOut)
        nc.restore()
        nc.restoreToCount(save)
    }
    nc.restore()
}

private fun drawGlow(
    nc: android.graphics.Canvas,
    paints: MetalPaints,
    state: MetalGlowState,
    t: Double,
    density: Float,
    w: Float,
    h: Float,
    radius: Float,
    kind: MetalShapeKind,
    mapping: SheetMapping,
    preset: LiquidMetalPreset,
    strength: Float,
    band: Path,
) {
    state.configure(w, h, radius, kind)
    val frame = state.tick((t * 1000).toFloat(), { x, y ->
        sampleMetal(preset, (mapping.ox + x * mapping.sx).toDouble(), (mapping.oy + y * mapping.sy).toDouble(), t)
    }, strength) ?: return
    val ratio = shapePerim(w, h, radius, kind) / rrPerim(140f, 40f, 20f)
    // sprites are baked at the screen's density, then drawn in points
    val halo = haloSprite(max(1f, HALO_HALF_LEN * ratio), density)
    val extra = extraSprite(max(0.6f, EXTRA_HALF_LEN * ratio), density)
    val m = GLOW_MARGIN

    paints.layer.alpha = (frame.env * 0.7f * 255).toInt().coerceIn(0, 255)
    val save = nc.saveLayer(-m, -m, w + m, h + m, paints.layer)
    val deg = Math.toDegrees(frame.tangent.toDouble()).toFloat()

    paints.sprite.alpha = (frame.haloOp * 255).toInt().coerceIn(0, 255)
    paints.sprite.colorFilter = PorterDuffColorFilter(
        android.graphics.Color.rgb(
            (frame.tint[0] * 255).toInt(), (frame.tint[1] * 255).toInt(), (frame.tint[2] * 255).toInt(),
        ),
        PorterDuff.Mode.MULTIPLY,
    )
    drawSprite(nc, halo, frame.x, frame.y, deg, paints.sprite)
    paints.sprite.alpha = (frame.extraOp * 255).toInt().coerceIn(0, 255)
    paints.sprite.colorFilter = null
    drawSprite(nc, extra, frame.ex, frame.ey, deg, paints.sprite)

    // Mask: half strength everywhere, full strength on the band itself.
    val mask = nc.saveLayer(-m, -m, w + m, h + m, paints.dstIn)
    paints.solid.color = android.graphics.Color.argb(128, 255, 255, 255)
    nc.drawRect(-m, -m, w + m, h + m, paints.solid)
    paints.solid.color = android.graphics.Color.WHITE
    nc.drawPath(band, paints.solid)
    nc.restoreToCount(mask)
    nc.restoreToCount(save)
}

private fun drawSprite(nc: android.graphics.Canvas, sprite: GlowSprite, x: Float, y: Float, deg: Float, paint: Paint) {
    nc.save()
    nc.translate(x, y)
    nc.rotate(deg)
    nc.drawBitmap(
        sprite.bitmap, null,
        RectF(-sprite.widthPt / 2, -sprite.heightPt / 2, sprite.widthPt / 2, sprite.heightPt / 2),
        paint,
    )
    nc.restore()
}

@Composable
private fun rememberMetalShader(): RuntimeShader? = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching { RuntimeShader(LIQUID_METAL_AGSL) }.getOrNull() else null
}

/**
 * Paper Shaders' `liquidMetal` fragment stage in `shape: none` mode, as upstream's React Native
 * port writes it (`metal-fx-native/src/shader.ts`, unchanged). The sheet window is
 * `uvOrigin + pos * uvScale`; with no derivatives, the stripe anti-aliasing width is a finite
 * difference over one texel of the web's 192 px sheet. Output is premultiplied.
 */
private const val LIQUID_METAL_AGSL = """
uniform float2 uvOrigin;
uniform float2 uvScale;
uniform float time;
uniform float4 colorBack;
uniform float4 colorTint;
uniform float repetition;
uniform float softness;
uniform float shiftRed;
uniform float shiftBlue;
uniform float distortion;
uniform float contour;
uniform float angle;
uniform float opacityMul;
uniform float ditherScale;

const float PI = 3.14159265358979323846;
const float TEXEL = 1.0 / 192.0;

float3 permute3(float3 x) { return mod(((x * 34.0) + 1.0) * x, 289.0); }

float snoise(float2 v) {
  const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
  float2 i = floor(v + dot(v, C.yy));
  float2 x0 = v - i + dot(i, C.xx);
  float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
  float4 x12 = x0.xyxy + C.xxzz;
  x12.xy -= i1;
  i = mod(i, 289.0);
  float3 p = permute3(permute3(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
  float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
  m = m * m;
  m = m * m;
  float3 x = 2.0 * fract(p * C.www) - 1.0;
  float3 h = abs(x) - 0.5;
  float3 ox = floor(x + 0.5);
  float3 a0 = x - ox;
  m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
  float3 g;
  g.x = a0.x * x0.x + h.x * x0.y;
  g.yz = a0.yz * x12.xz + h.yz * x12.yw;
  return 130.0 * dot(m, g);
}

float ss(float e0, float e1, float x) { return smoothstep(e0, max(e1, e0 + 1e-6), x); }

float2 rot2(float2 uv, float th) { return float2(cos(th) * uv.x - sin(th) * uv.y, sin(th) * uv.x + cos(th) * uv.y); }

float colorChanges(float c1, float c2, float p, float3 w, float blur, float bump, float tint, float tintA) {
  blur = max(blur, 1e-5);
  float ch = mix(c2, c1, ss(0.0, 2.0 * blur, p));
  float border = w[0];
  ch = mix(ch, c2, ss(border, border + 2.0 * blur, p));
  border = w[0] + 0.4 * (1.0 - bump) * w[1];
  ch = mix(ch, c1, ss(border, border + 2.0 * blur, p));
  border = w[0] + 0.5 * (1.0 - bump) * w[1];
  ch = mix(ch, c2, ss(border, border + 2.0 * blur, p));
  border = w[0] + w[1];
  ch = mix(ch, c1, ss(border, border + 2.0 * blur, p));
  float gt = (p - w[0] - w[1]) / w[2];
  float gradient = mix(c1, c2, ss(0.0, 1.0, gt));
  ch = mix(ch, gradient, ss(border, border + 0.5 * blur, p));
  ch = mix(ch, 1.0 - min(1.0, (1.0 - ch) / max(tint, 0.0001)), tintA);
  return ch;
}

float4 fieldA(float2 uv, float t, float noise, float cw, float2 rot) {
  float2 uvc = clamp(uv, 0.0, 1.0);
  float2 mask = min(uvc, 1.0 - uvc);
  float maskX = pow(ss(0.0, 0.5, mask.x), 0.25);
  float maskY = pow(ss(0.0, 0.5, mask.y), 0.25);
  float edge = clamp(1.0 - maskX * maskY, 0.0, 1.0);
  const float fwE = 0.002;
  edge = mix(ss(0.9 - 2.0 * fwE, 0.9, edge), edge, ss(0.0, 0.4, contour));
  edge = 1.2 * edge;

  float2 r = uv - 0.5;
  float2 rotated = float2(r.x * rot.x - r.y * rot.y, r.x * rot.y + r.y * rot.x) + 0.5;
  float diagBL = rotated.x - rotated.y;

  float2 g = uv - 0.5;
  float dist = length(g + float2(0.0, 0.2 * diagBL));
  g = rot2(g, (0.25 - 0.2 * diagBL) * PI);
  float direction = g.x;

  float uy = max(uvc.y, 0.0);
  float bump = 1.0 - pow(1.8 * dist, 1.2);
  bump *= pow(uy, 0.3);

  edge += (1.0 - edge) * distortion * noise;

  direction += diagBL;
  float sE = ss(0.0, 1.0, edge);
  direction -= 2.0 * noise * diagBL * (sE * (1.0 - sE));
  float c51 = ss(0.5, 1.0, contour);
  direction *= mix(1.0, 1.0 - edge, c51);
  direction -= 1.7 * edge * c51;
  direction += 0.2 * pow(contour, 4.0) * (1.0 - sE);

  bump *= clamp(pow(uy, 0.1), 0.3, 1.0);
  direction *= (0.1 + (1.1 - edge) * bump);
  direction *= (0.4 + 0.6 * (1.0 - ss(0.5, 1.0, edge)));
  direction += 0.18 * (ss(0.1, 0.2, uv.y) * (1.0 - ss(0.2, 0.4, uv.y)));
  direction += 0.03 * (ss(0.1, 0.2, 1.0 - uv.y) * (1.0 - ss(0.2, 0.4, 1.0 - uv.y)));
  direction *= (0.5 + 0.5 * uv.y * uv.y);
  direction *= cw;
  direction -= t;
  return float4(direction, bump, edge, diagBL);
}

half4 main(float2 pos) {
  float2 uv = uvOrigin + pos * uvScale;
  float t = 0.3 * (time + 2.8);
  float cw = repetition * 2.0;
  float a = (-angle + 70.0) * PI / 180.0;
  float2 rot = float2(cos(a), sin(a));

  float noise = snoise(uv - t);
  float4 f = fieldA(uv, t, noise, cw, rot);
  float4 fx = fieldA(uv + float2(TEXEL, 0.0), t, noise, cw, rot);
  float4 fy = fieldA(uv + float2(0.0, TEXEL), t, noise, cw, rot);
  float fw = abs(fx.x - f.x) + abs(fy.x - f.x);
  float direction = f.x, bump = f.y, edge = f.z, diagBL = f.w;

  float2 r = uv - 0.5;
  float diagTL = (r.x * rot.x - r.y * rot.y + 0.5) + (r.x * rot.y + r.y * rot.x + 0.5);

  const float fwE = 0.002;
  float2 uvc = clamp(uv, 0.0, 1.0);
  float2 mask = min(uvc, 1.0 - uvc);
  float rawEdge = clamp(1.0 - pow(ss(0.0, 0.5, mask.x), 0.25) * pow(ss(0.0, 0.5, mask.y), 0.25), 0.0, 1.0);
  float opacity = 1.0 - ss(0.9 - 2.0 * fwE, 0.9, rawEdge);

  float3 color1 = float3(0.98, 0.98, 1.0);
  float3 color2 = float3(0.1, 0.1, 0.1 + 0.1 * ss(0.7, 1.3, diagTL));

  float thin1 = 0.12 / cw * (1.0 - 0.4 * bump);
  float thin2 = 0.07 / cw * (1.0 + 0.4 * bump);
  float3 w = float3(cw * thin1, cw * thin2, 1.0 - thin1 - thin2);

  float cd = clamp(1.0 - bump, 0.0, 1.0);
  float dR = cd;
  dR += 0.03 * bump * noise;
  dR += 5.0 * (ss(-0.1, 0.2, uv.y) * (1.0 - ss(0.1, 0.5, uv.y))) * (ss(0.4, 0.6, bump) * (1.0 - ss(0.4, 1.0, bump)));
  dR -= diagBL;
  float dB = cd * 1.3;
  dB += (ss(0.0, 0.4, uv.y) * (1.0 - ss(0.1, 0.8, uv.y))) * (ss(0.4, 0.6, bump) * (1.0 - ss(0.4, 0.8, bump)));
  dB -= 0.2 * edge;
  dR *= (shiftRed / 20.0);
  dB *= (shiftBlue / 20.0);

  float blur = softness / 15.0;
  w[1] -= 0.02 * ss(0.0, 1.0, edge + bump);

  float cr = colorChanges(color1.r, color2.r, fract(direction + dR), w, blur + fw, bump, colorTint.r, colorTint.a);
  float cg = colorChanges(color1.g, color2.g, fract(direction), w, blur + fw, bump, colorTint.g, colorTint.a);
  float cb = colorChanges(color1.b, color2.b, fract(direction - dB), w, blur + fw, bump, colorTint.b, colorTint.a);

  float3 color = float3(cr, cg, cb) * opacity;
  float3 bg = colorBack.rgb * colorBack.a;
  color = color + bg * (1.0 - opacity);
  opacity = opacity + colorBack.a * (1.0 - opacity);
  color += 1.0 / 256.0 * (fract(sin(dot(0.014 * pos * ditherScale, float2(12.9898, 78.233))) * 43758.5453123) - 0.5);

  float k = opacityMul;
  return half4(half3(color * k), half(opacity * k));
}
"""
