package app.notomorrow.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import app.notomorrow.util.S

/**
 * Mon…Sun strip: letter label, 36 dp state circle, 5 dp row of who-trained dots — the port of
 * `WeekStripView` (`Features/Dashboard/WeekStripView.swift`).
 *
 * Attended = ember ring + check, missed/cancelled = rose ring + ✕, today = filled white
 * circle with the day number, a gym day still ahead = thin ring around the number, everything
 * else the bare day number. The dots (v2): ember when you trained and, when [isPaired], green
 * when your partner trained or rose when they missed.
 */
@Composable
fun WeekStrip(
    days: List<WeekDay>,
    isPaired: Boolean,
    partnerName: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        // `.accessibilityElement(children: .contain)` — the seven cells are one traversal
        // unit, read in order and not interleaved with the header or the card.
        modifier.fillMaxWidth().semantics { isTraversalGroup = true },
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (day in days) {
            WeekDayCell(day, weekStripDots(day, isPaired), partnerName, Modifier.weight(1f))
        }
    }
}

/** `WeekStripView.Dot` — one 5 dp dot under a day's circle. */
enum class WeekStripDot {
    /** You trained (ember). */
    You,

    /** Your partner trained (green). */
    PartnerTrained,

    /** Your partner missed or cancelled (rose). */
    PartnerMissed,
}

/**
 * `WeekStripView.dots(for:isPaired:)` — who trained that day, in order: you when you attended,
 * then — paired only — your partner when they attended or missed/cancelled. Empty on every
 * other day.
 */
internal fun weekStripDots(day: WeekDay, isPaired: Boolean): List<WeekStripDot> = buildList {
    if (day.myState is DayState.Attended) add(WeekStripDot.You)
    if (isPaired) {
        when {
            day.partnerState is DayState.Attended -> add(WeekStripDot.PartnerTrained)
            day.partnerState.isMissedOrCancelled -> add(WeekStripDot.PartnerMissed)
        }
    }
}

/**
 * `WeekStripView.isUpcomingGymDay` — a scheduled gym day other than today with nothing settled
 * yet (planned or confirmed): its number gets a thin ring. Without a record a future gym day
 * already reads [DayState.Planned]; a past one reads [DayState.Rest] and stays plain.
 */
internal fun isUpcomingGymDay(day: WeekDay): Boolean =
    day.isGymDay && !day.isToday &&
        (day.myState is DayState.Planned || day.myState is DayState.Confirmed)

@Composable
private fun WeekDayCell(
    day: WeekDay,
    dots: List<WeekStripDot>,
    partnerName: String?,
    modifier: Modifier = Modifier,
) {
    // `accessibilityText`: the day, then "<partner> trained" when the partner's dot is the green one.
    val dayLabel = Fmt.dayMonth(day.date)
    val label = if (WeekStripDot.PartnerTrained in dots && !partnerName.isNullOrEmpty()) {
        dayLabel + ", " + stringResource(S.dashboard_week_partnerDone_s, partnerName)
    } else {
        dayLabel
    }
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
        Row(
            Modifier.height(5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (dot in dots) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(
                            when (dot) {
                                WeekStripDot.You -> NT.Colors.ember
                                WeekStripDot.PartnerTrained -> NT.Colors.good
                                WeekStripDot.PartnerMissed -> NT.Colors.bad
                            },
                            CircleShape,
                        )
                )
            }
        }
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

            // `Circle().strokeBorder(NT.Colors.border, lineWidth: 1)` around the number in `ink`.
            isUpcomingGymDay(day) -> Box(
                Modifier.size(36.dp).border(1.dp, NT.Colors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                TabularText(
                    text = number,
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink,
                )
            }

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
