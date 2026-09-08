package app.notomorrow.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.io.File

/**
 * Export my data: `workouts.csv` (one row per set) and `meals.csv` are built into the cache
 * directory and handed to the share sheet — the port of `ExportView`
 * (`Features/Settings/SettingsExport.swift`).
 */
@Composable
fun ExportEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val export = state.export
    LaunchedEffect(Unit) { model.prepareExport() }

    StEditorScaffold(stringResource(S.settings_export), onBack, modifier) {
        NtText(
            text = stringResource(S.settings_export_description),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )

        StGroup {
            row {
                StInfoRow(
                    label = stringResource(S.settings_export_workouts),
                    value = Fmt.whole(export.counts.workouts.toDouble()),
                )
            }
            row {
                StInfoRow(
                    label = stringResource(S.settings_export_sets),
                    value = Fmt.whole(export.counts.sets.toDouble()),
                )
            }
            row {
                StInfoRow(
                    label = stringResource(S.settings_export_meals),
                    value = Fmt.whole(export.counts.meals.toDouble()),
                )
            }
        }

        if (export.isPreparing || export.files.isEmpty()) {
            // `SecondaryButton` has no disabled state on iOS; `ExportView` dims it by hand.
            SecondaryButton(
                title = stringResource(S.settings_export_preparing),
                modifier = Modifier.alphaLayer(0.5f),
                enabled = false,
                onClick = {},
            )
        } else {
            PrimaryButton(
                title = stringResource(S.settings_export_share),
                icon = NtIcons.SquareAndArrowUp,
            ) {
                shareCsv(context, export.files)
            }
        }
    }
}

/**
 * Hands both files to the system chooser through the `FileProvider` declared in the manifest —
 * nothing leaves the phone until the user picks a destination.
 */
private fun shareCsv(context: Context, files: List<File>) {
    val authority = "${context.packageName}.fileprovider"
    val builder = ShareCompat.IntentBuilder(context).setType(SettingsExportFiles.MIME_TYPE)
    val added = files.count { file ->
        runCatching { builder.addStream(FileProvider.getUriForFile(context, authority, file)) }
            .isSuccess
    }
    if (added == 0) return
    runCatching {
        context.startActivity(
            builder.createChooserIntent()
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
