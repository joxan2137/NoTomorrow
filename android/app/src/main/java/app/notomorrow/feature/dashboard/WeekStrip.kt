package app.notomorrow.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.service.DayState
import app.notomorrow.service.WeekDay
import app.notomorrow.util.Fmt

/**
 * Mon…Sun strip: letter label, 36 dp state circle, 5 dp gym-day dot — the port of
 * `WeekStripView` (`Features/Dashboard/WeekStripView.swift`).
 *
 * Attended = ember ring + check, missed/cancelled = rose ring + ✕, today = filled white
 * circle with the day number, everything else the bare day number.
 */
@Composable
fun WeekStrip(days: List<WeekDay>, modifier: Modifier = Modifier) {
    Row(
        // `.accessibilityElement(children: .contain)` — the seven cells are one traversal
        // unit, read in order and not interleaved with the header or the card.
        modifier.fillMaxWidth().semantics { isTraversalGroup = true },
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (day in days) {
            WeekDayCell(day, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeekDayCell(day: WeekDay, modifier: Modifier = Modifier) {
    val label = Fmt.dayMonth(day.date)
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NtText(
            text = stringResource(day.labelRes),
            style = NT.Fonts.caption,
            color = if (day.isToday) NT.Colors.ink else NT.Colors.ink2,
            maxLines = 1,
        )
        StateCircle(day)
        Box(
            Modifier
                .size(5.dp)
                .background(if (day.isGymDay) NT.Colors.ember else Color.Transparent, CircleShape)
        )
    }
}

@Composable
private fun StateCircle(day: WeekDay) {
    val number = Fmt.dayOfMonth(day.date)
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        when {
            day.isToday -> {
                Box(
                    Modifier.size(36.dp).background(NT.Colors.ink, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    TabularText(
                        text = number,
                        style = NT.Fonts.subheadlineBold,
                        color = NT.Colors.onPrimary,
                    )
                }
            }

            day.myState is DayState.Attended -> Ring(NT.Colors.ember, NtIcons.Checkmark)

            day.myState.isMissedOrCancelled -> Ring(NT.Colors.bad, NtIcons.Xmark)

            // rest / planned / confirmed
            else -> TabularText(
                text = number,
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        }
    }
}

/** `Circle().strokeBorder(color, lineWidth: 1.5)` plus a 14 pt bold glyph. */
@Composable
private fun Ring(color: Color, icon: NtIcons) {
    Box(
        Modifier.size(36.dp).border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(icon, size = sfIconSize(14f), tint = color)
    }
}
