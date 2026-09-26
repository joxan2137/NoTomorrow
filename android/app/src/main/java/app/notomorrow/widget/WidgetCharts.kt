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
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

/**
 * The two grids the widgets draw as bitmaps: the Fuel history heat grid with trained days on top
 * (`FuelCalendar`, `docs/widgets.md` "Fuel calendar") and the Mon…Sun strip (`WeekStripView`).
 */
internal object WidgetCharts {

    /** Gap between cells as a share of the side (⅕), and the corner radius (¼). */
    private const val GAP = 0.2f
    private const val RADIUS = 0.25f

    /** Trained-day dot, as a share of the side. */
    private const val DOT = 0.38f

    /** Height of the month-label band above the large grid. */
    private const val MONTH_BAND_DP = 16f

    /**
     * As many whole Monday-first weeks as fit [widthDp] (at most [FuelCalendar.WEEKS]), oldest left,
     * square cells filling [heightDp] (less the month band when [months]); future days blank.
     * Today's cell gets a 1.5 dp `ink` ring, a trained day a centred `ink` dot (on `heat[0]` when
     * nothing was logged).
     */
    fun heatGrid(
        context: Context,
        widthDp: Float,
        heightDp: Float,
        data: CalendarData,
        months: Boolean,
        locale: Locale,
    ): WidgetBitmaps.Sized {
        val band = if (months) MONTH_BAND_DP else 0f
        val gridHeight = (heightDp - band).coerceAtLeast(7f)
        val side = gridHeight / (7 + 6 * GAP)
        val gap = side * GAP
        val weeks = floor((widthDp + gap) / (side + gap)).toInt().coerceIn(1, FuelCalendar.WEEKS)
        val gridWidth = weeks * side + (weeks - 1) * gap
        val x0 = ((widthDp - gridWidth) / 2f).coerceAtLeast(0f)
        val layout = FuelCalendar.layout(data.today, weeks)

        val (bitmap, canvas) = WidgetBitmaps.canvas(widthDp, heightDp, WidgetBitmaps.scale(context))
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val dotPaint = WidgetBitmaps.paint(NT.Colors.ink)
        val ringPaint = WidgetBitmaps.paint(NT.Colors.ink).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val rect = RectF()
        layout.columns.forEachIndexed { c, column ->
            column.forEachIndexed { r, date ->
                if (date == null) return@forEachIndexed
                val left = x0 + c * (side + gap)
                val top = band + r * (side + gap)
                rect.set(left, top, left + side, top + side)
                cellPaint.color = WidgetBitmaps.paint(NT.Colors.heat[data.level(date)]).color
                canvas.drawRoundRect(rect, side * RADIUS, side * RADIUS, cellPaint)
                if (date in data.trainedDays) {
                    canvas.drawCircle(rect.centerX(), rect.centerY(), side * DOT / 2f, dotPaint)
                }
                if (date == data.today) {
                    rect.inset(0.75f, 0.75f)
                    val radius = (side * RADIUS - 0.75f).coerceAtLeast(0f)
                    canvas.drawRoundRect(rect, radius, radius, ringPaint)
                }
            }
        }

        if (months) {
            val paint = WidgetBitmaps.textPaint(Typeface.create("sans-serif-medium", Typeface.NORMAL), 12f, NT.Colors.ink3, false)
                .apply { textAlign = Paint.Align.LEFT }
            val format = DateTimeFormatter.ofPattern("LLL", locale)
            var nextFree = 0f
            for (label in layout.monthLabels) {
                // Only the columns that really hold a 1st.
                if (label.month !in layout.columns[label.column]) continue
                val x = x0 + label.column * (side + gap)
                if (x < nextFree) continue
                val text = format.format(label.month)
                canvas.drawText(text, x, band - 4f, paint)
                nextFree = x + paint.measureText(text) + 4f
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
    ): WidgetBitmaps.Sized {
        val dotRow = if (dots) DOT_GAP_DP + DOT_DP else 0f
        val heightDp = circleDp + dotRow
        val (bitmap, canvas) = WidgetBitmaps.canvas(widthDp, heightDp, WidgetBitmaps.scale(context))
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

    private const val DOT_DP = 5f
    private const val DOT_SPACING_DP = 3f
    private const val DOT_GAP_DP = 6f
}
