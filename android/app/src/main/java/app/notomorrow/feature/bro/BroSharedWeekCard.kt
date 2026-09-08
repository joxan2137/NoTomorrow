package app.notomorrow.feature.bro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.service.DayState
import app.notomorrow.service.WeekDay
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.Instant
import java.time.ZoneId

/**
 * The one card on the Bro tab — the port of `BroSharedWeekCard`
 * (`Features/Bro/BroSharedWeekCard.swift`): Mon–Sun letters, a row of cells for me and one
 * for the partner, then the next session line with who confirmed when.
 */
@Composable
fun BroSharedWeekCard(
    week: List<WeekDay>,
    partnerName: String,
    session: BroSessionLine?,
    modifier: Modifier = Modifier,
) {
    NTCard(modifier = modifier, padding = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LettersRow(week)
            CellsRow(week, stringResource(S.bro_you)) { it.myState }
            CellsRow(week, partnerName) { it.partnerState }
            if (session != null) {
                Hairline(Modifier.padding(top = 4.dp))
                SessionRow(session, partnerName)
            }
        }
    }
}

/** `labelWidth` — the leading name column, 52 pt on both rows. */
private val LabelWidth: Dp = 52.dp

@Composable
private fun LettersRow(week: List<WeekDay>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(LabelWidth))
        Row(Modifier.weight(1f)) {
            week.forEach { day ->
                Cell {
                    NtText(
                        text = stringResource(day.shortLabelRes),
                        style = NT.Fonts.caption,
                        color = if (day.isToday) NT.Colors.ink else NT.Colors.ink2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CellsRow(week: List<WeekDay>, label: String, state: (WeekDay) -> DayState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(
            text = label,
            modifier = Modifier.width(LabelWidth),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        Row(Modifier.weight(1f)) {
            week.forEach { day ->
                Cell { BroStatusCell(state = state(day), size = 30.dp) }
            }
        }
    }
}

/** `.frame(maxWidth: .infinity)` on each of the seven columns — equal width, centred. */
@Composable
private fun RowScope.Cell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SessionRow(session: BroSessionLine, partnerName: String) {
    val strings = rememberNtStrings()
    val you = stringResource(S.bro_you)
    val noOneYet = stringResource(S.bro_noOneYet)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NtText(
                text = headline(session, strings),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            TabularText(
                text = confirmations(session, partnerName, you, noOneYet),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
        StatusBadge(session, partnerName)
    }
}

@Composable
private fun StatusBadge(session: BroSessionLine, partnerName: String) {
    val text: String
    val dot: androidx.compose.ui.graphics.Color
    when {
        session.bothIn -> {
            text = stringResource(S.bro_bothIn)
            dot = NT.Colors.good
        }
        session.partnerState.isMissedOrCancelled -> {
            text = stringResource(S.bro_today_out_s, partnerName)
            dot = NT.Colors.bad
        }
        session.myState.isMissedOrCancelled -> {
            text = stringResource(S.bro_today_youOut)
            dot = NT.Colors.bad
        }
        else -> return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        NtText(text = text, style = NT.Fonts.footnoteBold, color = NT.Colors.ink, maxLines = 1)
    }
}

/** "Today · 18:00 · Push A" */
private fun headline(session: BroSessionLine, strings: app.notomorrow.util.Localizer): String {
    val parts = mutableListOf(
        Fmt.relativeDay(session.day, strings),
        Fmt.time(session.minuteOfDay),
    )
    session.routineName?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

/** "You 09:12 · Tomek 12:40", or "No one's in yet". */
private fun confirmations(
    session: BroSessionLine,
    partnerName: String,
    you: String,
    noOneYet: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val parts = mutableListOf<String>()
    session.myConfirmedAt?.let { parts.add("$you ${Fmt.time(Instant.ofEpochMilli(it), zone = zone)}") }
    session.partnerConfirmedAt?.let { parts.add("$partnerName ${Fmt.time(Instant.ofEpochMilli(it), zone = zone)}") }
    return if (parts.isEmpty()) noOneYet else parts.joinToString(" · ")
}
