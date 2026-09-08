package app.notomorrow.feature.bro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.service.DayState
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * "Log" header with the since/sessions/missed summary and one 56 dp row per past gym day —
 * the port of `BroLogSection` (`Features/Bro/BroLogSection.swift`).
 */
@Composable
fun BroLogSection(
    rows: List<BroLogRow>,
    partnerName: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtText(
                text = stringResource(S.bro_log),
                modifier = Modifier.alignByBaseline(),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            val since = rows.lastOrNull()?.day
            if (since != null) {
                // `.lineLimit(1).minimumScaleFactor(0.8)` — the line shrinks rather than truncating.
                BroAutoSizeText(
                    text = stringResource(
                        S.bro_sinceMissed_s_n_n,
                        Fmt.dayMonth(since),
                        rows.size,
                        rows.count { it.anyoneMissed },
                    ),
                    minScale = 0.8f,
                    modifier = Modifier.alignByBaseline().padding(start = 12.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                    tabular = true,
                )
            }
        }

        if (rows.isEmpty()) {
            NtText(
                text = stringResource(S.bro_log_empty),
                modifier = Modifier.padding(vertical = 14.dp),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        } else {
            rows.forEachIndexed { index, row ->
                BroLogRowView(row = row, partnerName = partnerName)
                if (index < rows.size - 1) Hairline()
            }
        }
    }
}

/** One 56 dp log row: date column, headline + note, then the two 22 dp status cells. */
@Composable
private fun BroLogRowView(row: BroLogRow, partnerName: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabularText(
            text = BroDerived.weekdayDay(row.day),
            modifier = Modifier.width(52.dp),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NtText(
                text = headline(row, partnerName),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            if (row.note != null) {
                NtText(
                    text = row.note,
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BroStatusCell(state = row.myState, size = 22.dp)
            BroStatusCell(state = row.partnerState, size = 22.dp)
        }
    }
}

/** "Pull A · both showed up" */
@Composable
private fun headline(row: BroLogRow, partnerName: String): String {
    val parts = mutableListOf<String>()
    row.routineName?.let { parts.add(it) }
    outcome(row, partnerName)?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

@Composable
private fun outcome(row: BroLogRow, partnerName: String): String? {
    val meIn = row.myState == DayState.Attended
    val broIn = row.partnerState == DayState.Attended
    val meOut = row.myState.isMissedOrCancelled
    val broOut = row.partnerState.isMissedOrCancelled
    return when {
        meIn && broIn -> stringResource(S.bro_bothShowedUp)
        meOut && broOut -> stringResource(S.bro_bothMissed)
        !meOut && broOut -> stringResource(S.bro_missed, partnerName)
        meOut && !broOut -> stringResource(S.bro_youMissed)
        else -> null
    }
}
