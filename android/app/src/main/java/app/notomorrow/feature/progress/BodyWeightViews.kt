package app.notomorrow.feature.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notomorrow.designsystem.BodyWeightChart
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * The body-weight half of Progress — the port of `BodyWeightCard`, `BodyDeltaLine` and
 * `BodyTabView` (`Features/Progress/BodyWeightViews.swift`).
 */

/** Compact card at the top of the Lifts tab: latest weight, 4-week delta, 120×48 sparkline. */
@Composable
fun BodyWeightCard(
    stats: BodyStats,
    unit: WeightUnit,
    onLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = stats.latest
    NTCard(
        // iOS is `.contentShape(…) + .onTapGesture` — a bare tap, no press feedback on the
        // card itself; only the inner "Log weight" button press-scales.
        modifier = modifier.then(
            if (latest != null) Modifier.ntPlainClickable(onClick = onLog) else Modifier,
        ),
        padding = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Eyebrow(stringResource(S.progress_bodyWeightTrend))
                if (latest != null) {
                    WeightHeadline(kg = latest.kg, unit = unit)
                    BodyDeltaLine(stats = stats, unit = unit)
                } else {
                    NtText(
                        text = stringResource(S.progress_noWeightYet),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink2,
                    )
                    Box(
                        modifier = Modifier.height(NT.Size.control).pressScale(onClick = onLog),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        NtText(
                            text = stringResource(S.progress_logWeight),
                            style = NT.Fonts.subheadlineBold,
                            color = NT.Colors.ink,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (stats.entries.size >= 2) {
                BodyWeightChart(
                    raw = stats.entries.takeLast(28).map { it.kg },
                    smoothed = stats.smoothed.takeLast(28),
                    modifier = Modifier.size(width = 120.dp, height = 48.dp),
                )
            }
        }
    }
}

/** "+0,4 kg over 4 weeks · logged 21 of 28 days" — the delta run is ember. */
@Composable
fun BodyDeltaLine(
    stats: BodyStats,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    val delta = stats.delta4w
    val tail = stringResource(S.progress_over4Weeks_n, stats.loggedLast28)
    // iOS puts `.font(footnote).lineLimit(1).minimumScaleFactor(0.85)` on the whole HStack, so
    // both runs shrink together. Two independently auto-sizing texts could settle at different
    // sizes inside one line, so this is one text with two spans instead — the 4 pt HStack gap
    // becomes the space between them.
    val line = remember(delta, tail, unit) {
        buildAnnotatedString {
            if (delta != null) {
                withStyle(
                    SpanStyle(color = NT.Colors.ember, fontFeatureSettings = "tnum"),
                ) {
                    append(Fmt.signedWeight(delta, unit, withUnit = true))
                }
                append(" ")
            }
            withStyle(SpanStyle(color = NT.Colors.ink2)) { append(tail) }
        }
    }
    BasicText(
        text = line,
        modifier = modifier,
        style = FootnoteTightLine,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = FootnoteAutoSize,
    )
}

/** Body tab: the full trend chart, Log weight, and the ten most recent readings. */
@Composable
fun BodyTab(
    stats: BodyStats,
    unit: WeightUnit,
    onLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recent = stats.entries.takeLast(90)
    val recentSmoothed = stats.smoothed.takeLast(90)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
    ) {
        NTCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow(stringResource(S.progress_bodyWeightTrend))
                    val latest = stats.latest
                    if (latest != null) {
                        // One baseline row: value, unit, spacer, day — exactly the iOS
                        // `HStack(alignment: .firstTextBaseline)`.
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NtText(
                                text = Fmt.weight(latest.kg, unit, withUnit = false),
                                modifier = Modifier.alignByBaseline(),
                                style = NT.Fonts.display(40).tabular(),
                                color = NT.Colors.ink,
                                maxLines = 1,
                            )
                            NtText(
                                text = unit.raw,
                                modifier = Modifier.alignByBaseline(),
                                style = NT.Fonts.subheadline,
                                color = NT.Colors.ink2,
                                maxLines = 1,
                            )
                            Spacer(Modifier.weight(1f))
                            NtText(
                                text = Fmt.dayMonth(latest.day),
                                modifier = Modifier.alignByBaseline(),
                                style = NT.Fonts.footnote.tabular(),
                                color = NT.Colors.ink2,
                                maxLines = 1,
                            )
                        }
                        BodyDeltaLine(stats = stats, unit = unit)
                    } else {
                        NtText(
                            text = stringResource(S.progress_noWeightYet),
                            style = NT.Fonts.subheadline,
                            color = NT.Colors.ink2,
                        )
                    }
                }
                if (recent.size >= 2) {
                    BodyWeightChart(
                        raw = recent.map { it.kg },
                        smoothed = recentSmoothed,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        dates = recent.map { it.day },
                        showsAxes = true,
                        unit = unit,
                    )
                }
                PrimaryButton(
                    title = stringResource(S.progress_logWeight),
                    height = NT.Size.cardButton,
                    onClick = onLog,
                )
            }
        }

        if (recent.isNotEmpty()) {
            val rows = recent.takeLast(10).reversed()
            Column {
                SectionHeader(
                    title = stringResource(S.progress_recentWeights),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                rows.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(NT.Size.control),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NtText(
                            text = Fmt.longDay(entry.day),
                            style = NT.Fonts.subheadline,
                            color = NT.Colors.ink,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        TabularText(
                            text = Fmt.weight(entry.kg, unit),
                            style = NT.Fonts.subheadline,
                            color = NT.Colors.ink,
                        )
                    }
                    if (index < rows.size - 1) Hairline()
                }
            }
        }
    }
}

// MARK: - Shared bits

/** `display(40)` weight with the unit in `subheadline`, first baselines aligned. */
@Composable
private fun WeightHeadline(kg: Double, unit: WeightUnit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NtText(
            text = Fmt.weight(kg, unit, withUnit = false),
            modifier = Modifier.alignByBaseline(),
            style = NT.Fonts.display(40).tabular(),
            color = NT.Colors.ink,
            maxLines = 1,
        )
        NtText(
            text = unit.raw,
            modifier = Modifier.alignByBaseline(),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
    }
}

/**
 * `footnote` with the **iOS line box**, not the HIG one.
 *
 * `Theme.swift:44` is `Font.system(size: 13, weight: .regular)` — a plain system font, so
 * SwiftUI lays the delta line out in `UIFont.systemFont(ofSize: 13).lineHeight` (15.5 pt,
 * rounded up to a 16 pt view height), **not** the 18 pt `.footnote` text-style leading the
 * ramp in `NT.kt` encodes. On the 3× parity captures that is 48 px against our 54 px, and
 * the 6 px lands entirely below the "over 4 weeks · logged…" line: it made the compact
 * `BodyWeightCard` 2 dp taller than iOS (09-progress) and pushed the Body tab's "Log
 * weight" button, and the card bottom with it, 2 dp down (20-progress-body).
 *
 * Overriding it here keeps the fix inside Progress; the ramp itself (every `sans()` entry
 * carries the HIG leading rather than the `Font.system` line box) is a design-system call.
 */
private val FootnoteTightLine = NT.Fonts.footnote.copy(lineHeight = 16.sp)

/** `footnote` with iOS's `minimumScaleFactor(0.85)` — 13 sp shrinking to 11.05 sp. */
private val FootnoteAutoSize = TextAutoSize.StepBased(
    minFontSize = 11.05.sp,
    maxFontSize = 13.sp,
    stepSize = 0.25.sp,
)
