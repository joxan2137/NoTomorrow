package app.notomorrow.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import app.notomorrow.R
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.feature.fuel.suggestedMealSlot
import app.notomorrow.rest.RestTimerController
import app.notomorrow.service.Days
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.service.localizedName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * A Quick log tap (`LogQuickFoodIntent` on iOS): one tap, one entry — no app launch, no sheet.
 * Marks the row "just logged" for [QuickLogWidget.LOGGED_MS], then redraws it plain.
 */
class LogQuickFoodAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = WidgetData.ready(context) ?: return
        val food = QuickFood(
            key = parameters[KEY] ?: return,
            foodId = parameters[FOOD_ID]?.takeIf { it.isNotEmpty() },
            name = parameters[NAME].orEmpty(),
            grams = parameters[GRAMS] ?: 0.0,
            kcal = parameters[KCAL] ?: 0.0,
            protein = parameters[PROTEIN] ?: 0.0,
            carbs = parameters[CARBS] ?: 0.0,
            fat = parameters[FAT] ?: 0.0,
            isAIEstimate = parameters[AI] ?: false,
        )
        val now = System.currentTimeMillis()
        val db = container.db
        QuickLogger.log(db.mealDao(), db.foodDao(), food, now)
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[QuickLogWidget.LOGGED_KEY] = food.key
            prefs[QuickLogWidget.LOGGED_AT] = now
        }
        WidgetUpdater.refresh(context, WidgetKind.QuickLog, WidgetKind.Calendar)
        // On the process scope, so the receiver that ran this callback can finish meanwhile.
        container.scope.launch {
            delay(QuickLogWidget.LOGGED_MS)
            updateAppWidgetState(context, glanceId) { prefs ->
                if (prefs[QuickLogWidget.LOGGED_AT] == now) {
                    prefs.remove(QuickLogWidget.LOGGED_KEY)
                    prefs.remove(QuickLogWidget.LOGGED_AT)
                }
            }
            WidgetUpdater.refresh(context, WidgetKind.QuickLog)
        }
    }

    companion object {
        val KEY = ActionParameters.Key<String>("key")
        val FOOD_ID = ActionParameters.Key<String>("foodId")
        val NAME = ActionParameters.Key<String>("name")
        val GRAMS = ActionParameters.Key<Double>("grams")
        val KCAL = ActionParameters.Key<Double>("kcal")
        val PROTEIN = ActionParameters.Key<Double>("protein")
        val CARBS = ActionParameters.Key<Double>("carbs")
        val FAT = ActionParameters.Key<Double>("fat")
        val AI = ActionParameters.Key<Boolean>("ai")
    }
}

/**
 * The write behind a Quick log tap — `WidgetSync.ingestPendingLogs` on iOS, the same columns the
 * Fuel sheets write: today (`day` = local midnight now, `loggedAt` = now), the slot
 * `MealSlot.suggested(now)`, the quick food's grams, figures and AI flag. A food-backed one links
 * the food and bumps its use count; if the food was deleted meanwhile it logs by name instead.
 */
internal object QuickLogger {

    suspend fun log(
        mealDao: MealDao,
        foodDao: FoodDao,
        food: QuickFood,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MealEntryEntity {
        val foodId = food.foodId?.takeIf { foodDao.byId(it) != null }
        val entry = MealEntryEntity(
            id = UUID.randomUUID().toString(),
            day = Days.startOfDay(now, zone),
            slot = suggestedMealSlot(Instant.ofEpochMilli(now).atZone(zone).toLocalTime()),
            foodId = foodId,
            customName = if (foodId == null) food.name else null,
            grams = food.grams,
            kcal = food.kcal,
            proteinG = food.protein,
            carbsG = food.carbs,
            fatG = food.fat,
            isAIEstimate = food.isAIEstimate,
            loggedAt = now,
        )
        mealDao.insert(entry)
        if (foodId != null) foodDao.bumpUsage(foodId, now)
        return entry
    }
}

/** A Break timer length: starts a rest through the same `RestTimerController.start` the workout uses. */
class StartRestAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val seconds = parameters[SECONDS] ?: return
        val controller = RestTimerController.get(context)
        controller.awaitRestored()
        val label = WidgetData.ready(context)?.let { RestLabel.current(it.workoutSession, it.db.workoutDao(), context) }
        controller.start(
            seconds = seconds,
            exerciseName = label?.exerciseName ?: context.getString(R.string.timer_rest),
            nextSetLabel = label?.nextSetLabel.orEmpty(),
            workoutName = label?.workoutName.orEmpty(),
        )
        WidgetUpdater.refresh(context, WidgetKind.Rest)
    }

    companion object {
        val SECONDS = ActionParameters.Key<Int>("seconds")
    }
}

/** `+15` / `−15` on a running rest. */
class AdjustRestAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[DELTA] ?: return
        val controller = RestTimerController.get(context)
        controller.awaitRestored()
        controller.adjust(delta)
        WidgetUpdater.refresh(context, WidgetKind.Rest)
    }

    companion object {
        val DELTA = ActionParameters.Key<Int>("delta")
    }
}

/** Skip: ends the rest with no alert. */
class SkipRestAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = RestTimerController.get(context)
        controller.awaitRestored()
        controller.skip()
        WidgetUpdater.refresh(context, WidgetKind.Rest)
    }
}

/**
 * What a widget-started rest is labelled with: the workout's current exercise (the first one with
 * a set still to do, else the last) and "Set n of m" for its next set, when a workout runs.
 */
internal object RestLabel {

    data class Label(val exerciseName: String, val nextSetLabel: String, val workoutName: String)

    suspend fun current(session: WorkoutSessionController, workoutDao: WorkoutDao, context: Context): Label? {
        session.awaitRestored()
        val workout = session.workout()?.takeIf { it.endedAt == null } ?: workoutDao.newestActiveWorkout() ?: return null
        val graph = workoutDao.workoutWithExercises(workout.id) ?: return null
        val exercises = graph.sortedExercises
        val current = exercises.firstOrNull { !it.isDone } ?: exercises.lastOrNull() ?: return null
        val name = current.exercise?.localizedName().orEmpty()
        if (name.isEmpty()) return null
        val sets = current.sortedSets
        val nextIndex = sets.indexOfFirst { !it.isCompleted }
        val next = if (nextIndex >= 0) context.getString(R.string.timer_setOf_n_n, nextIndex + 1, sets.size) else ""
        return Label(name, next, workout.name)
    }
}
