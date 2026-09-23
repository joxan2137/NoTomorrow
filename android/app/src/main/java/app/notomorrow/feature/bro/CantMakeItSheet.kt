package app.notomorrow.feature.bro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Grabber
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NtFlowLayout
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SheetChip
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.ntViewModel
import app.notomorrow.util.Fmt
import app.notomorrow.util.Localizer
import app.notomorrow.util.NtStrings
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings

/**
 * "Can't make it today" — the port of `CantMakeItSheet` (`Features/Bro/CantMakeItSheet.swift`),
 * opened from the Dashboard and from the Bro tab.
 *
 * Sending writes my `AttendanceRecord(.cancelled)`, the make-up day as planned and a local
 * `HeadsUp(.cantMakeIt)`, then tells the backend through `BroService` whenever I am signed in (the
 * partner hears about it only when paired).
 */
@Composable
fun CantMakeItSheet(onDismiss: () -> Unit, onSent: () -> Unit = {}) {
    val model = ntViewModel { container ->
        CantMakeItViewModel(
            scheduleDao = container.db.scheduleDao(),
            attendanceDao = container.db.attendanceDao(),
            routineDao = container.db.routineDao(),
            workoutDao = container.db.workoutDao(),
            pairingDao = container.db.broPairingDao(),
            headsUpDao = container.db.headsUpDao(),
            attendance = container.attendanceService,
            bro = container.broService,
            strings = NtStrings.from(container.app),
        )
    }
    val state by model.uiState.collectAsStateWithLifecycle()
    val strings = rememberNtStrings()
    val focus = LocalFocusManager.current

    // iOS presents a brand-new sheet every time; the view model outlives it.
    LaunchedEffect(Unit) { model.reset() }

    // `CantMakeItSheet.swift:65` `.presentationCornerRadius(24)`.
    NtSheet(onDismiss = onDismiss, minHeight = SHEET_MIN_HEIGHT, cornerRadius = 24.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Grabber() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // SwiftUI's interactive scroll-dismisses-keyboard on the sheet's scroll view.
                .ntDismissKeyboardOnScroll()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 18.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TitleBlock(state, strings)
            WhySection(
                state = state,
                onToggleReason = model::toggleReason,
                onNote = model::setNote,
                onDone = { focus.clearFocus() },
            )
            MakeUpSection(state, model::toggleMakeUp)
            SummaryBox(state, strings)
            Buttons(
                state = state,
                onSend = {
                    focus.clearFocus()
                    // Swift: `onDone(); dismiss()` — only `send()` runs the closure, never a
                    // "Never mind" or a swipe-down.
                    model.send {
                        onSent()
                        onDismiss()
                    }
                },
                onNeverMind = onDismiss,
            )
        }
    }
}

/** iOS `.presentationDetents([.height(660), .large])`. */
private val SHEET_MIN_HEIGHT = 660.dp

// ─────────────────────────────────────────────────────────────────────────────
// Sections
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TitleBlock(state: CantMakeItUiState, strings: Localizer) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NtText(
            text = stringResource(S.cant_title),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
        )
        NtText(
            text = sessionLine(state, strings),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
    }
}

@Composable
private fun WhySection(
    state: CantMakeItUiState,
    onToggleReason: (CantReason) -> Unit,
    onNote: (String) -> Unit,
    onDone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(stringResource(S.cant_why))
        NtFlowLayout(modifier = Modifier.fillMaxWidth(), spacing = 8.dp) {
            CantReason.entries.forEach { reason ->
                SheetChip(
                    title = stringResource(reason.labelRes),
                    selected = state.reason == reason,
                    onClick = { onToggleReason(reason) },
                )
            }
        }
        NoteField(note = state.note, onNote = onNote, onDone = onDone)
    }
}

@Composable
private fun NoteField(note: String, onNote: (String) -> Unit, onDone: () -> Unit) {
    val shape = NtShapes.rounded(NT.Radius.field)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NT.Size.control)
            .background(NT.Colors.ground, shape)
            .border(1.dp, NT.Colors.hairline, shape)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = note,
                onValueChange = onNote,
                modifier = Modifier.fillMaxWidth(),
                textStyle = NT.Fonts.body.copy(color = NT.Colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(NT.Colors.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
            )
            if (note.isEmpty()) {
                NtText(
                    text = stringResource(S.cant_note),
                    style = NT.Fonts.body,
                    color = PLACEHOLDER_INK,
                    maxLines = 1,
                )
            }
        }
        TabularText(
            text = "${note.length}/${CantMakeItViewModel.MAX_NOTE_LENGTH}",
            style = NT.Fonts.footnote,
            color = NT.Colors.ink3,
        )
    }
}

/**
 * SwiftUI's `TextField("cant.note", …)` draws its prompt in the system
 * `placeholderText` colour, which is dimmer than `NT.Colors.ink3` (the colour the
 * `0/80` counter beside it uses). Matching iOS keeps the prompt visibly quieter
 * than the counter instead of the two reading as the same grey.
 */
private val PLACEHOLDER_INK = Color(0xFFEBEBF5).copy(alpha = 0.30f)

@Composable
private fun MakeUpSection(state: CantMakeItUiState, onToggle: (MakeUpChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(stringResource(S.cant_makeUp))
        Row(
            modifier = Modifier
                .bleedHorizontally(NT.Spacing.screenH)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.makeUpDays.forEach { day ->
                val choice = MakeUpChoice.Day(day)
                SheetChip(
                    title = BroDerived.weekdayDay(day),
                    selected = state.makeUp == choice,
                    onClick = { onToggle(choice) },
                )
            }
            SheetChip(
                title = stringResource(S.cant_skipThisOne),
                selected = state.makeUp == MakeUpChoice.Skip,
                onClick = { onToggle(MakeUpChoice.Skip) },
            )
        }
    }
}

@Composable
private fun SummaryBox(state: CantMakeItUiState, strings: Localizer) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NT.Colors.ground, NtShapes.rounded(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            NtIcon(NtIcons.CalendarBadgeMinus, size = sfIconSize(16f), tint = NT.Colors.bad)
        }
        NtText(
            text = summaryText(state, strings),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
    }
}

@Composable
private fun Buttons(
    state: CantMakeItUiState,
    onSend: () -> Unit,
    onNeverMind: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val partner = state.partnerName
        PrimaryButton(
            title = if (partner != null) {
                stringResource(S.cant_send_s, partner)
            } else {
                stringResource(S.cant_sendSolo)
            },
            enabled = !state.isSending,
            onClick = onSend,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NT.Size.control)
                .pressScale(onClick = onNeverMind),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.cant_neverMind),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Copy
// ─────────────────────────────────────────────────────────────────────────────

/** "Friday 18:00 · Push A · Tomek" */
private fun sessionLine(state: CantMakeItUiState, strings: Localizer): String {
    val parts = mutableListOf(
        Fmt.relativeDay(state.sessionDay, strings) + " " + Fmt.time(state.minuteOfDay),
    )
    state.routineName?.let { parts.add(it) }
    state.partnerName?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

private fun summaryText(state: CantMakeItUiState, strings: Localizer): String {
    val dayName = Fmt.relativeDay(state.sessionDay, strings)
    val partner = state.partnerName
        ?: return strings.string(S.cant_summarySolo, dayName)
    var text = strings.string(S.cant_summary, dayName, partner)
    val makeUpDay = state.makeUp?.date
    if (makeUpDay != null) {
        val whenText = Fmt.relativeDay(makeUpDay, strings) + " " + Fmt.time(state.minuteOfDay)
        text += " " + strings.string(S.cant_summaryMakeUp, whenText)
    }
    return text
}
