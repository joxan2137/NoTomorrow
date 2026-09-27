package app.notomorrow.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.WorkoutImport
import app.notomorrow.service.WorkoutImporter
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Import workouts — port of `ImportView` (`Features/Settings/SettingsImport.swift`): pick
 * a CSV exported from Strong, Hevy or NoTomorrow (the system document picker), see what it holds,
 * import it.
 */
@Composable
fun ImportEditor(
    state: SettingsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var parsed by remember { mutableStateOf<WorkoutImport.Parsed?>(null) }
    var fileName by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<WorkoutImporter.Summary?>(null) }
    var importing by remember { mutableStateOf(false) }
    var writeFailed by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        failed = false
        writeFailed = false
        summary = null
        parsed = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { load(context, uri, state.units) }
            if (result == null) {
                failed = true
            } else {
                fileName = result.first
                parsed = result.second
            }
        }
    }

    StEditorScaffold(stringResource(S.import_title), onBack, modifier) {
        NtText(
            text = stringResource(S.import_description),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )

        val done = summary
        val file = parsed
        if (done != null) {
            StGroup(title = stringResource(S.import_done)) {
                row { StInfoRow(stringResource(S.settings_export_workouts), Fmt.count(done.workouts)) }
                row { StInfoRow(stringResource(S.settings_export_sets), Fmt.count(done.sets)) }
                if (done.duplicates > 0) {
                    row { StInfoRow(stringResource(S.import_duplicates), Fmt.count(done.duplicates)) }
                }
                if (done.newExercises.isNotEmpty()) {
                    row { StInfoRow(stringResource(S.import_newExercises), Fmt.count(done.newExercises.size)) }
                }
            }
            if (done.newExercises.isNotEmpty()) {
                NtText(
                    text = stringResource(S.import_newExercisesNote),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }
        } else if (file != null) {
            val starts = file.workouts.map { it.startedAt }
            StGroup(title = fileName) {
                row { StInfoRow(stringResource(S.import_format), formatName(file.format)) }
                row { StInfoRow(stringResource(S.settings_export_workouts), Fmt.count(file.workouts.size)) }
                row {
                    StInfoRow(stringResource(S.settings_export_sets), Fmt.count(file.workouts.sumOf { it.setCount }))
                }
                val first = starts.minOrNull()
                val last = starts.maxOrNull()
                if (first != null && last != null) {
                    row { StInfoRow(stringResource(S.import_range), day(first) + " – " + day(last)) }
                }
                if (file.skippedRows > 0) {
                    row { StInfoRow(stringResource(S.import_skipped), Fmt.count(file.skippedRows)) }
                }
            }
            PrimaryButton(
                title = stringResource(S.import_confirm_n, file.workouts.size),
                enabled = file.workouts.isNotEmpty() && !importing,
            ) {
                importing = true
                scope.launch {
                    val importer = WorkoutImporter(
                        workoutDao = container.db.workoutDao(),
                        exerciseDao = container.db.exerciseDao(),
                        recordService = container.recordService,
                        transaction = { block -> container.db.withTransaction { block() } },
                    )
                    try {
                        summary = importer.importWorkouts(file.workouts)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The transaction rolled back: nothing was imported. Say so instead of crashing the app.
                        writeFailed = true
                    } finally {
                        importing = false
                    }
                }
            }
        }

        if (failed || writeFailed) {
            NtText(
                text = stringResource(if (writeFailed) S.import_writeFailed else S.import_failed),
                style = NT.Fonts.footnote,
                color = NT.Colors.bad,
            )
        }

        SecondaryButton(
            title = stringResource(if (parsed == null || summary != null) S.import_choose else S.import_chooseAnother),
            icon = NtIcons.SquareAndArrowDown,
        ) {
            picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "text/*", "application/csv"))
        }
    }
}

/** `ImportView.formatName` — the source app's own name, never localized. */
internal fun formatName(format: WorkoutImport.Format): String = when (format) {
    WorkoutImport.Format.Strong -> "Strong"
    WorkoutImport.Format.Hevy -> "Hevy"
    WorkoutImport.Format.NoTomorrow -> "No Tomorrow"
}

private fun day(millis: Long): String =
    Fmt.mediumDate(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())

/** The file's name and what it holds; `null` when it cannot be read or is not a workout export. */
private fun load(context: Context, uri: Uri, unit: WeightUnit): Pair<String, WorkoutImport.Parsed>? = try {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    if (bytes == null) {
        null
    } else {
        val parsed = WorkoutImport.parse(decode(bytes), unit)
        (displayName(context, uri) ?: uri.lastPathSegment.orEmpty()) to parsed
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** UTF-8, falling back to Latin-1 like `String(data:encoding: .isoLatin1)`. */
private fun decode(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    String(bytes, Charsets.ISO_8859_1)
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
