package app.notomorrow.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.notomorrow.designsystem.NT
import app.notomorrow.feature.dashboard.WeekStripDot
import app.notomorrow.feature.dashboard.isUpcomingGymDay
import app.notomorrow.feature.dashboard.weekStripDots
import app.notomorrow.service.DayState
import app.notomorrow.service.WeekDay
import app.notomorrow.util.Fmt
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

/**
 * The two grids the widgets draw as bitmaps: the Fuel calendar (the recent weeks in `heat`
 * colours with trained days on top, `docs/widgets.md` "Fuel calendar") and the Mon…Sun strip
 * (`WeekStripView`).
 */
internal object WidgetCharts {

    /** Gap between calendar cells, their corner radius and the shortest row worth drawing. */
    private const val CELL_GAP_DP = 4f
    private const val CELL_RADIUS_DP = 8f
    private const val MIN_ROW_DP = 24f

    /** The most weeks any size shows; `WidgetData.calendar` reads this far back. */
    const val MAX_ROWS = 8

    /** The weekday-letter band above the calendar. */
    private const val WEEKDAY_BAND_DP = 18f

    /**
     * The Fuel calendar as a calendar: weekday letters (Monday first, today's in `ink`) over the
     * weeks up to this one, one row per week, the current week at the bottom. As many weeks as fit
     * [heightDp] with rows at least [MIN_ROW_DP] tall, and no more than would make the cells taller
     * than wide. Every cell is a rounded tile in its `heat` colour with the date in the display face
     * (the 1st shows the month's short name); a trained day adds an `ink` dot, today an `ink` ring,
     * and days still ahead are just the date in `ink3`.
     */
    fun calendar(
        context: Context,
        widthDp: Float,
        heightDp: Float,
        data: CalendarData,
        weekdayLetters: List<String>,
        locale: Locale,
    ): WidgetBitmaps.Sized {
        val gap = CELL_GAP_DP
        val cellWidth = (widthDp + gap) / 7f - gap
        val available = heightDp - WEEKDAY_BAND_DP
        val bySquare = Math.round((available + gap) / (cellWidth + gap))
        val byMin = floor((available + gap) / (MIN_ROW_DP + gap)).toInt()
        val rows = minOf(byMin, maxOf(bySquare, 4)).coerceIn(2, MAX_ROWS)
        val cellHeight = (available + gap) / rows - gap

        val (bitmap, canvas) = WidgetBitmaps.canvas(widthDp, heightDp, WidgetBitmaps.scale(context))
        val letterPaint = WidgetBitmaps.textPaint(Typeface.create("sans-serif-medium", Typeface.BOLD), 12f, NT.Colors.ink3, false)
        val lm = letterPaint.fontMetrics
        val todayColumn = data.today.dayOfWeek.value - 1
        for (c in 0 until 7) {
            letterPaint.color = WidgetBitmaps.paint(if (c == todayColumn) NT.Colors.ink else NT.Colors.ink3).color
            val cx = c * (cellWidth + gap) + cellWidth / 2f
            canvas.drawText(weekdayLetters.getOrElse(c) { "" }, cx, (WEEKDAY_BAND_DP - 4f) / 2f - (lm.ascent + lm.descent) / 2f, letterPaint)
        }

        val thisMonday = data.today.minusDays((data.today.dayOfWeek.value - 1).toLong())
        val first = thisMonday.minusWeeks((rows - 1).toLong())
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val dotPaint = WidgetBitmaps.paint(NT.Colors.ink)
        val ringPaint = WidgetBitmaps.paint(NT.Colors.ink).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val numberSp = minOf(cellHeight * 0.52f, cellWidth * 0.42f).coerceIn(10f, 22f)
        val numberPaint = WidgetBitmaps.textPaint(WidgetBitmaps.display(context), numberSp, NT.Colors.ink)
        val nm = numberPaint.fontMetrics
        val monthFormat = DateTimeFormatter.ofPattern("LLL", locale)
        val dot = (minOf(cellWidth, cellHeight) * 0.12f).coerceIn(2.5f, 4f)
        val radius = minOf(CELL_RADIUS_DP, cellHeight / 3f)
        val rect = RectF()
        for (r in 0 until rows) {
            for (c in 0 until 7) {
                val date = first.plusDays((r * 7 + c).toLong())
                val left = c * (cellWidth + gap)
                val top = WEEKDAY_BAND_DP + r * (cellHeight + gap)
                rect.set(left, top, left + cellWidth, top + cellHeight)
                val future = date > data.today
                val level = if (future) 0 else data.level(date)
                if (!future) {
                    cellPaint.color = WidgetBitmaps.paint(NT.Colors.heat[level]).color
                    canvas.drawRoundRect(rect, radius, radius, cellPaint)
                }
                val text = if (date.dayOfMonth == 1) {
                    monthFormat.format(date).trimEnd('.').uppercase(locale)
                } else {
                    Fmt.dayOfMonth(date, locale)
                }
                val paint = Paint(numberPaint).apply {
                    color = WidgetBitmaps.paint(
                        when {
                            future -> NT.Colors.ink3
                            level == 0 -> NT.Colors.ink2
                            else -> NT.Colors.ink
                        },
                    ).color
                    val measured = measureText(text)
                    if (measured > cellWidth - 6f) textSize = textSize * (cellWidth - 6f) / measured
                }
                val trained = !future && date in data.trainedDays
                // With room, the trained dot sits under the date; in a short row, beside it.
                val below = cellHeight >= numberSp * 1.25f + 3f * dot + 4f
                val textWidth = paint.measureText(text)
                val cx = rect.centerX() - if (trained && !below) dot * 1.5f else 0f
                val cy = rect.centerY() - if (trained && below) dot * 1.5f else 0f
                canvas.drawText(text, cx, cy - (nm.ascent + nm.descent) / 2f * paint.textSize / numberSp, paint)
                if (trained) {
                    if (below) {
                        canvas.drawCircle(rect.centerX(), cy + numberSp * 0.5f + dot * 1.4f, dot, dotPaint)
                    } else {
                        canvas.drawCircle(cx + textWidth / 2f + dot * 2f, rect.centerY(), dot, dotPaint)
                    }
                }
                if (date == data.today) {
                    rect.inset(1f, 1f)
                    canvas.drawRoundRect(rect, radius - 1f, radius - 1f, ringPaint)
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
