package app.notomorrow.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.R
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import java.time.LocalDate
import kotlin.math.max

/**
 * Canvas ports of `Features/Progress/ProgressCharts.swift`. No chart library —
 * every interpolation in the app is `.linear` and every axis, PR dot and
 * "this week" label is bespoke (research §5.7).
 *
 * Sizes come from the call site, exactly like SwiftUI's `.frame(...)`.
 */

// MARK: - Sparkline (72x24 in lift rows, 120x48 in the body card)

@Composable
fun SparklineChart(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier.size(width = 72.dp, height = 24.dp),
    lineWidth: Dp = 2.dp,
    showsEndDot: Boolean = true,
) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val domain = ChartScale.padded(values)
        val lastIndex = max(1, values.size - 1)
        val points = values.mapIndexed { index, value ->
            Offset(
                x = size.width * index / lastIndex,
                y = size.height * (1f - domain.fraction(value)),
            )
        }
        if (points.size > 1) {
            drawPath(
                path = linePath(points),
                color = color,
                style = Stroke(
                    width = lineWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
        if (showsEndDot) {
            drawCircle(color, radius = symbolRadius(24.0).dp.toPx(), center = points.last())
        }
    }
}

// MARK: - Body weight: raw readings under the 7-day moving average

@Composable
fun BodyWeightChart(
    raw: List<Double>,
    smoothed: List<Double>,
    modifier: Modifier = Modifier,
    dates: List<LocalDate> = emptyList(),
    showsAxes: Boolean = false,
    unit: WeightUnit = WeightUnit.Kg,
) {
    val measurer = rememberTextMeasurer()
    val domain = ChartScale.padded(raw + smoothed)
    val yTicks = if (showsAxes) ChartScale.niceTicks(domain, 3) else emptyList()
    val yLabels = yTicks.map { Fmt.weight(it, unit, withUnit = false) }
    val xLabels = if (showsAxes && dates.size > 1) {
        listOf(Fmt.dayMonth(dates.first()), Fmt.dayMonth(dates.last()))
    } else {
        emptyList()
    }

    Canvas(modifier) {
        if (raw.isEmpty() && smoothed.isEmpty()) return@Canvas
        val yLayouts = yLabels.map { measurer.measureAxis(it) }
        val xLayouts = xLabels.map { measurer.measureAxis(it) }
        val axisGap = 4.dp.toPx()
        val plotRight = size.width -
            (yLayouts.maxOfOrNull { it.size.width.toFloat() + axisGap } ?: 0f)
        val plotBottom = size.height -
            (xLayouts.maxOfOrNull { it.size.height.toFloat() + axisGap } ?: 0f)
        if (plotRight <= 0f || plotBottom <= 0f) return@Canvas

        // Y grid + trailing labels
        yTicks.forEachIndexed { index, tick ->
            val y = plotBottom * (1f - domain.fraction(tick))
            drawLine(
                color = NT.Colors.hairline,
                start = Offset(0f, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = yLayouts[index]
            drawAxisLabel(
                layout = layout,
                x = size.width,
                top = y - layout.size.height / 2f,
                anchor = ChartLabelAnchor.Trailing,
            )
        }

        val lastIndex = max(1, raw.size - 1)
        fun pointsOf(series: List<Double>): List<Offset> = series.mapIndexed { index, value ->
            Offset(
                x = plotRight * index / lastIndex,
                y = plotBottom * (1f - domain.fraction(value)),
            )
        }

        val rawPoints = pointsOf(raw)
        if (rawPoints.size > 1) {
            drawPath(
                path = linePath(rawPoints),
                color = NT.Colors.ink2.copy(alpha = NT.Colors.ink2.alpha * 0.6f),
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        val avgPoints = pointsOf(smoothed)
        if (avgPoints.size > 1) {
            drawPath(
                path = linePath(avgPoints),
                color = NT.Colors.ember,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        if (avgPoints.isNotEmpty()) {
            // `Circle().fill(ember).overlay(Circle().strokeBorder(surface, 2))`
            val r = symbolRadius(56.0).dp.toPx()
            val ring = 2.dp.toPx()
            drawCircle(NT.Colors.ember, radius = r, center = avgPoints.last())
            drawCircle(
                color = NT.Colors.surface,
                radius = r - ring / 2f,
                center = avgPoints.last(),
                style = Stroke(ring),
            )
        }

        // X labels: first anchored topLeading, last topTrailing
        if (xLayouts.size == 2) {
            val top = plotBottom + axisGap
            drawAxisLabel(xLayouts[0], 0f, top, ChartLabelAnchor.Leading)
            drawAxisLabel(xLayouts[1], plotRight, top, ChartLabelAnchor.Trailing)
        }
    }
}

// MARK: - e1RM line + area with PR marks

@Composable
fun E1RMChart(
    points: List<E1RMChartPoint>,
    modifier: Modifier = Modifier,
    unit: WeightUnit = WeightUnit.Kg,
) {
    val measurer = rememberTextMeasurer()
    val domain = ChartScale.padded(points.map { it.e1RM }, bottom = 0.08, top = 0.06)
    val yTicks = ChartScale.niceTicks(domain, 3)
    val yLabels = yTicks.map { Fmt.weight(it, unit, withUnit = false) }
    val xTicks = xAxisTicks(points, 4)
    val xLabels = xTicks.map { Fmt.dayMonth(it.second) }

    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val yLayouts = yLabels.map { measurer.measureAxis(it) }
        val xLayouts = xLabels.map { measurer.measureAxis(it) }
        val axisGap = 4.dp.toPx()
        val plotRight = size.width -
            (yLayouts.maxOfOrNull { it.size.width.toFloat() + axisGap } ?: 0f)
        val plotBottom = size.height -
            (xLayouts.maxOfOrNull { it.size.height.toFloat() + axisGap } ?: 0f)
        if (plotRight <= 0f || plotBottom <= 0f) return@Canvas

        // Scaled by timestamp, not by day: two sessions of the same lift on one day get two x
        // positions, exactly as Swift Charts plots them.
        val firstAt = points.first().atMillis.toDouble()
        val lastAt = points.last().atMillis.toDouble()
        val timeSpan = (lastAt - firstAt).let { if (it > 0.0) it else 1.0 }
        fun xOf(atMillis: Long): Float =
            (plotRight * ((atMillis - firstAt) / timeSpan)).toFloat()

        yTicks.forEachIndexed { index, tick ->
            val y = plotBottom * (1f - domain.fraction(tick))
            drawLine(
                color = NT.Colors.hairline,
                start = Offset(0f, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = yLayouts[index]
            drawAxisLabel(
                layout = layout,
                x = size.width,
                top = y - layout.size.height / 2f,
                anchor = ChartLabelAnchor.Trailing,
            )
        }

        val marks = points.map {
            Offset(xOf(it.atMillis), plotBottom * (1f - domain.fraction(it.e1RM)))
        }
        if (marks.size > 1) {
            // The app's second and last gradient: ember 0.22 -> ember 0.
            val top = marks.minOf { it.y }
            drawPath(
                path = areaPath(marks, plotBottom),
                brush = Brush.verticalGradient(
                    colors = listOf(
                        NT.Colors.ember.copy(alpha = 0.22f),
                        NT.Colors.ember.copy(alpha = 0f),
                    ),
                    startY = top,
                    endY = plotBottom,
                ),
            )
            drawPath(
                path = linePath(marks),
                color = NT.Colors.ember,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        points.forEachIndexed { index, point ->
            if (!point.isPR) return@forEachIndexed
            val isLatest = index == points.lastIndex
            val r = symbolRadius(if (isLatest) 110.0 else 60.0).dp.toPx()
            val ring = 2.dp.toPx()
            val centre = marks[index]
            if (isLatest) {
                drawCircle(NT.Colors.ember, radius = r, center = centre)
                drawCircle(NT.Colors.ground, radius = r - ring / 2f, center = centre, style = Stroke(ring))
            } else {
                drawCircle(NT.Colors.ground, radius = r, center = centre)
                drawCircle(NT.Colors.ember, radius = r - ring / 2f, center = centre, style = Stroke(ring))
            }
        }

        val top = plotBottom + axisGap
        xLayouts.forEachIndexed { index, layout ->
            val x = xOf(xTicks[index].first)
            val anchor = when (index) {
                0 -> ChartLabelAnchor.Leading
                xLayouts.lastIndex -> ChartLabelAnchor.Trailing
                else -> ChartLabelAnchor.Center
            }
            drawAxisLabel(layout, x, top, anchor, clampTo = plotRight)
        }
    }
}

/**
 * `AxisMarks(values: .automatic(desiredCount:))` over the point's **time** domain: evenly spaced
 * instants, each labelled with the day it falls in. Returns `(atMillis, day)` so the tick is
 * placed on the same millis scale the marks use.
 *
 * The day is counted from the first session's own day, so the chart needs no time zone: the tick
 * `n` days after the first session is labelled `firstDay + n`.
 */
private fun xAxisTicks(
    points: List<E1RMChartPoint>,
    desiredCount: Int,
): List<Pair<Long, LocalDate>> {
    if (points.isEmpty() || desiredCount <= 0) return emptyList()
    val first = points.first()
    val span = points.last().atMillis - first.atMillis
    if (span <= 0L) return listOf(first.atMillis to first.date)
    val steps = desiredCount - 1
    return (0..steps).map { step ->
        val at = first.atMillis + Math.round(span.toDouble() * step / steps)
        at to first.date.plusDays(Math.round((at - first.atMillis) / MILLIS_PER_DAY))
    }
}

private const val MILLIS_PER_DAY = 86_400_000.0

// MARK: - Weekly volume bars (last 8 ISO weeks)

@Composable
fun WeeklyVolumeChart(
    weeks: List<WeekVolumeBar>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val thisWeek = stringResource(R.string.progress_thisWeek)
    val firstLabel = weeks.firstOrNull()?.let {
        if (it.weekStart == weeks.last().weekStart) thisWeek else Fmt.dayMonth(it.weekStart)
    }
    val lastLabel = weeks.lastOrNull()?.let { thisWeek }

    Canvas(modifier) {
        if (weeks.isEmpty()) return@Canvas
        val firstLayout = firstLabel?.let { measurer.measureAxis(it) }
        val lastLayout = lastLabel?.let { measurer.measureAxis(it) }
        val axisGap = 4.dp.toPx()
        val labelHeight = maxOf(
            firstLayout?.size?.height?.toFloat() ?: 0f,
            lastLayout?.size?.height?.toFloat() ?: 0f,
        )
        val plotBottom = size.height - (if (labelHeight > 0f) labelHeight + axisGap else 0f)
        if (plotBottom <= 0f) return@Canvas

        val maxVolume = max(1.0, (weeks.maxOfOrNull { it.volumeKg } ?: 0.0) * 1.05)
        val band = size.width / weeks.size
        val barWidth = band * 0.66f
        val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        weeks.forEachIndexed { index, week ->
            val height = (plotBottom * (week.volumeKg / maxVolume)).toFloat().coerceAtLeast(0f)
            if (height <= 0f) return@forEachIndexed
            drawRoundRect(
                color = if (week.isCurrent) NT.Colors.ember else NT.Colors.surface2,
                topLeft = Offset(band * index + (band - barWidth) / 2f, plotBottom - height),
                size = Size(barWidth, height),
                cornerRadius = corner,
            )
        }

        // 1 dp hairline baseline overlaid at the bottom of the plot.
        drawLine(
            color = NT.Colors.hairline,
            start = Offset(0f, plotBottom),
            end = Offset(size.width, plotBottom),
            strokeWidth = 1.dp.toPx(),
        )

        val top = plotBottom + axisGap
        firstLayout?.let { drawAxisLabel(it, 0f, top, ChartLabelAnchor.Leading) }
        lastLayout?.let { drawAxisLabel(it, size.width, top, ChartLabelAnchor.Trailing) }
    }
}

