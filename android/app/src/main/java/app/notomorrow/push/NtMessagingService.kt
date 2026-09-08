package app.notomorrow.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM messages — the Android side of `backend/src/push.ts`, which
 * today only speaks APNs (`docs/android-research.md` §7.8 lists the server
 * delta; `docs/android-push.md` spells it out).
 *
 * Two responsibilities, both thin:
 *  - [onNewToken] hands the rotated registration token to [PushRegistrar],
 *    which POSTs it to `push/token` when there is a signed-in session.
 *  - [onMessageReceived] renders the payload on the `nt.headsup` channel and
 *    attaches the deep link. All copy is localized server-side from
 *    `users.locale`, so nothing here composes a sentence.
 *
 * The service is declared in the manifest but is inert without a
 * `google-services.json`: with no default `FirebaseApp` the SDK never starts
 * and neither callback fires. That is the intended state of the repo today.
 */
class NtMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated")
        PushRegistrar.get(this).onNewTokenAsync(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = PushPayload.from(message)
        if (payload.isEmpty) {
            // A silent/data-only message with no copy: nothing to show. The Bro
            // tab refreshes from `partnerState()` when it next becomes visible.
            Log.d(TAG, "push with no alert copy (kind=${payload.kind?.wire})")
            return
        }
        PushNotifier(this).show(payload)
    }

    /**
     * FCM dropped messages because too many were pending. There is no per-message
     * information to act on; the Bro tab reconciles from the backend on resume.
     */
    override fun onDeletedMessages() {
        Log.w(TAG, "FCM reported deleted messages")
    }

    companion object {
        internal const val TAG = "NtPush"
    }
}
