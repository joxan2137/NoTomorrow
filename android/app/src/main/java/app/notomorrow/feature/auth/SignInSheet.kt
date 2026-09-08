package app.notomorrow.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.R
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSegmentedLarge
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.di.ntViewModel
import app.notomorrow.util.S

/**
 * Sign-in / create-account sheet — the port of `NoTomorrow/Features/Auth/SignInView.swift`.
 *
 * Presented from Settings → Account, the Bro tab's signed-out state and the AI photo flow. Calls
 * [onSignedIn] and dismisses itself on success. `.presentationDetents([.large])` is the full-height
 * sheet, `.presentationDragIndicator(.hidden)` with the app's own [app.notomorrow.designsystem.Grabber]
 * on top, `.presentationBackground(NT.Colors.surface)`, `.presentationCornerRadius(24)`.
 */
@Composable
fun SignInSheet(
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit = {},
) {
    val model = ntViewModel(key = "signIn") { container ->
        SignInViewModel(
            appConfig = container.appConfig,
            authStore = container.authStore,
            broService = container.broService,
            pushRegistrar = container.pushRegistrar,
        )
    }
    // `@State private var model = SignInModel()` is per-presentation on iOS; a Compose view model is
    // scoped to the nav entry / Activity and outlives the sheet, so wipe it on teardown — otherwise
    // the next presentation opens on the previous username, mode, error and typed password.
    DisposableEffect(model) { onDispose { model.reset() } }

    val state by model.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val passwordFocus = remember { FocusRequester() }

    // The `guard model.canSubmit` lives in the view model; the view only drops the keyboard.
    val succeeded: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onSignedIn()
        onDismiss()
    }
    val submit: () -> Unit = {
        focusManager.clearFocus()
        model.submit(succeeded)
    }

    NtSheet(
        onDismiss = onDismiss,
        showsHandle = true,
        containerColor = NT.Colors.surface,
        shape = SignInSheetShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                // `.scrollDismissesKeyboard(.interactively)`
                .ntDismissKeyboardOnScroll()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SignInTitleBlock(mode = state.mode)

            val signInTitle = stringResource(S.auth_signIn)
            val createAccountTitle = stringResource(S.auth_createAccount)
            NtSegmentedLarge(
                options = SignInModeOptions,
                selected = state.mode,
                onSelect = model::setMode,
                label = { mode -> if (mode == SignInMode.SignIn) signInTitle else createAccountTitle },
            )

            SignInFields(
                state = state,
                onUsername = model::setUsername,
                onPassword = model::setPassword,
                passwordFocus = passwordFocus,
                onSubmit = submit,
            )

            // `.animation(.easeOut(duration: 0.2), value: model.errorMessage)`: SwiftUI's implicit
            // animation on the enclosing VStack moves every sibling below by the inserted height,
            // so the fade is paired with a height transition of the same 0.2 s ease-out.
            AnimatedVisibility(
                visible = state.errorRes != null,
                enter = fadeIn(NT.Anim.easeOut20) + expandVertically(SignInSizeSpec),
                exit = fadeOut(NT.Anim.easeOut20) + shrinkVertically(SignInSizeSpec),
            ) {
                NtText(
                    text = state.errorRes?.let { stringResource(it) } ?: "",
                    modifier = Modifier.fillMaxWidth(),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.bad,
                )
            }

            SignInSubmitButton(state = state, onSubmit = submit)

            SignInOrDivider()

            // iOS ships a disabled placeholder; Android wires the row to Credential Manager and
            // `POST /auth/google` (`docs/android-architecture.md`, "Auth"). It stays disabled — the
            // same visible state as iOS — only while no server client id is configured.
            SecondaryButton(
                title = stringResource(
                    if (GoogleSignInConfig.isConfigured) R.string.auth_googleTitle else S.auth_google,
                ),
                // TODO(shared): `icon = NtIcons.GCircle` once designsystem/Icons.kt gains the glyph —
                //  iOS draws SF `g.circle` 16 pt semibold, 8 pt before the label.
                enabled = GoogleSignInConfig.isConfigured && !state.isBusy,
                onClick = {
                    focusManager.clearFocus()
                    model.signInWithGoogle(context, succeeded)
                },
            )

            NtText(
                text = stringResource(S.auth_footnote),
                modifier = Modifier.fillMaxWidth(),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
    }
}

/** `.easeOut(duration: 0.2)` for the inserted/removed heights, matching the fades. */
private val SignInSizeSpec = tween<androidx.compose.ui.unit.IntSize>(200, easing = NT.Ease.out)

private val SignInModeOptions = listOf(SignInMode.SignIn, SignInMode.CreateAccount)

/** `titleBlock`: `VStack(alignment: .leading, spacing: 4)`. */
@Composable
private fun SignInTitleBlock(mode: SignInMode) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NtText(
            text = stringResource(if (mode == SignInMode.SignIn) S.auth_signIn else S.auth_createAccount),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
        )
        NtText(
            text = stringResource(S.auth_subtitle),
            modifier = Modifier.fillMaxWidth(),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )
    }
}

/** `fields`: `VStack(alignment: .leading, spacing: 14)`, each field labelled by an eyebrow. */
@Composable
private fun SignInFields(
    state: SignInUiState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    passwordFocus: FocusRequester,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SignInLabelledField(label = stringResource(S.auth_username)) {
            AuthField(
                value = state.username,
                onValue = onUsername,
                placeholder = stringResource(S.auth_username),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                // `.textContentType(.username)`
                contentType = ContentType.Username,
            )
        }

        SignInLabelledField(label = stringResource(S.auth_password)) {
            AuthField(
                value = state.password,
                onValue = onPassword,
                placeholder = stringResource(S.auth_password),
                focusRequester = passwordFocus,
                secure = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                // `.textContentType(model.mode == .signIn ? .password : .newPassword)`
                contentType = if (state.mode == SignInMode.SignIn) {
                    ContentType.Password
                } else {
                    ContentType.NewPassword
                },
            )
        }

        AnimatedVisibility(
            visible = state.mode == SignInMode.CreateAccount,
            enter = fadeIn(NT.Anim.easeOut20) + expandVertically(SignInSizeSpec),
            exit = fadeOut(NT.Anim.easeOut20) + shrinkVertically(SignInSizeSpec),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NtText(
                    text = stringResource(S.auth_hint_username),
                    modifier = Modifier.fillMaxWidth(),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
                NtText(
                    text = stringResource(S.auth_hint_password),
                    modifier = Modifier.fillMaxWidth(),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }
        }
    }
}

/** `stLabeled(_:)`: eyebrow above the field, 8 pt apart. */
@Composable
private fun SignInLabelledField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(text = label)
        content()
    }
}

/** `submitButton`: the primary pill with `ProgressView().tint(onPrimary)` overlaid while busy. */
@Composable
private fun SignInSubmitButton(state: SignInUiState, onSubmit: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        PrimaryButton(
            title = stringResource(
                if (state.mode == SignInMode.SignIn) S.auth_signIn else S.auth_createAccount,
            ),
            enabled = state.canSubmit,
            onClick = onSubmit,
        )
        if (state.isBusy) {
            AuthSpinner(color = NT.Colors.onPrimary)
        }
    }
}

/** `orDivider`: `HStack(spacing: 12) { Hairline(); Text("or"); Hairline() }`. */
@Composable
private fun SignInOrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Hairline(Modifier.weight(1f))
        NtText(
            text = stringResource(S.auth_or),
            style = NT.Fonts.caption,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
        Hairline(Modifier.weight(1f))
    }
}
