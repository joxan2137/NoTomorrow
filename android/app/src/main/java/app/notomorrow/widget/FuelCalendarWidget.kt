package app.notomorrow.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.text.Text
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.util.LocaleProvider

/**
 * Fuel calendar (`nt.widget.history`, `docs/widgets.md`): a GitHub-style contribution graph of the
 * weeks up to this one, each day a small square in its Fuel `heat` colour with a dot on the days
 * you trained, under a header with the widget's name and the days on target. The widget's size
 * decides how many weeks show; taller sizes add the legend. The whole widget opens Fuel on today.
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

@Composable
internal fun CalendarContent(data: CalendarData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val width = size.width.value - 2 * W.margin.value
    val content = size.height.value - 2 * W.margin.value
    // At the smallest heights the weeks get the whole widget.
    val showsHeader = content >= HEADER_MIN_CONTENT_DP
    val height = if (showsHeader) content - HEADER_DP - HEADER_GAP_DP else content
    val words = WidgetCharts.CalendarWords(
        weekdays = listOf(
            R.string.weekday_mon_short, R.string.weekday_tue_short, R.string.weekday_wed_short, R.string.weekday_thu_short,
            R.string.weekday_fri_short, R.string.weekday_sat_short, R.string.weekday_sun_short,
        ).map(context::getString),
        offTarget = context.getString(R.string.fuel_calendar_legend_off),
        onTarget = context.getString(R.string.fuel_calendar_onTarget),
        trained = context.getString(R.string.widget_history_trained),
    )
    WidgetFrame(onClick = W.open(context, AppState.Route.Fuel)) {
        Column(GlanceModifier.fillMaxSize()) {
            if (showsHeader) {
                Row(GlanceModifier.fillMaxWidth().height(HEADER_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow(context.getString(R.string.widget_history_name), icon = R.drawable.ic_flame_fill, iconColor = W.ember)
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        context.resources.getString(R.string.widget_history_onTarget_n, data.onTarget30),
                        style = W.footnote,
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.height(HEADER_GAP_DP.dp))
            }
            BitmapImage(
                WidgetCharts.contributions(
                    context, width, height.coerceAtLeast(40f), data, words,
                    legend = height >= LEGEND_MIN_HEIGHT_DP, locale = LocaleProvider.current(),
                ),
                description = context.getString(R.string.widget_history_description),
            )
        }
    }
}

private const val HEADER_DP = 18f
private const val HEADER_GAP_DP = 8f

/** Below this content height (the 4×2 minimum on small launchers) the header makes way for the weeks. */
private const val HEADER_MIN_CONTENT_DP = 110f

/** From this grid height the legend fits under the weeks (4×3 and up). */
private const val LEGEND_MIN_HEIGHT_DP = 160f
