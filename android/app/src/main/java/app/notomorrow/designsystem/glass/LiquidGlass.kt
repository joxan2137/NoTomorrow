package app.notomorrow.designsystem

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import kotlin.math.roundToInt

/**
 * Fills [shape] with the Liquid Glass transform of whatever the app's single [NtBackdrop] recorded
 * behind this node, then draws this node's own content on top.
 *
 * The backdrop comes from [LocalNtBackdrop] unless [backdrop] names another one (the toggle knob
 * samples its own track, not the page). With no backdrop at all the node degrades to
 * [GlassStyle.flatFill] — which over `NT.Colors.ground` is within 1/255 of the real material, so a
 * glass component composed outside the tab shell still looks right.
 *
 * **Only NT chrome may call this.** Feature code never touches blur, shaders or render effects
 * (`docs/android-architecture.md`, "Liquid Glass is part of identical"). Two hard rules:
 *  - never apply it to a node that is *inside* the recorded backdrop, and
 *  - never nest two of them: glass cannot sample glass.
 *
 * Breaking either is a RenderThread `SIGSEGV` elsewhere; here it is caught by
 * [NtBackdrop.recording] and degrades to the flat fill for that frame.
 */
fun Modifier.liquidGlass(
    shape: Shape,
    style: GlassStyle = GlassStyle.Regular,
    backdrop: NtBackdrop? = null,
): Modifier = this then LiquidGlassElement(shape, style, backdrop)

/**
 * Will a [liquidGlass] node against [backdrop] actually run the AGSL lens on this device, right
 * now? Answers the one question a caller cannot infer: [GlassStyle.flatFill] is a *colour*, so a
 * component whose degraded form is a different **drawing** (the tab pill: a lens above the icons
 * when this is true, an additive capsule beneath them when it is not) has to branch itself.
 *
 * It is deliberately not a promise — a node can still fall back per-frame if its backdrop has not
 * been positioned yet — only the device/tier/capture question, which is stable for a session.
 */
@Composable
fun ntGlassLensAvailable(backdrop: NtBackdrop): Boolean =
    LocalGlassTier.current == GlassTier.Full &&
        glassShaderCompiles &&
        !backdrop.captureFailed &&
        GlassDebug.pillLens

private data class LiquidGlassElement(
    val shape: Shape,
    val style: GlassStyle,
    val backdrop: NtBackdrop?,
) : ModifierNodeElement<LiquidGlassNode>() {

    override fun create(): LiquidGlassNode = LiquidGlassNode(shape, style, backdrop)

    override fun update(node: LiquidGlassNode) {
        node.update(shape, style, backdrop)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "liquidGlass"
        properties["shape"] = shape
        properties["style"] = style
    }
}

private class LiquidGlassNode(
    private var shape: Shape,
    private var style: GlassStyle,
    private var explicitBackdrop: NtBackdrop?,
) : Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode {

    private var coords: LayoutCoordinates? = null

    private var graphicsContext: GraphicsContext? = null
    private var effectLayer: GraphicsLayer? = null

    private var shader: RuntimeShader? = null
    private var shaderTried = false
    private var shaderFailed = false

    // Uniforms and the RenderEffect are constant for a given size + style, so both are built once
    // and reused. The backdrop's *content* still updates every frame: the effect layer holds a
    // reference to the backdrop RenderNode, not a copy of its pixels.
    private var cachedEffect: RenderEffect? = null
    private var cachedBlurEffect: RenderEffect? = null
    private var cachedKey: String? = null
    private var cachedOutline: Outline? = null
    private var cachedOutlineKey: String? = null

    fun update(shape: Shape, style: GlassStyle, backdrop: NtBackdrop?) {
        val changed = shape != this.shape || style != this.style || backdrop !== this.explicitBackdrop
        this.shape = shape
        this.style = style
        this.explicitBackdrop = backdrop
        if (changed) {
            cachedEffect = null
            cachedBlurEffect = null
            cachedKey = null
            cachedOutline = null
            cachedOutlineKey = null
            invalidateDraw()
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        // Only the origin matters, and it is baked into the effect layer's recorded translate.
        val previous = coords
        coords = coordinates
        if (previous == null) invalidateDraw()
    }

    override fun onDetach() {
        effectLayer?.let { graphicsContext?.releaseGraphicsLayer(it) }
        effectLayer = null
        graphicsContext = null
        shader = null
        shaderTried = false
        shaderFailed = false
        cachedEffect = null
        cachedBlurEffect = null
        cachedKey = null
    }

    override fun ContentDrawScope.draw() {
        drawGlass()
        drawContent()
    }

    // ── drawing ──────────────────────────────────────────────────────────────────────────────

    private fun DrawScope.drawGlass() {
        if (size.width <= 0f || size.height <= 0f) return
        val outline = outlineFor(size, layoutDirection)

        val tier = effectiveTier()
        val backdrop = explicitBackdrop ?: currentValueOf(LocalNtBackdrop)
        val source = backdrop?.coords
        val self = coords

        // A layer belongs to the GraphicsContext that made it. A glass node inside a Popup or a
        // Dialog (every NtSheet) lives in another window with another context, and drawing the
        // app's layer there is undefined at best — so it falls back to the flat fill, which for
        // every preset is the value the transfer function yields over `ground` anyway.
        val sameWindow = backdrop != null && backdrop.context === currentValueOf(LocalGraphicsContext)

        val sampleable = backdrop != null &&
            sameWindow &&
            !backdrop.recording &&
            backdrop.captureEnabled &&
            // A device whose RenderNode capture refused: there is nothing in the layer to sample.
            !backdrop.captureFailed &&
            source != null &&
            source.isAttached &&
            self != null &&
            self.isAttached &&
            tier != GlassTier.Tint

        if (!sampleable || backdrop == null || source == null || self == null) {
            drawFlat(outline)
            return
        }

        val sigmaPx = style.blurSigma.toPx()
        // A `refractionPower` style bends across its whole band, and a dispersing one spreads its
        // outer taps further still, so the outer ring of the shape samples *outside* itself and
        // the effect layer has to be that much wider. The measured presets pass 0 here and keep
        // their old layer size exactly.
        val reachPx = glassReachPx(style, maxOf(size.width, size.height) / 2f)
        val pad = glassPadding(sigmaPx, reachPx)
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        val radii = shape.glassRadii(size, layoutDirection, this)

        val layer = obtainLayer()
        val origin = source.localPositionOf(self, Offset.Zero)
        layer.record(this, layoutDirection, IntSize(w + 2 * pad, h + 2 * pad)) {
            translate(pad - origin.x, pad - origin.y) { drawLayer(backdrop.layer) }
        }
        layer.topLeft = IntOffset(-pad, -pad)

        val useShader = tier == GlassTier.Full &&
            !shaderFailed &&
            radii != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            glassShaderCompiles

        if (useShader) {
            val key = "$w×$h×$pad×${radii!!.joinToString(",")}×${style.hashCode()}"
            if (cachedKey != key || cachedEffect == null) {
                // Skia strips uniforms it can prove unused, and `setFloatUniform` then throws. A
                // shader problem must never reach the draw phase as an exception: it degrades to
                // the frosted tier, which carries the same colours, for the life of this node.
                val built = runCatching {
                    val s = shaderOrNull() ?: error("no shader")
                    val p = pad.toFloat()
                    s.bindGlassUniforms(w.toFloat(), h.toFloat(), p, p, radii, style, this)
                    buildGlassEffect(s, sigmaPx)
                }.getOrNull()
                if (built == null) {
                    shader = null
                    shaderTried = true
                    shaderFailed = true
                    drawBlurTier(layer, outline, sigmaPx)
                    return
                }
                cachedEffect = built
                cachedKey = key
            }
            layer.renderEffect = cachedEffect
            // The shader emits its own antialiased shape alpha, so nothing here clips.
            drawLayer(layer)
        } else {
            drawBlurTier(layer, outline, sigmaPx)
        }
    }

    /** API 31-32, and the API 33+ path when the shape is a generic path or the shader is gone. */
    private fun DrawScope.drawBlurTier(layer: GraphicsLayer, outline: Outline, sigmaPx: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            drawFlat(outline)
            return
        }
        val effect = cachedBlurEffect ?: buildBlurTierEffect(style, sigmaPx).also {
            cachedBlurEffect = it
        }
        layer.renderEffect = effect
        clipPath(outline.asClipPath()) { drawLayer(layer) }
        drawRim(outline)
    }

    /** API 26-30, plus every "no backdrop reachable" case. */
    private fun DrawScope.drawFlat(outline: Outline) {
        drawOutline(outline, color = style.flatFill)
        drawRim(outline)
    }

    /**
     * The specular rim for the tiers with no shader — the measured **ramp**, not a flat stroke.
     *
     * §3.5: *"The rim becomes a 1.22 dp `drawRect` inner stroke with a
     * `Brush.verticalGradient(White@0.165 -> Transparent)` clipped to the shape."* A gradient brush
     * cannot follow the inward normal around a capsule's caps, so the ramp is three nested strokes
     * instead (`ntGlassRimStrokes`), which does. The single `rimAlpha * 0.5` stroke this replaced
     * painted a constant 0.0825 where the measurement is 0.165 -> 0, and §4 acceptance row 4
     * (59 / 51 / 42 / body inward, +-4) could not pass on API 26-32.
     */
    private fun DrawScope.drawRim(outline: Outline) {
        val strokes = ntGlassRimStrokes(style, this)
        if (strokes.isEmpty()) return
        val path: Path = outline.asClipPath()
        clipPath(path) {
            strokes.forEach { (width, alpha) ->
                drawOutline(
                    outline = outline,
                    color = Color.White.copy(alpha = alpha),
                    style = Stroke(width = width),
                )
            }
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────────────────────

    private fun effectiveTier(): GlassTier {
        val tier = currentValueOf(LocalGlassTier)
        return if (tier == GlassTier.Full && !glassShaderCompiles) GlassTier.Blur else tier
    }

    private fun shaderOrNull(): RuntimeShader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (!shaderTried) {
            shaderTried = true
            shader = newGlassShader()
        }
        return shader
    }

    private fun obtainLayer(): GraphicsLayer {
        effectLayer?.let { return it }
        val ctx = currentValueOf(LocalGraphicsContext)
        graphicsContext = ctx
        return ctx.createGraphicsLayer().also { effectLayer = it }
    }

    private fun DrawScope.outlineFor(size: Size, direction: LayoutDirection): Outline {
        val key = "${size.width}×${size.height}×$direction"
        val cached = cachedOutline
        if (cached != null && cachedOutlineKey == key) return cached
        return shape.createOutline(size, direction, this).also {
            cachedOutline = it
            cachedOutlineKey = key
        }
    }
}
