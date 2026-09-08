package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Badge
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.KcalLabel
import app.notomorrow.designsystem.MacroBar
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
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
 * `FuelHomeView.header`: the eyebrow date between two day chevrons, `fuel.title`, and the
 * protein-streak chip pinned to the bottom of the row.
 */
@Composable
fun FuelHeader(
    day: LocalDate,
    canGoForward: Boolean,
    streak: Int,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.height(NT.Size.control),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DayChevron(
                    icon = NtIcons.ChevronLeft,
                    label = stringResource(S.fuel_previousDay),
                    enabled = true,
                    onClick = onPreviousDay,
                )
                Eyebrow(text = Fmt.shortDay(day))
                DayChevron(
                    icon = NtIcons.ChevronRight,
                    label = stringResource(S.fuel_nextDay),
                    enabled = canGoForward,
                    onClick = onNextDay,
                )
            }
            NtText(
                text = stringResource(S.fuel_title),
                style = NT.Fonts.largeTitle,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        StreakChip(streak = streak, modifier = Modifier.padding(bottom = 6.dp))
    }
}

/** 28 x 44 hit box, 12 sp Bold glyph, `ink3 @ 0.4` when the day cannot move that way. */
@Composable
private fun DayChevron(
    icon: NtIcons,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = NT.Size.control)
            .ntPlainClickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(
            icon = icon,
            size = sfIconSize(12f),
            tint = if (enabled) NT.Colors.ink2 else NT.Colors.ink3.copy(alpha = 0.4f),
            contentDescription = label,
        )
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

/** The tappable slot header: name on the left, "Nothing yet" or the slot's kcal on the right. */
@Composable
fun FuelMealHeaderRow(
    slot: MealSlot,
    kcal: Double,
    isEmpty: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // `.frame(minHeight: NT.Size.control)` centres the whole `HStack` in the enlarged row. A
    // `defaultMinSize` on the `Row` itself would not: once a child declares `alignByBaseline`,
    // Compose pins the baseline group to the top of the grown row.
    Box(
        modifier = modifier
            .fillMaxWidth()
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
}

/** `FuelEntryRow` (`FuelHomeSubviews.swift:112`). */
@Composable
fun FuelEntryRow(entry: FuelEntryUi, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 30.dp),
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
