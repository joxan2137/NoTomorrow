package app.notomorrow.rest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms the rest timer after a reboot. iOS gets this for free — a pending
 * `UNNotificationRequest` survives a restart — while an Android alarm does not
 * (`docs/android-architecture.md`, "Rest timer" §4).
 *
 * A rest whose `endAt` is already in the past is discarded by
 * [RestTimerController.restore]; only a still-pending one is re-scheduled.
 */
class BootRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val controller = RestTimerController.get(context)
        val pending = goAsync()
        scope.launch {
            try {
                controller.rescheduleAfterBoot()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
