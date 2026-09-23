package app.notomorrow.feature.fuel

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.di.ntViewModel
import app.notomorrow.model.MealSlot
import app.notomorrow.util.rememberCurrentDayAndZone
import kotlinx.coroutines.delay
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
            todayRequests = container.appState.fuelTodayRequests,
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    val pendingUndo by model.pendingUndo.collectAsStateWithLifecycle()
    val barcodeState by model.barcode.state.collectAsStateWithLifecycle()

    var sheet by remember { mutableStateOf<FuelSheet?>(null) }
    val tabBarHeight = LocalTabBarHeight.current

    // Midnight rollover: on appear, on a date/time/zone change and on every resume. Keyed on the
    // zone too, so a zone change on the same date still re-keys the day queries.
    val currentDay = rememberCurrentDayAndZone()
    LaunchedEffect(currentDay) { model.syncToday() }
    FuelBackgroundEffect(model)

    // `.sensoryFeedback(.selection, trigger: dayChanges)`: a tick for every day change the user
    // makes (chevrons, swipe, the Today pill, a History cell) — rollovers and the Dashboard jump
    // stay silent because they never come through here.
    val haptics = LocalHapticFeedback.current
    val changeDay: (() -> Boolean) -> Unit = { change ->
        if (change()) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    val swipe = Modifier.dayNavigationSwipe(
        onPrevious = { changeDay(model::goPreviousDay) },
        onNext = { changeDay(model::goNextDay) },
    )

    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        ) {
            FuelHeader(
                day = state.day,
                today = state.today,
                canGoForward = state.canGoForward,
                streak = state.proteinStreak,
                onPreviousDay = { changeDay(model::goPreviousDay) },
                onNextDay = { changeDay(model::goNextDay) },
                onOpenCalendar = { sheet = FuelSheet.Calendar },
                onToday = { changeDay(model::goToday) },
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
                // "Log again today" and "Copy to today" exist only while a past day is on screen.
                val isPastDay = state.day != state.today
                state.slots.forEachIndexed { index, slotUi ->
                    val isLast = index == state.slots.lastIndex

                    item(key = "header-${slotUi.slot.raw}") {
                        FuelMealHeaderRow(
                            slot = slotUi.slot,
                            kcal = slotUi.kcal,
                            isEmpty = slotUi.entries.isEmpty(),
                            onOpen = { sheet = FuelSheet.Search(slotUi.slot, state.day) },
                            modifier = Modifier.padding(
                                start = NT.Spacing.screenH,
                                end = NT.Spacing.screenH,
                                top = 4.dp,
                            ),
                            onCopyToToday = if (isPastDay) {
                                { model.copyToToday(slotUi.entries.map { it.id }) }
                            } else {
                                null
                            },
                        )
                    }

                    items(slotUi.entries, key = { it.id }) { entry ->
                        val edit = { sheet = FuelSheet.Edit(entry.id, hasFood = entry.hasFood) }
                        val delete = { model.delete(entry.id) }
                        EntrySwipeRow(onEdit = edit, onDelete = delete) {
                            FuelEntryRow(
                                entry = entry,
                                onEdit = edit,
                                onDelete = delete,
                                onLogAgain = if (isPastDay) {
                                    { model.logAgainToday(entry.id) }
                                } else {
                                    null
                                },
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
            onAIPhoto = { sheet = FuelSheet.AIScan(suggestedMealSlot(), state.day) },
            onBarcode = { sheet = FuelSheet.Barcode(suggestedMealSlot(), state.day) },
            onSearch = { sheet = FuelSheet.Search(suggestedMealSlot(), state.day) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = tabBarHeight),
        )

        // The undo toast and the barcode lookup pill share one spot above the add bar; when both
        // show, they stack 8 dp apart with the toast on top (`VStack(spacing: 8)` on iOS).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp + tabBarHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // `.transition(.move(edge: .bottom).combined(with: .opacity))`. The last undo stays
            // drawn while the exit animation runs.
            val shownUndo = rememberLastNonNull(pendingUndo)
            AnimatedVisibility(
                visible = pendingUndo != null,
                enter = slideInVertically(tween(200, easing = NT.Ease.out)) { it } + fadeIn(NT.Anim.easeOut20),
                exit = slideOutVertically(tween(200, easing = NT.Ease.out)) { it } + fadeOut(NT.Anim.easeOut20),
            ) {
                shownUndo?.let { undo ->
                    FuelUndoToast(
                        message = stringResource(undo.messageRes),
                        onUndo = model::undo,
                        modifier = Modifier.padding(bottom = if (barcodeState.isLookingUp) 8.dp else 0.dp),
                    )
                }
            }
            // `lookupPill.transition(.opacity)`.
            AnimatedVisibility(
                visible = barcodeState.isLookingUp,
                enter = fadeIn(NT.Anim.easeOut60),
                exit = fadeOut(NT.Anim.easeOut60),
            ) {
                FuelLookupPill()
            }
        }
    }

    UndoTimerEffect(undo = pendingUndo, onExpire = model::expireUndo)

    FuelSheetHost(
        sheet = sheet,
        onDismiss = { sheet = null },
        onBarcode = { scan, code ->
            sheet = null
            model.lookupBarcode(code, scan.meal, scan.day)
        },
        // The edit sheets' Delete: the same delete as the row's, so the undo toast offers it back.
        onDeleteEntry = { entryId ->
            sheet = null
            model.delete(entryId)
        },
    )

    if (sheet == FuelSheet.Calendar) {
        // Collected only while the sheet is up, so the kcal-per-day query runs only then.
        val kcalByDay by model.calendarKcal.collectAsStateWithLifecycle()
        FuelCalendarSheet(
            selectedDay = state.day,
            today = state.today,
            kcalGoal = state.goals.kcal,
            goal = state.trainingGoal,
            kcalByDay = kcalByDay,
            onSelect = { date ->
                sheet = null
                changeDay { model.goTo(date) }
            },
            onDismiss = { sheet = null },
        )
    }

    state.portionFood?.let { food ->
        PortionSheet(
            food = food,
            meal = state.lookupMeal,
            day = state.lookupDay,
            onDismiss = model::clearPortionFood,
        )
    }

    // The lookup's alert (partial / not found / failed) and the label form.
    BarcodeLookupPrompts(
        flow = model.barcode,
        state = barcodeState,
        onFood = model::openPortion,
        onQuickAdd = { name -> sheet = FuelSheet.QuickAdd(state.lookupMeal, state.lookupDay, name) },
    )
}

/**
 * `FuelHomeView.FuelSheet` — the six things the tab can present.
 *
 * The logging sheets carry the **day** they were opened on: the 30-minute snap-back and the
 * midnight rollover move the tab's selected day while a sheet is up, and the sheet must still log
 * to the day the user opened it for.
 */
sealed interface FuelSheet {
    data class Search(val meal: MealSlot, val day: LocalDate) : FuelSheet
    data class AIScan(val meal: MealSlot, val day: LocalDate) : FuelSheet
    data class Barcode(val meal: MealSlot, val day: LocalDate) : FuelSheet
    /** [name] pre-fills the form: the product a partial barcode hit found. */
    data class QuickAdd(val meal: MealSlot, val day: LocalDate, val name: String = "") : FuelSheet

    /** A tap on a logged row; [hasFood] picks which of the two edit sheets can size it. */
    data class Edit(val entryId: String, val hasFood: Boolean) : FuelSheet

    /** The header's date button: the History grid ([FuelCalendarSheet]), hosted by the screen. */
    data object Calendar : FuelSheet
}

/**
 * The 30-minute snap-back's two ends: the **activity's** `ON_STOP` / `ON_START` — iOS's
 * `didEnterBackground` / `willEnterForeground`. Not the tab's own lifecycle owner, which is the
 * navigation entry and also stops while the workout cover sits on top of it.
 */
@Composable
private fun FuelBackgroundEffect(model: FuelViewModel) {
    val activity = LocalActivity.current as? LifecycleOwner ?: return
    DisposableEffect(activity, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> model.appDidEnterBackground()
                Lifecycle.Event.ON_START -> model.appWillEnterForeground()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun FuelSheetHost(
    sheet: FuelSheet?,
    onDismiss: () -> Unit,
    onBarcode: (FuelSheet.Barcode, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    when (sheet) {
        null -> Unit

        is FuelSheet.Search -> FoodSearchSheet(
            meal = sheet.meal,
            day = sheet.day,
            onDismiss = onDismiss,
        )

        is FuelSheet.QuickAdd -> QuickAddSheet(
            meal = sheet.meal,
            day = sheet.day,
            onDismiss = onDismiss,
            initialName = sheet.name,
        )

        is FuelSheet.Edit -> if (sheet.hasFood) {
            PortionEditSheet(
                entryId = sheet.entryId,
                onDismiss = onDismiss,
                onDelete = { onDeleteEntry(sheet.entryId) },
            )
        } else {
            QuickAddEditSheet(
                entryId = sheet.entryId,
                onDismiss = onDismiss,
                onDelete = { onDeleteEntry(sheet.entryId) },
            )
        }

        is FuelSheet.Barcode -> NtSheet(
            onDismiss = onDismiss,
            containerColor = NT.Colors.ground,
        ) {
            BarcodeScannerScreen(
                onCode = { code -> onBarcode(sheet, code) },
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
            AIScanScreen(meal = sheet.meal, onDismiss = onDismiss, day = sheet.day)
        }

        // Needs the view model's calendar read, so [FuelHomeScreen] presents it.
        FuelSheet.Calendar -> Unit
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
    // The callers pass fresh lambdas every composition; reading them through state keeps the
    // gesture detector alive across recompositions instead of restarting it mid-drag.
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    return this.pointerInput(threshold) {
        var dx = 0f
        detectHorizontalDragGestures(
            onDragStart = { dx = 0f },
            onDragEnd = {
                if (abs(dx) > threshold) {
                    if (dx > 0) previous() else next()
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
 * The entry row's two `.swipeActions` on the single swipeable site in the app: a leading swipe
 * edits (`surface3`, pencil), a trailing one deletes (`bad`, trash) — the toast then offers Undo.
 *
 * `SwipeToDismissBox` has no resting-open value, so the revealed button cannot be tapped — the
 * accepted first-build behaviour per research §6.7; releasing past the threshold acts instead,
 * which is what `allowsFullSwipe` does on iOS. The threshold is **half the row, whatever the
 * speed** ([FuelDerive.swipeCommits]): Material commits any flick faster than 125 dp/s, and an
 * 83 dp flick used to delete a past entry outright. An edit swipe opens the sheet and springs back.
 *
 * The state is a plain `remember`, not the saveable one: a deleted row's key comes back on Undo,
 * and a restored "dismissed" value would draw it swiped off. It uses the `confirmValueChange`
 * constructor (deprecated in Material 1.4) because that callback is the only place that still
 * sees the release offset — the replacement settles on velocity before anyone can veto it.
 */
@Suppress("DEPRECATION")
@Composable
private fun EntrySwipeRow(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val edit by rememberUpdatedState(onEdit)
    val delete by rememberUpdatedState(onDelete)
    val width = remember { mutableFloatStateOf(0f) }
    val state = remember(density) {
        lateinit var self: SwipeToDismissBoxState
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            confirmValueChange = { value ->
                // The release offset; a state that has not been laid out yet has none (NaN throws).
                val offset = runCatching { self.requireOffset() }.getOrDefault(0f)
                when {
                    value == SwipeToDismissBoxValue.Settled -> true
                    !FuelDerive.swipeCommits(offset, width.floatValue) -> false
                    value == SwipeToDismissBoxValue.EndToStart -> {
                        delete()
                        true
                    }
                    else -> {
                        edit()
                        false
                    }
                }
            },
            positionalThreshold = { total -> total * FuelDerive.SWIPE_COMMIT_FRACTION },
        ).also { self = it }
    }
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.onSizeChanged { width.floatValue = it.width.toFloat() },
        backgroundContent = {
            when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> FuelEditBackground(Modifier.fillMaxSize())
                SwipeToDismissBoxValue.EndToStart -> FuelDeleteBackground(Modifier.fillMaxSize())
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
    ) {
        content()
    }
}

/**
 * [value], or the last non-null one while it is null — what an exit animation keeps drawing. A
 * plain holder, not state: remembering it must not recompose anything.
 */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    @Suppress("UNCHECKED_CAST")
    return last[0] as T?
}

/**
 * `.task(id: pendingUndo?.id)`: one timer per undo — a newer delete or copy restarts it, and an
 * expiry names its own undo so it never ends a newer one. 4 s, or 10 s while TalkBack runs (it
 * needs time to reach the button), stretched further by the system's "time to take action".
 * Each undo is also announced to TalkBack once, when it arrives.
 */
@Composable
private fun UndoTimerEffect(undo: FuelUndo?, onExpire: (String) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val accessibility = LocalAccessibilityManager.current
    val expire by rememberUpdatedState(onExpire)
    LaunchedEffect(undo?.id) {
        val id = undo?.id ?: return@LaunchedEffect
        // Every new undo is announced (iOS posts an announcement too). A live region would stay
        // silent for a second delete in a row: the toast is already up and its text is the same.
        @Suppress("DEPRECATION")
        view.announceForAccessibility(context.resources.getString(undo.messageRes))
        val screenReader = context.getSystemService(AccessibilityManager::class.java)
            ?.isTouchExplorationEnabled == true
        val base = FuelDerive.undoDurationMs(screenReader)
        val duration = accessibility?.calculateRecommendedTimeoutMillis(
            base,
            containsText = true,
            containsControls = true,
        ) ?: base
        delay(duration)
        expire(id)
    }
}
