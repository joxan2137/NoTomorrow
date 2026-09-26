package app.notomorrow.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.appLocale
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.feature.workout.WorkoutDetailPresenter
import app.notomorrow.feature.workout.workoutCount
import app.notomorrow.feature.workout.workoutSetCount
import app.notomorrow.feature.workout.workoutWeekStreak
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

/**
 * Progress > Lifts: a month of training at a glance — 1:1 port of `TrainingCalendarCard.swift`.
 * Days with a finished workout fill in the heat colours by how many sets were done; tapping one
 * opens that workout ([WorkoutDetailPresenter]). Chevrons page through months (never past this
 * one); the footer counts the month's workouts and the current weekly streak.
 *
 * [days] is [TrainingCalendar.days] of the whole history; [today] the day the screen measures from.
 */
@Composable
fun TrainingCalendarCard(
    days: Map<LocalDate, TrainingCalendar.Day>,
    today: LocalDate,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    var month by rememberSaveable { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf<String?>(null) }
    val weeks = TrainingCalendar.weeks(month)
    val inMonth = days.filterKeys { YearMonth.from(it) == month }.values.sumOf { it.workoutIds.size }
    val streak = TrainingCalendar.weekStreak(days.keys, today)
    val isCurrentMonth = month >= YearMonth.from(today)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = stringResource(S.calendar_title))
        NTCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonthHeader(
                    month = month,
                    canGoForward = !isCurrentMonth,
                    onPage = { months -> month = month.plusMonths(months.toLong()) },
                )
                WeekdayHeader()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (week in weeks) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (day in week) {
                                DayCell(
                                    day = day,
                                    entry = day?.let { days[it] },
                                    today = today,
                                    modifier = Modifier.weight(1f),
                                    onOpen = { selected = it },
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabularText(workoutCount(inMonth), style = NT.Fonts.footnote, color = NT.Colors.ink2)
                    Spacer(Modifier.weight(1f))
                    if (streak > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NtIcon(NtIcons.FlameFill, size = sfIconSize(12f), tint = NT.Colors.ember)
                            TabularText(workoutWeekStreak(streak), style = NT.Fonts.footnote, color = NT.Colors.ink2)
                        }
                    }
                }
            }
        }
    }

    WorkoutDetailPresenter(workoutId = selected, unit = unit, host = "progressCalendar", onDismiss = { selected = null })
}

@Composable
private fun MonthHeader(month: YearMonth, canGoForward: Boolean, onPage: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NtText(
            text = Fmt.monthYear(month.atDay(1), appLocale()),
            style = NT.Fonts.headline,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        PageButton(NtIcons.ChevronLeft, stringResource(S.calendar_previous), enabled = true) { onPage(-1) }
        PageButton(NtIcons.ChevronRight, stringResource(S.calendar_next), enabled = canGoForward) { onPage(1) }
    }
}

@Composable
private fun PageButton(icon: NtIcons, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .ntPlainClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(
            icon = icon,
            size = sfIconSize(14f),
            tint = if (enabled) NT.Colors.ink else NT.Colors.ink3,
            contentDescription = label,
        )
    }
}

/** M T W T F S S in the user's language, Monday first. */
@Composable
private fun WeekdayHeader() {
    val locale = appLocale()
    // Single letters; each day cell reads its own date.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clearAndSetSemantics {}) {
        for (day in DayOfWeek.entries) {
            NtText(
                text = day.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                modifier = Modifier.weight(1f),
                style = NT.Fonts.caption,
                color = NT.Colors.ink3,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate?,
    entry: TrainingCalendar.Day?,
    today: LocalDate,
    modifier: Modifier,
    onOpen: (String) -> Unit,
) {
    if (day == null) {
        Spacer(modifier.aspectRatio(1f).clearAndSetSemantics {})
        return
    }
    val sets = entry?.sets ?: 0
    val level = TrainingCalendar.level(sets)
    val first = entry?.workoutIds?.firstOrNull()
    val label = Fmt.dayMonth(day, appLocale())
    val workouts = entry?.workoutIds?.size ?: 0
    val value = if (first == null) {
        stringResource(S.calendar_rest)
    } else {
        workoutCount(workouts) + ", " + workoutSetCount(sets)
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(NT.Colors.heat[level], DAY_SHAPE)
            .then(if (day == today) Modifier.border(1.5.dp, NT.Colors.ink, DAY_SHAPE) else Modifier)
            .ntPlainClickable(enabled = first != null) { first?.let(onOpen) }
            .semantics {
                contentDescription = label
                stateDescription = value
            },
        contentAlignment = Alignment.Center,
    ) {
        TabularText(
            text = day.dayOfMonth.toString(),
            style = if (level > 0) NT.Fonts.footnoteBold else NT.Fonts.footnote,
            color = when {
                level >= 3 -> NT.Colors.onPrimary
                day > today -> NT.Colors.ink3
                else -> NT.Colors.ink
            },
        )
    }
}

/** `RoundedRectangle(cornerRadius: 8, style: .continuous)`. */
private val DAY_SHAPE = NtShapes.rounded(8.dp)
