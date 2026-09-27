package app.notomorrow.feature.workout

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Badge
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * "Import routine" from the Train tab — `NoTomorrow/Features/Workout/RoutineImportSheet.swift`:
 * paste the text a friend shared ("Share routine", [RoutineShare]), see the routine's exercises
 * (library matches, and the ones that become custom exercises marked New), and "Add routine",
 * which writes it through [RoutineStore.addShared] with a unique name. The clipboard is read only
 * when Paste is tapped.
 *
 * @param loadCatalog the user's exercises for matching ([RoutineStore.shareCatalog]).
 * @param onAdd the routine's name, its matched lines, and the name to use when it has none.
 */
@Composable
fun RoutineImportSheet(
    loadCatalog: suspend () -> RoutineShare.Catalog,
    onAdd: (String, List<RoutineShare.Planned>, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    val catalog by produceState<RoutineShare.Catalog?>(null) { value = loadCatalog() }
    val parsed = remember(text, catalog) {
        val library = catalog ?: return@remember null
        val shared = RoutineShare.decode(text) ?: return@remember null
        val items = RoutineShare.plan(shared, library)
        if (items.isEmpty()) null else shared to items
    }
    val defaultName = stringResource(S.routine_import_defaultName)

    NtSheet(onDismiss = onDismiss, showsHandle = true, containerColor = NT.Colors.ground) {
        RoutineImportHeader(onCancel = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = NT.Spacing.section),
        ) {
            NtText(
                text = stringResource(S.routine_import_intro),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
            Box(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(NT.Colors.surface, NtShapes.tile)
                    .padding(14.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(122.dp),
                    textStyle = NT.Fonts.footnote.copy(color = NT.Colors.ink),
                    cursorBrush = SolidColor(NT.Colors.ink),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                )
                if (text.isEmpty()) {
                    NtText(
                        text = stringResource(S.routine_import_placeholder),
                        style = NT.Fonts.body,
                        color = NT.Colors.ink3,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = NT.Size.control)
                        .ntPlainClickable {
                            // Only on the user's tap: Android shows its "pasted from clipboard" notice.
                            val pasted = clipboard.getText()?.text
                            if (!pasted.isNullOrEmpty()) {
                                focusManager.clearFocus()
                                text = pasted
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NtIcon(NtIcons.DocOnDoc, size = sfIconSize(15f), tint = NT.Colors.ink)
                    NtText(text = stringResource(S.routine_import_paste), style = NT.Fonts.subheadlineBold, color = NT.Colors.ink)
                }
                if (text.isNotEmpty()) {
                    Box(
                        modifier = Modifier.heightIn(min = NT.Size.control).ntPlainClickable { text = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        NtText(text = stringResource(S.routine_import_clear), style = NT.Fonts.subheadline, color = NT.Colors.ink2)
                    }
                }
            }

            if (parsed != null) {
                RoutineImportPreview(name = parsed.first.name.ifEmpty { defaultName }, items = parsed.second)
            } else if (text.isNotBlank() && catalog != null) {
                NtText(
                    text = stringResource(S.routine_import_invalid),
                    modifier = Modifier.padding(top = 12.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ember,
                )
            }
        }

        PrimaryButton(
            title = stringResource(S.routine_import_add),
            modifier = Modifier.padding(horizontal = NT.Spacing.screenH).padding(vertical = 8.dp),
            enabled = parsed != null,
            onClick = {
                val result = parsed ?: return@PrimaryButton
                onAdd(result.first.name, result.second, defaultName)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onDismiss()
            },
        )
    }
}

/** Cancel · Import routine. */
@Composable
private fun RoutineImportHeader(onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 16.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        NtText(text = stringResource(S.routine_import), style = NT.Fonts.headline, color = NT.Colors.ink, maxLines = 1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.heightIn(min = NT.Size.control).ntPlainClickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                NtText(text = stringResource(S.common_cancel), style = NT.Fonts.body, color = NT.Colors.ink2)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The routine's name and exercise count, then one row per exercise. */
@Composable
private fun RoutineImportPreview(name: String, items: List<RoutineShare.Planned>) {
    val letters = Superset.letters(items.map { it.supersetGroup })
    Column(Modifier.padding(top = NT.Spacing.section)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NtText(text = name, modifier = Modifier.weight(1f), style = NT.Fonts.title2, color = NT.Colors.ink)
            Spacer(Modifier.width(8.dp))
            TabularText(
                text = stringResource(NtKeys.exerciseCount(items.size), items.size),
                style = NT.Fonts.caption,
                color = NT.Colors.ink2,
            )
        }
        items.forEach { item ->
            if (item.index > 0) Hairline()
            RoutineImportRow(item, letters[item.index])
        }
    }
}

/** "Bench Press  [New] ······ 3 × 8  2:00", with the superset tag above the name. */
@Composable
private fun RoutineImportRow(item: RoutineShare.Planned, letter: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (letter != null) SupersetTag(letter)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NtText(
                    text = item.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
                if (item.isNew) Badge(text = stringResource(S.routine_import_newExercise))
            }
        }
        TabularText(
            text = "${item.sets} ${Fmt.TIMES} ${item.reps}",
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
        TabularText(
            text = if (item.restSeconds <= 0) stringResource(S.routine_restDefault) else Fmt.clock(item.restSeconds),
            modifier = Modifier.widthIn(min = 34.dp),
            style = NT.Fonts.caption.copy(textAlign = TextAlign.End),
            color = NT.Colors.ink3,
        )
    }
}

/** The share text's localized parts: "No Tomorrow routine: %s", "rest %s", "Superset %s". */
internal fun routineShareLabels(context: Context): RoutineShare.Labels = RoutineShare.Labels(
    header = { context.getString(S.routine_share_header_s, it) },
    rest = { context.getString(S.routine_share_rest_s, it) },
    superset = { context.getString(S.superset_tag_s, it) },
)

/** `ShareLink(item: text)` — the system chooser with the routine's share text. */
internal fun shareRoutineText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
