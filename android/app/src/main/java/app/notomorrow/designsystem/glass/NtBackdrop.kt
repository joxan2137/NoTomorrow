package app.notomorrow.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize

/**
 * The one thing every glass surface samples: a [GraphicsLayer] holding the app's content, recorded
 * once per frame by the node that wears [ntBackdropSource].
 *
 * There is exactly **one** of these per app — created in `RootScreen` (§3.3) and recorded around
 * whichever screen is mounted: the tab `NavHost` in `MainTabScaffold`, or the ActiveWorkout
 * destination, never both. `NtTabBar` is a sibling of that `NavHost` in the same `Box`, drawn after
 * it, so it sits *outside* the capture and can legally sample it.
 */
@Stable
class NtBackdrop internal constructor(
    internal val layer: GraphicsLayer,
    /**
     * The context that made [layer]. A sampler in another window (a `Popup`, a `Dialog`, every
     * `NtSheet`) has a different one and must not draw this layer — `Modifier.liquidGlass` checks
     * identity and falls back to the flat fill.
     */
    internal val context: GraphicsContext,
) {

    /** Where the recorded subtree sits on screen, so samplers can align their own origin to it. */
    internal var coords: LayoutCoordinates? = null

    internal var captureEnabled: Boolean = true

    /**
     * True while [ntBackdropSource] is recording. A glass node that finds this set is being drawn
     * *inside* the capture — glass sampling glass, which is a `SIGSEGV` on the RenderThread, not a
     * visual glitch. Such a node draws its flat fill instead and the frame survives.
     */
    internal var recording: Boolean = false
        private set

    /**
     * Latched the first time [ntBackdropSource] cannot record this subtree at all (a
     * `RenderNode` that refuses the capture throws out of `GraphicsLayer.record`).
     *
     * Once set the source stops trying, every `Modifier.liquidGlass` that resolves this backdrop
     * degrades to its flat fill, and `RootScreen` demotes `LocalGlassTier` to [GlassTier.Tint] for
     * the rest of the session. It is snapshot state precisely so that last step is a recomposition
     * and not a stale read.
     */
    var captureFailed: Boolean by mutableStateOf(false)
        internal set

    internal fun guarded(block: () -> Unit) {
        recording = true
        try {
            block()
        } finally {
            recording = false
        }
    }
}

/** No backdrop by default: glass composed outside the tab shell falls back to its flat fill. */
val LocalNtBackdrop = staticCompositionLocalOf<NtBackdrop?> { null }

/**
 * Creates a backdrop. Call it once for the app, in `RootScreen` — the only other legal use is a
 * component that samples **itself** rather than the page, which today is `NtToggle`'s knob over its
 * own track (§3.6).
 */
@Composable
fun rememberNtBackdrop(): NtBackdrop {
    val layer = rememberGraphicsLayer()
    val context = LocalGraphicsContext.current
    return remember(layer, context) { NtBackdrop(layer, context) }
}

/**
 * Records this subtree into [backdrop] **and** draws it to the screen directly.
 *
 * This used to draw only the recording (`drawLayer(backdrop.layer)`) so the subtree was rasterised
 * once per frame instead of twice (§2.3 rule 3). That made the whole screen depend on a
 * `RenderNode` capture succeeding: on a device/API level where the capture comes back empty the one
 * surviving paint is this block's `drawRect(NT.Colors.ground)`, and the app renders as a bare
 * `#0A0A0B` rectangle with only the out-of-capture chrome (the tab-bar capsule) on it. That is what
 * the 06-20 parity captures hit, and it is not a defect a screen may ever have:
 *
 * > A device that cannot do the capture must lose the blur, never the screen.
 *
 * So the content is now drawn on its own path and the recording is a *side effect* that only the
 * glass samplers consume. A capture that silently comes back empty now costs the blur (the glass
 * samples flat `ground`, which over this app's one backdrop is within 1/255 of the real material
 * anyway) instead of the frame. The extra cost is one display-list build of the subtree per frame;
 * the layer's rasterisation was already happening twice, once here and once per sampler.
 *
 * Two rules, both still load-bearing:
 *  - the layer is filled with `NT.Colors.ground` first. A transparent pixel in the source becomes a
 *    hole in the glass. (The screen gets its ground from `RootScreen`'s root `Box`, which is why
 *    dropping `drawLayer` here changes nothing visible on a healthy device.)
 *  - never nest one of these inside another, and never put `Modifier.liquidGlass` on a node that is
 *    inside the capture.
 *
 * A `record` that *throws* latches [NtBackdrop.captureFailed]: the source stops recording, the
 * samplers fall back to their flat fill and the tier is demoted, all for the rest of the session.
 *
 * No explicit invalidation is wired between the source and its samplers, and that is deliberate:
 * the recorded layer is a `RenderNode`, the effect layer holds a *reference* to it, so re-recording
 * updates every sampler on the RenderThread with no CPU work. The samplers are siblings in the same
 * (layer-less) `Box`, so any invalidation that repaints this node repaints them too.
 */
fun Modifier.ntBackdropSource(backdrop: NtBackdrop, enabled: Boolean = true): Modifier =
    this.onGloballyPositioned { backdrop.coords = it }
        .drawWithContent {
            backdrop.captureEnabled = enabled
            val target = IntSize(size.width.toInt(), size.height.toInt())
            if (enabled && target.width > 0 && target.height > 0 && !backdrop.captureFailed) {
                val recorded = runCatching {
                    backdrop.guarded {
                        // `DrawScope`'s member-extension `GraphicsLayer.record(size) {}`, NOT the
                        // `GraphicsLayer.record(density, layoutDirection, size) {}` member. They
                        // look interchangeable and are not: the plain member records with the
                        // layer's OWN `CanvasDrawScope`, so `drawContent()` inside it still paints
                        // into the *screen* canvas that this `ContentDrawScope` holds and the layer
                        // comes back holding nothing but the `drawRect` below. `LayoutNodeDrawScope`
                        // overrides `DrawScope.record` precisely to swap its canvas for the layer's
                        // first, which is what makes `drawContent()` land in the recording.
                        //
                        // That one-word difference is the whole "glass renders as a flat fill" bug
                        // (`docs/android-status.md` §3): every sampler was reading a layer that
                        // contained a single `ground` rect, so it produced `transfer(ground)` —
                        // a constant — everywhere instead of the blurred screen. It is also why the
                        // pre-fix build painted the app as a bare `#0A0A0B` rectangle when this
                        // block still drew the recording instead of the content.
                        backdrop.layer.record(target) {
                            drawRect(NT.Colors.ground)
                            this@drawWithContent.drawContent()
                        }
                    }
                }.isSuccess
                if (!recorded) backdrop.captureFailed = true
            }
            // The screen never depends on the capture.
            drawContent()
        }
