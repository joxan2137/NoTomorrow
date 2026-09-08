package app.notomorrow.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 1:1 port of `BroFlowLayout` (and the byte-identical private `FlowLayout` in
 * `LastSessionRow.swift`): a left-aligned wrapping row.
 *
 * The measure/place model is the same as SwiftUI's `Layout` — children are
 * measured unconstrained (`proposal: .unspecified`), a child starts a new row
 * when it does not fit **and** the row is not empty (`x > 0`), the reported
 * width is the widest row (trailing spacing removed) and the height is the last
 * row's baseline plus its height.
 *
 * iOS passes one `spacing`; [hSpacing] / [vSpacing] default to it.
 */
@Composable
fun NtFlowLayout(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    hSpacing: Dp = spacing,
    vSpacing: Dp = spacing,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val h = hSpacing.roundToPx()
        val v = vSpacing.roundToPx()
        val maxWidth = constraints.maxWidth
        val childConstraints = Constraints(maxWidth = maxWidth)
        val placeables = measurables.map { it.measure(childConstraints) }

        var x = 0
        var y = 0
        var rowHeight = 0
        var maxX = 0
        val positions = ArrayList<Pair<Int, Int>>(placeables.size)
        for (placeable in placeables) {
            if (x > 0 && x + placeable.width > maxWidth) {
                x = 0
                y += rowHeight + v
                rowHeight = 0
            }
            positions.add(x to y)
            x += placeable.width + h
            rowHeight = max(rowHeight, placeable.height)
            maxX = max(maxX, x - h)
        }
        // SwiftUI reports the content width (the widest row), not the proposal.
        val width = maxX.coerceIn(
            constraints.minWidth,
            if (constraints.hasBoundedWidth) maxWidth else max(maxX, constraints.minWidth),
        )
        val height = (y + rowHeight).coerceIn(
            constraints.minHeight,
            if (constraints.hasBoundedHeight) constraints.maxHeight else y + rowHeight,
        )

        layout(width.coerceAtLeast(0), height.coerceAtLeast(0)) {
            positions.forEachIndexed { index, (px, py) ->
                placeables[index].place(px, py)
            }
        }
    }
}

