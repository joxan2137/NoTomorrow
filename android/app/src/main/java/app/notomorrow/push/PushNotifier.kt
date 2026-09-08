package app.notomorrow.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.notomorrow.MainActivity
import app.notomorrow.NtChannels
import app.notomorrow.R
import app.notomorrow.designsystem.NT

/**
 * Renders a [PushPayload] on the heads-up channel.
 *
 * Mirrors the APNs presentation of `buildApnsPayload`: title + body, default
 * sound, and the `thread-id` grouping (`"bro"`) — here as the notification
 * group. Tapping opens [MainActivity] with the route extras the app shell
 * turns into `AppState.pendingRoute`.
 */
internal class PushNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    fun show(payload: PushPayload) {
        if (!manager.areNotificationsEnabled()) return
        val title = payload.title ?: appContext.getString(R.string.push_fallback_title)
        val body = payload.body.orEmpty()
        val builder = NotificationCompat.Builder(appContext, NtChannels.HEADS_UPS)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent(payload))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(NT.Colors.ember.toArgb())
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setGroup(payload.threadId ?: PushPayload.DEFAULT_GROUP)
            .setAutoCancel(true)
        try {
            manager.notify(payload.notificationId, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the post.
        }
    }

    /**
     * `MainActivity` is `singleTask`, so a running app receives this through
     * `onNewIntent` and a cold start through `getIntent()`.
     */
    private fun contentIntent(payload: PushPayload): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NtPushIntents.EXTRA_ROUTE, payload.route)
            .putExtra(NtPushIntents.EXTRA_KIND, payload.kind?.wire)
            .putExtra(NtPushIntents.EXTRA_SESSION_DAY, payload.sessionDay)
        return PendingIntent.getActivity(
            appContext,
            payload.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        val SMALL_ICON = R.drawable.ic_launcher_monochrome
    }
}
