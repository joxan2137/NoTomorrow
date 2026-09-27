package app.notomorrow.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.util.Fmt

/**
 * Quick log (`nt.widget.fuel`, `docs/widgets.md`): kcal left in the macro ring and the usual foods
 * one tap away. Small: the ring and one button for the first quick food; medium: the ring with
 * eaten / goal on the left and up to three food rows on the right. A tap logs the food with no
 * app launch ([LogQuickFoodAction]); anywhere else opens Fuel on today.
 */
class QuickLogWidget : GlanceAppWidget() {

    override val sizeMode = WidgetSizeMode

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = preload(WidgetKind.QuickLog) { WidgetData.quickLog(context) }
        provideContent {
            val data = rememberWidgetData(WidgetKind.QuickLog, first) { WidgetData.quickLog(context) }
            if (data == null) SetupContent(context) else QuickLogContent(data)
        }
    }

    companion object {
        /** The "just logged" marker, per widget (Glance state): the food's key and when. */
        internal val LOGGED_KEY = stringPreferencesKey("nt.widget.logged.key")
        internal val LOGGED_AT = longPreferencesKey("nt.widget.logged.at")

        /** How long a logged row shows the green check and `widget.fuel.logged`. */
        internal const val LOGGED_MS = 4_000L
    }
}

class QuickLogWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickLogWidget()
}

@Composable
internal fun QuickLogContent(data: QuickLogData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val prefs = currentState<Preferences>()
    val loggedAt = prefs[QuickLogWidget.LOGGED_AT] ?: 0L
    val logged = prefs[QuickLogWidget.LOGGED_KEY]
        ?.takeIf { System.currentTimeMillis() - loggedAt in 0 until QuickLogWidget.LOGGED_MS }
    WidgetFrame(onClick = W.open(context, AppState.Route.Fuel)) {
        if (size.width.value >= MEDIUM_MIN_WIDTH_DP) {
            Medium(context, data, logged, size.width.value - 32f, size.height.value - 32f)
        } else {
            Small(context, data, logged, size.height.value - 32f)
        }
    }
}

@Composable
private fun Small(context: Context, data: QuickLogData, logged: String?, heightDp: Float) {
    // Eyebrow 14, button 36, two 8 dp gaps: the ring takes what is left, up to 64 dp.
    val ring = (heightDp - 14f - W.buttonHeight.value - 16f).coerceIn(40f, 64f)
    Column(GlanceModifier.fillMaxSize()) {
        Eyebrow(context.getString(R.string.fuel_kcalLeft))
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.CenterStart) {
            BitmapImage(
                WidgetBitmaps.macroRing(context, ring, 8f, data, Fmt.kcal(data.kcalLeft, withUnit = false), 26f, null),
                description = context.getString(R.string.fuel_kcalLeft),
            )
        }
        val food = data.foods.firstOrNull()
        if (food == null) {
            Text(context.getString(R.string.widget_fuel_empty), style = W.footnote, maxLines = 2)
        } else {
            val isLogged = logged == food.key
            Capsule(onClick = logAction(food), modifier = GlanceModifier.fillMaxWidth()) {
                Glyph(if (isLogged) R.drawable.ic_checkmark else R.drawable.ic_plus, 14.dp, if (isLogged) W.good else W.ink)
                Spacer(GlanceModifier.width(6.dp))
                Text(food.name, style = W.subheadlineBold, maxLines = 1, modifier = GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = if (isLogged) context.getString(R.string.widget_fuel_logged) else Fmt.kcal(food.kcal, withUnit = false),
                    style = W.footnote,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Medium(context: Context, data: QuickLogData, logged: String?, widthDp: Float, heightDp: Float) {
    val left = widthDp * 0.42f
    val ring = (heightDp - 36f).coerceIn(56f, 92f).coerceAtMost(left)
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            GlanceModifier.width(left.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BitmapImage(
                WidgetBitmaps.macroRing(
                    context, ring, 8f, data,
                    Fmt.kcal(data.kcalLeft, withUnit = false),
                    34f * ring / 92f,
                    context.getString(R.string.dashboard_left),
                ),
                description = context.getString(R.string.fuel_kcalLeft),
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = context.getString(
                    R.string.fuel_eatenGoal,
                    Fmt.kcal(data.kcal, withUnit = false),
                    Fmt.kcal(data.goals.kcal, withUnit = false),
                ),
                style = W.footnote,
                maxLines = 2,
            )
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            val rows = ((heightDp + 1f) / (ROW_DP + 1f)).toInt().coerceIn(1, 3)
            val foods = data.foods.take(rows)
            if (foods.isEmpty()) {
                Text(context.getString(R.string.widget_fuel_empty), style = W.footnote, maxLines = 4)
            }
            foods.forEachIndexed { index, food ->
                if (index > 0) Box(GlanceModifier.fillMaxWidth().height(1.dp).background(W.hairline)) {}
                FoodRow(context, food, logged == food.key)
            }
        }
    }
}

private const val ROW_DP = 44f

@Composable
private fun FoodRow(context: Context, food: QuickFood, isLogged: Boolean) {
    Row(
        GlanceModifier.fillMaxWidth().height(ROW_DP.dp).clickable(logAction(food)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text(food.name, style = W.subheadlineBold, maxLines = 1)
            Text(
                text = if (isLogged) {
                    context.getString(R.string.widget_fuel_logged)
                } else {
                    // A food logged without a weight shows just its kcal, not "0 g".
                    if (food.grams > 0) "${Fmt.grams(food.grams)} · ${Fmt.kcal(food.kcal)}" else Fmt.kcal(food.kcal)
                },
                style = W.footnote,
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        Box(
            GlanceModifier.size(30.dp).background(ImageProvider(R.drawable.widget_circle)),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(if (isLogged) R.drawable.ic_checkmark else R.drawable.ic_plus, 16.dp, if (isLogged) W.good else W.ink)
        }
    }
}

private fun logAction(food: QuickFood): Action = actionRunCallback<LogQuickFoodAction>(
    actionParametersOf(
        LogQuickFoodAction.KEY to food.key,
        LogQuickFoodAction.FOOD_ID to (food.foodId ?: ""),
        LogQuickFoodAction.NAME to food.name,
        LogQuickFoodAction.GRAMS to food.grams,
        LogQuickFoodAction.KCAL to food.kcal,
        LogQuickFoodAction.PROTEIN to food.protein,
        LogQuickFoodAction.CARBS to food.carbs,
        LogQuickFoodAction.FAT to food.fat,
        LogQuickFoodAction.AI to food.isAIEstimate,
    ),
)
