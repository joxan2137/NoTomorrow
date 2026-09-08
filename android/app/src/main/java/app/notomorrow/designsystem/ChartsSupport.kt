package app.notomorrow.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// MARK: - Series types
//
// The charts take their own value types rather than the Progress feature's
// models, so `designsystem/` stays leaf-level. `feature/progress` maps
// `E1RMPoint` -> [E1RMChartPoint] and `WeekVolume` -> [WeekVolumeBar].

/** One session's best e1RM, and whether that session set a PR. */
@Immutable
data class E1RMChartPoint(
    /** The session's calendar day in the app's zone — the axis *label* domain. */
    val date: LocalDate,
    /**
     * The session's full timestamp, as epoch millis. iOS plots `.value("date", point.date)` at
     * `Date` resolution (`ProgressCharts.swift:105-112`), so an AM and a PM session of the same
     * lift on one day are two x positions; flattening to [date] would collapse them onto one and
     * draw a vertical segment with stacked PR markers. Millis rather than an `Instant` so the
     * chart needs no time zone of its own — [date] already carries the app's.
     */
    val atMillis: Long,
    val e1RM: Double,
    val isPR: Boolean,
)

/** One ISO week of training volume. */
@Immutable
data class WeekVolumeBar(
    val weekStart: LocalDate,
    val volumeKg: Double,
    val isCurrent: Boolean,
)

// MARK: - Scales

/** A y (or x) domain, the Compose stand-in for Swift Charts' `ClosedRange`. */
@Immutable
data class ChartDomain(val lo: Double, val hi: Double) {
    val span: Double get() = (hi - lo).let { if (it > 0.0) it else 1.0 }

    /** 0 at [lo], 1 at [hi]. */
    fun fraction(value: Double): Float = ((value - lo) / span).toFloat()
}

/** Port of `ProgressCharts.swift`'s `ChartScale`. */
object ChartScale {

    /**
     * Y domain with breathing room so line ends and dots are not clipped by the
     * plot edge — verbatim from Swift, including the `max(hi * 0.04, 1)` floor.
     */
    fun padded(values: List<Double>, bottom: Double = 0.15, top: Double = 0.15): ChartDomain {
        val lo = values.minOrNull() ?: return ChartDomain(0.0, 1.0)
        val hi = values.maxOrNull() ?: return ChartDomain(0.0, 1.0)
        val span = max(hi - lo, max(hi * 0.04, 1.0))
        return ChartDomain(lo - span * bottom, hi + span * top)
    }

    /** `AxisMarks(values: .automatic(desiredCount:))` — rounded steps inside the domain. */
    fun niceTicks(domain: ChartDomain, desiredCount: Int): List<Double> {
        if (desiredCount <= 0 || domain.span <= 0.0) return emptyList()
        val rough = domain.span / desiredCount
        if (rough <= 0.0 || !rough.isFinite()) return emptyList()
        val magnitude = 10.0.pow(floor(log10(rough)))
        val normalized = rough / magnitude
        val step = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 2.5 -> 2.5
            normalized <= 5.0 -> 5.0
            else -> 10.0
        } * magnitude
        val ticks = ArrayList<Double>(desiredCount + 2)
        var value = ceil(domain.lo / step) * step
        while (value <= domain.hi + step * 1e-6 && ticks.size < 16) {
            ticks.add(value)
            value += step
        }
        return ticks
    }
}

// MARK: - Marks

/**
 * Swift Charts' `symbolSize` is an **area** in square points, so the radius is
 * `sqrt(size / pi)` points: 24 -> 2.76, 56 -> 4.22, 60 -> 4.37, 110 -> 5.92.
 */
fun symbolRadius(symbolSize: Double): Float = sqrt(symbolSize / PI).toFloat()

/** `.interpolationMethod(.linear)` — every series in this app is a polyline. */
internal fun linePath(points: List<Offset>): Path {
    val path = Path()
    points.forEachIndexed { index, point ->
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    return path
}

/** `AreaMark`: the polyline closed down to the plot baseline. */
internal fun areaPath(points: List<Offset>, baselineY: Float): Path {
    if (points.isEmpty()) return Path()
    val path = linePath(points)
    path.lineTo(points.last().x, baselineY)
    path.lineTo(points.first().x, baselineY)
    path.close()
    return path
}

// MARK: - Axis text

/**
 * `ChartAxisText`: 12 pt **Regular** `ink2`, tabular — deliberately lighter than
 * `NT.Fonts.caption` (12 Medium).
 */
val ChartAxisTextStyle: TextStyle = TextStyle(
    fontFamily = NtSansFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    color = NT.Colors.ink2,
    fontFeatureSettings = "tnum",
    fontSynthesis = FontSynthesis.None,
)

/** `AxisValueLabel(anchor:)`. */
internal enum class ChartLabelAnchor { Leading, Center, Trailing }

internal fun TextMeasurer.measureAxis(text: String, style: TextStyle = ChartAxisTextStyle): TextLayoutResult =
    measure(text = text, style = style, maxLines = 1)

internal fun DrawScope.drawAxisLabel(
    layout: TextLayoutResult,
    x: Float,
    top: Float,
    anchor: ChartLabelAnchor,
    clampTo: Float? = null,
) {
    val width = layout.size.width.toFloat()
    var left = when (anchor) {
        ChartLabelAnchor.Leading -> x
        ChartLabelAnchor.Center -> x - width / 2f
        ChartLabelAnchor.Trailing -> x - width
    }
    if (clampTo != null) left = left.coerceIn(0f, max(0f, clampTo - width))
    drawText(layout, topLeft = Offset(left, top))
}

