package app.notomorrow.feature.bro

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.service.DayState
import app.notomorrow.util.S
import kotlin.math.roundToInt

/**
 * One attendance cell — the port of `BroStatusCell` (`Features/Bro/BroStatusCell.swift`).
 *
 * `attended` = ember ring + check, `missed`/`cancelled` = rose ring + ✕, `planned` = faint
 * ring, `confirmed` = filled ink circle with "In", `rest` = nothing.
 *
 * `strokeBorder` draws **inside** the circle, and so does `Modifier.border`, so a 1.5 dp
 * ring on a 30 dp cell has the same 30 dp outer diameter it does on iOS.
 */
@Composable
fun BroStatusCell(
    state: DayState,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
) {
    val iconPt = (size.value * 0.43f).roundToInt().toFloat()
    Box(
        modifier = modifier
            .size(size)
            .then(
                when (state) {
                    DayState.Rest -> Modifier
                    DayState.Planned -> Modifier.border(RING, NT.Colors.border, CircleShape)
                    DayState.Confirmed -> Modifier.background(NT.Colors.ink, CircleShape)
                    DayState.Attended -> Modifier.border(RING, NT.Colors.ember, CircleShape)
                    else -> Modifier.border(RING, NT.Colors.bad, CircleShape)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            DayState.Rest, DayState.Planned -> Unit
            // `.lineLimit(1).minimumScaleFactor(0.6)`.
            DayState.Confirmed -> BroAutoSizeText(
                text = stringResource(S.bro_in),
                minScale = 0.6f,
                style = NT.Fonts.caption.copy(
                    fontSize = (if (size >= 30.dp) 12 else 9).sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = NT.Colors.onPrimary,
            )
            DayState.Attended -> NtIcon(
                icon = NtIcons.Checkmark,
                size = sfIconSize(iconPt),
                tint = NT.Colors.ember,
            )
            else -> NtIcon(
                icon = NtIcons.Xmark,
                size = sfIconSize(iconPt - 1f),
                tint = NT.Colors.bad,
            )
        }
    }
}

/** `strokeBorder(lineWidth: 1.5)` on every ringed state. */
private val RING: Dp = 1.5.dp
