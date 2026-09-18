package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.tabular
import app.notomorrow.di.ntViewModel
import app.notomorrow.model.MealSlot
import app.notomorrow.service.Days
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtKeys
import app.notomorrow.util.Parsing
import app.notomorrow.util.S
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * Manual entry for foods the database does not know — the port of
 * `Features/Fuel/QuickAddSheet.swift`. Name and kcal are required; P/C/F are optional and
 * default to 0. The result is a `MealEntry` with a `customName` and no food row.
 *
 * [QuickAddEditSheet] is the same sheet pointed at a custom row that is already logged.
 */
@Composable
fun QuickAddSheet(
    meal: MealSlot,
    day: LocalDate,
    onDismiss: () -> Unit,
    initialName: String = "",
    onAdded: () -> Unit = onDismiss,
) {
    val model = ntViewModel(key = "quickAdd") { container ->
        QuickAddViewModel(container.db.mealDao())
    }
    LaunchedEffect(initialName) { model.start(initialName) }
    DisposableEffect(Unit) { onDispose { model.reset() } }
    val state by model.state.collectAsStateWithLifecycle()

    QuickAddSheetBody(
        state = state,
        model = model,
        title = stringResource(S.fuel_quickAdd),
        buttonTitle = stringResource(S.fuel_addTo, stringResource(NtKeys.meal(meal))),
        onDismiss = onDismiss,
        onSubmit = { model.add(meal = meal, day = day, onAdded = onAdded) },
    )
}

/**
 * The edit sheet for a quick-add or AI-scan row (`FuelEntryRow`'s tap on an entry with no
 * food): every field is seeded from the entry, plus the grams the scan estimated and the
 * meal slot. Its own view-model key, so an interrupted quick-add keeps its fields.
 */
@Composable
fun QuickAddEditSheet(entryId: String, onDismiss: () -> Unit) {
    val model = ntViewModel(key = "quickAdd-edit") { container ->
        QuickAddViewModel(container.db.mealDao())
    }
    LaunchedEffect(entryId) { model.bindEntry(entryId) }
    DisposableEffect(Unit) { onDispose { model.reset() } }
    val state by model.state.collectAsStateWithLifecycle()

    QuickAddSheetBody(
        state = state,
        model = model,
        title = stringResource(S.fuel_editEntry),
        buttonTitle = stringResource(S.common_save),
        onDismiss = onDismiss,
        onSubmit = { model.save(onSaved = onDismiss) },
    )
}

/**
 * Both presentations share every row; edit mode adds the grams field under the name and the
 * [MealSlotPicker] under the macros, and never grabs focus (the fields are already filled).
 */
@Composable
private fun QuickAddSheetBody(
    state: QuickAddUiState,
    model: QuickAddViewModel,
    title: String,
    buttonTitle: String,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val nameFocus = remember { FocusRequester() }
    val kcalFocus = remember { FocusRequester() }
    LaunchedEffect(state.autofocus) {
        if (!state.autofocus) return@LaunchedEffect
        runCatching {
            if (state.name.isEmpty()) nameFocus.requestFocus() else kcalFocus.requestFocus()
        }
    }

    NtSheet(onDismiss = onDismiss, containerColor = NT.Colors.ground) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            QuickAddHeader(title = title, onCancel = onDismiss)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // `.scrollDismissesKeyboard(.interactively)`.
                    .ntDismissKeyboardOnScroll()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuickAddTextRow(
                    label = stringResource(S.fuel_foodName),
                    value = state.name,
                    onValue = model::setName,
                    focusRequester = nameFocus,
                    nextFocusRequester = kcalFocus,
                )
                if (state.showsGrams) {
                    QuickAddNumberRow(
                        label = stringResource(S.fuel_grams),
                        unit = stringResource(S.unit_g),
                        value = state.gramsText,
                        onValue = model::setGrams,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                QuickAddNumberRow(
                    label = stringResource(S.unit_kcal),
                    unit = stringResource(S.unit_kcal),
                    value = state.kcalText,
                    onValue = model::setKcal,
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = kcalFocus,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAddNumberRow(
                        label = stringResource(S.fuel_macro_p),
                        unit = stringResource(S.unit_g),
                        value = state.proteinText,
                        onValue = model::setProtein,
                        modifier = Modifier.weight(1f),
                    )
                    QuickAddNumberRow(
                        label = stringResource(S.fuel_macro_c),
                        unit = stringResource(S.unit_g),
                        value = state.carbsText,
                        onValue = model::setCarbs,
                        modifier = Modifier.weight(1f),
                    )
                    QuickAddNumberRow(
                        label = stringResource(S.fuel_macro_f),
                        unit = stringResource(S.unit_g),
                        value = state.fatText,
                        onValue = model::setFat,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.isEditing) {
                    MealSlotPicker(
                        slot = state.slot,
                        onSelect = model::setSlot,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                NtText(
                    text = stringResource(S.fuel_quickAdd_hint),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }

            PrimaryButton(
                title = buttonTitle,
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(bottom = 12.dp),
                enabled = state.canAdd,
                onClick = onSubmit,
            )
        }
    }
}

@Composable
private fun QuickAddHeader(title: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .padding(horizontal = NT.Spacing.screenH)
            .height(NT.Size.control),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(
            text = title,
            modifier = Modifier.weight(1f),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = NT.Size.control)
                .ntPlainClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.common_cancel),
                style = NT.Fonts.body,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
    }
}

/** 52 dp `surface` row: label on the left, right-aligned free text on the right. */
@Composable
private fun QuickAddTextRow(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    focusRequester: FocusRequester,
    nextFocusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(NT.Colors.surface, NtShapes.field)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(text = label, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            textStyle = NT.Fonts.body.copy(color = NT.Colors.ink, textAlign = TextAlign.End),
            singleLine = true,
            cursorBrush = SolidColor(NT.Colors.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            // `.submitLabel(.next).onSubmit { focus = .kcal }`.
            keyboardActions = KeyboardActions(
                onNext = { runCatching { nextFocusRequester.requestFocus() } },
            ),
        )
    }
}

/** The same row with a decimal keypad and a trailing unit. */
@Composable
private fun QuickAddNumberRow(
    label: String,
    unit: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val field = remember { FocusRequester() }
    val requester = focusRequester ?: field
    Row(
        modifier = modifier
            .height(52.dp)
            .background(NT.Colors.surface, NtShapes.field)
            .ntPlainClickable(onClick = { runCatching { requester.requestFocus() } })
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(text = label, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) {
                NtText(
                    text = "0",
                    style = numberFieldStyle(NT.Colors.ink3),
                    color = NT.Colors.ink3,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
                textStyle = numberFieldStyle(NT.Colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(NT.Colors.ink),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {}),
            )
        }
        NtText(text = unit, style = NT.Fonts.footnote, color = NT.Colors.ink2, maxLines = 1)
    }
}

private fun numberFieldStyle(color: androidx.compose.ui.graphics.Color): TextStyle =
    NT.Fonts.body.tabular().copy(color = color, textAlign = TextAlign.End)

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

/** `QuickAddSheet`'s five fields and the insert, plus the edit sheet's grams and slot. */
class QuickAddViewModel(
    private val mealDao: MealDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: () -> Locale = { LocaleProvider.current() },
) : ViewModel() {

    private val _state = MutableStateFlow(QuickAddUiState())
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    private var started = false

    /** The row being rewritten in edit mode; `null` while the sheet is adding. */
    private var entry: MealEntryEntity? = null

    /** `init` — the sheet opens pre-filled with whatever the user typed into the search field. */
    fun start(initialName: String) {
        if (started) return
        started = true
        _state.value = QuickAddUiState(name = initialName, autofocus = true)
    }

    /**
     * The same sheet re-opened on a logged custom row: the fields come from the entry, and the
     * grams row only exists when there is a weight to edit (a quick-add has none).
     */
    fun bindEntry(entryId: String) {
        if (entry?.id == entryId) return
        _state.value = QuickAddUiState(isEditing = true)
        viewModelScope.launch {
            val row = mealDao.byId(entryId) ?: return@launch
            entry = row
            val weighed = row.grams > 0
            _state.value = QuickAddUiState(
                name = row.customName.orEmpty(),
                gramsText = if (weighed) text(row.grams) else "",
                kcalText = text(row.kcal),
                proteinText = text(row.proteinG),
                carbsText = text(row.carbsG),
                fatText = text(row.fatG),
                slot = row.slot,
                showsGrams = weighed,
                isEditing = true,
            )
        }
    }

    /** The sheet closed: iOS discards its `@State`, so this one discards the fields too. */
    fun reset() {
        started = false
        entry = null
        _state.value = QuickAddUiState()
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun setGrams(value: String) {
        _state.value = _state.value.copy(gramsText = value)
    }

    fun setKcal(value: String) {
        _state.value = _state.value.copy(kcalText = value)
    }

    fun setProtein(value: String) {
        _state.value = _state.value.copy(proteinText = value)
    }

    fun setCarbs(value: String) {
        _state.value = _state.value.copy(carbsText = value)
    }

    fun setFat(value: String) {
        _state.value = _state.value.copy(fatText = value)
    }

    fun setSlot(slot: MealSlot) {
        _state.value = _state.value.copy(slot = slot)
    }

    fun add(meal: MealSlot, day: LocalDate, onAdded: () -> Unit) {
        val snapshot = _state.value
        val kcal = snapshot.kcal ?: return
        if (!snapshot.canAdd) return
        viewModelScope.launch {
            mealDao.insert(
                MealEntryEntity(
                    id = UUID.randomUUID().toString(),
                    day = Days.millis(day, zone),
                    slot = meal,
                    customName = snapshot.name.trim(),
                    grams = 0.0,
                    kcal = kcal,
                    proteinG = Parsing.decimal(snapshot.proteinText) ?: 0.0,
                    carbsG = Parsing.decimal(snapshot.carbsText) ?: 0.0,
                    fatG = Parsing.decimal(snapshot.fatText) ?: 0.0,
                ),
            )
            onAdded()
        }
    }

    /** The edit-mode button: the row keeps its `id` and `loggedAt`, everything else is the user's. */
    fun save(onSaved: () -> Unit) {
        val entry = entry ?: return
        val snapshot = _state.value
        val kcal = snapshot.kcal ?: return
        if (!snapshot.canAdd) return
        viewModelScope.launch {
            mealDao.update(
                FuelDerive.overwritten(
                    entry = entry,
                    name = snapshot.name.trim(),
                    grams = Parsing.nonNegative(snapshot.gramsText) ?: 0.0,
                    kcal = kcal,
                    proteinG = Parsing.decimal(snapshot.proteinText) ?: 0.0,
                    carbsG = Parsing.decimal(snapshot.carbsText) ?: 0.0,
                    fatG = Parsing.decimal(snapshot.fatText) ?: 0.0,
                    slot = snapshot.slot,
                ),
            )
            onSaved()
        }
    }

    private fun text(value: Double): String = FuelDerive.portionText(value, locale())
}

data class QuickAddUiState(
    val name: String = "",
    val gramsText: String = "",
    val kcalText: String = "",
    val proteinText: String = "",
    val carbsText: String = "",
    val fatText: String = "",
    val slot: MealSlot = suggestedMealSlot(),
    /** A quick-add row has no weight, so it edits without the grams field. */
    val showsGrams: Boolean = false,
    val isEditing: Boolean = false,
    val autofocus: Boolean = false,
) {
    val kcal: Double? get() = Parsing.decimal(kcalText)

    /** `canAdd` — a non-blank name and a positive calorie figure. */
    val canAdd: Boolean get() = name.trim().isNotEmpty() && (kcal ?: 0.0) > 0
}
