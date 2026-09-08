package app.notomorrow.feature.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Google sign-in configuration. iOS never got the Google SDK (`SignInView.googleRow` is a disabled
 * placeholder), but the code spec is explicit that Android wires the row to Credential Manager and
 * `POST /auth/google` (`docs/android-architecture.md`, "Auth (`feature/auth`)").
 *
 * The backend verifies the id token's `aud` against `GOOGLE_CLIENT_IDS` (`backend/src/env.ts:29`),
 * so [SERVER_CLIENT_ID] must be the **web** OAuth client id of that same project — not the Android
 * client id. Until it is filled in, [isConfigured] is `false` and the row stays disabled, which is
 * exactly the state iOS ships; nothing else in the flow changes when the value lands.
 */
object GoogleSignInConfig {

    /**
     * Web OAuth client id. Empty in the repository on purpose: it is per-deployment, must match one
     * of the backend's `GOOGLE_CLIENT_IDS`, and there is no `google-services.json` in the tree yet.
     */
    const val SERVER_CLIENT_ID: String = ""

    val isConfigured: Boolean get() = SERVER_CLIENT_ID.isNotBlank()
}

/**
 * Runs the Credential Manager "Sign in with Google" flow and returns the Google id token.
 *
 * `GetSignInWithGoogleOption` is the explicit *button* flow (as opposed to `GetGoogleIdOption`,
 * which is the bottom-sheet one-tap): the user always sees the account chooser, which is what a row
 * labelled "Google" promises.
 *
 * @param context must be an **Activity** context — Credential Manager hosts its own UI.
 * @return the id token, or `null` when the user dismissed the chooser (a dismissal is not an error;
 *   iOS's equivalent cancel path shows no message either).
 * @throws Exception any other Credential Manager or Play services failure, for the caller to map.
 */
suspend fun requestGoogleIdToken(
    context: Context,
    serverClientId: String = GoogleSignInConfig.SERVER_CLIENT_ID,
): String? {
    val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val response = try {
        CredentialManager.create(context).getCredential(context, request)
    } catch (_: GetCredentialCancellationException) {
        return null
    }
    val credential = response.credential
    if (credential is CustomCredential &&
        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    error("Unexpected credential type ${credential.type}")
}
