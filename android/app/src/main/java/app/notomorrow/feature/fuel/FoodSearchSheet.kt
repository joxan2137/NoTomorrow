package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.ntViewModel
import app.notomorrow.model.FoodCandidate
import app.notomorrow.model.MealSlot
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * "Add to &lt;meal&gt;" — the port of `Features/Fuel/FoodSearchView.swift`.
 *
 * Search field + barcode button, the last ten used foods, Open Food Facts results, and a
 * portion sheet on tap. Every empty state offers Quick add.
 */
@Composable
fun FoodSearchSheet(
    meal: MealSlot,
    day: LocalDate,
    onDismiss: () -> Unit,
) {
    val model = ntViewModel(key = "foodSearch") { container ->
        FoodSearchViewModel(container.db.foodDao(), container.foodSearchService)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // iOS builds a fresh `FoodSearchModel` per presentation.
    DisposableEffect(Unit) { onDispose { model.reset() } }

    var portionFood by remember { mutableStateOf<PortionFood?>(null) }
    var showsScanner by remember { mutableStateOf(false) }
    var showsQuickAdd by remember { mutableStateOf(false) }
    var missingBarcode by remember { mutableStateOf("") }
    var showsLabel by remember { mutableStateOf(false) }
    var lookupError by remember { mutableStateOf(false) }
    var barcodeNotFound by remember { mutableStateOf(false) }

    NtSheet(onDismiss = onDismiss, containerColor = NT.Colors.ground) {
        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
            Column(Modifier.fillMaxWidth()) {
                FoodSearchHeader(meal = meal, onCancel = onDismiss)

                FoodSearchField(
                    query = state.query,
                    onQuery = model::setQuery,
                    onSubmit = model::retry,
                    onBarcode = { showsScanner = true },
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .padding(horizontal = NT.Spacing.screenH),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // `.scrollDismissesKeyboard(.immediately)` (`FoodSearchView.swift:39`).
                        .ntDismissKeyboardOnScroll()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = NT.Spacing.screenH)
                        .padding(top = 16.dp, bottom = 40.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    FoodSearchContent(
                        state = state,
                        onCandidate = { portionFood = PortionFood.Candidate(it) },
                        onItem = { portionFood = PortionFood.Item(it) },
                        onRetry = model::retry,
                        onQuickAdd = { showsQuickAdd = true },
                    )
                }
            }

            if (state.isLookingUpBarcode) {
                FuelLookupPill(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                )
            }
        }
    }

    portionFood?.let { food ->
        PortionSheet(
            food = food,
            meal = meal,
            day = day,
            onAdded = {
                portionFood = null
                onDismiss()
            },
            onDismiss = { portionFood = null },
        )
    }

    if (showsScanner) {
        NtSheet(onDismiss = { showsScanner = false }, containerColor = NT.Colors.ground) {
            BarcodeScannerScreen(
                onCode = { code ->
                    showsScanner = false
                    scope.launch {
                        try {
                            val saved = model.savedBarcode(code)
                            if (saved != null) portionFood = PortionFood.Item(saved)
                            else {
                                val found = model.lookup(code)
                                if (found != null) portionFood = PortionFood.Candidate(found)
                                else {
                                    missingBarcode = app.notomorrow.service.FoodSearchService.barcodeForms(code).firstOrNull() ?: code
                                    barcodeNotFound = true
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { lookupError = true }
                    }
                },
                onCancel = { showsScanner = false },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }

    if (showsQuickAdd) {
        QuickAddSheet(
            meal = meal,
            day = day,
            initialName = state.query.trim(),
            onAdded = {
                showsQuickAdd = false
                onDismiss()
            },
            onDismiss = { showsQuickAdd = false },
        )
    }

    if (showsLabel) {
        ProductLabelSheet(missingBarcode, onDismiss = { showsLabel = false }) { name, values ->
            val saved = model.saveLabel(missingBarcode, name, values)
            showsLabel = false
            portionFood = PortionFood.Item(saved)
        }
    }
    if (lookupError) {
        NtAlert(title = stringResource(S.fuel_search_error_network),
            actions = listOf(NtAlertAction(title = stringResource(S.common_cancel), role = NtAlertRole.Cancel)),
            onDismiss = { lookupError = false })
    }
    if (barcodeNotFound) {
        NtAlert(
            title = stringResource(S.fuel_barcodeNotFound),
            actions = listOf(
                NtAlertAction(title = stringResource(app.notomorrow.R.string.fuel_label_title), onClick = { barcodeNotFound = false; showsLabel = true }),
                NtAlertAction(
                    title = stringResource(S.fuel_quickAdd),
                    onClick = { showsQuickAdd = true },
                ),
                NtAlertAction(title = stringResource(S.common_cancel), role = NtAlertRole.Cancel),
            ),
            onDismiss = { barcodeNotFound = false },
        )
    }
}

/** `FoodSearchView.header` — "Add to Lunch" + Cancel, 44 dp tall. */
@Composable
private fun FoodSearchHeader(meal: MealSlot, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .padding(horizontal = NT.Spacing.screenH)
            .height(NT.Size.control),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(
            text = stringResource(S.fuel_addTo, stringResource(NtKeys.meal(meal))),
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

/** `FoodSearchView.searchRow` — 44 dp field with a clear button, plus the 44 dp barcode tile. */
@Composable
private fun FoodSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onBarcode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(NT.Size.control)
                .background(NT.Colors.surface, NtShapes.field)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtIcon(NtIcons.MagnifyingGlass, size = sfIconSize(16f), tint = NT.Colors.ink2)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    NtText(
                        text = stringResource(S.fuel_searchProduct),
                        style = NT.Fonts.body,
                        color = NT.Colors.ink3,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = NT.Fonts.body.copy(color = NT.Colors.ink),
                    singleLine = true,
                    cursorBrush = SolidColor(NT.Colors.ink),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = NT.Size.control)
                        .ntPlainClickable(onClick = { onQuery("") }),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(20.dp).background(NT.Colors.ink3, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        NtIcon(NtIcons.Xmark, size = sfIconSize(9f), tint = NT.Colors.ground)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(NT.Size.control)
                .pressScale(
                    onClickLabel = stringResource(S.fuel_barcode),
                    onClick = onBarcode,
                )
                .background(NT.Colors.surface, NtShapes.field),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(NtIcons.BarcodeViewfinder, size = sfIconSize(20f), tint = NT.Colors.ink)
        }
    }
}

/** `FoodSearchView.content` — Recent, then the phase-driven Products section. */
@Composable
private fun FoodSearchContent(
    state: FoodSearchUiState,
    onCandidate: (FoodCandidate) -> Unit,
    onItem: (FoodItemEntity) -> Unit,
    onRetry: () -> Unit,
    onQuickAdd: () -> Unit,
) {
    val hasRecent = state.recent.isNotEmpty()
    val sectionTop = if (hasRecent) 16.dp else 0.dp

    if (hasRecent) {
        FoodSectionLabel(stringResource(S.fuel_recent))
        state.recent.forEach { item ->
            FoodRecentRow(item = item, onAdd = { onItem(item) })
        }
    }

    if (state.canSearch) {
        when (val phase = state.phase) {
            FoodSearchPhase.Idle, FoodSearchPhase.Loading -> {
                FoodSectionLabel(
                    text = stringResource(S.fuel_products),
                    modifier = Modifier.padding(top = sectionTop),
                )
                FoodStateRow(kind = FoodStateKind.Loading)
            }

            is FoodSearchPhase.Results -> {
                FoodSectionLabel(
                    text = stringResource(S.fuel_products) + " · " +
                        stringResource(S.fuel_found, phase.hits.size),
                    modifier = Modifier.padding(top = sectionTop),
                )
                phase.hits.forEach { hit ->
                    FoodResultRow(candidate = hit, onAdd = { onCandidate(hit) })
                }
            }

            FoodSearchPhase.Empty -> FoodStateRow(
                kind = FoodStateKind.NotFound,
                modifier = Modifier.padding(top = sectionTop),
                onQuickAdd = onQuickAdd,
            )

            is FoodSearchPhase.Error -> FoodStateRow(
                kind = FoodStateKind.Error(phase.messageRes),
                modifier = Modifier.padding(top = sectionTop),
                onRetry = onRetry,
                onQuickAdd = onQuickAdd,
            )
        }
    } else if (!hasRecent) {
        FoodStateRow(kind = FoodStateKind.Hint, onQuickAdd = onQuickAdd)
    }
}
