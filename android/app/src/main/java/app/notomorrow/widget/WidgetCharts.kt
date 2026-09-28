package app.notomorrow.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.notomorrow.designsystem.NT
import app.notomorrow.feature.dashboard.WeekStripDot
import app.notomorrow.feature.dashboard.isUpcomingGymDay
import app.notomorrow.feature.dashboard.weekStripDots
import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.service.DayState
import app.notomorrow.service.WeekDay
import app.notomorrow.util.Fmt
import java.util.Locale
import kotlin.math.floor

/**
 * The two grids the widgets draw as bitmaps: the Fuel calendar (a GitHub-style contribution
 * graph in `heat` colours with a dot on the days you trained, `docs/widgets.md` "Fuel calendar")
 * and the Mon…Sun strip (`WeekStripView`).
 */
internal object WidgetCharts {

    /** Cell gap as a share of the cell pitch (GitHub: 3 px on 13), and the pitch's bounds. */
    private const val GAP_RATIO = 0.22f
    private const val MAX_PITCH_DP = 22f
    private const val MIN_TWO_BAND_PITCH_DP = 15f

    /** The gap between two bands of weeks. */
    private const val BAND_GAP_DP = 12f

    /** The most weeks any size shows (a year); `WidgetData.calendar` reads this far back. */
    const val MAX_WEEKS = 53

    /**
     * The Fuel calendar as a GitHub contribution graph, squares only: one column per week, Monday
     * on top, the current week at the right. Each day is a small rounded square in its `heat`
     * colour, a day you trained gets an `ink` dot in the middle, today an `ink` ring; days still
     * ahead are left out. Cells are square and as big as the height allows (at most [MAX_PITCH_DP]
     * a pitch); when that would leave the grid only a few weeks wide, the weeks wrap into a second
     * band under the first (the older half on top). The grid is centred in the bitmap.
     */
    fun contributions(context: Context, widthDp: Float, heightDp: Float, data: CalendarData): WidgetBitmaps.Sized {
        val (bitmap, canvas) = WidgetBitmaps.canvas(widthDp, heightDp, WidgetBitmaps.scale(context))
        fun pitchFor(bands: Int) = (heightDp - (bands - 1) * BAND_GAP_DP) / (bands * 7 - GAP_RATIO)
        val bands = if (pitchFor(1) > MAX_PITCH_DP && pitchFor(2) >= MIN_TWO_BAND_PITCH_DP) 2 else 1
        val pitch = minOf(pitchFor(bands), MAX_PITCH_DP).coerceAtLeast(4f)
        val gap = pitch * GAP_RATIO
        val cell = pitch - gap
        val perBand = floor((widthDp + gap) / pitch).toInt().coerceIn(1, MAX_WEEKS / bands)
        val layout = FuelCalendar.layout(data.today, perBand * bands)

        val bandHeight = 7 * pitch - gap
        val left = (widthDp - (perBand * pitch - gap)) / 2f
        val top = (heightDp - (bands * bandHeight + (bands - 1) * BAND_GAP_DP)) / 2f

        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val dotPaint = WidgetBitmaps.paint(NT.Colors.ink)
        val ringPaint = WidgetBitmaps.paint(NT.Colors.ink).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val radius = cell * 0.24f
        val dot = (cell * 0.2f).coerceAtLeast(1.75f)
        val rect = RectF()
        for (band in 0 until bands) {
            val bandTop = top + band * (bandHeight + BAND_GAP_DP)
            for (column in 0 until perBand) {
                val days = layout.columns.getOrNull(band * perBand + column) ?: continue
                for (row in 0 until 7) {
                    val date = days[row] ?: continue
                    val x = left + column * pitch
                    val y = bandTop + row * pitch
                    rect.set(x, y, x + cell, y + cell)
                    cellPaint.color = WidgetBitmaps.paint(NT.Colors.heat[data.level(date)]).color
                    canvas.drawRoundRect(rect, radius, radius, cellPaint)
                    if (date in data.trainedDays) canvas.drawCircle(rect.centerX(), rect.centerY(), dot, dotPaint)
                    if (date == data.today) {
                        rect.inset(0.75f, 0.75f)
                        canvas.drawRoundRect(rect, radius, radius, ringPaint)
                    }
                }
            }
        }
        return WidgetBitmaps.Sized(bitmap, widthDp, heightDp)
    }

    /**
     * The Mon…Sun circles (`WeekStripView`): attended = ember fill with a dark check; missed or
     * cancelled = rose 1.5 dp ring with a rose ×; today = white fill with the date in `ground`; a
     * planned or confirmed gym day ahead = 1 dp `border` ring around an `ink` number; rest days the
     * number in `ink3`. Without [numbers] (the 12 dp small strip) a rest day is a `surface2` dot.
     * [dots] adds the 5 dp row of who-trained dots under each circle.
     */
    fun weekStrip(
        context: Context,
        widthDp: Float,
        circleDp: Float,
        days: List<WeekDay>,
        isPaired: Boolean,
        numbers: Boolean,
        dots: Boolean,
        locale: Locale,
        labels: List<String>? = null,
    ): WidgetBitmaps.Sized {
        val dotRow = if (dots) DOT_GAP_DP + DOT_DP else 0f
        val labelSp = (circleDp * 0.42f).coerceIn(9f, 11f)
        val labelRow = if (labels != null) labelSp + LABEL_GAP_DP else 0f
        val heightDp = labelRow + circleDp + dotRow
        val (bitmap, canvas) = WidgetBitmaps.canvas(widthDp, heightDp, WidgetBitmaps.scale(context))
        // Weekday letters over the columns, today's in `ink`; the strip itself below them.
        if (labels != null) {
            val labelPaint = WidgetBitmaps.textPaint(Typeface.create("sans-serif-medium", Typeface.NORMAL), labelSp, NT.Colors.ink3, false)
            val m = labelPaint.fontMetrics
            days.take(7).forEachIndexed { i, day ->
                labelPaint.color = WidgetBitmaps.paint(if (day.isToday) NT.Colors.ink else NT.Colors.ink3).color
                canvas.drawText(labels.getOrElse(i) { "" }, widthDp / 7f * (i + 0.5f), -m.ascent * 0.92f, labelPaint)
            }
            canvas.translate(0f, labelRow)
        }
        val column = widthDp / 7f
        val r = circleDp / 2f
        val numberPaint = WidgetBitmaps.textPaint(
            Typeface.create("sans-serif-medium", Typeface.NORMAL),
            circleDp * 0.46f,
            NT.Colors.ink,
        )
        days.take(7).forEachIndexed { i, day ->
            val cx = column * (i + 0.5f)
            val cy = r
            val number = Fmt.dayOfMonth(day.date, locale)
            fun drawNumber(color: androidx.compose.ui.graphics.Color) {
                if (!numbers) return
                numberPaint.color = WidgetBitmaps.paint(color).color
                val m = numberPaint.fontMetrics
                canvas.drawText(number, cx, cy - (m.ascent + m.descent) / 2f, numberPaint)
            }
            fun ring(color: androidx.compose.ui.graphics.Color, width: Float) {
                val paint = WidgetBitmaps.paint(color).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = width
                }
                canvas.drawCircle(cx, cy, r - width / 2f, paint)
            }
            when {
                day.isToday -> {
                    canvas.drawCircle(cx, cy, r, WidgetBitmaps.paint(NT.Colors.ink))
                    drawNumber(NT.Colors.ground)
                }
                day.myState is DayState.Attended -> {
                    canvas.drawCircle(cx, cy, r, WidgetBitmaps.paint(NT.Colors.ember))
                    if (numbers) WidgetBitmaps.glyph(canvas, cx, cy, circleDp * 0.6f, check = true, color = NT.Colors.ground)
                }
                day.myState.isMissedOrCancelled -> {
                    ring(NT.Colors.bad, 1.5f)
                    if (numbers) WidgetBitmaps.glyph(canvas, cx, cy, circleDp * 0.5f, check = false, color = NT.Colors.bad)
                }
                isUpcomingGymDay(day) -> {
                    ring(NT.Colors.border, 1f)
                    drawNumber(NT.Colors.ink)
                }
                numbers -> drawNumber(NT.Colors.ink3)
                else -> canvas.drawCircle(cx, cy, r, WidgetBitmaps.paint(NT.Colors.surface2))
            }
            if (dots) {
                val marks = weekStripDots(day, isPaired)
                val total = marks.size * DOT_DP + (marks.size - 1).coerceAtLeast(0) * DOT_SPACING_DP
                var x = cx - total / 2f + DOT_DP / 2f
                val y = circleDp + DOT_GAP_DP + DOT_DP / 2f
                for (mark in marks) {
                    val color = when (mark) {
                        WeekStripDot.You -> NT.Colors.ember
                        WeekStripDot.PartnerTrained -> NT.Colors.good
                        WeekStripDot.PartnerMissed -> NT.Colors.bad
                    }
                    canvas.drawCircle(x, y, DOT_DP / 2f, WidgetBitmaps.paint(color))
                    x += DOT_DP + DOT_SPACING_DP
                }
            }
        }
        return WidgetBitmaps.Sized(bitmap, widthDp, heightDp)
    }

    private const val LABEL_GAP_DP = 6f
    private const val DOT_DP = 5f
    private const val DOT_SPACING_DP = 3f
    private const val DOT_GAP_DP = 6f
}
