package app.notomorrow.feature.fuel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Badge
import app.notomorrow.designsystem.KcalLabel
import app.notomorrow.designsystem.MacroBar
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtMenu
import app.notomorrow.designsystem.NtMenuItem
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ProgressRing
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.MealSlot
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.LocalDate

/*
 * The pieces of `FuelHomeView` / `FuelHomeSubviews.swift`. Geometry is load-bearing:
 * every number here is the Swift number, 1 pt -> 1 dp.
 */

/** Height of [FuelAddBar]: 12 top + 56 tile + 10 bottom (`FuelHomeSubviews.swift:141`). */
val FuelAddBarHeight = 12.dp + NT.Size.primaryButton + 10.dp

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `FuelHomeView.header`, two rows:
 *  1. `‹ [▦ Today ▾] ›  …  (Today)` — 44 dp chevrons around the date capsule that opens the
 *     History sheet, and the Today pill, which fades in only on a past day;
 *  2. `fuel.title` and the protein-streak chip pinned to the bottom of the row.
 */
@Composable
fun FuelHeader(
    day: LocalDate,
    today: LocalDate,
    canGoForward: Boolean,
    streak: Int,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenCalendar: () -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(NT.Size.control),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The pill is measured first (it keeps its natural width, `fixedSize()`); the chevron
            // group gets the rest — `Spacer(minLength: 0)` between them on iOS.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Row(
                    // Pulls the first chevron's glyph back towards the title's left edge; the
                    // 44 dp target stays whole and the row still accounts for the group's width.
                    modifier = Modifier.pullStart(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DayChevron(
                        icon = NtIcons.ChevronLeft,
                        label = stringResource(S.fuel_previousDay),
                        enabled = true,
                        onClick = onPreviousDay,
                    )
                    DateButton(
                        day = day,
                        today = today,
                        onClick = onOpenCalendar,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    DayChevron(
                        icon = NtIcons.ChevronRight,
                        label = stringResource(S.fuel_nextDay),
                        enabled = canGoForward,
                        onClick = onNextDay,
                    )
                }
            }
            // `todayPill.transition(.opacity)` under `.animation(.easeOut(duration: 0.2))`.
            AnimatedVisibility(
                visible = day != today,
                enter = fadeIn(NT.Anim.easeOut20),
                exit = fadeOut(NT.Anim.easeOut20),
            ) {
                TodayPill(onClick = onToday)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            NtText(
                text = stringResource(S.fuel_title),
                style = NT.Fonts.largeTitle,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            StreakChip(streak = streak, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/**
 * SwiftUI's `.padding(.leading, -amount)`: the content starts [amount] earlier and reports
 * [amount] less width, so the layout around it accounts for the shift (an `offset` would not).
 */
private fun Modifier.pullStart(amount: Dp): Modifier = layout { measurable, constraints ->
    val pull = amount.roundToPx()
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(maxWidth = constraints.maxWidth + pull)
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - pull).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.placeRelative(-pull, 0) }
}

/** 44 × 44 hit box, 15 sp SemiBold glyph in `ink`, `ink3 @ 0.4` when the day cannot move that way. */
@Composable
private fun DayChevron(
    icon: NtIcons,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(NT.Size.control)
            .ntPlainClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(
            icon = icon,
            size = sfIconSize(15f),
            tint = if (enabled) NT.Colors.ink else NT.Colors.ink3.copy(alpha = 0.4f),
            contentDescription = label,
        )
    }
}

/**
 * `FuelHomeView.dateButton`: "▦ Today ▾" on a 32 dp `surface` capsule inside the 44 dp target;
 * opens the History sheet. Read as "Choose day", valued with the long date.
 */
@Composable
private fun DateButton(
    day: LocalDate,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberNtStrings()
    val label = stringResource(S.fuel_chooseDay)
    val value = Fmt.longDay(day)
    Box(
        modifier = modifier
            .height(NT.Size.control)
            .pressScale(onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = value
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .background(NT.Colors.surface, CircleShape)
                .padding(horizontal = 12.dp)
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtIcon(NtIcons.Calendar, size = sfIconSize(12f), tint = NT.Colors.ink2)
            ShrinkingText(
                text = Fmt.dayTitle(day, strings, today = today),
                style = NT.Fonts.footnoteBold,
                color = NT.Colors.ink,
                minScale = 0.8f,
                modifier = Modifier.weight(1f, fill = false),
            )
            NtIcon(NtIcons.ChevronDown, size = sfIconSize(10f), tint = NT.Colors.ink3)
        }
    }
}

/** `FuelHomeView.todayPill`: `day.today` on a 32 dp `surface2` capsule, read as "Go to today". */
@Composable
private fun TodayPill(onClick: () -> Unit) {
    val label = stringResource(S.fuel_goToToday)
    Box(
        modifier = Modifier
            .height(NT.Size.control)
            .pressScale(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .background(NT.Colors.surface2, CircleShape)
                .padding(horizontal = 14.dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.day_today),
                style = NT.Fonts.footnoteBold,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
    }
}

/** `FuelHomeView.streakChip` — `target` in ember + the streak line, 32 dp `surface` capsule. */
@Composable
private fun StreakChip(streak: Int, modifier: Modifier = Modifier) {
    val text = when (streak) {
        0 -> stringResource(S.fuel_streak_zero)
        1 -> stringResource(S.fuel_streak_one)
        else -> stringResource(S.fuel_streak_other, streak)
    }
    Row(
        modifier = modifier
            .height(32.dp)
            .background(NT.Colors.surface, CircleShape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(NtIcons.Target, size = sfIconSize(13f), tint = NT.Colors.ember)
        TabularText(text = text, style = NT.Fonts.footnote, color = NT.Colors.ink)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hero: ring + macro bars
// ─────────────────────────────────────────────────────────────────────────────

/** `FuelHeroView` (`FuelHomeSubviews.swift:5`). */
@Composable
fun FuelHeroView(state: FuelUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            ProgressRing(
                progress = state.ringProgress,
                modifier = Modifier.size(132.dp),
                lineWidth = 10.dp,
                color = NT.Colors.ember,
                track = NT.Colors.surface,
            )
            Column(
                modifier = Modifier.width(100.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ShrinkingText(
                    text = Fmt.kcal(state.kcalLeft, withUnit = false),
                    style = NT.Fonts.display(44),
                    color = NT.Colors.ink,
                    minScale = 0.6f,
                )
                NtText(
                    text = stringResource(S.fuel_kcalLeft),
                    style = NT.Fonts.caption,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MacroBar(
                label = stringResource(S.macro_protein),
                value = state.proteinEaten,
                goal = state.goals.protein,
                fill = NT.Colors.ink,
            )
            MacroBar(
                label = stringResource(S.macro_carbs),
                value = state.carbsEaten,
                goal = state.goals.carbs,
                fill = NT.Colors.ink2,
            )
            MacroBar(
                label = stringResource(S.macro_fat),
                value = state.fatEaten,
                goal = state.goals.fat,
                fill = NT.Colors.ink2,
            )
            TabularText(
                text = stringResource(
                    S.fuel_eatenGoal,
                    Fmt.kcal(state.kcalEaten, withUnit = false),
                    Fmt.kcal(state.goals.kcal, withUnit = false),
                ),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Meal rows
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The slot header: a tappable name on the left with "Nothing yet" or the slot's kcal on the right
 * (opens the search sheet), then — on a past day, for a slot with entries — the "Copy to today"
 * icon button ([onCopyToToday] is null on today).
 */
@Composable
fun FuelMealHeaderRow(
    slot: MealSlot,
    kcal: Double,
    isEmpty: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyToToday: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `.frame(minHeight: NT.Size.control)` centres the whole `HStack` in the enlarged row. A
        // `defaultMinSize` on the `Row` itself would not: once a child declares `alignByBaseline`,
        // Compose pins the baseline group to the top of the grown row.
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = NT.Size.control)
                .ntPlainClickable(onClick = onOpen),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                NtText(
                    text = stringResource(NtKeys.meal(slot)),
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.headline,
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                if (isEmpty) {
                    NtText(
                        text = stringResource(S.fuel_nothingYet),
                        modifier = Modifier.alignByBaseline(),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink3,
                        maxLines = 1,
                    )
                } else {
                    KcalLabel(kcal = kcal)
                }
            }
        }
        if (onCopyToToday != null && !isEmpty) {
            val label = stringResource(S.fuel_copyToToday)
            // A 44 dp target with the glyph at its trailing edge, flush with the rows below.
            Box(
                modifier = Modifier
                    .size(NT.Size.control)
                    .ntPlainClickable(role = Role.Button, onClick = onCopyToToday),
                contentAlignment = Alignment.CenterEnd,
            ) {
                NtIcon(
                    icon = NtIcons.PlusSquareOnSquare,
                    size = sfIconSize(15f),
                    tint = NT.Colors.ink2,
                    contentDescription = label,
                )
            }
        }
    }
}

/**
 * `FuelEntryRow` (`FuelHomeSubviews.swift`): name, grams, the "est" badge and kcal, then a small
 * trailing chevron that says the row opens something. A tap edits; a long press opens the menu
 * — Edit · Log again today (past days only, [onLogAgain] non-null) · Delete — the Android reading
 * of iOS's `.contextMenu`. TalkBack gets the menu's other two entries as custom actions, as
 * VoiceOver gets iOS's swipe actions.
 *
 * [modifier] carries the row's background and side padding; the menu anchors to the padded
 * content, so it opens over the row, inset like the text.
 */
@Composable
fun FuelEntryRow(
    entry: FuelEntryUi,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLogAgain: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val editLabel = stringResource(S.common_edit)
    // TalkBack: "Double-tap to <action>", so the tap gets an infinitive phrase, not the menu title.
    val editAction = stringResource(S.fuel_editEntry_action)
    val logAgainLabel = stringResource(S.fuel_logAgainToday)
    val deleteLabel = stringResource(S.common_delete)
    // The chevron's centre sits on the text's cap-height middle, like an SF Symbol inline with
    // `footnote` (an image has no baseline, so it is aligned by its own height).
    val chevronLift = with(LocalDensity.current) { 4.5.dp.roundToPx() }

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 30.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = editAction,
                    onClick = onEdit,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .semantics {
                    customActions = listOfNotNull(
                        onLogAgain?.let { action ->
                            CustomAccessibilityAction(logAgainLabel) { action(); true }
                        },
                        CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            NtText(
                text = entry.name,
                modifier = Modifier.alignByBaseline(),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            if (entry.grams > 0) {
                TabularText(
                    text = "· " + Fmt.grams(entry.grams),
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }
            if (entry.isAIEstimate) {
                // `HStack(alignment: .firstTextBaseline)` aligns the chip's own text baseline.
                Badge(
                    text = stringResource(S.fuel_est),
                    modifier = Modifier.alignByBaseline(),
                    color = NT.Colors.ink2,
                )
            }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            TabularText(
                text = Fmt.kcal(entry.kcal, withUnit = false),
                modifier = Modifier.alignByBaseline(),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
            NtIcon(
                icon = NtIcons.ChevronRight,
                modifier = Modifier.alignBy { it.measuredHeight / 2 + chevronLift },
                size = sfIconSize(11f),
                tint = NT.Colors.ink3,
                contentDescription = null,
            )
        }
        NtMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOfNotNull(
                NtMenuItem(title = editLabel, onClick = onEdit, icon = NtIcons.Pencil),
                onLogAgain?.let {
                    NtMenuItem(title = logAgainLabel, onClick = it, icon = NtIcons.PlusSquareOnSquare)
                },
                NtMenuItem(
                    title = deleteLabel,
                    onClick = onDelete,
                    destructive = true,
                    icon = NtIcons.Trash,
                    separatorBefore = true,
                ),
            ),
        )
    }
}

/**
 * `MealSlotPicker` (`FuelSupport.swift:184`): four equal capsules, the selected one on
 * `surface3`. Both edit sheets use it to move a logged entry to another meal.
 */
@Composable
fun MealSlotPicker(
    slot: MealSlot,
    onSelect: (MealSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(S.fuel_mealSlot)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MealSlotOrdered.forEach { candidate ->
            val isSelected = candidate == slot
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .pressScale(onClick = { onSelect(candidate) })
                    .background(
                        if (isSelected) NT.Colors.surface3 else NT.Colors.surface2,
                        CircleShape,
                    )
                    .semantics { selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                NtText(
                    text = stringResource(NtKeys.meal(candidate)),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * `EntryDayStepper` (`FuelSupport.swift`): "Day   ‹ Yesterday ›" — moves a logged entry to another
 * day from the edit sheets. Past days and today only, so `›` dims on today. The value reads
 * Today / Yesterday / "Mon, 21 Sep" ([Fmt.dayTitle]); every change ticks.
 *
 * One TalkBack node, like iOS's adjustable element: "Day, <long date>", with the previous / next
 * day as custom actions.
 *
 * The portion sheet uses the default 44 dp on `surface2` (the slot capsules' fill); the quick-add
 * sheet passes 52 dp on `surface`, the same as its field rows.
 */
@Composable
fun EntryDayStepper(
    day: LocalDate,
    today: LocalDate,
    onChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = NT.Size.control,
    background: Color = NT.Colors.surface2,
) {
    val strings = rememberNtStrings()
    val haptics = LocalHapticFeedback.current
    val label = stringResource(S.fuel_day)
    val previousLabel = stringResource(S.fuel_previousDay)
    val nextLabel = stringResource(S.fuel_nextDay)
    val value = Fmt.longDay(day)
    val canGoForward = day < today
    val step: (Long) -> Unit = { days ->
        val target = FuelDerive.stepDay(day, days, today)
        if (target != day) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onChange(target)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(background, NtShapes.field)
            .padding(start = 14.dp)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = value
                customActions = listOfNotNull(
                    CustomAccessibilityAction(previousLabel) { step(-1); true },
                    if (canGoForward) CustomAccessibilityAction(nextLabel) { step(1); true } else null,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(text = label, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        StepperChevron(icon = NtIcons.ChevronLeft, enabled = true, onClick = { step(-1) })
        // `.frame(minWidth: 88)`: the value centres in at least 88 dp and shrinks to 0.8 first.
        Box(Modifier.widthIn(min = 88.dp), contentAlignment = Alignment.Center) {
            ShrinkingText(
                text = Fmt.dayTitle(day, strings, today = today),
                style = NT.Fonts.footnoteBold,
                color = NT.Colors.ink,
                minScale = 0.8f,
            )
        }
        StepperChevron(icon = NtIcons.ChevronRight, enabled = canGoForward, onClick = { step(1) })
    }
}

/** The stepper's 44 × 44 chevron: 15 sp glyph in `ink`, `ink3 @ 0.4` when disabled. */
@Composable
private fun StepperChevron(icon: NtIcons, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(NT.Size.control)
            .ntPlainClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(
            icon = icon,
            size = sfIconSize(15f),
            tint = if (enabled) NT.Colors.ink else NT.Colors.ink3.copy(alpha = 0.4f),
        )
    }
}

/** The ember hint under the first empty slot. */
@Composable
fun FuelProteinHint(grams: Double, modifier: Modifier = Modifier) {
    TabularText(
        text = stringResource(S.fuel_proteinToGo, Fmt.grams(grams)),
        modifier = modifier,
        style = NT.Fonts.footnoteBold,
        color = NT.Colors.ember,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Add bar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `FuelAddBar` (`FuelHomeSubviews.swift:135`): three 56 dp tiles over the app's only other
 * gradient — a `ground` scrim that fades in over the top half of the bar.
 */
@Composable
fun FuelAddBar(
    onAIPhoto: () -> Unit,
    onBarcode: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to NT.Colors.ground.copy(alpha = 0f),
                    0.5f to NT.Colors.ground,
                    1f to NT.Colors.ground,
                ),
            )
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AddTile(
            title = stringResource(S.fuel_aiPhoto),
            icon = NtIcons.Camera,
            primary = true,
            modifier = Modifier.weight(1f),
            onClick = onAIPhoto,
        )
        AddTile(
            title = stringResource(S.fuel_barcode),
            icon = NtIcons.BarcodeViewfinder,
            primary = false,
            modifier = Modifier.weight(1f),
            onClick = onBarcode,
        )
        AddTile(
            title = stringResource(S.fuel_search),
            icon = NtIcons.MagnifyingGlass,
            primary = false,
            modifier = Modifier.weight(1f),
            onClick = onSearch,
        )
    }
}

@Composable
private fun AddTile(
    title: String,
    icon: NtIcons,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = NtShapes.rounded(18.dp)
    val tint = if (primary) NT.Colors.onPrimary else NT.Colors.ink
    Column(
        modifier = modifier
            .height(NT.Size.primaryButton)
            .pressScale(onClick = onClick)
            .background(if (primary) NT.Colors.ink else NT.Colors.surface, shape)
            .then(
                if (primary) Modifier else Modifier.border(1.dp, NT.Colors.hairline, shape),
            ),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NtIcon(icon, size = sfIconSize(20f), tint = tint)
        NtText(
            text = title,
            // `.font(NT.Fonts.caption).fontWeight(.semibold)` — caption is Medium by default.
            style = NT.Fonts.caption.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
        )
    }
}
