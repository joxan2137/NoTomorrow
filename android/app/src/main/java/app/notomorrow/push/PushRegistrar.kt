package app.notomorrow.push

import android.content.Context
import android.util.Log
import app.notomorrow.net.BackendClient
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Owns the FCM registration token — the Android replacement for iOS's
 * `registerForRemoteNotifications()` / `didRegisterForRemoteNotificationsWithDeviceToken`.
 *
 * Two entry points:
 *  - [register] after a successful sign-in (`AuthSheet` → `BroService.didSignIn()`),
 *  - [onNewToken] from [NtMessagingService] when FCM rotates the token.
 *
 * **There is no `google-services.json` in this repo yet.** Without it the FCM
 * SDK has no default [FirebaseApp], so every call here logs one line and
 * returns — the app must never crash because push is unconfigured
 * (`docs/android-push.md` explains how to add the file).
 *
 * The registrar takes a `suspend () -> BackendClient?` rather than a client,
 * because `AppConfig.makeBackendClient()` is resolved per call (the demo toggle
 * needs no relaunch) and because a token can rotate while signed out, when
 * there is nothing to register against.
 */
class PushRegistrar internal constructor(
    context: Context,
    private val backend: suspend () -> BackendClient?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val appContext = context.applicationContext

    /** True when a default [FirebaseApp] exists, i.e. `google-services.json` shipped. */
    fun isAvailable(): Boolean = FirebaseApp.getApps(appContext).isNotEmpty()

    /** The current registration token, or `null` when push is unconfigured or unreachable. */
    suspend fun currentToken(): String? {
        if (!isAvailable()) {
            Log.i(TAG, "push disabled: no FirebaseApp (google-services.json missing)")
            return null
        }
        return try {
            FirebaseMessaging.getInstance().token.await().takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "could not read the FCM token: ${error.message}")
            null
        }
    }

    /**
     * Fetches the token and registers it with the backend.
     * Returns true only when the server accepted it.
     */
    suspend fun register(): Boolean {
        val token = currentToken() ?: return false
        return send(token)
    }

    /** Fire-and-forget [register], for callers that are not in a coroutine. */
    fun registerAsync() {
        scope.launch { register() }
    }

    /** FCM rotated the token: push the new one at the backend. */
    suspend fun onNewToken(token: String) {
        if (token.isEmpty()) return
        send(token)
    }

    fun onNewTokenAsync(token: String) {
        scope.launch { onNewToken(token) }
    }

    /**
     * Sign-out: drop the backend row, then the device's registration token, so this install
     * stops receiving the signed-out account's pushes. The next [register] mints a fresh one.
     *
     * ⚠️ **Call this before the session is cleared.** `DELETE push/token` is an authenticated
     * route: the supplier returns `null` once `AuthStore` has dropped the session, and the row
     * would then only go when the server prunes it. Both halves are best-effort — a failed
     * delete is logged and the local token is deleted regardless, which already stops delivery
     * to this install.
     */
    suspend fun unregister() {
        if (!isAvailable()) return
        val token = currentToken()
        if (token != null) revoke(token)
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
            Log.i(TAG, "FCM token deleted on sign-out")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "could not delete the FCM token: ${error.message}")
        }
    }

    fun unregisterAsync() {
        scope.launch { unregister() }
    }

    /** Best-effort `DELETE push/token`; a signed-out or unreachable backend is not an error. */
    private suspend fun revoke(token: String) {
        val client = client() ?: return
        try {
            client.unregisterPushToken(token)
            Log.i(TAG, "push token unregistered")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "push token unregistration failed: ${error.message}")
        }
    }

    private suspend fun client(): BackendClient? = try {
        backend()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.w(TAG, "no backend client for push registration: ${error.message}")
        null
    }

    private suspend fun send(token: String): Boolean {
        val client = client()
        if (client == null) {
            Log.i(TAG, "push token not registered: signed out or no backend client")
            return false
        }
        return try {
            client.registerPushToken(token, BackendClient.ANDROID_PLATFORM)
            Log.i(TAG, "push token registered")
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Never surface: an unregistered device still works, and the backend
            // may not accept Android tokens yet (docs/android-push.md).
            Log.w(TAG, "push token registration failed: ${error.message}")
            false
        }
    }

    companion object {
        private const val TAG = NtMessagingService.TAG

        @Volatile
        private var instance: PushRegistrar? = null

        /**
         * Installs the process-wide registrar. `AppContainer` calls this once,
         * passing a supplier that returns the current backend client while a
         * session exists and `null` while signed out.
         */
        fun install(context: Context, backend: suspend () -> BackendClient?): PushRegistrar =
            synchronized(this) {
                PushRegistrar(context.applicationContext, backend).also { instance = it }
            }

        /**
         * The installed registrar, or a no-op one — [NtMessagingService] can run
         * in a process where `AppContainer` was never built.
         */
        fun get(context: Context): PushRegistrar =
            instance ?: synchronized(this) {
                instance ?: PushRegistrar(context.applicationContext, { null }).also { instance = it }
            }
    }
}
