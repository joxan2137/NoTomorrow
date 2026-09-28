package app.notomorrow.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
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
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.designsystem.NT
import app.notomorrow.util.Fmt

/**
 * Quick log (`nt.widget.fuel`, `docs/widgets.md`): kcal left in the macro ring and the usual foods
 * one tap away. Small: the ring, big, and one button for the first quick food; medium: the ring at
 * full height, and beside it protein / carbs / fat against their goals over as many food buttons
 * as fit. A tap logs the food with no app launch ([LogQuickFoodAction]); anywhere else opens Fuel
 * on today.
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
            Small(context, data, logged, size.width.value - 32f, size.height.value - 32f)
        }
    }
}

/** The ring's number and label: kcal left, or "+250 over" in `bad` past the goal. */
private fun ring(context: Context, data: QuickLogData, sizeDp: Float, lineDp: Float, numberSp: Float, withLabel: Boolean): WidgetBitmaps.Sized {
    val over = data.kcal - data.goals.kcal
    return if (over >= 1.0) {
        WidgetBitmaps.macroRing(
            context, sizeDp, lineDp, data,
            "+" + Fmt.kcal(over, withUnit = false), numberSp,
            if (withLabel) context.getString(R.string.widget_fuel_over) else null,
            numberColor = NT.Colors.bad, labelColor = NT.Colors.bad,
        )
    } else {
        WidgetBitmaps.macroRing(
            context, sizeDp, lineDp, data,
            Fmt.kcal(data.kcalLeft, withUnit = false), numberSp,
            if (withLabel) context.getString(R.string.dashboard_left) else null,
        )
    }
}

@Composable
private fun Small(context: Context, data: QuickLogData, logged: String?, widthDp: Float, heightDp: Float) {
    // The ring as big as the widget allows, and one button for the usual food under it.
    val food = data.foods.firstOrNull()
    val button = if (food != null) W.buttonHeight.value + GAP_DP else 0f
    val ring = minOf(widthDp, heightDp - button).coerceIn(56f, 150f)
    Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
            BitmapImage(ring(context, data, ring, 10f, 0.3f * ring, withLabel = true), description = context.getString(R.string.fuel_kcalLeft))
        }
        if (food != null) {
            Spacer(GlanceModifier.height(GAP_DP.dp))
            FoodCapsule(context, food, logged == food.key, showKcal = false)
        }
    }
}

@Composable
private fun Medium(context: Context, data: QuickLogData, logged: String?, widthDp: Float, heightDp: Float) {
    // Left the ring at full height; right the macros, and the usual foods as buttons under them.
    val ring = minOf(heightDp, widthDp * 0.44f).coerceIn(64f, 160f)
    val right = widthDp - ring - 16f
    // Macros, a gap, then n buttons 6 dp apart.
    val slots = ((heightDp - MACROS_DP - GAP_DP + 6f) / (W.buttonHeight.value + 6f)).toInt().coerceIn(0, 3)
    val foods = data.foods.take(slots)
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        BitmapImage(ring(context, data, ring, 11f, 0.29f * ring, withLabel = true), description = context.getString(R.string.fuel_kcalLeft))
        Spacer(GlanceModifier.width(16.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            val names = listOf(R.string.macro_protein, R.string.macro_carbs, R.string.macro_fat).map(context::getString)
            BitmapImage(WidgetBitmaps.macroColumns(context, right, MACROS_DP, data, names))
            if (foods.isNotEmpty()) {
                Spacer(GlanceModifier.defaultWeight())
                foods.forEachIndexed { index, food ->
                    if (index > 0) Spacer(GlanceModifier.height(6.dp))
                    FoodCapsule(context, food, logged == food.key, showKcal = true)
                }
            }
        }
    }
}

private const val GAP_DP = 10f
private const val MACROS_DP = 76f

/** One tap logs [food]: `+`, the name and (with room) its kcal; a green check and Logged just after. */
@Composable
private fun FoodCapsule(context: Context, food: QuickFood, isLogged: Boolean, showKcal: Boolean) {
    Capsule(onClick = logAction(food), modifier = GlanceModifier.fillMaxWidth()) {
        Glyph(if (isLogged) R.drawable.ic_checkmark else R.drawable.ic_plus, 16.dp, if (isLogged) W.good else W.ink)
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = if (isLogged) context.getString(R.string.widget_fuel_logged) else food.name,
            style = if (isLogged) W.subheadlineBold.copy(color = W.good) else W.subheadlineBold,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (showKcal && !isLogged) {
            Spacer(GlanceModifier.width(6.dp))
            Text(Fmt.kcal(food.kcal, withUnit = false), style = W.footnote, maxLines = 1)
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
