package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
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
import app.notomorrow.util.Fmt
import app.notomorrow.util.Parsing
import app.notomorrow.util.S

/** Column widths of the set table — load-bearing, `WorkoutExerciseSection.columnHeader`. */
internal object SetTable {
    val setColumn = 36.dp
    val cell = 60.dp
    val check = 48.dp
    val spacing = 8.dp
}

/**
 * Identifies one numeric cell for the shared keyboard focus — `SetField` (`SetRowView.swift:5`).
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

    /** `focus = field` — moves the keyboard onto one cell. */
    fun request(field: SetField) {
        requesters[field]?.requestFocus()
    }
}

/**
 * One 44 dp row of the set table — 1:1 port of `SetRowView.swift`:
 * kind/number menu · previous ghost · kg cell · reps cell · check.
 *
 * The two cells keep their own text state and reconcile it with the model exactly as the
 * SwiftUI `onChange` pair does: typing writes through to Room, and a model value that no
 * longer parses back to the text replaces it (an undo, a prefill on tick).
 */
@Composable
fun SetRow(
    row: SetRowUi,
    focus: SetFieldFocus,
    modifier: Modifier = Modifier,
    onKind: (SetKind) -> Unit,
    onWeight: (Double) -> Unit,
    onReps: (Int) -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(NT.Size.control)
            .alpha(if (row.isCompleted) 0.55f else 1f),
        horizontalArrangement = Arrangement.spacedBy(SetTable.spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindMenu(row = row, onKind = onKind)

        NtText(
            text = row.previous?.let { Fmt.set(it.weightKg, it.reps) } ?: EM_DASH,
            modifier = Modifier.weight(1f),
            style = NT.Fonts.subheadline.tabular(),
            color = NT.Colors.ink2,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )

        NumericCell(
            rowId = row.id,
            field = SetField(row.id, isReps = false),
            focus = focus,
            value = row.weightKg,
            enabled = !row.isCompleted,
            isCurrent = row.isCurrent,
            keyboardType = KeyboardType.Decimal,
            initialText = { weightText(row) },
            format = { if (it > 0) Fmt.weight(it, withUnit = false) else "" },
            parse = { Parsing.decimal(it) ?: 0.0 },
            onValue = onWeight,
        )

        NumericCell(
            rowId = row.id,
            field = SetField(row.id, isReps = true),
            focus = focus,
            value = row.reps.toDouble(),
            enabled = !row.isCompleted,
            isCurrent = row.isCurrent,
            keyboardType = KeyboardType.Number,
            initialText = { repsText(row) },
            format = { if (it > 0) it.toInt().toString() else "" },
            parse = { parseReps(it).toDouble() },
            onValue = { onReps(it.toInt()) },
        )

        CheckButton(isCompleted = row.isCompleted, onToggle = onToggle)
    }
}

// MARK: - Set column

/** 36 × 44 cell: the row number, or the kind letter in a 24 dp `surface2` circle. */
@Composable
private fun KindMenu(row: SetRowUi, onKind: (SetKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val items = listOf(
        NtMenuItem(title = stringResource(S.workout_warmup), onClick = { onKind(SetKind.Warmup) }),
        NtMenuItem(title = stringResource(S.workout_dropset), onClick = { onKind(SetKind.Drop) }),
        NtMenuItem(title = stringResource(S.workout_failure), onClick = { onKind(SetKind.Failure) }),
        NtMenuItem(title = stringResource(S.workout_normalSet), onClick = { onKind(SetKind.Normal) }),
    )
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

/** `kindLetter` — W / D / F, never localized (`SetRowView.swift:73`). */
private fun kindLetter(kind: SetKind): String = when (kind) {
    SetKind.Warmup -> "W"
    SetKind.Drop -> "D"
    SetKind.Failure -> "F"
    SetKind.Normal -> ""
}

// MARK: - Cells

/**
 * One 60 × 44 numeric cell. Border: `ink` 1.5 dp while focused, `border` on the current
 * (first uncompleted) row, transparent otherwise.
 */
@Composable
private fun NumericCell(
    rowId: Long,
    field: SetField,
    focus: SetFieldFocus,
    value: Double,
    enabled: Boolean,
    isCurrent: Boolean,
    keyboardType: KeyboardType,
    initialText: () -> String,
    format: (Double) -> String,
    parse: (String) -> Double,
    onValue: (Double) -> Unit,
) {
    var text by remember(rowId) { mutableStateOf(initialText()) }
    // Not state: flipping it must not recompose, it only suppresses the first run of the
    // model→text effect (SwiftUI's `onChange` does not fire on appear).
    val firstModelSync = remember(rowId) { booleanArrayOf(true) }
    val focusManager = LocalFocusManager.current

    // `onAppear` + the `onChange(of: weightText)` it triggers: a cell prefilled from the
    // previous workout pushes that number into the model, so the row reads what it shows.
    LaunchedEffect(rowId) {
        val parsed = parse(text)
        if (parsed != value) onValue(parsed)
    }

    // `onChange(of: set.weightKg)` — an external change (undo, tick prefill) rewrites the text.
    LaunchedEffect(rowId, value) {
        if (firstModelSync[0]) {
            firstModelSync[0] = false
            return@LaunchedEffect
        }
        if (parse(text) != value) text = format(value)
    }

    val border: Color = when {
        focus.focused == field -> NT.Colors.ink
        isCurrent -> NT.Colors.border
        else -> Color.Transparent
    }

    BasicTextField(
        value = text,
        onValueChange = { new ->
            text = new
            val parsed = parse(new)
            if (parsed != value) onValue(parsed)
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

/** 28 dp check inside a 48 × 44 hit column. */
@Composable
private fun CheckButton(isCompleted: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = SetTable.check, height = NT.Size.control)
            .ntPlainClickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .then(
                    if (isCompleted) {
                        Modifier.background(NT.Colors.ink, CircleShape)
                    } else {
                        Modifier.border(1.5.dp, NT.Colors.ink3, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isCompleted) {
                NtIcon(NtIcons.Checkmark, size = sfIconSize(13f), tint = NT.Colors.onPrimary)
            }
        }
    }
}

// MARK: - Text ↔ model

/** `syncFromModel` for the kg cell: the model value, else the previous workout's ghost. */
internal fun weightText(row: SetRowUi): String = when {
    row.weightKg > 0 -> Fmt.weight(row.weightKg, withUnit = false)
    row.previous != null -> Fmt.weight(row.previous.weightKg, withUnit = false)
    else -> ""
}

/** `syncFromModel` for the reps cell. A previous rep count of 0 is not offered. */
internal fun repsText(row: SetRowUi): String = when {
    row.reps > 0 -> row.reps.toString()
    row.previous != null && row.previous.reps > 0 -> row.previous.reps.toString()
    else -> ""
}

/** `parseReps` — a plain integer, else the rounded decimal parse. */
internal fun parseReps(text: String): Int =
    text.trim().toIntOrNull() ?: Fmt.roundHalfAwayFromZero(Parsing.decimal(text) ?: 0.0).toInt()

/** The em dash the Previous column shows when the exercise was never done. */
private const val EM_DASH = "—"

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
    val isCompleted: Boolean,
    /** Row number in the Set column — warm-ups do not count (`setNumber(for:in:)`). */
    val number: Int,
    val previous: SetValue?,
    /** First uncompleted row of the exercise: the one with the highlighted cells. */
    val isCurrent: Boolean,
)
