package app.notomorrow.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtKeys

/**
 * Fuel calendar (`nt.widget.history`, `docs/widgets.md`): just the calendar — the weeks up to this
 * one, each day a tile in its Fuel `heat` colour with the date on it and a dot on the days you
 * trained. The widget's size decides how many weeks show. The whole widget opens Fuel on today.
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
    val height = size.height.value - 2 * W.margin.value
    val letters = (1..7).map { context.getString(NtKeys.weekday(it)) }
    WidgetFrame(onClick = W.open(context, AppState.Route.Fuel)) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BitmapImage(
                WidgetCharts.calendar(context, width, height.coerceAtLeast(60f), data, letters, LocaleProvider.current()),
                description = context.getString(R.string.widget_history_description),
            )
        }
    }
}
