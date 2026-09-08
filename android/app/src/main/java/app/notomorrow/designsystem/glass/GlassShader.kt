package app.notomorrow.designsystem

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

/**
 * The one-pass Liquid Glass shader (`docs/android-glass.md` §3.4).
 *
 * It consumes an **already blurred** input — a `BlurEffect` is chained beneath it — and does rim
 * refraction, the dynamic-range transfer, the specular rim, optional grain and the shape's
 * antialiased alpha in a single pass.
 *
 * Attribution: `sdRoundRect` is Inigo Quilez's standard rounded-box SDF; the `circleMap` rim
 * profile follows the approach in Kyant0/AndroidLiquidGlass (Apache-2.0).
 *
 * Colour space: AGSL runs in the destination space, which for this app's non-wide-gamut window is
 * sRGB **with** the transfer curve applied — the same space every constant in §1.1 was measured in.
 * Do not put the window into F16/linear or every number here becomes wrong.
 */
internal const val NT_LIQUID_GLASS_AGSL = """
uniform shader content;

uniform float2 size;
uniform float2 pad;
uniform float4 radii;
uniform float  refractHeight;
uniform float  refractAmount;
uniform float  refractPow;
uniform float  lensMag;
uniform float  dispersion;
uniform float  rimWidth;
uniform float  rimAlpha;
uniform float2 lightDir;
uniform float  rimAniso;
uniform float  contrast;
uniform float  liftLo;
uniform float  liftHi;
uniform float  liftK0;
uniform float  liftK1;
uniform float  adaptive;
uniform float  overlay;
uniform float  noise;
layout(color) uniform half4 tint;

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

float radiusFor(float2 p, float4 r) {
    float top = p.x < 0.0 ? r.x : r.y;
    float bot = p.x < 0.0 ? r.w : r.z;
    return p.y < 0.0 ? top : bot;
}

float sdRoundRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

float2 sdGrad(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    float2 g;
    if (q.x > 0.0 || q.y > 0.0) {
        g = normalize(max(q, float2(1e-4)));
    } else {
        g = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
    }
    float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    return s * g;
}

float circleMap(float x) { return 1.0 - sqrt(max(0.0, 1.0 - x * x)); }

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p        = coord - pad - halfSize;
    float  r        = radiusFor(p, radii);
    float  sd       = sdRoundRect(p, halfSize, r);
    float  depth    = -min(sd, 0.0);

    // Whole-body magnification about the centre. Sampling *nearer* the centre than we draw makes
    // the content inside spread outward by (1 + lensMag) — the flat top of a thick lens. 0 for
    // every measured preset, so this term vanishes there.
    float2 off = -p * (lensMag / (1.0 + lensMag));

    if (refractHeight > 0.0 && depth < refractHeight) {
        float t = 1.0 - depth / refractHeight;
        // refractPow == 0 keeps the measured `circleMap` bevel (a bend that only exists in the
        // last fraction of a dp). A positive power is the *lens* profile: a broad ramp across the
        // whole band, which is what the tab pill needs mid-travel.
        float prof = refractPow > 0.0 ? pow(t, refractPow) : circleMap(t);
        off += (prof * refractAmount) * sdGrad(p, halfSize, max(r, 1.0));
    }

    float3 src;
    if (dispersion > 0.0) {
        // Shorter wavelengths refract further, so blue carries the larger offset and a feature
        // shows its blue copy pulled further in than its red one. The split rides on the *whole*
        // displacement, magnification included — which is what the burst measures: 6.75 px red to
        // blue on `burst-09`'s label where the local bend is ~28 px, and 1.25 px on its icon where
        // only the magnification's ~5 px is acting. 12 % in both places.
        src = float3(content.eval(coord + off * (1.0 - dispersion)).r,
                     content.eval(coord + off).g,
                     content.eval(coord + off * (1.0 + dispersion)).b);
    } else {
        src = float3(content.eval(coord + off).rgb);
    }

    float agg = luma(src);
    if (adaptive > 0.5) {
        float a = 0.0;
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                float2 uv = pad + size * float2((float(i) + 0.5) / 3.0,
                                                (float(j) + 0.5) / 3.0);
                a += luma(float3(content.eval(uv).rgb));
            }
        }
        agg = a / 9.0;
    }
    float lift = mix(liftLo, liftHi, smoothstep(liftK0, liftK1, agg));

    float3 rgb = src * contrast + float3(tint.rgb) * lift + overlay;

    if (rimWidth > 0.0 && depth < rimWidth) {
        float k   = 1.0 - depth / rimWidth;
        float dir = mix(1.0, max(0.0, dot(-sdGrad(p, halfSize, max(r, 1.0)), lightDir)), rimAniso);
        float bright = rimAlpha * k * dir * (1.0 - smoothstep(0.45, 0.85, agg));
        float dark   = rimAlpha * k * dir * smoothstep(0.55, 0.95, agg) * 0.5;
        rgb = mix(rgb, float3(1.0), bright);
        rgb = mix(rgb, float3(0.0), dark);
    }

    if (noise > 0.0) {
        float n = fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
        rgb += n * noise;
    }

    float cov = clamp(0.5 - sd, 0.0, 1.0);
    rgb = clamp(rgb, 0.0, 1.0) * cov;
    return half4(half3(rgb), half(cov));
}
"""

/**
 * The one compiled shader for the process, or `null` where it cannot exist (API < 33) or Skia
 * refused it.
 *
 * Sharing is safe because `RenderEffect.createRuntimeShaderEffect` **copies** the uniforms: the
 * platform hands the shader's `SkRuntimeShaderBuilder` to `SkImageFilters::RuntimeShader`, which
 * stores it by value. A node binds its uniforms and builds its effect in the same draw call, so
 * nothing reads them afterwards. Before this, every glass node compiled its own instance — an SkSL
 * front-end pass on the UI thread, repeated each time a node re-attached (every return to the tab
 * shell, every sheet) — which is not what a draw phase is for (`docs/android-glass.md` §2.3 rule 5).
 */
private val sharedGlassShader: RuntimeShader? by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        null
    } else {
        val source = if (GlassDebug.shaderPassthrough) NT_PASSTHROUGH_AGSL else NT_LIQUID_GLASS_AGSL
        runCatching { RuntimeShader(source) }.getOrNull()
    }
}

/** Debug pricing only: the effect-layer mechanism with no material at all. Same uniforms declared
 *  so `bindGlassUniforms` still binds; Skia strips what `main` does not read, and a stripped uniform
 *  makes `setFloatUniform` throw — hence every one is touched. */
internal const val NT_PASSTHROUGH_AGSL = """
uniform shader content;
uniform float2 size; uniform float2 pad; uniform float4 radii;
uniform float refractHeight; uniform float refractAmount; uniform float refractPow; uniform float lensMag;
uniform float dispersion; uniform float rimWidth; uniform float rimAlpha; uniform float2 lightDir;
uniform float rimAniso; uniform float contrast; uniform float liftLo; uniform float liftHi;
uniform float liftK0; uniform float liftK1; uniform float adaptive; uniform float overlay; uniform float noise;
layout(color) uniform half4 tint;
half4 main(float2 coord) {
    float k = (size.x + pad.x + radii.x + refractHeight + refractAmount + refractPow + lensMag + dispersion +
               rimWidth + rimAlpha + lightDir.x + rimAniso + contrast + liftLo + liftHi + liftK0 + liftK1 +
               adaptive + overlay + noise + float(tint.a)) * 0.0;
    return content.eval(coord) + half4(half(k));
}
"""

/**
 * Does this device's Skia actually compile [NT_LIQUID_GLASS_AGSL]? Answered once, lazily, on the
 * first glass draw; a `false` here drops every glass node to `GlassTier.Blur` for the whole process
 * rather than letting an `IllegalArgumentException` reach the draw phase.
 */
internal val glassShaderCompiles: Boolean
    get() = sharedGlassShader != null

/** The process-wide compiled shader. Bind every uniform, then build the effect, in one draw. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun newGlassShader(): RuntimeShader? = sharedGlassShader

/**
 * AOSP `frameworks/base/libs/hwui/utils/Blur.cpp`:
 * ```
 * static const float BLUR_SIGMA_SCALE = 0.57735f;                 // 1/sqrt(3)
 * convertRadiusToSigma(radius) = radius > 0 ? 0.57735f * radius + 0.5f : 0.0f
 * ```
 * `RenderEffect.createBlurEffect` puts its radius through that before Skia, so to get sigma `S` px
 * you must ask for `radius = (S - 0.5) / 0.57735`. Sigma 2.8 dp at density 3 = 8.4 px -> radius
 * 13.68 px. Getting this backwards is the single most common way to ship the wrong blur.
 */
internal fun blurRadiusForSigma(sigmaPx: Float): Float =
    if (sigmaPx > 0.5f) (sigmaPx - 0.5f) / 0.57735f else 0f

/**
 * Padding around the element inside the effect layer: enough for the blur kernel to be fed, and —
 * when the style bends far enough to sample outside its own shape — enough for the lens to reach.
 *
 * [reachPx] is 0 for every measured preset (their `circleMap` bend is under a dp, and 2.5 sigma is
 * already wider than it), so their layer size and therefore their rendering is untouched. The tab
 * pill's lens reaches ~11.5 dp outward at the rim; without the extra margin that band samples past
 * the layer and comes back transparent, i.e. a black bite out of the moving blob's edge.
 */
internal fun glassPadding(sigmaPx: Float, reachPx: Float = 0f): Int {
    val blur = 2.5f * sigmaPx
    val need = if (reachPx > 0f) reachPx + blur else blur
    return kotlin.math.ceil(kotlin.math.max(blur, need)).toInt().coerceAtLeast(2)
}

/**
 * Binds everything in [style] that the shader reads, for a shape [widthPx] x [heightPx] whose
 * top-left corner sits at ([padXPx], [padYPx]) inside the effect layer. `liquidGlass` centres the
 * shape behind a uniform padding; `liquidGlassLens` moves it about inside a layer that never resizes.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShader.bindGlassUniforms(
    widthPx: Float,
    heightPx: Float,
    padXPx: Float,
    padYPx: Float,
    radii: FloatArray,
    style: GlassStyle,
    density: androidx.compose.ui.unit.Density,
) {
    with(density) {
        setFloatUniform("size", widthPx, heightPx)
        setFloatUniform("pad", padXPx, padYPx)
        setFloatUniform("radii", radii[0], radii[1], radii[2], radii[3])
        setFloatUniform("refractHeight", style.refractionHeight.toPx())
        setFloatUniform("refractAmount", style.refractionAmount.toPx())
        setFloatUniform("refractPow", style.refractionPower)
        setFloatUniform("lensMag", style.lensMagnification)
        setFloatUniform("dispersion", style.dispersion)
        setFloatUniform("rimWidth", style.rimWidth.toPx())
        setFloatUniform("rimAlpha", style.rimAlpha)
        val a = Math.toRadians(style.rimAngle.toDouble() - 90.0)
        setFloatUniform("lightDir", kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
        setFloatUniform("rimAniso", style.rimAniso)
        setFloatUniform("contrast", style.contrast)
        setFloatUniform("liftLo", style.liftLo)
        setFloatUniform("liftHi", style.liftHi)
        setFloatUniform("liftK0", style.liftK0)
        setFloatUniform("liftK1", style.liftK1)
        setFloatUniform("adaptive", if (style.luminanceAdaptive && !GlassDebug.noAggregate) 1f else 0f)
        setFloatUniform("overlay", style.overlay)
        setFloatUniform("noise", style.noise)
        setColorUniform("tint", style.tint.toArgb())
    }
}

/** `shader(blur(source))` — `createChainEffect`'s *outer* argument runs second. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun buildGlassEffect(shader: RuntimeShader, sigmaPx: Float): ComposeRenderEffect {
    val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
    val radius = if (GlassDebug.noBlur) 0f else blurRadiusForSigma(sigmaPx)
    val chained = if (radius > 0f) {
        android.graphics.RenderEffect.createChainEffect(
            lens,
            android.graphics.RenderEffect.createBlurEffect(
                radius,
                radius,
                android.graphics.Shader.TileMode.CLAMP,
            ),
        )
    } else {
        lens
    }
    return chained.asComposeRenderEffect()
}

/**
 * Tier `Blur` (API 31-32): the transfer function as a `ColorMatrix`, which reproduces
 * `out = contrast * bd + lift` **exactly** — `ColorMatrix` offsets are in 0..255. `lift` is pinned
 * to `liftLo`; there is no aggregate without a shader.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun buildBlurTierEffect(style: GlassStyle, sigmaPx: Float): ComposeRenderEffect {
    val c = style.contrast
    val lift = style.liftLo * 255f
    val m = floatArrayOf(
        c, 0f, 0f, 0f, lift * style.tint.red,
        0f, c, 0f, 0f, lift * style.tint.green,
        0f, 0f, c, 0f, lift * style.tint.blue,
        0f, 0f, 0f, 1f, 0f,
    )
    val colour = android.graphics.RenderEffect.createColorFilterEffect(
        android.graphics.ColorMatrixColorFilter(m),
    )
    val radius = blurRadiusForSigma(sigmaPx)
    val chained = if (radius > 0f) {
        android.graphics.RenderEffect.createChainEffect(
            colour,
            android.graphics.RenderEffect.createBlurEffect(
                radius,
                radius,
                android.graphics.Shader.TileMode.CLAMP,
            ),
        )
    } else {
        colour
    }
    return chained.asComposeRenderEffect()
}
