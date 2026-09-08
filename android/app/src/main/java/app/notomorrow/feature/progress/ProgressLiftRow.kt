package app.notomorrow.feature.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SparklineChart
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt

/**
 * One lift in the home list — the port of `ProgressLiftRow`
 * (`Features/Progress/ProgressLiftRow.swift`): name, context · last PR, a 72×24
 * sparkline, the current e1RM and its 3-month delta.
 *
 * The sparkline is ember while the lift PR'd inside the last 30 days, `ink2` otherwise;
 * the delta only turns ember when it is both positive **and** recent.
 */
@Composable
fun ProgressLiftRow(
    lift: LiftRowState,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
    showsHairline: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (lift.isHot) NT.Colors.ember else NT.Colors.ink2
    val deltaColor = if (lift.delta > 0 && lift.isHot) NT.Colors.ember else NT.Colors.ink2

    // iOS wraps the row in `NavigationLink { … }.buttonStyle(.plain)` — tappable, and no
    // press feedback on the 64 dp row.
    Column(modifier.fillMaxWidth().ntPlainClickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                NtText(
                    text = lift.name,
                    style = NT.Fonts.headline,
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
                val context = lift.context?.takeIf { it.isNotEmpty() }
                val subtitle = lift.lastPR.asText().let { phrase ->
                    if (context != null) "$context · $phrase" else phrase
                }
                NtText(
                    text = subtitle,
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }

            SparklineChart(
                values = lift.sparkline,
                color = color,
                modifier = Modifier.size(width = 72.dp, height = 24.dp),
            )

            Column(
                modifier = Modifier.widthIn(min = 64.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.End,
            ) {
                TabularText(
                    text = Fmt.weight(lift.current, unit),
                    style = NT.Fonts.headline,
                    color = NT.Colors.ink,
                )
                TabularText(
                    text = Fmt.signedWeight(lift.delta, unit),
                    style = NT.Fonts.footnote,
                    color = deltaColor,
                )
            }
        }
        if (showsHairline) Hairline()
    }
}
