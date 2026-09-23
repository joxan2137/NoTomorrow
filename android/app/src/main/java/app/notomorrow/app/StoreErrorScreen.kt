package app.notomorrow.app

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import app.notomorrow.designsystem.LocalNtBackdrop
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.ntBackdropSource
import app.notomorrow.designsystem.ntClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.util.S
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shown instead of the app when the saved data cannot be opened — the port of
 * `StoreErrorView.swift` (see [StoreLoader]). Try again first; save a copy of the files through
 * the share sheet; or, as a last resort, move them into a backup and start from setup.
 *
 * [message] is the raw error, for a bug report: not translated, so it stays collapsed behind
 * "Details" ([StoreErrorDetails]).
 */
@Composable
fun StoreErrorScreen(message: String, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val backdrop = LocalNtBackdrop.current

    var busy by remember { mutableStateOf(false) }
    var confirmsStartFresh by remember { mutableStateOf(false) }
    val hasFiles = remember(message) { container.store.hasFiles }

    fun run(action: suspend () -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = action()
            haptics.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
            busy = false
        }
    }

    Column(
        modifier
            .background(NT.Colors.ground)
            // The start-fresh confirmation is glass and samples this screen.
            .then(if (backdrop != null) Modifier.ntBackdropSource(backdrop) else Modifier),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(top = 56.dp)
                .padding(horizontal = NT.Spacing.screenH),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(NT.Colors.emberTint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NtIcon(NtIcons.ExclamationTriangleFill, size = sfIconSize(26f), tint = NT.Colors.ember)
            }
            NtText(
                text = stringResource(S.store_error_title),
                modifier = Modifier
                    .padding(top = 20.dp)
                    .semantics { heading() },
                style = NT.Fonts.title1,
                color = NT.Colors.ink,
            )
            NtText(
                text = stringResource(S.store_error_body),
                modifier = Modifier.padding(top = 10.dp),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
            if (message.isNotBlank()) {
                StoreErrorDetails(message, Modifier.padding(top = 14.dp))
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(NT.Colors.ground)
                .navigationBarsPadding()
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(title = stringResource(S.store_error_retry), enabled = !busy) {
                run { container.retryStore() }
            }
            if (hasFiles) {
                SecondaryButton(
                    title = stringResource(S.store_error_share),
                    icon = NtIcons.SquareAndArrowUp,
                ) {
                    scope.launch { shareStore(context, container.store.shareableCopies()) }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(NT.Size.control)
                    .pressScale(enabled = !busy) { confirmsStartFresh = true },
                contentAlignment = Alignment.Center,
            ) {
                NtText(
                    text = stringResource(S.store_error_startFresh),
                    style = NT.Fonts.subheadlineBold,
                    color = NT.Colors.bad,
                    maxLines = 1,
                )
            }
        }
    }

    if (confirmsStartFresh) {
        NtActionSheet(
            actions = listOf(
                NtAlertAction(
                    title = stringResource(S.store_error_startFresh),
                    role = NtAlertRole.Destructive,
                    // An empty store has no profile or schedule: setup runs again
                    // (`AppContainer.startFreshStore` turns `hasOnboarded` off).
                    onClick = { run { container.startFreshStore() } },
                ),
            ),
            cancel = stringResource(S.common_cancel),
            onDismiss = { confirmsStartFresh = false },
            title = stringResource(S.store_error_startFresh_title),
            message = stringResource(S.store_error_startFresh_message),
        )
    }
}

/**
 * The raw error behind a collapsed "Details" row — iOS's `DisclosureGroup`. It is English and
 * technical (an exception class and a file path), there for a bug report, so a Polish user sees
 * only the translated explanation unless they open it. Selectable, so it can be copied.
 */
@Composable
private fun StoreErrorDetails(message: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "storeErrorDetailsChevron",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .ntClickable(role = Role.Button) { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    if (expanded) {
                        collapse { expanded = false; true }
                    } else {
                        expand { expanded = true; true }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtText(
                text = stringResource(S.store_error_details),
                modifier = Modifier.weight(1f),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
            NtIcon(
                NtIcons.ChevronRight,
                modifier = Modifier.rotate(chevronRotation),
                size = sfIconSize(13f),
                tint = NT.Colors.ink2,
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            // Raw error for a bug report; not translated on purpose.
            SelectionContainer(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                NtText(text = message, style = NT.Fonts.caption, color = NT.Colors.ink3)
            }
        }
    }
}

/** The store files, handed to the system chooser through the app's `FileProvider`. */
private fun shareStore(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val authority = "${context.packageName}.fileprovider"
    val builder = ShareCompat.IntentBuilder(context).setType("application/octet-stream")
    val added = files.count { file ->
        runCatching { builder.addStream(FileProvider.getUriForFile(context, authority, file)) }.isSuccess
    }
    if (added == 0) return
    runCatching {
        context.startActivity(
            builder.createChooserIntent()
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
