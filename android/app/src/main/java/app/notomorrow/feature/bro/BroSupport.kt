package app.notomorrow.feature.bro

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSpinner
import app.notomorrow.designsystem.tabular

/**
 * SwiftUI's `ProgressView()` on the Pair button and pull-to-refresh — the design system's
 * [NtSpinner] at the 20 dp geometry this feature uses.
 */
@Composable
internal fun BroSpinner(
    modifier: Modifier = Modifier,
    color: Color = NT.Colors.ink,
    alpha: Float = 1f,
) = NtSpinner(modifier = modifier, color = color, size = 20.dp, alpha = alpha)

/**
 * SwiftUI's `.lineLimit(1).minimumScaleFactor(x)`: one line that shrinks to `x` of its font
 * size before it would truncate. Compose's own step-based auto-sizing does the same walk.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BroAutoSizeText(
    text: String,
    minScale: Float,
    modifier: Modifier = Modifier,
    style: TextStyle = NT.Fonts.body,
    color: Color = NT.Colors.ink,
    tabular: Boolean = false,
) {
    val resolved = if (tabular) style.tabular() else style
    BasicText(
        text = text,
        modifier = modifier,
        style = resolved.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = resolved.fontSize * minScale,
            maxFontSize = resolved.fontSize,
        ),
    )
}
