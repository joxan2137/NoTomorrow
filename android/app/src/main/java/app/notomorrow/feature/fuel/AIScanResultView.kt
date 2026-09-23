package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.ConfidenceDots
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NumericText
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIFood
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.time.LocalDate
import java.util.Locale

/**
 * `AIScanResultView` (`Features/Fuel/AIScanResultView.swift`) — the editable estimate: photo
 * with detection tags, hero total, one row per food (grams, count, edit, remove), "add something
 * it missed" from the food database, details + recalculate, the disclaimer, and the log bar.
 */
@Composable
fun AIScanResultView(
    state: AIScanUiState,
    onNotes: (String) -> Unit = {},
    onRefine: () -> Unit = {},
    onScale: (String, Double) -> Unit,
    onSetGrams: (String, Double) -> Unit,
    onStepCount: (String, Boolean) -> Unit,
    onUpdate: (AIFood) -> Unit,
    onRemove: (String) -> Unit,
    onAppend: (AIFood) -> Unit,
    onMeal: (MealSlot) -> Unit,
    onSaveRecipe: () -> Unit,
    onLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddMissed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AIFood?>(null) }

    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            AIScanPhoto(
                photo = state.photo,
                tags = state.foods.map { it.name },
                modifier = Modifier.padding(top = 12.dp),
            )

            AIScanTotalBlock(state = state, modifier = Modifier.padding(top = 16.dp))

            Column(Modifier.padding(top = 10.dp)) {
                state.foods.forEach { food ->
                    key(food.id) {
                        AIScanFoodRow(
                            food = food,
                            onScale = { factor -> onScale(food.id, factor) },
                            onSetGrams = { grams -> onSetGrams(food.id, grams) },
                            onStepCount = { up -> onStepCount(food.id, up) },
                            onEdit = { editing = food },
                            onRemove = { onRemove(food.id) },
                        )
                    }
                }
            }

            AIScanAddMissedRow(onClick = { showAddMissed = true })

            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.assumptions.forEach { NtText(it, style = NT.Fonts.footnote) }
                state.questions.forEach { NtText(it, style = NT.Fonts.subheadline) }
                AIMealNotes(state.notes, onNotes)
                SecondaryButton(title = stringResource(S.fuel_ai_refine), enabled = state.hasItems, onClick = onRefine)
            }
            AIScanDisclaimer(Modifier.padding(top = 6.dp))
        }

        AIScanBottomBar(
            meal = state.meal,
            canLog = state.hasItems,
            onSaveRecipe = onSaveRecipe,
            onMeal = onMeal,
            onLog = onLog,
        )
    }

    if (showAddMissed) {
        FoodSearchSheet(
            meal = state.meal,
            day = LocalDate.now(),
            onDismiss = { showAddMissed = false },
            pick = FoodSearchPick(
                mode = FoodSearchPick.Mode.Add,
                title = stringResource(S.fuel_ai_addMissed),
            ) { food, grams ->
                onAppend(AIScanCorrections.fromDatabase(food, grams ?: food.servingSizeG ?: 100.0))
            },
        )
    }

    editing?.let { food ->
        key(food.id) {
            AIScanItemSheet(
                food = food,
                meal = state.meal,
                onDone = { edited ->
                    onUpdate(edited)
                    editing = null
                },
                onRemove = {
                    onRemove(food.id)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

/** `AIScanResultView.totalBlock` — the hero kcal on the left, macros and confidence right. */
@Composable
private fun AIScanTotalBlock(
    state: AIScanUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow(stringResource(S.fuel_ai_estimatedTotal))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                NumericText(
                    text = Fmt.kcal(state.totalKcal, withUnit = false),
                    style = NT.Fonts.display(56).tabular(),
                    color = NT.Colors.ink,
                    durationMs = 250,
                )
                NtText(
                    text = "kcal",
                    style = NT.Fonts.title2,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.padding(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            TabularText(
                text = stringResource(
                    S.fuel_ai_macrosTotal,
                    AIScanFormat.wholeGrams(state.totalProtein),
                    AIScanFormat.wholeGrams(state.totalCarbs),
                    AIScanFormat.wholeGrams(state.totalFat),
                ),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConfidenceDots(level = state.confidenceLevel.bars)
                NtText(
                    text = stringResource(state.confidenceLevel.labelRes),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

/** `AIScanResultView.addMissedRow`. */
@Composable
private fun AIScanAddMissedRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NT.Size.control)
            .ntPlainClickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(NtIcons.Plus, size = sfIconSize(14f), tint = NT.Colors.ink2)
        NtText(
            text = stringResource(S.fuel_ai_addMissed),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )
    }
}

/** `AIScanResultView.disclaimer`. */
@Composable
private fun AIScanDisclaimer(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NtIcon(
            NtIcons.InfoCircle,
            modifier = Modifier.padding(top = 1.dp),
            size = sfIconSize(14f),
            tint = NT.Colors.ink2,
        )
        NtText(
            text = stringResource(S.fuel_ai_disclaimer),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
    }
}

/** `AIScanResultView.bottomBar` — the safe-area inset iOS pins under the scroll view. */
@Composable
private fun AIScanBottomBar(
    meal: MealSlot,
    canLog: Boolean,
    onSaveRecipe: () -> Unit,
    onMeal: (MealSlot) -> Unit,
    onLog: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NT.Colors.ground)
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 10.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `.fixedSize(horizontal: true, vertical: false)` — the pill hugs its label.
        Box(Modifier.width(IntrinsicSize.Max)) {
            SecondaryButton(title = stringResource(S.fuel_ai_saveRecipe), onClick = onSaveRecipe)
        }
        LogToMealButton(
            meal = meal,
            onMeal = onMeal,
            onLog = onLog,
            modifier = Modifier.weight(1f),
            enabled = canLog,
        )
    }
}

/** `AIScanFormat` (`AIScanResultView.swift`) — whole grams, grouped, no unit; the portion line. */
object AIScanFormat {

    /** `Int(value.rounded()).formatted(.number.grouping(.automatic))` — the unit is the caller's. */
    fun wholeGrams(value: Double): String = Fmt.whole(value)

    /**
     * The two arguments of `fuel.ai.portionBasis` ("6 szt. × 35 g"): the count with its unit and
     * the grams of one unit, as edit-field figures. Null for a single unit or a mass, where the
     * grams cell says it all.
     */
    fun portionBasis(food: AIFood, locale: Locale): Pair<String, String>? {
        val unit = food.unitName ?: return null
        if (food.units == 1.0) return null
        return "${FuelDerive.portionText(food.units, locale)} $unit" to FuelDerive.portionText(food.unitGrams, locale)
    }
}
