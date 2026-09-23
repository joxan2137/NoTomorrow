package app.notomorrow.feature.auth

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.net.BackendError
import app.notomorrow.net.dto.Session
import app.notomorrow.push.PushRegistrar
import app.notomorrow.service.AppConfig
import app.notomorrow.service.AuthStore
import app.notomorrow.service.BroService
import app.notomorrow.util.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** `SignInModel.Mode` (`Features/Auth/SignInModel.swift:12`). */
enum class SignInMode { SignIn, CreateAccount }

/**
 * One immutable snapshot of the sign-in sheet. The two derived properties are the Swift
 * computed properties of the same name, so they are unit-tested without a view.
 */
data class SignInUiState(
    val mode: SignInMode = SignInMode.SignIn,
    val username: String = "",
    val password: String = "",
    val isBusy: Boolean = false,
    @param:StringRes val errorRes: Int? = null,
) {
    /** `trimmedUsername`: whitespace off, lowercased — the server stores usernames lowercase. */
    val trimmedUsername: String
        get() = username.trim().lowercase(Locale.ROOT)

    /**
     * `canSubmit`: never while a request is in flight, a username the server will accept, and a
     * password that is long enough to register / merely present to sign in.
     *
     * **Deliberate divergence from `SignInModel.canSubmit`**, which only checks the *length*
     * (`Self.usernameRange.contains(trimmedUsername.count)`). The code spec is explicit that
     * Android mirrors the server rule in full — "username 3…24 matching `^[a-z0-9_.]{3,24}$`"
     * (`docs/android-architecture.md`, "Auth") — so a name with a capital, a space or a dash is
     * caught here instead of round-tripping to a `username_invalid` reply.
     */
    val canSubmit: Boolean
        get() {
            if (isBusy) return false
            if (!SignInViewModel.USERNAME_REGEX.matches(trimmedUsername)) return false
            return if (mode == SignInMode.CreateAccount) {
                password.length >= SignInViewModel.PASSWORD_MINIMUM
            } else {
                password.isNotEmpty()
            }
        }
}

/**
 * Username + password sign-in / registration against `AppConfig.makeBackendClient()` — the port of
 * `SignInModel` (`NoTomorrow/Features/Auth/SignInModel.swift`).
 *
 * On success the session goes to the secure store ([AuthStore]), [BroService.didSignIn] pushes the
 * local schedule and refreshes the partner state, and — the one Android addition — the FCM token is
 * registered (iOS does that from `didRegisterForRemoteNotifications`). Errors are generic on
 * purpose: nothing here may confirm whether an account exists.
 */
class SignInViewModel(
    private val appConfig: AppConfig,
    private val authStore: AuthStore,
    private val broService: BroService,
    private val pushRegistrar: PushRegistrar,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun setUsername(value: String) {
        _state.update { it.copy(username = value) }
    }

    fun setPassword(value: String) {
        _state.update { it.copy(password = value) }
    }

    /** `switchMode()`: the segmented control writes a value; a real change clears the error. */
    fun setMode(mode: SignInMode) {
        _state.update { if (it.mode == mode) it else it.copy(mode = mode, errorRes = null) }
    }

    /**
     * Back to the pristine state. `SignInView` holds its model in `@State`, so every presentation
     * of the sheet starts with empty fields, `.signIn` mode and no error; a Compose view model is
     * scoped to the host (nav entry or Activity) and outlives the sheet, so the sheet calls this on
     * teardown. It also drops the typed password, which must not linger in memory after a *failed*
     * attempt — `submit` only clears it on success.
     */
    fun reset() {
        _state.value = SignInUiState()
    }

    /**
     * `submit(in:)`. [onSignedIn] runs only after the session is durable and the post-sign-in bro
     * sync has finished, exactly like the Swift `defer { isBusy = false }` ordering.
     */
    fun submit(onSignedIn: () -> Unit) {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        _state.update { it.copy(isBusy = true, errorRes = null) }
        viewModelScope.launch {
            val client = appConfig.makeBackendClient()
            val session = try {
                when (snapshot.mode) {
                    SignInMode.SignIn -> client.signIn(snapshot.trimmedUsername, snapshot.password)
                    SignInMode.CreateAccount ->
                        client.register(snapshot.trimmedUsername, snapshot.password, null)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update {
                    it.copy(isBusy = false, errorRes = messageFor(BackendError.wrap(error), snapshot.mode))
                }
                return@launch
            }
            finishSignIn(session, onSignedIn)
        }
    }

    /**
     * The Google row (`docs/android-architecture.md`: "on iOS it is disabled at 0.4 opacity, on
     * Android it works"). Credential Manager hands back a Google id token, the backend exchanges it
     * at `POST /auth/google`, and from there the flow is byte-for-byte the password one.
     *
     * A dismissed account chooser is not a failure: [requestGoogleIdToken] returns `null` and the
     * sheet simply stops spinning, matching how iOS treats a cancelled system sheet.
     *
     * @param activityContext must be the hosting Activity — Credential Manager presents its own UI.
     */
    fun signInWithGoogle(activityContext: Context, onSignedIn: () -> Unit) {
        if (_state.value.isBusy) return
        val mode = _state.value.mode
        _state.update { it.copy(isBusy = true, errorRes = null) }
        viewModelScope.launch {
            val idToken = try {
                requestGoogleIdToken(activityContext)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update { it.copy(isBusy = false, errorRes = messageFor(BackendError.wrap(error), mode)) }
                return@launch
            }
            if (idToken == null) {
                _state.update { it.copy(isBusy = false) }
                return@launch
            }
            val session = try {
                appConfig.makeBackendClient().signInGoogle(idToken)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update { it.copy(isBusy = false, errorRes = messageFor(BackendError.wrap(error), mode)) }
                return@launch
            }
            finishSignIn(session, onSignedIn)
        }
    }

    /**
     * The tail both credential paths share: persist the session, drop the password, run the
     * post-sign-in bro sync, register for push, then hand control back to the sheet.
     */
    private suspend fun finishSignIn(session: Session, onSignedIn: () -> Unit) {
        authStore.saveAwait(session)
        _state.update { it.copy(password = "") }
        // `BroShared.service.didSignIn(in:)` is non-throwing on iOS; a bro/network hiccup must
        // never turn a completed sign-in into an error.
        try {
            broService.didSignIn()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // ignored, exactly as the Swift service swallows its own failures
        }
        _state.update { it.copy(isBusy = false) }
        pushRegistrar.registerAsync()
        onSignedIn()
    }

    companion object {
        /** Server rules (`backend/src/auth/password.ts`): username 3–24 `[a-z0-9_.]`. */
        val USERNAME_RANGE = 3..24

        /** The same rule as a pattern — the client gate the code spec asks for. */
        val USERNAME_REGEX = Regex("^[a-z0-9_.]{3,24}$")

        /** …and a password of 10–128 characters. */
        const val PASSWORD_MINIMUM = 10

        /**
         * `SignInModel.message(for:mode:)` — generic copy for credential failures, specific only
         * where the user can act on it (taken name, invalid name, weak password, rate limit).
         */
        @StringRes
        fun messageFor(error: BackendError, mode: SignInMode): Int = when (error) {
            BackendError.Network, BackendError.TimedOut -> S.error_network
            is BackendError.Http -> when {
                error.code == "username_taken" -> S.auth_error_usernameTaken
                error.code == "username_invalid" -> S.auth_hint_username
                error.code.startsWith("password_") -> S.auth_error_password
                error.status == 429 -> S.auth_error_tooMany
                else -> generic(mode)
            }
            else -> generic(mode)
        }

        @StringRes
        private fun generic(mode: SignInMode): Int =
            if (mode == SignInMode.SignIn) S.auth_error_generic else S.auth_error_register
    }
}
