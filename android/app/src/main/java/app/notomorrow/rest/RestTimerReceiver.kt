package app.notomorrow.rest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.notomorrow.widget.WidgetKind
import app.notomorrow.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The rest timer's only background entry point.
 *
 * - [ACTION_END] is the `AlarmManager` wake-up: post the end-of-rest alert and
 *   clear the persisted state.
 * - [ACTION_PLUS15] / [ACTION_SKIP] are the notification actions. They go
 *   straight to a receiver — never through an Activity, which Android 12 blocks.
 *
 * Everything is routed through [RestTimerController.get], so an action that
 * lands in a fresh process still reads the persisted `endAt` before acting. Each
 * one ends by redrawing the Break timer widget (`docs/widgets.md`).
 */
class RestTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_END && action != ACTION_PLUS15 && action != ACTION_SKIP) return
        val controller = RestTimerController.get(context)
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_END -> controller.handleAlarmFired()
                    ACTION_PLUS15 -> controller.handlePlus15()
                    ACTION_SKIP -> controller.handleSkip()
                }
                // A fresh process may be gone before `WidgetUpdater` sees the new state: the Break
                // timer widget flips back to idle (or to "just ended") here, before `finish()`.
                WidgetUpdater.refresh(context, WidgetKind.Rest)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_END = "app.notomorrow.rest.END"
        const val ACTION_PLUS15 = "app.notomorrow.rest.PLUS15"
        const val ACTION_SKIP = "app.notomorrow.rest.SKIP"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
