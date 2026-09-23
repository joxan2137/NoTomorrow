package app.notomorrow.designsystem

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor

/** One frame of a [liquidGlassLens]: where the lens sits inside the node, in px, and its optics. */
@Immutable
class GlassLensFrame(val rect: Rect, val style: GlassStyle)

/**
 * A Liquid Glass lens that **moves and morphs every frame** without paying for it — the tab bar's
 * selection pill while it travels.
 *
 * [Modifier.liquidGlass] is built for chrome that sits still. Driving it from a spring meant, per
 * frame: a new `GlassStyle` (a recomposition and a `ModifierNodeElement.update`), a new node size
 * (a relayout, and an effect layer re-allocated at that size — a fresh GPU surface every 8 ms), a
 * `RuntimeShader` compile the first time each node drew, and every one of those invalidations
 * bubbling up to the nearest graphics layer, which in the tab shell was the whole screen. That is
 * the "laggy when switching tabs" the S22 showed. This node renders the same optics with none of it:
 *
 *  - [frame] is evaluated in the **draw phase**. The snapshot reads inside it (the spring's value,
 *    the motion envelope) invalidate this node's draw and nothing else — no composition, no layout.
 *  - The effect layer is allocated **once**, at [maxSize] plus the padding [maxStyle] needs, and
 *    only its origin moves. The shape's live size and position reach the shader through the `size`
 *    and `pad` uniforms it already had, so the layer never resizes.
 *  - The shader is the process-wide compiled instance (`newGlassShader`). A frame costs one uniform
 *    bind, one immutable `RenderEffect`, and a re-record holding a single `drawLayer` command.
 *
 * Put `Modifier.graphicsLayer()` **before** it so the per-frame invalidation stops at the lens's own
 * layer (`NtTabBar` does). Everything `liquidGlass` forbids is forbidden here too: only NT chrome,
 * never inside [backdrop]'s own recording, never glass over glass.
 *
 * There is no degraded *material*: a lens exists to bend what is under it. A tier that cannot
 * sample, and any frame that cannot (backdrop not positioned yet, shader refused), draws the flat
 * additive capsule the rest state measures — [fallbackLift] with `BlendMode.Plus`, which is what
 * the shader itself produces at `motion = 0`.
 */
fun Modifier.liquidGlassLens(
    backdrop: NtBackdrop,
    shape: Shape,
    maxSize: DpSize,
    maxStyle: GlassStyle,
    fallbackLift: Float = GlassStyle.PILL_REST_LIFT,
    frame: Density.() -> GlassLensFrame,
): Modifier = this then LiquidGlassLensElement(backdrop, shape, maxSize, maxStyle, fallbackLift, frame)

private class LiquidGlassLensElement(
    val backdrop: NtBackdrop,
    val shape: Shape,
    val maxSize: DpSize,
    val maxStyle: GlassStyle,
    val fallbackLift: Float,
    val frame: Density.() -> GlassLensFrame,
) : ModifierNodeElement<LiquidGlassLensNode>() {

    override fun create(): LiquidGlassLensNode =
        LiquidGlassLensNode(backdrop, shape, maxSize, maxStyle, fallbackLift, frame)

    override fun update(node: LiquidGlassLensNode) {
        node.update(backdrop, shape, maxSize, maxStyle, fallbackLift, frame)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "liquidGlassLens"
        properties["shape"] = shape
        properties["maxSize"] = maxSize
    }

    override fun equals(other: Any?): Boolean =
        other is LiquidGlassLensElement &&
            other.backdrop === backdrop &&
            other.shape == shape &&
            other.maxSize == maxSize &&
            other.maxStyle == maxStyle &&
            other.fallbackLift == fallbackLift &&
            other.frame === frame

    override fun hashCode(): Int {
        var h = System.identityHashCode(backdrop)
        h = 31 * h + shape.hashCode()
        h = 31 * h + maxSize.hashCode()
        h = 31 * h + maxStyle.hashCode()
        h = 31 * h + fallbackLift.hashCode()
        h = 31 * h + System.identityHashCode(frame)
        return h
    }
}

private class LiquidGlassLensNode(
    private var backdrop: NtBackdrop,
    private var shape: Shape,
    private var maxSize: DpSize,
    private var maxStyle: GlassStyle,
    private var fallbackLift: Float,
    private var frame: Density.() -> GlassLensFrame,
) : Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode {

    private var coords: LayoutCoordinates? = null

    private var graphicsContext: GraphicsContext? = null
    private var effectLayer: GraphicsLayer? = null

    private var shader: RuntimeShader? = null
    private var shaderFailed = false

    fun update(
        backdrop: NtBackdrop,
        shape: Shape,
        maxSize: DpSize,
        maxStyle: GlassStyle,
        fallbackLift: Float,
        frame: Density.() -> GlassLensFrame,
    ) {
        this.backdrop = backdrop
        this.shape = shape
        this.maxSize = maxSize
        this.maxStyle = maxStyle
        this.fallbackLift = fallbackLift
        this.frame = frame
        invalidateDraw()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val first = coords == null
        coords = coordinates
        if (first) invalidateDraw()
    }

    override fun onDetach() {
        effectLayer?.let { graphicsContext?.releaseGraphicsLayer(it) }
        effectLayer = null
        graphicsContext = null
        shader = null
        shaderFailed = false
    }

    override fun ContentDrawScope.draw() {
        drawLens()
        drawContent()
    }

    private fun DrawScope.drawLens() {
        // Draw-phase read: whatever snapshot state `frame` touches now re-runs only this draw.
        val f = frame()
        val rect = f.rect
        if (rect.width <= 0f || rect.height <= 0f) return
        val radii = shape.glassRadii(rect.size, layoutDirection, this)

        val source = backdrop.coords
        val self = coords
        val sampleable = currentValueOf(LocalGlassTier) == GlassTier.Full &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            glassShaderCompiles &&
            !shaderFailed &&
            radii != null &&
            // Same window, same GraphicsContext — a layer must not be drawn by another context.
            backdrop.context === currentValueOf(LocalGraphicsContext) &&
            !backdrop.recording &&
            backdrop.captureEnabled &&
            !backdrop.captureFailed &&
            source != null &&
            source.isAttached &&
            self != null &&
            self.isAttached

        if (!sampleable || source == null || self == null || radii == null) {
            drawFallback(rect)
            return
        }

        // One allocation for the life of the node: the widest the shape will ever be, plus the
        // blur kernel and the furthest the lens band and its outermost dispersion tap can reach
        // outside the shape at full motion.
        val reachPx = glassReachPx(maxStyle, maxOf(maxSize.width.toPx(), maxSize.height.toPx()) / 2f)
        val pad = glassPadding(maxStyle.blurSigma.toPx(), reachPx)
        val layerW = ceil(maxSize.width.toPx()).toInt() + 2 * pad
        val layerH = ceil(maxSize.height.toPx()).toInt() + 2 * pad

        // The layer moves on integer pixels; the sub-pixel remainder rides into the `pad` uniform,
        // so the shape still lands exactly where the spring put it.
        val ox = floor(rect.left - pad)
        val oy = floor(rect.top - pad)
        val fx = rect.left - pad - ox
        val fy = rect.top - pad - oy

        val layer = obtainLayer()
        // This node's origin, in the backdrop's coordinates; layer pixel (0, 0) is node (ox, oy).
        val origin = source.localPositionOf(self, Offset.Zero)
        layer.record(this, layoutDirection, IntSize(layerW, layerH)) {
            translate(-origin.x - ox, -origin.y - oy) { drawLayer(backdrop.layer) }
        }
        layer.topLeft = IntOffset(ox.toInt(), oy.toInt())

        val built = runCatching {
            val s = shader ?: newGlassShader()?.also { shader = it } ?: error("no shader")
            s.bindGlassUniforms(rect.width, rect.height, pad + fx, pad + fy, radii, f.style, this)
            buildGlassEffect(s, f.style.blurSigma.toPx())
        }.getOrNull()
        if (built == null) {
            // A shader problem must never reach the draw phase as an exception; the pill keeps
            // its measured rest look for the life of this node.
            shaderFailed = true
            drawFallback(rect)
            return
        }
        layer.renderEffect = built
        // The shader emits its own antialiased shape alpha, so nothing here clips.
        drawLayer(layer)
    }

    /** The measured rest pill: a uniform additive lift over whatever is beneath, no rim. */
    private fun DrawScope.drawFallback(rect: Rect) {
        if (fallbackLift <= 0f) return
        val outline = shape.createOutline(rect.size, layoutDirection, this)
        translate(rect.left, rect.top) {
            drawOutline(
                outline = outline,
                color = Color(fallbackLift, fallbackLift, fallbackLift, 1f),
                blendMode = BlendMode.Plus,
            )
        }
    }

    private fun obtainLayer(): GraphicsLayer {
        effectLayer?.let { return it }
        val ctx = currentValueOf(LocalGraphicsContext)
        graphicsContext = ctx
        return ctx.createGraphicsLayer().also { effectLayer = it }
    }
}
