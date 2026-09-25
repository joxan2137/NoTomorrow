package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtMenu
import app.notomorrow.designsystem.NtMenuItem
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.Parsing
import app.notomorrow.util.S
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

/** Column widths of the set table — load-bearing, `WorkoutExerciseSection.columnHeader`. */
internal object SetTable {
    val setColumn = 36.dp
    val cell = 60.dp
    val check = 48.dp
    val spacing = 8.dp
}

/**
 * Identifies one numeric cell for the shared keyboard focus — `SetField` (`SetCells.swift`).
 * [setId] is the `set_entry` row id in the active workout, and the draft row's id (negative
 * while unsaved) in the workout editor.
 */
data class SetField(val setId: Long, val isReps: Boolean)

/**
 * The analogue of SwiftUI's shared `@FocusState private var focus: SetField?`, which
 * `ActiveWorkoutView` hoists and hands to every `SetRowView` as a binding.
 *
 * Hoisted once in `ActiveWorkoutScreen` and threaded down, so the table has a single source of
 * truth for *which* cell owns the keyboard ([focused], which drives the cell border) and a hook
 * for *moving* it ([request]) — the two halves of the Swift binding.
 */
@Stable
class SetFieldFocus {
    /** Requesters are identity, not state: creating one must never invalidate composition. */
    private val requesters = HashMap<SetField, FocusRequester>()

    /** `focus.wrappedValue` — the cell the keyboard is on, `null` when it is down. */
    var focused: SetField? by mutableStateOf(null)
        private set

    fun requester(field: SetField): FocusRequester = requesters.getOrPut(field) { FocusRequester() }

    /** `.focused(focus, equals: field)` — the write half. */
    fun onFocusChanged(field: SetField, isFocused: Boolean) {
        if (isFocused) {
            focused = field
        } else if (focused == field) {
            focused = null
        }
    }

    /**
     * `focus = field` — moves the keyboard onto one cell. With [keyboard] the keyboard also comes
     * up when that cell had kept the focus after back only hid it (a refocus alone would not bring
     * it back, and the digits typed next would go nowhere).
     */
    fun request(field: SetField, keyboard: SoftwareKeyboardController? = null) {
        val requester = requesters[field] ?: return
        requester.requestFocus()
        keyboard?.show()
    }
}

/**
 * One 44 dp row of the set table — 1:1 port of `SetRowView.swift`:
 * kind/number menu · previous ghost · weight cell · reps cell · check.
 *
 * Weights show and parse in the user's [unit] (stored in kg). The two cells keep their own text
 * and reconcile it with the model **in display space**, as `SetNumberCell` does: typing writes
 * through, and a model value the text no longer reads as (a prefill, a tick's fallback) replaces
 * it — so a rounded or lb-converted number is never written back.
 *
 * A completed row sits on a faint ember tint (radius `NT.Radius.cell`), its numbers at full
 * strength. [editing] is the workout editor's row: never tinted or locked, the Previous column
 * left blank.
 * [onDelete] adds "Delete set" to the kind menu. [onAppear] runs once per row, like `.onAppear`
 * (the active table prefills an open row from Previous there).
 */
@Composable
fun SetRow(
    row: SetRowUi,
    focus: SetFieldFocus,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    onKind: (SetKind) -> Unit,
    onWeight: (Double) -> Unit,
    onReps: (Int) -> Unit,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onAppear: () -> Unit = {},
) {
    LaunchedEffect(row.id) { onAppear() }
    Row(
        modifier = modifier
            .height(NT.Size.control)
            .then(if (row.isCompleted && !editing) Modifier.background(DONE_TINT, NtShapes.cell) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(SetTable.spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindMenu(row = row, onKind = onKind, onDelete = onDelete)

        if (editing) {
            Spacer(Modifier.weight(1f).height(1.dp))
        } else {
            NtText(
                text = row.previous?.let { Fmt.set(it.weightKg, it.reps, unit) } ?: EM_DASH,
                modifier = Modifier.weight(1f),
                style = NT.Fonts.subheadline.tabular(),
                color = NT.Colors.ink2,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }

        NumericCell(
            rowId = row.id,
            field = SetField(row.id, isReps = false),
            focus = focus,
            text = SetInput.text(row.weightKg, unit),
            enabled = editing || !row.isCompleted,
            isCurrent = row.isCurrent,
            keyboardType = KeyboardType.Decimal,
            onEdit = { onWeight(SetInput.weightKg(it, unit)) },
        )

        NumericCell(
            rowId = row.id,
            field = SetField(row.id, isReps = true),
            focus = focus,
            text = SetInput.text(row.reps),
            enabled = editing || !row.isCompleted,
            isCurrent = row.isCurrent,
            keyboardType = KeyboardType.Number,
            onEdit = { onReps(SetInput.reps(it)) },
        )

        SetCheckButton(isCompleted = row.isCompleted, onToggle = onToggle)
    }
}

// MARK: - Set column

/**
 * 36 × 44 cell: the row number, or the kind letter in a 24 dp `surface2` circle. The menu ends
 * with a destructive "Delete set" after a divider when [onDelete] is set.
 */
@Composable
private fun KindMenu(row: SetRowUi, onKind: (SetKind) -> Unit, onDelete: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val items = buildList {
        add(NtMenuItem(title = stringResource(S.workout_warmup), onClick = { onKind(SetKind.Warmup) }))
        add(NtMenuItem(title = stringResource(S.workout_dropset), onClick = { onKind(SetKind.Drop) }))
        add(NtMenuItem(title = stringResource(S.workout_failure), onClick = { onKind(SetKind.Failure) }))
        add(NtMenuItem(title = stringResource(S.workout_normalSet), onClick = { onKind(SetKind.Normal) }))
        if (onDelete != null) {
            add(
                NtMenuItem(
                    title = stringResource(S.workout_edit_deleteSet),
                    onClick = onDelete,
                    destructive = true,
                    separatorBefore = true,
                ),
            )
        }
    }
    Box(
        modifier = Modifier
            .size(width = SetTable.setColumn, height = NT.Size.control)
            .ntPlainClickable { expanded = true },
        contentAlignment = Alignment.Center,
    ) {
        if (row.kind == SetKind.Normal) {
            TabularText(row.number.toString(), style = NT.Fonts.subheadline, color = NT.Colors.ink)
        } else {
            Box(
                modifier = Modifier.size(24.dp).background(NT.Colors.surface2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NtText(
                    text = kindLetter(row.kind),
                    style = NT.Fonts.caption,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }
        NtMenu(expanded = expanded, onDismiss = { expanded = false }, items = items)
    }
}

/** `SetKindMenu.letter(for:)` — W / D / F, never localized; the detail sheet uses the same glyphs. */
internal fun kindLetter(kind: SetKind): String = when (kind) {
    SetKind.Warmup -> "W"
    SetKind.Drop -> "D"
    SetKind.Failure -> "F"
    SetKind.Normal -> ""
}

// MARK: - Cells

/**
 * One 60 × 44 numeric cell — `SetNumberCell`. [text] is the stored value formatted for display.
 * Only the user's own edits reach [onEdit], compared with [text] in display space. Border: `ink`
 * 1.5 dp while focused, `border` on the current (first uncompleted) row, transparent otherwise.
 */
@Composable
private fun NumericCell(
    rowId: Long,
    field: SetField,
    focus: SetFieldFocus,
    text: String,
    enabled: Boolean,
    isCurrent: Boolean,
    keyboardType: KeyboardType,
    onEdit: (String) -> Unit,
) {
    var typed by remember(rowId) { mutableStateOf(text) }
    val focusManager = LocalFocusManager.current

    // `onChange(of: text)` — a model change the typed text no longer reads as replaces it.
    LaunchedEffect(rowId, text) {
        if (SetInput.number(typed) != SetInput.number(text)) typed = text
    }

    val border: Color = when {
        focus.focused == field -> NT.Colors.ink
        isCurrent -> NT.Colors.border
        else -> Color.Transparent
    }

    BasicTextField(
        value = typed,
        onValueChange = { new ->
            typed = new
            if (SetInput.number(new) != SetInput.number(text)) onEdit(new)
        },
        modifier = Modifier
            .size(width = SetTable.cell, height = NT.Size.control)
            .background(NT.Colors.surface2, NtShapes.cell)
            .border(1.5.dp, border, NtShapes.cell)
            .focusRequester(focus.requester(field))
            .onFocusChanged { focus.onFocusChanged(field, it.isFocused) },
        enabled = enabled,
        textStyle = NT.Fonts.body.tabular().copy(color = NT.Colors.ink, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(NT.Colors.ink),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        decorationBox = { inner ->
            Box(Modifier.size(width = SetTable.cell, height = NT.Size.control), Alignment.Center) {
                inner()
            }
        },
    )
}

// MARK: - Text ↔ model

/** Text ↔ number for the set cells — `SetInput`. Weights are stored in kg and typed / shown in the user's unit. */
object SetInput {

    /** kg → the number shown in [unit]. */
    fun display(kg: Double, unit: WeightUnit): Double = if (unit == WeightUnit.Kg) kg else kg * Fmt.LB_PER_KG

    /** A number typed in [unit] → kg. */
    fun kg(fromDisplay: Double, unit: WeightUnit): Double =
        if (unit == WeightUnit.Kg) fromDisplay else fromDisplay / Fmt.LB_PER_KG

    /** Cell text for a weight: up to two decimals ("81,25" — plates come in 1.25), empty for none. */
    fun text(weightKg: Double, unit: WeightUnit, locale: Locale = LocaleProvider.current()): String {
        if (weightKg <= 0) return ""
        val format = (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
            isGroupingUsed = false
        }
        return format.format(display(weightKg, unit))
    }

    fun text(reps: Int): String = if (reps > 0) reps.toString() else ""

    /** Lenient parse of what was typed: "82,5", "82.5", "1 000". Anything else (or ≤ 0) is 0. */
    fun number(text: String): Double = Parsing.decimal(text)?.takeIf { it > 0 } ?: 0.0

    /** Typed weight in [unit] → kg to store. */
    fun weightKg(text: String, unit: WeightUnit): Double = kg(minOf(number(text), MAX_WEIGHT), unit)

    /** Typed reps, rounded half away from zero and capped (never overflows). */
    fun reps(text: String): Int = Fmt.roundHalfAwayFromZero(minOf(number(text), MAX_REPS)).toInt()

    private const val MAX_WEIGHT = 10_000.0
    private const val MAX_REPS = 9_999.0
}

/** The em dash the Previous column shows when the exercise was never done. */
private const val EM_DASH = "—"

/** `NT.Colors.ember.opacity(0.07)` behind a completed row. */
private val DONE_TINT = NT.Colors.ember.copy(alpha = 0.07f)

// MARK: - Row state

/** One (weight, reps) pair — a previous row, a best, a prefill. */
data class SetValue(val weightKg: Double, val reps: Int)

/** One row of the set table. */
data class SetRowUi(
    val id: Long,
    val order: Int,
    val kind: SetKind,
    val weightKg: Double,
    val reps: Int,
    /** The ✓: completed in the active table; ticked and with reps (`SetDraft.isLogged`) in the editor. */
    val isCompleted: Boolean,
    /** Row number in the Set column — warm-ups do not count (`setNumber(for:in:)`). */
    val number: Int,
    val previous: SetValue?,
    /** First uncompleted row of the exercise: the one with the highlighted cells. */
    val isCurrent: Boolean,
)
