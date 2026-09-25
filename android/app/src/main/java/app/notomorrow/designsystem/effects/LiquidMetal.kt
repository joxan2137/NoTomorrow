package app.notomorrow.designsystem.effects

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Liquid metal for a surface's edge — `MetalFx(variant: .button, glow: false, tilt: false)` from
 * MetalFxKit (libraries.dev `metal-fx`, MIT, © Jakub Antalik), the configuration the app uses.
 *
 * Draws, behind [content]: the [fill] in the rounded box, a [ringWidth] band of the animated
 * material along its edge, and the 1 dp white rim over the band. The material is Paper Shaders'
 * `liquidMetal` (Apache-2.0, © Paper Design, Inc.) as upstream's React Native port writes it in
 * SkSL (`metal-fx-native/src/shader.ts`), which AGSL accepts as is.
 *
 * AGSL needs API 33; below it (or if the shader fails to compile) the band is a still, brushed
 * silver gradient. The tilt bend and the glow halo of the iOS package are left out on both
 * platforms at the call sites, so this is the whole look.
 */
@Composable
fun LiquidMetalSurface(
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    preset: LiquidMetalPreset = LiquidMetalPreset.Silver,
    strength: Float = 1f,
    ringWidth: Dp = 1.dp,
    fill: Color = Color(0xFF272727),
    content: @Composable BoxScope.() -> Unit,
) {
    val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) rememberMetalShader() else null
    val time = rememberEffectTime(running = shader != null)
    Box(
        modifier.drawBehind {
            val radius = min(cornerRadius.toPx(), size.minDimension / 2)
            val ring = ringWidth.toPx()
            val outer = RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))
            drawPath(Path().apply { addRoundRect(outer) }, fill)

            val band = bandPath(outer, ring, radius)
            val brush = if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                shader.update(this, preset, time.value, strength)
                shader.brush
            } else {
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.75f * strength), Color.White.copy(alpha = 0.2f * strength)),
                )
            }
            drawPath(band, brush)
            // the 1 dp rim over the band: white 10 % on dark
            drawPath(bandPath(outer, 1.dp.toPx(), radius), Color.White.copy(alpha = 0.1f))
        },
        content = content,
    )
}

/** The rounded box minus its inset, even-odd, so only the ring is painted. */
private fun bandPath(outer: RoundRect, inset: Float, radius: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    addRoundRect(outer)
    addRoundRect(
        RoundRect(
            inset, inset, outer.right - inset, outer.bottom - inset, CornerRadius(max(0f, radius - inset)),
        ),
    )
}

/**
 * The dark-theme presets (`MetalMaterial.preset`): the same silver material, told apart by the
 * colour-burn tint (whose alpha is the burn amount) and the channel dispersion.
 */
enum class LiquidMetalPreset(
    internal val tint: FloatArray,
    internal val speed: Float,
    internal val repetition: Float,
    internal val softness: Float,
    internal val shiftRed: Float,
    internal val shiftBlue: Float,
    internal val shaderOpacity: Float,
) {
    /** Iridescent: a cool blue burn and a wide R/B split. `#88ccff2e`. */
    Chromatic(floatArrayOf(0x88 / 255f, 0xCC / 255f, 1f, 0x2E / 255f), 1f, 2f, 0.09f, 0.75f, 0.75f, 1f),

    /** Cool steel: Paper's material nearly untouched. `#ffffff66`. */
    Silver(floatArrayOf(1f, 1f, 1f, 0x66 / 255f), 1f, 1.5f, 0.05f, 0.3f, 0.3f, 0.88f),
}

// Paper `fullScreenPreset` values shared by every preset.
private const val DISTORTION = 0.1f
private const val CONTOUR = 0.4f
private const val ANGLE = 90f

/** `MetalSheetMapping`: the material is laid out on a 140 × 40 pt sheet, zoomed by the variant's 1.6. */
private const val CANONICAL_W = 140f
private const val CANONICAL_H = 40f
private const val BUTTON_SHADER_SCALE = 1.6f

private class MetalShader(val shader: RuntimeShader) {
    val brush = ShaderBrush(shader)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun update(scope: DrawScope, preset: LiquidMetalPreset, t: Double, strength: Float) {
        // The mapping is defined in points; the shader sees pixels, so fold density into the scale.
        val d = scope.density
        val w = max(1f, scope.size.width / d)
        val h = max(1f, scope.size.height / d)
        val fx = min(1f, w / (CANONICAL_W * BUTTON_SHADER_SCALE))
        val fy = min(1f, h / (CANONICAL_H * BUTTON_SHADER_SCALE))
        shader.setFloatUniform("uvOrigin", 0.5f - 0.5f * fx, 0.5f - 0.5f * fy)
        shader.setFloatUniform("uvScale", fx / w / d, fy / h / d)
        shader.setFloatUniform("time", (t * preset.speed).toFloat())
        shader.setFloatUniform("colorBack", 0f, 0f, 0f, 0f)
        shader.setFloatUniform("colorTint", preset.tint)
        shader.setFloatUniform("repetition", preset.repetition)
        shader.setFloatUniform("softness", preset.softness)
        shader.setFloatUniform("shiftRed", preset.shiftRed)
        shader.setFloatUniform("shiftBlue", preset.shiftBlue)
        shader.setFloatUniform("distortion", DISTORTION)
        shader.setFloatUniform("contour", CONTOUR)
        shader.setFloatUniform("angle", ANGLE)
        shader.setFloatUniform("opacityMul", strength.coerceIn(0f, 1f) * preset.shaderOpacity)
        // positions already arrive in pixels
        shader.setFloatUniform("ditherScale", 1f)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberMetalShader(): MetalShader? = remember {
    runCatching { MetalShader(RuntimeShader(LIQUID_METAL_AGSL)) }.getOrNull()
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
