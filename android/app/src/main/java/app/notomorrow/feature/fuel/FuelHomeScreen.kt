package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.di.ntViewModel
import app.notomorrow.model.MealSlot
import app.notomorrow.util.S
import java.time.LocalDate
import kotlin.math.abs

/**
 * The Fuel tab — the port of `Features/Fuel/FuelHomeView.swift`.
 *
 * The only screen in the app with a swipeable row, so the only `LazyColumn` that is not a
 * list of static rows. The per-row insets below encode the section rhythm exactly as
 * `FuelHomeSubviews.swift:52-108` does with `listRowInsets`.
 */
@Composable
fun FuelHomeScreen() {
    val model = ntViewModel { container ->
        FuelViewModel(
            foodDao = container.db.foodDao(),
            profileDao = container.db.profileDao(),
            mealDao = container.db.mealDao(),
            foodSearch = container.foodSearchService,
        )
    }
    val state by model.state.collectAsStateWithLifecycle()

    var sheet by remember { mutableStateOf<FuelSheet?>(null) }
    val tabBarHeight = LocalTabBarHeight.current

    LaunchedEffect(Unit) { model.refreshToday() }

    val swipe = Modifier.dayNavigationSwipe(
        onPrevious = model::goPreviousDay,
        onNext = model::goNextDay,
    )

    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        ) {
            FuelHeader(
                day = state.day,
                canGoForward = state.canGoForward,
                streak = state.proteinStreak,
                onPreviousDay = model::goPreviousDay,
                onNextDay = model::goNextDay,
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 8.dp)
                    .then(swipe),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = FuelAddBarHeight + tabBarHeight),
            ) {
                item(key = "hero") {
                    FuelHeroView(
                        state = state,
                        modifier = Modifier
                            .padding(
                                start = NT.Spacing.screenH,
                                end = NT.Spacing.screenH,
                                top = 20.dp,
                                bottom = 4.dp,
                            )
                            .then(swipe),
                    )
                }

                val firstEmpty = state.firstEmptySlot
                state.slots.forEachIndexed { index, slotUi ->
                    val isLast = index == state.slots.lastIndex

                    item(key = "header-${slotUi.slot.raw}") {
                        FuelMealHeaderRow(
                            slot = slotUi.slot,
                            kcal = slotUi.kcal,
                            isEmpty = slotUi.entries.isEmpty(),
                            onOpen = { sheet = FuelSheet.Search(slotUi.slot) },
                            modifier = Modifier.padding(
                                start = NT.Spacing.screenH,
                                end = NT.Spacing.screenH,
                                top = 4.dp,
                            ),
                        )
                    }

                    items(slotUi.entries, key = { it.id }) { entry ->
                        SwipeToDeleteRow(onDelete = { model.delete(entry.id) }) {
                            FuelEntryRow(
                                entry = entry,
                                modifier = Modifier
                                    .background(NT.Colors.ground)
                                    .padding(horizontal = NT.Spacing.screenH),
                            )
                        }
                    }

                    if (slotUi.entries.isEmpty() &&
                        firstEmpty == slotUi.slot &&
                        state.proteinRemaining > 0
                    ) {
                        item(key = "hint-${slotUi.slot.raw}") {
                            // The hint is a `List` row on iOS, so it inherits the 44 pt minimum
                            // row height with the text centred in it; the insets stay outside.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .heightIn(min = NT.Size.control),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                FuelProteinHint(
                                    grams = state.proteinRemaining,
                                    modifier = Modifier.padding(horizontal = NT.Spacing.screenH),
                                )
                            }
                        }
                    }

                    item(key = "rule-${slotUi.slot.raw}") {
                        if (!isLast) {
                            // The rule is its own `List` row on iOS: the 1 pt line is centred in
                            // a 44 pt minimum-height row, and the 8/4 insets sit outside that.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp)
                                    .heightIn(min = NT.Size.control),
                                contentAlignment = Alignment.Center,
                            ) {
                                Hairline(Modifier.padding(horizontal = NT.Spacing.screenH))
                            }
                        } else {
                            Spacer(Modifier.fillMaxWidth().height(24.dp))
                        }
                    }
                }
            }
        }

        FuelAddBar(
            onAIPhoto = { sheet = FuelSheet.AIScan(suggestedMealSlot()) },
            onBarcode = { sheet = FuelSheet.Barcode(suggestedMealSlot()) },
            onSearch = { sheet = FuelSheet.Search(suggestedMealSlot()) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = tabBarHeight),
        )

        // `lookupPill.transition(.opacity)`.
        AnimatedVisibility(
            visible = state.isLookingUpBarcode,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp + tabBarHeight),
            enter = fadeIn(NT.Anim.easeOut60),
            exit = fadeOut(NT.Anim.easeOut60),
        ) {
            FuelLookupPill()
        }
    }

    FuelSheetHost(
        sheet = sheet,
        day = state.day,
        onDismiss = { sheet = null },
        onBarcode = { meal, code ->
            sheet = null
            model.lookupBarcode(code, meal)
        },
    )

    state.portionFood?.let { food ->
        PortionSheet(
            food = food,
            meal = state.lookupMeal,
            day = state.day,
            onDismiss = model::clearPortionFood,
        )
    }

    var showsLabel by remember { mutableStateOf(false) }
    if (showsLabel) {
        ProductLabelSheet(state.missingBarcode, onDismiss = { showsLabel = false }) { name, values ->
            model.saveLabel(name, values)
            showsLabel = false
        }
    }
    if (state.lookupError) {
        NtAlert(title = stringResource(S.fuel_search_error_network),
            actions = listOf(NtAlertAction(title = stringResource(S.common_cancel), role = NtAlertRole.Cancel)),
            onDismiss = model::dismissLookupError)
    }
    if (state.barcodeNotFound) {
        val meal = state.lookupMeal
        NtAlert(
            title = stringResource(S.fuel_barcodeNotFound),
            actions = listOf(
                NtAlertAction(title = stringResource(app.notomorrow.R.string.fuel_label_title), onClick = { model.dismissNotFound(); showsLabel = true }),
                NtAlertAction(
                    title = stringResource(S.fuel_quickAdd),
                    onClick = { sheet = FuelSheet.QuickAdd(meal) },
                ),
                NtAlertAction(
                    title = stringResource(S.common_cancel),
                    role = NtAlertRole.Cancel,
                ),
            ),
            onDismiss = model::dismissNotFound,
        )
    }
}

/** `FuelHomeView.FuelSheet` — the four things the tab can present. */
sealed interface FuelSheet {
    val meal: MealSlot

    data class Search(override val meal: MealSlot) : FuelSheet
    data class AIScan(override val meal: MealSlot) : FuelSheet
    data class Barcode(override val meal: MealSlot) : FuelSheet
    data class QuickAdd(override val meal: MealSlot) : FuelSheet
}

@Composable
private fun FuelSheetHost(
    sheet: FuelSheet?,
    day: LocalDate,
    onDismiss: () -> Unit,
    onBarcode: (MealSlot, String) -> Unit,
) {
    when (sheet) {
        null -> Unit

        is FuelSheet.Search -> FoodSearchSheet(
            meal = sheet.meal,
            day = day,
            onDismiss = onDismiss,
        )

        is FuelSheet.QuickAdd -> QuickAddSheet(
            meal = sheet.meal,
            day = day,
            onDismiss = onDismiss,
        )

        is FuelSheet.Barcode -> NtSheet(
            onDismiss = onDismiss,
            containerColor = NT.Colors.ground,
        ) {
            BarcodeScannerScreen(
                onCode = { code -> onBarcode(sheet.meal, code) },
                onCancel = onDismiss,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Owned by the AI-scan agent (`AIScan*.kt`); presented here exactly as iOS presents
        // `AIScanView` from the Fuel tab.
        is FuelSheet.AIScan -> NtSheet(
            onDismiss = onDismiss,
            containerColor = NT.Colors.ground,
        ) {
            AIScanScreen(meal = sheet.meal, onDismiss = onDismiss, day = day)
        }
    }
}

/**
 * `FuelHomeView.dayNavigationSwipe`: a 40 dp horizontal drag moves one day; forward past
 * today is a no-op.
 *
 * Horizontal-only touch slop, so that a vertical drag that starts on the header or the hero
 * is never claimed here and falls through to the `LazyColumn` — the arbitration SwiftUI does
 * between the List's scroll gesture and a `DragGesture(minimumDistance: 40)`.
 */
@Composable
private fun Modifier.dayNavigationSwipe(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier {
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    return this.pointerInput(onPrevious, onNext, threshold) {
        var dx = 0f
        detectHorizontalDragGestures(
            onDragStart = { dx = 0f },
            onDragEnd = {
                if (abs(dx) > threshold) {
                    if (dx > 0) onPrevious() else onNext()
                }
            },
            onDragCancel = { dx = 0f },
        ) { change, drag ->
            change.consume()
            dx += drag
        }
    }
}

/**
 * `.swipeActions(edge: .trailing, allowsFullSwipe: true)` on the single site in the app.
 *
 * `SwipeToDismissBox` has no resting-open value, so the revealed button cannot be tapped —
 * the accepted first-build behaviour per research §6.7. The full swipe deletes, which is
 * what `allowsFullSwipe` does on iOS.
 */
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { FuelDeleteBackground(Modifier.fillMaxSize()) },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        content()
    }
}
