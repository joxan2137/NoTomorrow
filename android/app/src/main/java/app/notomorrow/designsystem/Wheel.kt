package app.notomorrow.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The iOS wheel (`UIPickerView` / `DatePicker(.wheel)`) rebuilt on a
 * `LazyColumn`: nine 32 dp rows, snap fling, the measured 302 x 34 selection
 * band behind the centre row, and rows projected onto a cylinder - they shrink,
 * fade and bunch toward the band exactly like the UIKit wheel.
 *
 * The selection is the item nearest the **viewport centre** (research §9.6 —
 * `firstVisibleItemIndex` is off by the padding), reported when the wheel comes
 * to rest; a `SegmentFrequentTick` fires as each row passes the centre while
 * dragging.
 *
 * [wraps] makes it endless like `UIDatePicker`'s components — see [NtWheelLoopSpan].
 */
@Composable
fun <T> NtWheelPicker(
    items: List<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = NtWheelRowHeight,
    visibleRows: Int = NtWheelVisibleRows,
    style: TextStyle = NT.Fonts.wheelDigit,
    highlight: Boolean = true,
    /**
     * `UIDatePicker` spins without ends: above `00` sit `55, 50, …`, and the hour column runs
     * `23 → 00` in either direction. A `UIPickerView` built from a plain list does **not**, so this
     * is opt-in: only the clock columns pass it.
     */
    wraps: Boolean = false,
    label: (T) -> String,
) {
    if (items.isEmpty()) return
    val size = items.size
    // A one-item list has nothing to spin through, and `rows % size` would pin it anyway.
    val wrapping = wraps && size > 1
    // The endless wheel is [NtWheelLoopSpan] copies of the list laid end to end, entered in the
    // middle copy: `index % size` maps a row back to its value, and reaching either end of the
    // LazyColumn would take hundreds of full turns, so no repositioning trick is needed.
    val rows = if (wrapping) size * ntWheelLoops(size) else size
    val origin = if (wrapping) (ntWheelLoops(size) / 2) * size else 0
    val initialRow = (origin + selectedIndex.coerceIn(0, size - 1)).coerceIn(0, rows - 1)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialRow)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val haptics = LocalHapticFeedback.current
    val onSelectState by rememberUpdatedState(onSelect)

    /** The LazyColumn row at the band — a *repeat* index once [wrapping]. */
    val centreRow by remember(rows) {
        derivedStateOf {
            val info = state.layoutInfo
            val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo
                .minByOrNull { abs((it.offset + it.size / 2f) - centre) }
                ?.index
                ?: initialRow
        }
    }
    /**
     * That row's value index — what the caller means by "selected". Derived, not a plain `val`:
     * the two `LaunchedEffect`s below read it from inside a coroutine that outlives the
     * composition that started it, so it has to stay a live snapshot read.
     */
    val centreIndex by remember(rows, size, wrapping) {
        derivedStateOf { if (wrapping) centreRow.mod(size) else centreRow }
    }

    // Tick as each row crosses the centre, but only while the user is moving it.
    LaunchedEffect(state, rows) {
        snapshotFlow { centreRow }.collect {
            if (state.isScrollInProgress) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }
    }
    // Commit when the wheel comes to rest, exactly like UIKit's selection.
    //
    // `snapshotFlow` replays its current value the moment it is collected, so an
    // un-touched wheel used to emit `false` on the very first frame and commit
    // whatever `centreIndex` happened to resolve to before layout settled — which
    // silently rewrote the stored time (17:00 -> 16:00 -> 15:00 across two
    // open/back cycles of the Gym-days editor). Only a wheel the user has
    // actually dragged may write back.
    LaunchedEffect(state, rows) {
        var everScrolled = false
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                everScrolled = true
                return@collect
            }
            if (everScrolled && centreIndex in items.indices && centreIndex != selectedIndex) {
                onSelectState(centreIndex)
            }
        }
    }
    // Follow an external change (a reset, a different day's override). On a wrapping wheel the
    // target is the copy of `selectedIndex` NEAREST the current row — scrolling back to the
    // middle copy would spin the whole column past the user.
    LaunchedEffect(selectedIndex) {
        if (!state.isScrollInProgress && selectedIndex in items.indices && selectedIndex != centreIndex) {
            val target = if (wrapping) {
                val forward = (selectedIndex - centreRow).mod(size)
                val row = if (forward * 2 <= size) centreRow + forward else centreRow + forward - size
                row.coerceIn(0, rows - 1)
            } else {
                selectedIndex
            }
            state.scrollToItem(target)
        }
    }

    Box(
        modifier = modifier.height(ntWheelHeight(rowHeight, visibleRows)),
        contentAlignment = Alignment.Center,
    ) {
        // The scrolling viewport is `visibleRows` rows tall REGARDLESS of the frame the caller
        // wraps the wheel in, and it is centred in that frame.
        //
        // A LazyColumn only composes the rows whose LAYOUT box meets its viewport, and the
        // cylinder's `pull` is a draw-time translation that does not move that box — so deriving
        // the viewport from the measured frame (180 dp in `ScheduleEditor`, 164 in
        // `ObStepControls`) capped the wheel at the ±3 rows that fit uncompressed and silently
        // dropped the ±4 pair iOS still draws (`13-settings-schedule.png`: 9 rows, the outermost
        // at y 828-833 / 1351-1356).
        //
        // `requiredHeight` therefore overflows the frame on purpose — the frame clips, and after
        // the pull every row lands well inside it: the d4 row sits 87.2 dp out in a 90 dp
        // half-frame. Half a viewport minus half a row is what centres row 0 on the band.
        val viewport = rowHeight * visibleRows
        val pad = ((viewport - rowHeight) / 2).coerceAtLeast(0.dp)
        if (highlight) NtWheelBand()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().requiredHeight(viewport),
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = pad),
        ) {
            items(rows) { row ->
                // The cylinder is a property of the row's DISTANCE from the band, so it is read
                // off the repeat index; only the label and the selected-ink test fold back.
                val d = abs(row - centreRow).coerceAtMost(NtWheelRungs.lastIndex)
                val below = row > centreRow
                val index = if (wrapping) row.mod(size) else row
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .graphicsLayer {
                            val rung = NtWheelRungs[d]
                            alpha = rung.alpha
                            scaleX = rung.scale
                            scaleY = rung.scale
                            // The cylinder compresses the pitch outward (31.2 -> 26.9
                            // -> 19.2 pt); the rows are laid out on a uniform pitch, so
                            // pull each one back toward the band by the difference.
                            val pull = rung.pull.toPx()
                            translationY = if (below) -pull else pull
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    NtText(
                        text = label(items[index]),
                        style = style.tabular(),
                        // The rung alphas below are applied to FULL ink, so the
                        // out-rows land on the measured composite instead of
                        // being dimmed twice by `ink2`'s own 0.60.
                        color = if (row == centreRow) NtWheelSelectedInk else NT.Colors.ink,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// docs/android-glass.md §1.4, re-checked here at native 3x on
// design/ios26-reference/11-wheel-schedule-editor.png:
//
//   band   y 347.33 -> 381.33 = 34.0 pt; half-height 12.0 at dx 5 and 15.5 at
//          dx 10 from the cap  =>  capsule r = 17, so 302 x 34 inside the
//          320 pt picker, inset 9 each side
//   fill   (44,44,48) over `surface` (28,28,30)  =>  #EBEBF5 @ ~8.5 %, FLAT:
//          no rim, no lensing - the band is not a glass surface
//   rows   centre cell 32.0 [view tree]; 9 visible (re-counted on
//          design/parity/ios/13-settings-schedule.png — see NtWheelVisibleRows);
//          pitch 31.2 -> 26.9 -> 19.2 -> 9.7 walking out, glyph scale
//          1.0 -> .84 -> .64 -> .39 -> .115
//   ink    re-sampled per rung on the same PNG (peak glyph pixel, background
//          `surface` 28/28/30 outside the band): d0 = (193,193,195) FLAT — the
//          selected digit is NOT full `ink`; d1 108, d2 102, d3 49, i.e.
//          effective alphas .377 / .353 / .21 against `ink`. The old .48/.44/.26
//          ladder was multiplied by `ink2`'s own .60 at the call site and
//          rendered the out-rows ~22 % under-lit, so the alphas now apply to
//          FULL ink.
//   digits selected group 75 x 51 px = 25 x 17 dp  =>  ~23 pt REGULAR, not the
//          20 pt semibold `title3` this file used to default to
//   cols   digit-group centres x 475 / 700  =>  75 dp apart, the pair centred
//          5 dp LEFT of the band centre (602.5)
// ─────────────────────────────────────────────────────────────────────────────

/** `UIPickerTableViewWrapperCell` height at the centre of the wheel. */
val NtWheelRowHeight: Dp = 32.dp

/**
 * How many rows a wrapping wheel lays out, before rounding up to a whole number of copies.
 *
 * `UIPickerView` fakes its endlessness by teleporting back to the middle of a large fixed range;
 * a `LazyColumn` only ever composes the ~7 rows on screen, so the range can simply be big enough
 * that no one reaches an end: 2000 rows at the 32 dp pitch is 64 000 dp of travel, ~83 turns of the
 * hour column, from a start in the middle copy.
 */
private const val NtWheelLoopSpan: Int = 2000

/** [NtWheelLoopSpan] rounded to an odd number of whole copies, so one copy sits in the middle. */
private fun ntWheelLoops(size: Int): Int = ((NtWheelLoopSpan / size) or 1).coerceAtLeast(3)

/**
 * `UIPickerView` shows **nine** rows through its window — not seven.
 *
 * [measured] on `design/parity/ios/13-settings-schedule.png` at 3x. Nine digit groups sit inside
 * the wheel's 180 pt tile (y 823 → 1362 px), their glyph runs at
 *
 *   828-833 · 849-870 · 900-934 · 976-1019 · [1042-1143 band] · 1165-1208 · 1250-1284 ·
 *   1315-1335 · 1351-1356
 *
 * i.e. centres 87.2 / 58.2 / 19.2… pt out from the band on the compressed pitch — the ±4 pair is
 * a 2 pt sliver, but it is drawn, and Android rendered only seven. The extra ring is
 * [NtWheelRungs]`[4]`; the seven-row count came from the `ios26-reference` frame, whose wheel is
 * cropped tighter.
 */
const val NtWheelVisibleRows: Int = 9

/**
 * `UIDatePicker.intrinsicContentSize = 320 × 216` [view tree] — **not** `32 × 9 = 288`. The outer
 * rows are compressed onto the cylinder, which is what the `pull` ladder in [NtWheelRungs] models,
 * so the 32 dp layout pitch stays, the container shows 216, and the compressed nine rows span only
 * 2 × 87.2 + 32 ≈ 206 of it. The LazyColumn's own viewport is the full 288 (see [NtWheelPicker]):
 * it has to be, or the outermost pair is never composed.
 */
val NtWheelPickerHeight: Dp = 216.dp

/** The container height for a wheel: the measured 216 for the default wheel, derived otherwise. */
private fun ntWheelHeight(rowHeight: Dp, visibleRows: Int): Dp =
    if (rowHeight == NtWheelRowHeight && visibleRows == NtWheelVisibleRows) {
        NtWheelPickerHeight
    } else {
        rowHeight * visibleRows
    }

/** The selection band: 302 x 34 inside a 320 pt picker, i.e. 9 pt in on each side. */
val NtWheelBandHeight: Dp = 34.dp
private val NtWheelBandInset: Dp = 9.dp

/**
 * `UIDatePicker.intrinsicContentSize.width`. The 9 dp inset is measured off THIS,
 * not off whatever card the wheel is dropped into — a `fillMaxWidth()` band in the
 * 362 dp schedule card came out 343.7 dp instead of 302.
 */
private val NtWheelPickerWidth: Dp = 320.dp
private val NtWheelBandFill: Color = Color(0xFFEBEBF5).copy(alpha = 0.085f)

/** One ring of the cylinder: how a row `d` places away from the band renders. */
@Immutable
internal data class NtWheelRung(val scale: Float, val alpha: Float, val pull: Dp)

/**
 * The measured projection ladder. `pull` is the gap between the uniform layout
 * pitch (32) and the cylinder's cumulative one (31.2 / 58.1 / 77.3), applied as
 * a translation toward the band.
 */
internal val NtWheelRungs: List<NtWheelRung> = listOf(
    NtWheelRung(scale = 1.00f, alpha = 1.00f, pull = 0.0.dp),
    NtWheelRung(scale = 0.84f, alpha = 0.377f, pull = 0.8.dp),
    NtWheelRung(scale = 0.64f, alpha = 0.353f, pull = 5.9.dp),
    NtWheelRung(scale = 0.39f, alpha = 0.210f, pull = 18.7.dp),
    // d4 — the outermost ring iOS draws and this file used to have no room for.
    // [measured] on `13-settings-schedule.png`: the row's centre sits 261.5 px = 87.2 dp from the
    // band against a uniform layout pitch of 4 × 32 = 128 dp, so pull = 40.8; the glyph run is
    // 6 px tall against the selected group's 51 (scale 0.115) and peaks at 51/255 over `surface`
    // 28, on the same ladder the three rungs above use. 0.120 rendered that peak at 55, so the
    // alpha is scaled back to the 0.105 that lands on iOS's 51.
    NtWheelRung(scale = 0.115f, alpha = 0.105f, pull = 40.8.dp),
    // A guard rung, not a measured one: mid-fling the viewport straddles two rows and `centreRow`
    // can leave a composed row 5 places out. iOS has nothing there, so neither has this.
    NtWheelRung(scale = 0.115f, alpha = 0.000f, pull = 40.8.dp),
)

/**
 * The selected digit. iOS renders it at (193,193,195) — `ink` at ~75 % over the
 * band — not at full `ink` (242,242,244), which read as a hard white next to the
 * capture's softer grey.
 */
internal val NtWheelSelectedInk: Color = Color(0xFFC1C1C3)

/**
 * The flat capsule the selected row sits in. Never a glass surface (§1.4) — `internal` so
 * `GlassProbeScreen` verifies **this** band rather than a `GlassStyle.WheelBand` path the app never
 * executes (§4 row 9).
 */
@Composable
internal fun NtWheelBand(modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(max = NtWheelPickerWidth)
            .fillMaxWidth()
            .padding(horizontal = NtWheelBandInset)
            .height(NtWheelBandHeight)
            .background(NtWheelBandFill, NtShapes.capsule),
    )
}

/**
 * `DatePicker(.hourAndMinute)` in `.wheel` style: two wheels side by side, one
 * selection band across both.
 *
 * [minuteStep] is 1 for iOS parity (`OBTimeWheel`, `ScheduleEditor` both use the
 * system picker's default `minuteInterval`); pass 5 for a coarser wheel.
 * Hours are 0…23 — the app writes `minuteOfDay` and reads it back through
 * `Fmt.time(minuteOfDay)`, so the wheel itself is 24 h regardless of locale.
 */
@Composable
fun NtTimeWheel(
    hour: Int,
    minute: Int,
    onChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
    minuteStep: Int = 1,
    rowHeight: Dp = NtWheelRowHeight,
    visibleRows: Int = NtWheelVisibleRows,
) {
    val hours = remember { (0..23).toList() }
    val minutes = remember(minuteStep) { (0..59 step minuteStep.coerceAtLeast(1)).toList() }
    val minuteIndex = remember(minute, minutes) {
        minutes.indexOfFirst { it >= minute }.let { if (it < 0) minutes.lastIndex else it }
    }
    Box(
        modifier = modifier.height(ntWheelHeight(rowHeight, visibleRows)),
        contentAlignment = Alignment.Center,
    ) {
        NtWheelBand()
        // Fixed-width columns, centred as a pair: `weight(1f)` across the whole
        // card put each digit group on the 25 % / 75 % marks (184.5 dp apart in
        // the 362 dp schedule card) where iOS sits them 75 dp apart, with the
        // pair 5 dp left of centre.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = -NtTimeWheelPairOffset),
            horizontalArrangement = Arrangement.Center,
        ) {
            NtWheelPicker(
                items = hours,
                selectedIndex = hour.coerceIn(0, 23),
                onSelect = { onChange(hours[it], minutes[minuteIndex]) },
                modifier = Modifier.width(NtTimeWheelColumnWidth),
                rowHeight = rowHeight,
                visibleRows = visibleRows,
                highlight = false,
                // `UIDatePicker` wraps both components: 23 -> 00 on the hour, 59 -> 00 on the minute.
                wraps = true,
                label = { twoDigits(it) },
            )
            NtWheelPicker(
                items = minutes,
                selectedIndex = minuteIndex,
                onSelect = { onChange(hour.coerceIn(0, 23), minutes[it]) },
                modifier = Modifier.width(NtTimeWheelColumnWidth),
                rowHeight = rowHeight,
                visibleRows = visibleRows,
                highlight = false,
                // `UIDatePicker` wraps both components: 23 -> 00 on the hour, 59 -> 00 on the minute.
                wraps = true,
                label = { twoDigits(it) },
            )
        }
    }
}

/** Column centres 75 dp apart, so each centred column is exactly 75 dp wide. */
private val NtTimeWheelColumnWidth: Dp = 75.dp

/** The `UIDatePicker` pair sits 5 dp left of the band centre. */
private val NtTimeWheelPairOffset: Dp = 5.dp

/**
 * Zero-padded two-digit clock component. Deliberately ASCII and unlocalized,
 * exactly like `Fmt.clock` ("%d:%02d" is the one string iOS does not localize).
 */
private fun twoDigits(value: Int): String {
    val v = value.coerceIn(0, 99)
    return "${'0' + v / 10}${'0' + v % 10}"
}

