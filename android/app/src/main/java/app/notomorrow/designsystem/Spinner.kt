package app.notomorrow.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SwiftUI's indeterminate `ProgressView()`, drawn rather than imported.
 *
 * Material's `CircularProgressIndicator` is forbidden here (it brings the wrong sweep, the wrong
 * stroke and a Material colour scheme), so this is the app's single hand-drawn spinner: a 270°
 * round-capped arc rotating once every 900 ms, linear — an indeterminate spinner must not ease,
 * or it reads as progress.
 *
 * The arc is inset by half the stroke so the ring is not clipped by its own bounds.
 */
@Composable
fun NtSpinner(
    modifier: Modifier = Modifier,
    color: Color = NT.Colors.ink,
    size: Dp = 18.dp,
    strokeWidth: Dp = 2.dp,
    alpha: Float = 1f,
) {
    val transition = rememberInfiniteTransition(label = "ntSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ntSpinnerAngle",
    )
    Canvas(modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
}
