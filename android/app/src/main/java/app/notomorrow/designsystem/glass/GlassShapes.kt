package app.notomorrow.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** A true capsule: `RoundedCornerShape(50)` resolves to `min(w, h) / 2` on every corner. */
val CapsuleShape: RoundedCornerShape = RoundedCornerShape(50)

/**
 * The four corner radii in px, in the order the shader wants them: **TL, TR, BR, BL**, already
 * resolved for the layout direction and clamped so a capsule cannot exceed half the short side.
 *
 * `null` means the shape is a [Outline.Generic] path, which the SDF cannot express — the caller
 * falls back to a clipped tier-`Blur` draw.
 */
internal fun Shape.glassRadii(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): FloatArray? = when (val outline = createOutline(size, layoutDirection, density)) {
    is Outline.Rectangle -> floatArrayOf(0f, 0f, 0f, 0f)
    is Outline.Rounded -> {
        val r = outline.roundRect
        val cap = minOf(size.width, size.height) * 0.5f
        floatArrayOf(
            r.topLeftCornerRadius.x.coerceIn(0f, cap),
            r.topRightCornerRadius.x.coerceIn(0f, cap),
            r.bottomRightCornerRadius.x.coerceIn(0f, cap),
            r.bottomLeftCornerRadius.x.coerceIn(0f, cap),
        )
    }

    is Outline.Generic -> null
}

/** The shape as a [Path], for the tiers that have to clip rather than emit their own alpha. */
internal fun Outline.asClipPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}
