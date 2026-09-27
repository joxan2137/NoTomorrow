package app.notomorrow.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtFlowLayout
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Last session" header (day · name · duration · n PRs) with one chip per PR set — the port
 * of `LastSessionRow` (`Features/Dashboard/LastSessionRow.swift`). Tapping opens that workout's
 * detail over Today (the Train tab when there is none yet).
 */
@Composable
fun LastSessionRow(
    session: LastSession?,
    units: WeightUnit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().pressScale(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            NtText(
                text = stringResource(S.dashboard_lastSession),
                modifier = Modifier.alignByBaseline(),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            if (session != null) Summary(session, Modifier.alignByBaseline())
        }
        if (session != null) {
            if (session.prSets.isNotEmpty()) PrChips(session.prSets, units)
        } else {
            NtText(
                text = stringResource(S.dashboard_noSessionsYet),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        }
    }
}

/** `"Fri · Legs · 52 min"`, plus `" · 2 PRs"` in ember when the session set records. */
@Composable
private fun Summary(session: LastSession, modifier: Modifier = Modifier) {
    val strings = rememberNtStrings()
    val name = app.notomorrow.feature.workout.workoutDisplayName(session.name)
    val text = buildAnnotatedString {
        append(dayLabel(session.at))
        append(" · ")
        append(name)
        append(" · ")
        append(Fmt.duration(session.durationSeconds, strings))
        if (session.prCount > 0) {
            append(" · ")
            withStyle(SpanStyle(color = NT.Colors.ember)) {
                append(stringResource(S.dashboard_prs, session.prCount))
            }
        }
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = NT.Fonts.subheadline.tabular().copy(
            color = NT.Colors.ink2,
            textAlign = TextAlign.End,
        ),
        overflow = TextOverflow.Ellipsis,
    )
}

/** Weekday for anything in the last six days, otherwise day + month. */
private fun dayLabel(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = at.atZone(zone).toLocalDate()
    val weekAgo = LocalDate.now(zone).minusDays(6)
    return if (!date.isBefore(weekAgo)) Fmt.weekdayShort(date) else Fmt.dayMonth(date)
}

/** Wrapping row of 32 dp trophy chips. */
@Composable
private fun PrChips(sets: List<PrSet>, units: WeightUnit) {
    NtFlowLayout(Modifier.fillMaxWidth(), spacing = 8.dp) {
        for (set in sets) {
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .background(NT.Colors.surface, NtShapes.capsule)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtIcon(NtIcons.TrophyFill, size = sfIconSize(12f), tint = NT.Colors.ember)
                TabularText(
                    text = set.exerciseName + " " + Fmt.set(set.weightKg, set.reps, units),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink,
                )
            }
        }
    }
}
