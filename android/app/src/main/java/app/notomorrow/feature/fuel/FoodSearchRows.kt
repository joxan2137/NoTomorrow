package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.KcalLabel
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSpinner
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.FoodCandidate
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/*
 * Rows and states for `FoodSearchSheet`, per `design/FoodSearch.dc.html` and
 * `Features/Fuel/FoodSearchRows.swift`: 62 dp rows with a bottom hairline, name / detail on
 * the left, kcal and a 32 dp "+" inside a 44 dp hit box on the right.
 */

/** `FoodSectionLabel` — an `ink3` eyebrow with 4 dp under it. */
@Composable
fun FoodSectionLabel(text: String, modifier: Modifier = Modifier) {
    Eyebrow(
        text = text,
        modifier = modifier.padding(bottom = 4.dp),
        color = NT.Colors.ink3,
    )
}

/** An Open Food Facts hit: "brand · per 100 g · P 12 g", priced per 100 g. */
@Composable
fun FoodResultRow(
    candidate: FoodCandidate,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
) {
    val parts = buildList {
        candidate.brand?.let { add(it) }
        add(stringResource(S.fuel_per100))
        add(stringResource(S.fuel_proteinShort, Fmt.grams(candidate.proteinPer100)))
    }
    FoodRow(
        name = candidate.name,
        detail = parts.joinToString(" · "),
        kcal = candidate.kcalPer100,
        modifier = modifier,
        onAdd = onAdd,
    )
}

/** A food already in the library: "brand · 40 g", priced at its serving (or 100 g). */
@Composable
fun FoodRecentRow(
    item: FoodItemEntity,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
) {
    val amount = FuelDerive.recentAmount(item.servingSizeG)
    val parts = buildList {
        item.brand?.let { add(it) }
        add(Fmt.grams(amount))
    }
    FoodRow(
        name = item.name,
        detail = parts.joinToString(" · "),
        kcal = item.kcalPer100 * amount / 100.0,
        modifier = modifier,
        onAdd = onAdd,
    )
}

/** `FoodRow` — the shared 62 dp row. */
@Composable
fun FoodRow(
    name: String,
    detail: String,
    kcal: Double,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // Swift: `HStack(spacing: 12)` with `.padding(.trailing, -6)` on the "+" button, so
            // the button only takes 38 pt of layout width. Compose has no negative padding: the
            // 44 dp box keeps its size and is nudged out with `offset`, so the spacing carries
            // the -6 instead. 6 + 44 == 12 + 38, and the content column ends where iOS's does.
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(62.dp)
                    .ntPlainClickable(onClick = onAdd),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // `softWrap = false` truncates at the exact overflowing character the way
                    // `.lineLimit(1)` does on iOS; with word wrapping on, Android breaks at the
                    // last word boundary and keeps that trailing space before the ellipsis.
                    BasicText(
                        text = name.replace(WHITESPACE, " ").trim(),
                        style = NT.Fonts.headline.copy(color = NT.Colors.ink),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    NtText(
                        text = detail,
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.widthIn(min = 8.dp))
                KcalLabel(kcal = kcal)
            }

            Box(
                modifier = Modifier
                    .size(NT.Size.control)
                    // `.padding(.trailing, -6)`: the 44 pt hit box overhangs the screen
                    // margin so the visible 32 pt circle lines up with it.
                    .offset(x = 6.dp)
                    .ntPlainClickable(
                        onClickLabel = stringResource(S.fuel_quickAdd),
                        onClick = onAdd,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(32.dp).background(NT.Colors.surface2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    NtIcon(NtIcons.Plus, size = sfIconSize(15f), tint = NT.Colors.ink)
                }
            }
        }
        Hairline(Modifier.align(Alignment.BottomStart))
    }
}

/** `FoodStateRow` — loading / not found / error / idle hint. Empty states carry their action. */
@Composable
fun FoodStateRow(
    kind: FoodStateKind,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onQuickAdd: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (kind) {
            FoodStateKind.Loading -> Row(
                modifier = Modifier.height(62.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtSpinner(color = NT.Colors.ink2)
                NtText(
                    text = stringResource(S.fuel_searching),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }

            FoodStateKind.NotFound -> {
                // "Add it by hand" only where there is a Quick add; a pick for an AI estimate
                // has none, so it points at another name or the barcode instead.
                StateMessage(stringResource(if (onQuickAdd != null) S.fuel_notFound else S.fuel_notFound_pick))
                if (onQuickAdd != null) {
                    GhostButton(
                        title = stringResource(S.fuel_quickAdd),
                        icon = NtIcons.Plus,
                        onClick = onQuickAdd,
                    )
                }
            }

            is FoodStateKind.Error -> {
                StateMessage(stringResource(kind.messageRes))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onRetry != null) {
                        GhostButton(
                            title = stringResource(S.fuel_search_retry),
                            modifier = Modifier.weight(1f),
                            onClick = onRetry,
                        )
                    }
                    if (onQuickAdd != null) {
                        GhostButton(
                            title = stringResource(S.fuel_quickAdd),
                            modifier = Modifier.weight(1f),
                            icon = NtIcons.Plus,
                            onClick = onQuickAdd,
                        )
                    }
                }
            }

            FoodStateKind.Hint -> {
                StateMessage(stringResource(S.fuel_search_hint))
                if (onQuickAdd != null) {
                    GhostButton(
                        title = stringResource(S.fuel_quickAdd),
                        icon = NtIcons.Plus,
                        onClick = onQuickAdd,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateMessage(text: String) {
    NtText(
        text = text,
        modifier = Modifier.padding(top = 12.dp),
        style = NT.Fonts.subheadline,
        color = NT.Colors.ink2,
    )
}

/** `FoodStateRow.Kind`. */
sealed interface FoodStateKind {
    data object Loading : FoodStateKind
    data object NotFound : FoodStateKind
    data class Error(val messageRes: Int) : FoodStateKind
    data object Hint : FoodStateKind
}

/** Collapses runs of whitespace so a name never truncates on a stray double space. */
private val WHITESPACE = Regex("\\s+")
