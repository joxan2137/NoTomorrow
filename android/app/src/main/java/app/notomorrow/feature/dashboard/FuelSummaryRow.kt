package app.notomorrow.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.notomorrow.designsystem.MacroBar
import app.notomorrow.designsystem.MacroRing
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSansFamily
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.S

/**
 * "Fuel" header with eaten / goal, a 64 dp kcal-left [MacroRing] and three macro bars in the
 * macro hues — the port of `FuelSummaryRow` (`Features/Dashboard/FuelSummaryRow.swift`).
 * Tapping goes to the Fuel tab.
 */
@Composable
fun FuelSummaryRow(
    totals: FuelTotals,
    goals: FuelGoals,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kcalLeft = maxOf(0.0, goals.kcal - totals.kcal)

    Column(
        // `.accessibilityElement(children: .combine)` — headline, ratio, chevron, ring and
        // the three macro bars are one clickable node, not eight.
        modifier = modifier
            .fillMaxWidth()
            .pressScale(onClick = onClick)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NtText(stringResource(S.dashboard_fuel), style = NT.Fonts.headline, color = NT.Colors.ink)
            Box(Modifier.weight(1f))
            Row(
                // Swift is `HStack(spacing: 4)`, but its chevron is an SF Symbol trimmed to
                // the glyph's own bounds, so it lands flush against the trailing content
                // margin. Ours is a 24-unit drawable centred in NtIcon's square box, which
                // parks ~5 dp of dead space on either side: the Box below measures only the
                // chevron's ink so the trailing edge matches, and the extra 2 dp here stands
                // in for the symbol's natural left side bearing.
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabularText(
                    text = Fmt.kcal(totals.kcal, withUnit = false) + " / " + Fmt.kcal(goals.kcal),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink2,
                )
                Box(Modifier.width(CHEVRON_INK_WIDTH), contentAlignment = Alignment.Center) {
                    NtIcon(
                        NtIcons.ChevronRight,
                        modifier = Modifier.requiredSize(CHEVRON_BOX),
                        size = CHEVRON_BOX,
                        tint = NT.Colors.ink3,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KcalRing(totals = totals, goals = goals, kcalLeft = kcalLeft)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                MacroBar(
                    label = stringResource(S.macro_protein),
                    value = totals.protein,
                    goal = goals.protein,
                    fill = NT.Colors.protein,
                )
                MacroBar(
                    label = stringResource(S.macro_carbs),
                    value = totals.carbs,
                    goal = goals.carbs,
                    fill = NT.Colors.carbs,
                )
                MacroBar(
                    label = stringResource(S.macro_fat),
                    value = totals.fat,
                    goal = goals.fat,
                    fill = NT.Colors.fat,
                )
            }
        }
    }
}

/** 64 dp [MacroRing] with the kcal left and a hand-rolled 8 sp "LEFT" micro-eyebrow inside it. */
@Composable
private fun KcalRing(totals: FuelTotals, goals: FuelGoals, kcalLeft: Double) {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        MacroRing(
            protein = totals.protein,
            carbs = totals.carbs,
            fat = totals.fat,
            kcalGoal = goals.kcal,
            modifier = Modifier.fillMaxSize(),
            lineWidth = 6.dp,
        )
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = Fmt.kcal(kcalLeft, withUnit = false),
                style = RingValueStyle.tabular().copy(color = NT.Colors.ink),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = (RING_VALUE_SIZE * RING_VALUE_MIN_SCALE).sp,
                    maxFontSize = RING_VALUE_SIZE.sp,
                    stepSize = 0.5.sp,
                ),
            )
            NtText(
                text = stringResource(S.dashboard_left).uppercase(LocaleProvider.current()),
                style = RingCaptionStyle,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
    }
}

/** `Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold))`. */
private val CHEVRON_BOX = sfIconSize(13f)

/**
 * Ink width of `ic_chevron_right` inside [CHEVRON_BOX]: the stroked path spans 9.4 of the
 * 24-unit viewport. The glyph is drawn at full size and overflows this box symmetrically.
 */
private val CHEVRON_INK_WIDTH = 7.5.dp

private const val RING_VALUE_SIZE = 14f
private const val RING_VALUE_MIN_SCALE = 0.7f

/** `.system(size: 14, weight: .bold)` — no ramp entry exists at 14 pt. */
private val RingValueStyle = TextStyle(
    fontFamily = NtSansFamily,
    fontWeight = FontWeight.Bold,
    fontSize = RING_VALUE_SIZE.sp,
    lineHeight = 18.sp,
    letterSpacing = (-0.0107f).em,
    fontSynthesis = FontSynthesis.None,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** `.system(size: 8, weight: .semibold).tracking(0.6)` — smaller than `NT.Fonts.eyebrow`. */
private val RingCaptionStyle = TextStyle(
    fontFamily = NtSansFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 8.sp,
    lineHeight = 10.sp,
    letterSpacing = 0.0750f.em,
    fontSynthesis = FontSynthesis.None,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
