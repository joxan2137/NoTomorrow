package app.notomorrow.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.FocalCard
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.service.DayState
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.Instant

/**
 * The one card on the dashboard (its [FocalCard]): next session time, relative day + countdown,
 * bro row, action row — the port of `NextSessionCard` (`Features/Dashboard/NextSessionCard.swift`).
 */
@Composable
fun NextSessionCard(
    state: DashboardUiState,
    onConfirm: () -> Unit,
    onStart: () -> Unit,
    onCantMakeIt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.session
    val sessionIsToday = session?.isToday == true
    val isIn = sessionIsToday &&
        (state.myState is DayState.Confirmed || state.myState is DayState.Attended)
    val isOut = sessionIsToday && state.myState.isMissedOrCancelled
    val partnerIsIn = state.partnerState is DayState.Confirmed || state.partnerState is DayState.Attended

    FocalCard(modifier) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TitleRow(
                eyebrow = stringResource(
                    when {
                        state.sessionDone -> S.dashboard_todaysSession
                        session == null || sessionIsToday -> S.dashboard_nextSession
                        else -> S.dashboard_restDay_next
                    }
                ),
                routineName = state.routineName,
            )
            HeroRow(session, done = state.sessionDone)
            Hairline()
            StatusRow(
                state = state,
                sessionIsToday = sessionIsToday,
                isIn = isIn,
                isOut = isOut,
                partnerIsIn = partnerIsIn,
            )
            ButtonRow(
                state = state,
                sessionIsToday = sessionIsToday,
                isIn = isIn,
                onConfirm = onConfirm,
                onStart = onStart,
                onCantMakeIt = onCantMakeIt,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rows
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TitleRow(eyebrow: String, routineName: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(eyebrow, color = NT.Colors.ember)
        // Swift's `HStack { … Spacer() … }` uses the default 8 pt spacing on BOTH sides of
        // the Spacer, so the routine name never comes closer than 16 pt to the eyebrow.
        // The gap has to live on the (non-weighted) name, which Row measures before the
        // weighted spacer — a `widthIn` on the spacer itself would be coerced away to 0.
        Spacer(Modifier.weight(1f))
        if (routineName != null) {
            NtText(
                text = routineName,
                modifier = Modifier.padding(start = TITLE_MIN_GAP),
                style = NT.Fonts.caption,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
    }
}

/** [done]: today's session is trained, so "Done" stands where the countdown would. */
@Composable
private fun HeroRow(session: DashboardSession?, done: Boolean) {
    if (session == null) {
        NtText(
            text = stringResource(S.dashboard_noSchedule),
            modifier = Modifier.padding(vertical = 8.dp),
            style = NT.Fonts.headline,
            color = NT.Colors.ink,
        )
        return
    }
    val strings = rememberNtStrings()
    val nowMillis by rememberSecondTicker(COUNTDOWN_TICK_MILLIS)
    val now = Instant.ofEpochMilli(nowMillis)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The clock is the weighted child so Row measures it LAST, against whatever the
        // day + countdown column left over — SwiftUI's fixed-size VStack keeps its
        // intrinsic width and `minimumScaleFactor(0.6)` shrinks the hero, not the column.
        // (In a 12-hour locale "10:30 PM" at 64 sp would otherwise eat the whole row.)
        HeroTime(
            Fmt.time(session.minuteOfDay),
            Modifier.weight(1f, fill = false).alignBy(LastBaseline),
        )
        Column(
            modifier = Modifier.alignBy(LastBaseline).padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NtText(
                text = Fmt.relativeDay(session.date, strings),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
            )
            TabularText(
                text = when {
                    done -> stringResource(S.dashboard_sessionDone)
                    !session.at.isAfter(now) -> stringResource(S.dashboard_now)
                    else -> Fmt.countdown(session.at, strings, now)
                },
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        }
    }
}

/** `display(64)` with `minimumScaleFactor(0.6)` — one line, shrinking to 38.4 sp. */
@Composable
private fun HeroTime(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier,
        style = NT.Fonts.display(HERO_SIZE).tabular().copy(color = NT.Colors.ink),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(
            minFontSize = (HERO_SIZE * HERO_MIN_SCALE).sp,
            maxFontSize = HERO_SIZE.sp,
            stepSize = 0.5.sp,
        ),
    )
}

@Composable
private fun StatusRow(
    state: DashboardUiState,
    sessionIsToday: Boolean,
    isIn: Boolean,
    isOut: Boolean,
    partnerIsIn: Boolean,
) {
    when {
        !sessionIsToday -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtIcon(NtIcons.MoonZzz, size = sfIconSize(15f), tint = NT.Colors.ink2)
            NtText(
                text = stringResource(S.dashboard_restDay),
                style = NT.Fonts.subheadlineBold,
                color = NT.Colors.ink2,
            )
        }

        !state.isPaired -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarStack(state, isIn, isOut, partnerIsIn)
            NtText(
                text = stringResource(
                    when {
                        isIn -> S.dashboard_youreIn
                        isOut -> S.dashboard_youreOut
                        else -> S.dashboard_noBro
                    }
                ),
                modifier = Modifier.weight(1f).padding(end = STATUS_MIN_GAP),
                style = NT.Fonts.subheadlineBold,
                color = if (isIn || isOut) NT.Colors.ink else NT.Colors.ink2,
                maxLines = 2,
            )
            if (isIn && state.myTime != null) TimeStamp(state.myTime, NT.Colors.good)
        }

        else -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarStack(state, isIn, isOut, partnerIsIn)
            NtText(
                text = broLine(state, isIn, isOut, partnerIsIn),
                modifier = Modifier.weight(1f).padding(end = STATUS_MIN_GAP),
                style = NT.Fonts.subheadlineBold,
                color = NT.Colors.ink,
                maxLines = 2,
            )
            BroTimes(state, isIn, partnerIsIn)
        }
    }
}

@Composable
private fun ButtonRow(
    state: DashboardUiState,
    sessionIsToday: Boolean,
    isIn: Boolean,
    onConfirm: () -> Unit,
    onStart: () -> Unit,
    onCantMakeIt: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val button = Modifier.weight(1f)
        when {
            state.hasActiveWorkout -> PrimaryButton(
                title = stringResource(S.dashboard_resumeWorkout),
                modifier = button,
                height = NT.Size.cardButton,
                onClick = onStart,
            )

            !sessionIsToday -> SecondaryButton(
                title = stringResource(S.dashboard_startWorkout),
                modifier = button,
                height = NT.Size.cardButton,
                onClick = onStart,
            )

            isIn -> PrimaryButton(
                title = stringResource(S.dashboard_startWorkout),
                modifier = button,
                height = NT.Size.cardButton,
                onClick = onStart,
            )

            else -> PrimaryButton(
                title = stringResource(S.dashboard_imIn),
                modifier = button,
                height = NT.Size.cardButton,
                enabled = !state.isConfirming,
                onClick = onConfirm,
            )
        }
        if (offersCantMakeIt(sessionIsToday, state.myState, state.trainedToday)) {
            SecondaryButton(
                title = stringResource(S.dashboard_cantMakeIt),
                modifier = button,
                height = NT.Size.cardButton,
                onClick = onCantMakeIt,
            )
        }
    }
}

/**
 * `NextSessionCard.offersCantMakeIt` — "Can't make it" is for a session still ahead today: not once
 * I am out, and not once I trained (it would cancel a day that is already attended).
 */
internal fun offersCantMakeIt(sessionIsToday: Boolean, myState: DayState, trainedToday: Boolean): Boolean =
    sessionIsToday && !myState.isMissedOrCancelled && myState !is DayState.Attended && !trainedToday

/** `TimelineView(.periodic(by: 60))` — the relative day and the countdown. */
private const val COUNTDOWN_TICK_MILLIS = 60_000L
private const val HERO_SIZE = 64
private const val HERO_MIN_SCALE = 0.6f

/** Swift's implicit 8 pt HStack spacing on both sides of `titleRow`'s `Spacer()`. */
private val TITLE_MIN_GAP = 16.dp

/**
 * `statusRow`'s `Spacer(minLength: 8)` between the two 10 pt HStack gaps: 28 pt in all.
 * The status text fills the weighted slot (so the trailing timestamp stays flush right,
 * as SwiftUI's expanding Spacer keeps it) and reserves the remaining 18 pt as padding.
 */
private val STATUS_MIN_GAP = 18.dp
