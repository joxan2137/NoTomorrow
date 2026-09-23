package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Grabber
import app.notomorrow.designsystem.KcalLabel
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.model.TrainingGoal
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import java.time.LocalDate

/*
 * "History" — the port of `FuelCalendarSheet` (`Features/Fuel/FuelCalendarSheet.swift`): half a
 * year of days as ember cells (brighter = closer to the kcal goal, judged for the current training
 * goal), a legend and three stat tiles. Geometry is the Swift geometry, 1 pt -> 1 dp.
 */

/** `.presentationDetents([.height(450)])`. */
val FuelCalendarSheetHeight = 450.dp

private val CellSize = 24.dp

/** Cell pitch: every tap target is the full 28 × 28 square, the 24 dp fill centred in it. */
private val CellPitch = 28.dp

/** The selected day's ring: 3 dp around the fill, so it overhangs the 28 dp cell by 1 dp a side. */
private val RingSize = CellSize + 6.dp

/**
 * The scrolling grid's inset at each end: twice the ring's overhang (the overhang plus room for
 * the stroke's anti-aliased edge), so the ring on the current week's column — flush against the
 * trailing edge — and on the oldest column is never clipped.
 */
private val GridEdgeInset = RingSize - CellPitch
private val MonthRow = 14.dp
private val MonthGap = 6.dp
private val WeekdayColumn = 22.dp

/**
 * Legend width that is not text: the level-0 swatch, the four-swatch scale (4 × 12 + 3 × 3), the
 * spacer's 12 dp minimum and the five 6 dp gaps between the row's six children.
 */
private val LegendFixedWidth = 12.dp + 57.dp + 12.dp + 6.dp * 5

/**
 * Tapping a day calls [onSelect]; the owner closes the sheet and moves Fuel there. [kcalByDay] is
 * keyed by [FuelCalendar.dayKey]; days with nothing logged are absent.
 */
@Composable
fun FuelCalendarSheet(
    selectedDay: LocalDate,
    today: LocalDate,
    kcalGoal: Double,
    goal: TrainingGoal,
    kcalByDay: Map<LocalDate, Double>,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val layout = remember(today) { FuelCalendar.layout(today) }
    val stats = remember(kcalByDay, today, kcalGoal, goal) {
        FuelCalendar.stats(kcalByDay, today, kcalGoal, goal)
    }

    NtSheet(
        onDismiss = onDismiss,
        containerColor = NT.Colors.ground,
        height = FuelCalendarSheetHeight,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH),
        ) {
            Grabber(Modifier.align(Alignment.CenterHorizontally))

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtText(
                    text = stringResource(S.fuel_calendar_title),
                    style = NT.Fonts.title2,
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                // `.buttonStyle(.plain)` — tappable, no press feedback.
                Box(
                    modifier = Modifier
                        .height(NT.Size.control)
                        .ntPlainClickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    NtText(
                        text = stringResource(S.common_done),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink2,
                        maxLines = 1,
                    )
                }
            }

            // History is scored against today's goal (no goal history is kept), so say which one.
            ShrinkingText(
                text = stringResource(
                    S.fuel_calendar_goalNote,
                    stringResource(NtKeys.goal(goal)),
                    Fmt.kcal(kcalGoal),
                ),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
                minScale = 0.8f,
            )

            FuelCalendarGrid(
                layout = layout,
                selectedDay = selectedDay,
                kcalGoal = kcalGoal,
                goal = goal,
                kcalByDay = kcalByDay,
                onSelect = onSelect,
                modifier = Modifier.padding(top = 18.dp),
            )
            FuelCalendarLegend(Modifier.padding(top = 10.dp))
            FuelCalendarStats(stats, Modifier.padding(top = 18.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Grid
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FuelCalendarGrid(
    layout: FuelCalendar.Layout,
    selectedDay: LocalDate,
    kcalGoal: Double,
    goal: TrainingGoal,
    kcalByDay: Map<LocalDate, Double>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.clearAndSetSemantics {}) {
            Spacer(Modifier.height(MonthRow + MonthGap))
            for (isoWeekday in 1..7) {
                Box(
                    modifier = Modifier.size(width = WeekdayColumn, height = CellPitch),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    NtText(
                        text = stringResource(NtKeys.weekday(isoWeekday)),
                        style = NT.Fonts.caption,
                        color = NT.Colors.ink3,
                        maxLines = 1,
                    )
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(MonthRow + MonthGap + CellPitch * 7),
        ) {
            val count = layout.columns.size
            val visible = ((maxWidth - GridEdgeInset * 2) / CellPitch).toInt().coerceAtLeast(1)
            val first = FuelCalendar.firstVisibleColumn(layout.columnOf(selectedDay), visible, count)
            // Opens on the current week at the right edge (`defaultScrollAnchor(.trailing)`): asking
            // for the last column lets the list settle flush against its end. A selected day that
            // would be off screen there is centred instead.
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = if (first >= count - visible) count - 1 else first,
            )
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = GridEdgeInset),
            ) {
                items(count = count, key = { it }) { c ->
                    Column(Modifier.width(CellPitch)) {
                        MonthLabelSlot(layout.monthLabels.firstOrNull { it.column == c })
                        Spacer(Modifier.height(MonthGap))
                        for (day in layout.columns[c]) {
                            CalendarCell(
                                day = day,
                                today = layout.today,
                                selectedDay = selectedDay,
                                kcal = day?.let { kcalByDay[it] },
                                kcalGoal = kcalGoal,
                                goal = goal,
                                onSelect = onSelect,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Labels are at least two columns apart, so a name wider than one column may run over its neighbour's empty slot. */
@Composable
private fun MonthLabelSlot(label: FuelCalendar.MonthLabel?) {
    Box(Modifier.size(width = CellPitch, height = MonthRow)) {
        if (label != null) {
            NtText(
                text = Fmt.monthShort(label.month),
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .clearAndSetSemantics {},
                style = NT.Fonts.caption,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CalendarCell(
    day: LocalDate?,
    today: LocalDate,
    selectedDay: LocalDate,
    kcal: Double?,
    kcalGoal: Double,
    goal: TrainingGoal,
    onSelect: (LocalDate) -> Unit,
) {
    if (day == null) {
        // A future day this week: not drawn, not tappable, not read.
        Spacer(Modifier.size(CellPitch))
        return
    }
    val isToday = day == today
    val isSelected = day == selectedDay
    val level = FuelCalendar.level(
        kcalEaten = kcal ?: 0.0,
        kcalGoal = kcalGoal,
        goal = goal,
        hasEntries = kcal != null,
        isToday = isToday,
    )
    val label = cellLabel(day, kcal, kcalGoal, isToday)
    // TalkBack reads "Double-tap to <action>", so this is the action phrase ("otworzyć ten dzień"),
    // not the iOS hint sentence ("Otwiera ten dzień").
    val action = stringResource(S.fuel_calendar_cell_action)
    Box(
        modifier = Modifier
            .size(CellPitch)
            .pressScale(role = Role.Button, onClickLabel = action) { onSelect(day) }
            .semantics {
                contentDescription = label
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(CellSize)
                .background(NT.Colors.heat[level], NtShapes.rounded(6.dp)),
        )
        if (isToday) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(if (level >= 3) NT.Colors.ground else NT.Colors.ink, CircleShape),
            )
        }
        if (isSelected) {
            Box(
                Modifier
                    .requiredSize(RingSize)
                    .border(2.dp, NT.Colors.ink, NtShapes.rounded(9.dp)),
            )
        }
    }
}

/** "Monday, 21 September: 2 140 kcal, 93% of goal" ("… so far …" for today, "… nothing logged" when empty). */
@Composable
private fun cellLabel(day: LocalDate, kcal: Double?, kcalGoal: Double, isToday: Boolean): String {
    val date = Fmt.longDay(day)
    if (kcal == null) return stringResource(S.fuel_calendar_cell_empty, date)
    val percent = if (kcalGoal > 0) Fmt.percent(kcal / kcalGoal) else "–"
    return stringResource(
        if (isToday) S.fuel_calendar_cell_today else S.fuel_calendar_cell,
        date,
        Fmt.kcal(kcal),
        percent,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Legend & stats
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `[■0] Not logged · · · Off target [■1■2■3■4] On target`. Swatch 0 means "nothing logged", not
 * "far off", so it sits apart from the off → on target scale. One line; the text shrinks to 0.8
 * before it truncates (`.lineLimit(1).minimumScaleFactor(0.8)`), and it is read as one element.
 */
@Composable
private fun FuelCalendarLegend(modifier: Modifier = Modifier) {
    val none = stringResource(S.fuel_calendar_legend_none)
    val off = stringResource(S.fuel_calendar_legend_off)
    val on = stringResource(S.fuel_calendar_onTarget)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val style = remember(none, off, on, available, density) {
            val caption = NT.Fonts.caption
            val text = listOf(none, off, on).sumOf {
                measurer.measure(it, caption, maxLines = 1, softWrap = false).size.width
            }
            val room = available - with(density) { LegendFixedWidth.roundToPx() }
            if (text <= 0 || room >= text) caption
            else caption.copy(fontSize = caption.fontSize * (room.toFloat() / text).coerceAtLeast(0.8f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendSwatch(0)
            NtText(none, style = style, color = NT.Colors.ink2, maxLines = 1)
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            NtText(off, style = style, color = NT.Colors.ink2, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (level in 1..4) LegendSwatch(level)
            }
            NtText(on, style = style, color = NT.Colors.ink2, maxLines = 1)
        }
    }
}

@Composable
private fun LegendSwatch(level: Int) {
    Box(
        Modifier
            .size(12.dp)
            .background(NT.Colors.heat[level], NtShapes.rounded(3.dp)),
    )
}

/**
 * 7-day avg · 30-day avg · on target. The labels may wrap to two lines ("ZGODNIE Z CELEM"); every
 * tile then takes the tallest height and the values stay on one line at the bottom.
 */
@Composable
private fun FuelCalendarStats(stats: FuelCalendar.Stats, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CalendarStatTile(stringResource(S.fuel_calendar_avg7), Modifier.weight(1f)) {
            KcalValue(stats.avg7)
        }
        CalendarStatTile(stringResource(S.fuel_calendar_avg30), Modifier.weight(1f)) {
            KcalValue(stats.avg30)
        }
        CalendarStatTile(stringResource(S.fuel_calendar_onTarget), Modifier.weight(1f)) {
            TabularText(
                text = stringResource(S.fuel_calendar_ofDays, stats.onTarget30, FuelCalendar.STATS_WINDOW),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
            )
        }
    }
}

@Composable
private fun KcalValue(kcal: Double?) {
    if (kcal != null) {
        KcalLabel(kcal = kcal, numberStyle = NT.Fonts.headline)
    } else {
        TabularText(text = "–", style = NT.Fonts.headline, color = NT.Colors.ink3)
    }
}

/** `StatTile` chrome with any value view underneath a label of up to two lines. */
@Composable
private fun CalendarStatTile(
    label: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(NT.Colors.surface, NtShapes.tile)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.Start,
    ) {
        Eyebrow(label, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Spacer(Modifier.weight(1f))
        value()
    }
}
