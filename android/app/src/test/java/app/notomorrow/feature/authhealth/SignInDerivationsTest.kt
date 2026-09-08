package app.notomorrow.feature.authhealth

import androidx.health.connect.client.HealthConnectClient
import app.notomorrow.feature.auth.SignInMode
import app.notomorrow.feature.auth.SignInUiState
import app.notomorrow.feature.auth.SignInViewModel
import app.notomorrow.feature.health.HealthAvailability
import app.notomorrow.feature.health.healthAvailability
import app.notomorrow.net.BackendError
import app.notomorrow.util.S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of `SignInModel` (`NoTomorrow/Features/Auth/SignInModel.swift`): the two computed
 * properties and the error map, plus the Android-only Health Connect availability mapping.
 *
 * Everything here is framework-free — no view model instance, no coroutines, no Android runtime.
 */
class SignInDerivationsTest {

    private fun state(
        mode: SignInMode = SignInMode.SignIn,
        username: String = "",
        password: String = "",
        isBusy: Boolean = false,
    ) = SignInUiState(mode = mode, username = username, password = password, isBusy = isBusy)

    // MARK: trimmedUsername

    @Test
    fun `trimmedUsername strips whitespace and lowercases`() {
        assertEquals("joxan", state(username = "  Joxan ").trimmedUsername)
        assertEquals("joxan", state(username = "JOXAN\n").trimmedUsername)
        assertEquals("nt.bro_1", state(username = "NT.Bro_1").trimmedUsername)
        assertEquals("", state(username = "   ").trimmedUsername)
    }

    // MARK: canSubmit — sign in

    @Test
    fun `sign in needs a 3 to 24 character username and any password`() {
        assertFalse(state(username = "ab", password = "x").canSubmit)
        assertTrue(state(username = "abc", password = "x").canSubmit)
        assertTrue(state(username = "a".repeat(24), password = "x").canSubmit)
        assertFalse(state(username = "a".repeat(25), password = "x").canSubmit)
        assertFalse(state(username = "abc", password = "").canSubmit)
    }

    @Test
    fun `the trimmed username decides the length, not the raw one`() {
        // Two spaces around a 2-character name: too short, even though `username.count` is 4.
        assertFalse(state(username = " ab ", password = "x").canSubmit)
        assertTrue(state(username = "  abc  ", password = "x").canSubmit)
    }

    // MARK: canSubmit — create account

    @Test
    fun `create account needs at least ten password characters`() {
        val short = state(mode = SignInMode.CreateAccount, username = "abc", password = "123456789")
        val long = state(mode = SignInMode.CreateAccount, username = "abc", password = "1234567890")
        assertFalse(short.canSubmit)
        assertTrue(long.canSubmit)
        assertEquals(10, SignInViewModel.PASSWORD_MINIMUM)
        assertEquals(3..24, SignInViewModel.USERNAME_RANGE)
    }

    @Test
    fun `nothing can be submitted while a request is in flight`() {
        assertFalse(state(username = "abc", password = "x", isBusy = true).canSubmit)
        assertFalse(
            state(
                mode = SignInMode.CreateAccount,
                username = "abc",
                password = "1234567890",
                isBusy = true,
            ).canSubmit,
        )
    }

    // MARK: Error map

    @Test
    fun `network failures are generic across both modes`() {
        assertEquals(S.error_network, SignInViewModel.messageFor(BackendError.Network, SignInMode.SignIn))
        assertEquals(
            S.error_network,
            SignInViewModel.messageFor(BackendError.Network, SignInMode.CreateAccount),
        )
    }

    @Test
    fun `only actionable server codes get specific copy`() {
        assertEquals(
            S.auth_error_usernameTaken,
            SignInViewModel.messageFor(http(409, "username_taken"), SignInMode.CreateAccount),
        )
        assertEquals(
            S.auth_hint_username,
            SignInViewModel.messageFor(http(400, "username_invalid"), SignInMode.CreateAccount),
        )
        assertEquals(
            S.auth_error_password,
            SignInViewModel.messageFor(http(400, "password_too_short"), SignInMode.CreateAccount),
        )
        assertEquals(
            S.auth_error_password,
            SignInViewModel.messageFor(http(400, "password_too_long"), SignInMode.SignIn),
        )
        assertEquals(
            S.auth_error_tooMany,
            SignInViewModel.messageFor(http(429, "rate_limited"), SignInMode.SignIn),
        )
    }

    @Test
    fun `a taken username beats the 429 branch when both could match`() {
        // The Swift `if` chain tests the code before the status, so a 429 carrying
        // `username_taken` still reads as the taken-name message.
        assertEquals(
            S.auth_error_usernameTaken,
            SignInViewModel.messageFor(http(429, "username_taken"), SignInMode.CreateAccount),
        )
    }

    @Test
    fun `everything else is the mode's generic message`() {
        assertEquals(
            S.auth_error_generic,
            SignInViewModel.messageFor(http(401, "invalid_credentials"), SignInMode.SignIn),
        )
        assertEquals(
            S.auth_error_register,
            SignInViewModel.messageFor(http(401, "invalid_credentials"), SignInMode.CreateAccount),
        )
        assertEquals(
            S.auth_error_generic,
            SignInViewModel.messageFor(BackendError.Unauthorized, SignInMode.SignIn),
        )
        assertEquals(
            S.auth_error_register,
            SignInViewModel.messageFor(BackendError.Decoding, SignInMode.CreateAccount),
        )
        assertEquals(
            S.auth_error_register,
            SignInViewModel.messageFor(BackendError.Server("boom"), SignInMode.CreateAccount),
        )
    }

    // MARK: Health Connect availability

    @Test
    fun `sdk status maps to the three settings states`() {
        assertEquals(
            HealthAvailability.Available,
            healthAvailability(HealthConnectClient.SDK_AVAILABLE),
        )
        assertEquals(
            HealthAvailability.ProviderUpdateRequired,
            healthAvailability(HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED),
        )
        assertEquals(
            HealthAvailability.Unavailable,
            healthAvailability(HealthConnectClient.SDK_UNAVAILABLE),
        )
        // An unknown future value must never read as "ready".
        assertEquals(HealthAvailability.Unavailable, healthAvailability(Int.MAX_VALUE))
    }

    private fun http(status: Int, code: String) =
        BackendError.Http(status = status, code = code, serverMessage = "")
}
