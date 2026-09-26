package app.notomorrow.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import app.notomorrow.R
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.macroRingArcs
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max

/**
 * What Glance cannot draw — arcs, the display face — drawn with `android.graphics` into bitmaps
 * the widgets show through `Image(ImageProvider(bitmap))` at an explicit dp size.
 *
 * Bitmaps are rendered at the screen density, capped at 3×: RemoteViews carry every responsive
 * layout's bitmaps at once, and their memory is bounded by the screen size.
 */
internal object WidgetBitmaps {

    /** A rendered bitmap and the dp size to show it at. */
    class Sized(val bitmap: Bitmap, val widthDp: Float, val heightDp: Float)

    private const val MAX_SCALE = 3f

    @Volatile
    private var displayFace: Typeface? = null

    fun scale(context: Context): Float = context.resources.displayMetrics.density.coerceIn(1f, MAX_SCALE)

    /** Big Shoulders Display ExtraBold (`NT.Fonts.display`); the bold system face if it cannot load. */
    fun display(context: Context): Typeface =
        displayFace ?: (
            runCatching { ResourcesCompat.getFont(context, R.font.bigshouldersdisplay_extrabold) }.getOrNull()
                ?: Typeface.DEFAULT_BOLD
            ).also { displayFace = it }

    fun canvas(widthDp: Float, heightDp: Float, scale: Float): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(
            max(1, ceil(widthDp * scale).toInt()),
            max(1, ceil(heightDp * scale).toInt()),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        return bitmap to canvas
    }

    fun paint(color: Color): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }

    fun textPaint(typeface: Typeface, sizeSp: Float, color: Color, tabular: Boolean = true): Paint =
        paint(color).apply {
            this.typeface = typeface
            textSize = sizeSp
            textAlign = Paint.Align.CENTER
            if (tabular) fontFeatureSettings = "'tnum'"
        }

    /** A hero number (or any one line) in the display face, tight to its ink height. */
    fun displayText(context: Context, text: String, sizeSp: Float, color: Color = NT.Colors.ink): Sized {
        val paint = textPaint(display(context), sizeSp, color)
        val metrics = paint.fontMetrics
        val width = paint.measureText(text).coerceAtLeast(1f) + 2f
        val height = metrics.descent - metrics.ascent
        val (bitmap, canvas) = canvas(width, height, scale(context))
        canvas.drawText(text, width / 2f, -metrics.ascent, paint)
        return Sized(bitmap, width, height)
    }

    private fun ringStroke(color: Color, widthDp: Float): Paint = paint(color).apply {
        style = Paint.Style.STROKE
        strokeWidth = widthDp
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * `MacroRing`: a `surface2` track and the eaten kcal as protein / carbs / fat arcs (4 / 4 / 9 kcal
     * per gram) clockwise from 12 o'clock, full past the goal; kcal left inside in the display face,
     * and [label] under it when given.
     */
    fun macroRing(
        context: Context,
        sizeDp: Float,
        lineDp: Float,
        data: QuickLogData,
        number: String,
        numberSp: Float,
        label: String?,
        numberColor: Color = NT.Colors.ink,
        labelColor: Color = NT.Colors.ink2,
    ): Sized {
        val (bitmap, canvas) = canvas(sizeDp, sizeDp, scale(context))
        val inset = lineDp / 2f
        val oval = RectF(inset, inset, sizeDp - inset, sizeDp - inset)
        canvas.drawOval(oval, ringStroke(NT.Colors.surface2, lineDp))
        val circumference = (PI * oval.width()).toFloat()
        val gap = if (circumference > 0f) (lineDp + 3f) / circumference else 0f
        val colors = listOf(NT.Colors.protein, NT.Colors.carbs, NT.Colors.fat)
        for (arc in macroRingArcs(data.protein, data.carbs, data.fat, data.goals.kcal, gap)) {
            canvas.drawArc(
                oval,
                -90f + 360f * arc.start,
                360f * (arc.end - arc.start),
                false,
                ringStroke(colors[arc.macro], lineDp),
            )
        }
        val numberPaint = textPaint(display(context), numberSp, numberColor)
        val labelPaint = label?.let { textPaint(Typeface.create("sans-serif", Typeface.NORMAL), 12f, labelColor, false) }
        // Shrink a long number to the inner width (`ShrinkingText` in the app).
        val inner = sizeDp - 2 * lineDp - 8f
        val measured = numberPaint.measureText(number)
        if (measured > inner) numberPaint.textSize = numberPaint.textSize * inner / measured
        val nm = numberPaint.fontMetrics
        val numberHeight = nm.descent - nm.ascent
        val labelHeight = labelPaint?.fontMetrics?.let { it.descent - it.ascent } ?: 0f
        val top = (sizeDp - numberHeight - labelHeight) / 2f
        canvas.drawText(number, sizeDp / 2f, top - nm.ascent, numberPaint)
        if (label != null && labelPaint != null) {
            canvas.drawText(label, sizeDp / 2f, top + numberHeight - labelPaint.fontMetrics.ascent, labelPaint)
        }
        return Sized(bitmap, sizeDp, sizeDp)
    }

    /**
     * The small Quick log's macro column: per macro a line "96 / 180 g" (eaten in `ink`, the goal in
     * `ink3`) over a 4 dp bar in the macro's hue on a `surface2` track, full at the goal.
     */
    fun macroBars(context: Context, widthDp: Float, heightDp: Float, data: QuickLogData, unit: String): Sized {
        val (bitmap, canvas) = canvas(widthDp, heightDp, scale(context))
        val rows = listOf(
            Triple(data.protein, data.goals.protein, NT.Colors.protein),
            Triple(data.carbs, data.goals.carbs, NT.Colors.carbs),
            Triple(data.fat, data.goals.fat, NT.Colors.fat),
        )
        val pitch = heightDp / rows.size
        val bar = 4f
        val strong = textPaint(Typeface.create("sans-serif-medium", Typeface.NORMAL), 12f, NT.Colors.ink).apply {
            textAlign = Paint.Align.LEFT
        }
        val weak = textPaint(Typeface.create("sans-serif", Typeface.NORMAL), 11f, NT.Colors.ink3).apply {
            textAlign = Paint.Align.LEFT
        }
        val track = paint(NT.Colors.surface2)
        val rect = RectF()
        rows.forEachIndexed { i, (eaten, goal, color) ->
            val top = i * pitch
            val barTop = top + pitch - bar - 4f
            val baseline = barTop - 4f
            val eatenText = Math.round(eaten).toString()
            canvas.drawText(eatenText, 0f, baseline, strong)
            val x = strong.measureText(eatenText)
            // "96 / 180 g", or "130 g" when the goal does not fit the column.
            val full = " / ${Math.round(goal)} $unit"
            canvas.drawText(if (x + weak.measureText(full) <= widthDp) full else " $unit", x, baseline, weak)
            rect.set(0f, barTop, widthDp, barTop + bar)
            canvas.drawRoundRect(rect, bar / 2f, bar / 2f, track)
            val fraction = if (goal > 0) (eaten / goal).toFloat().coerceIn(0f, 1f) else 0f
            if (fraction > 0f) {
                rect.set(0f, barTop, (widthDp * fraction).coerceAtLeast(bar), barTop + bar)
                canvas.drawRoundRect(rect, bar / 2f, bar / 2f, paint(color))
            }
        }
        return Sized(bitmap, widthDp, heightDp)
    }

    /** A thin rounded progress bar: a `surface2` track and a [color] fill of [fraction]. */
    fun bar(context: Context, widthDp: Float, heightDp: Float, fraction: Float, color: Color = NT.Colors.ember): Sized {
        val (bitmap, canvas) = canvas(widthDp, heightDp, scale(context))
        val r = heightDp / 2f
        canvas.drawRoundRect(RectF(0f, 0f, widthDp, heightDp), r, r, paint(NT.Colors.surface2))
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) canvas.drawRoundRect(RectF(0f, 0f, (widthDp * f).coerceAtLeast(heightDp), heightDp), r, r, paint(color))
        return Sized(bitmap, widthDp, heightDp)
    }

    /**
     * A progress ring: [track] circle and a [color] arc of [fraction] from 12 o'clock, round caps;
     * [center] in the display face inside when given (the idle Break timer's default length).
     */
    fun progressRing(
        context: Context,
        sizeDp: Float,
        lineDp: Float,
        fraction: Float,
        color: Color = NT.Colors.ember,
        track: Color = NT.Colors.surface2,
        center: String? = null,
        centerSp: Float = 0f,
    ): Sized {
        val (bitmap, canvas) = canvas(sizeDp, sizeDp, scale(context))
        val inset = lineDp / 2f
        val oval = RectF(inset, inset, sizeDp - inset, sizeDp - inset)
        canvas.drawOval(oval, ringStroke(track, lineDp))
        val sweep = 360f * fraction.coerceIn(0f, 1f)
        if (sweep > 0f) canvas.drawArc(oval, -90f, sweep, false, ringStroke(color, lineDp))
        if (center != null) {
            val paint = textPaint(display(context), centerSp, NT.Colors.ink)
            val m = paint.fontMetrics
            canvas.drawText(center, sizeDp / 2f, sizeDp / 2f - (m.ascent + m.descent) / 2f, paint)
        }
        return Sized(bitmap, sizeDp, sizeDp)
    }

    /** A check (✓) or a cross (×) centred in a box of [sizeDp], in the rounded stroke of the app's glyphs. */
    fun glyph(canvas: Canvas, cx: Float, cy: Float, sizeDp: Float, check: Boolean, color: Color) {
        val paint = ringStroke(color, sizeDp * 0.16f).apply { strokeJoin = Paint.Join.ROUND }
        val h = sizeDp / 2f
        val path = Path()
        if (check) {
            // ic_checkmark: M4.6,12.8 L9.6,17.8 L19.4,6.4 on a 24 grid.
            val u = sizeDp / 24f
            path.moveTo(cx - h + 4.6f * u, cy - h + 12.8f * u)
            path.lineTo(cx - h + 9.6f * u, cy - h + 17.8f * u)
            path.lineTo(cx - h + 19.4f * u, cy - h + 6.4f * u)
        } else {
            val d = h * 0.62f
            path.moveTo(cx - d, cy - d)
            path.lineTo(cx + d, cy + d)
            path.moveTo(cx + d, cy - d)
            path.lineTo(cx - d, cy + d)
        }
        canvas.drawPath(path, paint)
    }
}
