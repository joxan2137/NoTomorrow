package app.notomorrow.feature.auth

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/**
 * `.presentationCornerRadius(24)` on `SignInView` — it overrides the measured system 38 for this
 * one sheet. The bottom of a `.large` sheet sits on the screen edge, so only the top corners are
 * rounded, and they are **continuous** (the squircle measured in `docs/android-glass.md` §1.5),
 * not the circular corner `RoundedCornerShape` draws.
 *
 * `NtShapes.rounded(24.dp)` would be the right curve but rounds all four corners, and its
 * `ContinuousCornerShape` is private to the design system — hence the local per-vertex polygon.
 */
internal val SignInSheetShape: Shape = TopContinuousCornerShape(24.dp)

/** A rectangle whose two top vertices carry a continuous (smoothing 0.6) rounding. */
private data class TopContinuousCornerShape(
    val radius: Dp,
    val smoothing: Float = 0.6f,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.minDimension <= 0f) return Outline.Rectangle(size.toRect())
        val r = with(density) { radius.toPx() }.coerceIn(0f, size.minDimension / 2f)
        val rounded = CornerRounding(radius = r, smoothing = smoothing)
        val polygon = RoundedPolygon(
            vertices = floatArrayOf(
                0f, 0f,                     // top-leading
                size.width, 0f,             // top-trailing
                size.width, size.height,    // bottom-trailing
                0f, size.height,            // bottom-leading
            ),
            perVertexRounding = listOf(
                rounded,
                rounded,
                CornerRounding.Unrounded,
                CornerRounding.Unrounded,
            ),
            centerX = size.width / 2f,
            centerY = size.height / 2f,
        )
        return Outline.Generic(polygon.toPath().asComposePath())
    }
}
