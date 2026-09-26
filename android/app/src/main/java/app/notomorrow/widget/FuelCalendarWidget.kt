package app.notomorrow.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.designsystem.NT
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider

/**
 * Fuel calendar (`nt.widget.history`, `docs/widgets.md`): the Fuel history heat grid with the days
 * you trained on top. Medium: header and grid; large adds month labels, the legend and three stat
 * columns. The whole widget opens Fuel on today.
 */
class FuelCalendarWidget : GlanceAppWidget() {

    override val sizeMode = WidgetSizeMode

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = preload(WidgetKind.Calendar) { WidgetData.calendar(context) }
        provideContent {
            val data = rememberWidgetData(WidgetKind.Calendar, first) { WidgetData.calendar(context) }
            if (data == null) SetupContent(context) else CalendarContent(data)
        }
    }
}

class FuelCalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FuelCalendarWidget()
}

/** From this height (dp, margins included) the large layout: months, legend, stats. */
private const val LARGE_MIN_HEIGHT_DP = 220f

private const val HEADER_DP = 18f
private const val LEGEND_DP = 16f
private const val STATS_DP = 50f
private const val GAP_DP = 10f

@Composable
internal fun CalendarContent(data: CalendarData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val large = size.height.value >= LARGE_MIN_HEIGHT_DP
    val width = size.width.value - 2 * W.margin.value
    val height = size.height.value - 2 * W.margin.value
    val gridHeight = height - HEADER_DP - GAP_DP - if (large) LEGEND_DP + STATS_DP + 2 * GAP_DP else 0f
    val locale = LocaleProvider.current()
    WidgetFrame(onClick = W.open(context, AppState.Route.Fuel)) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth().height(HEADER_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(context.getString(R.string.widget_history_name), modifier = GlanceModifier.defaultWeight())
                Text(
                    context.getString(R.string.widget_history_onTarget_n, data.stats.onTarget30),
                    style = W.footnote,
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(GAP_DP.dp))
            BitmapImage(
                WidgetCharts.heatGrid(context, width, gridHeight.coerceAtLeast(24f), data, months = large, locale = locale),
                description = context.getString(R.string.widget_history_description),
            )
            if (large) {
                Spacer(GlanceModifier.height(GAP_DP.dp))
                Legend(context)
                Spacer(GlanceModifier.height(GAP_DP.dp))
                Stats(context, data)
            }
        }
    }
}

/** The five `heat` swatches, then On target; an `ink` dot, then Trained. */
@Composable
private fun Legend(context: Context) {
    Row(GlanceModifier.fillMaxWidth().height(LEGEND_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        // The swatches get their own row: Glance drops the children of a Row past its 10th.
        Row(verticalAlignment = Alignment.CenterVertically) {
            NT.Colors.heat.forEachIndexed { index, color ->
                if (index > 0) Spacer(GlanceModifier.width(3.dp))
                Box(GlanceModifier.size(10.dp).background(W.color(color)).cornerRadius(2.5.dp)) {}
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        Text(context.getString(R.string.fuel_calendar_onTarget), style = W.caption, maxLines = 1)
        Spacer(GlanceModifier.width(14.dp))
        Box(GlanceModifier.size(6.dp).background(ImageProvider(R.drawable.widget_dot))) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(context.getString(R.string.widget_history_trained), style = W.caption, maxLines = 1)
    }
}

/** 7-day avg, 30-day avg (kcal, "–" with no logged day) and sessions in the last 30 days. */
@Composable
private fun Stats(context: Context, data: CalendarData) {
    Row(GlanceModifier.fillMaxWidth().height(STATS_DP.dp), verticalAlignment = Alignment.Top) {
        StatColumn(context, data.stats.avg7?.let { Fmt.kcal(it, withUnit = false) } ?: "–", R.string.fuel_calendar_avg7)
        StatColumn(context, data.stats.avg30?.let { Fmt.kcal(it, withUnit = false) } ?: "–", R.string.fuel_calendar_avg30)
        StatColumn(context, Fmt.count(data.sessions30), R.string.widget_history_sessions30)
    }
}

@Composable
private fun androidx.glance.layout.RowScope.StatColumn(context: Context, value: String, label: Int) {
    Column(GlanceModifier.defaultWeight()) {
        BitmapImage(WidgetBitmaps.displayText(context, value, 28f), description = value)
        Text(context.getString(label), style = W.caption, maxLines = 1)
    }
}
