package app.notomorrow.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch

/**
 * 1:1 port of `PressScale` (`NoTomorrow/DesignSystem/Components.swift:76-83`):
 *
 * ```swift
 * configuration.label
 *     .scaleEffect(configuration.isPressed ? 0.97 : 1)
 *     .opacity(configuration.isPressed ? 0.9 : 1)
 *     .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
 * ```
 *
 * **Both** halves matter — dropping the alpha is the commonest port bug
 * (research §5.6). There is no ripple anywhere in this app: `NTTheme` installs
 * this as `LocalIndication`, so a bare `Modifier.clickable {}` already presses
 * the iOS way.
 *
 * SwiftUI scales the *whole* button label, background included, so the
 * indication must sit **before** `.background(...)` in the modifier chain — a
 * `DrawModifierNode` wraps everything that draws after it.
 */
object NTPressScale : IndicationNodeFactory {

    /** Scale at full press. `scaleEffect(0.97)`. */
    private const val PRESSED_SCALE = 0.97f

    /** Alpha at full press. `opacity(0.9)`. */
    private const val PRESSED_ALPHA = 0.90f

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressScaleNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0x7E55CA1E

    private class PressScaleNode(
        private val interactionSource: InteractionSource,
    ) : Modifier.Node(), DrawModifierNode {

        /** 0 = released, 1 = fully pressed. Read in [draw], so it self-invalidates. */
        private val press = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                var depth = 0
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> depth++
                        is PressInteraction.Release -> depth--
                        is PressInteraction.Cancel -> depth--
                        else -> return@collect
                    }
                    val target = if (depth > 0) 1f else 0f
                    if (press.targetValue != target) {
                        launch { press.animateTo(target, NT.Anim.easeOut12) }
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            val p = press.value
            if (p == 0f) {
                drawContent()
                return
            }
            val s = 1f + (PRESSED_SCALE - 1f) * p
            val a = 1f + (PRESSED_ALPHA - 1f) * p
            // saveLayer is what gives the *group* a single opacity, exactly like
            // SwiftUI's `.opacity` on a composed view — per-primitive alpha would
            // let overlapping strokes show through each other.
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = a })
                scale(s, s, center) { this@draw.drawContent() }
                canvas.restore()
            }
        }
    }
}

/**
 * `.buttonStyle(PressScale())` — makes the receiver tappable with the iOS press
 * feedback and no ripple.
 *
 * Put it **above** the paint modifiers so the whole pill scales:
 * ```
 * Modifier.height(56.dp).pressScale(onClick = …).background(ink, CircleShape).padding(…)
 * ```
 */
@Composable
fun Modifier.pressScale(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = ntClickable(
    enabled = enabled,
    role = role,
    onClickLabel = onClickLabel,
    onClick = onClick,
)

/**
 * The app's `clickable`. Identical to [pressScale]; the second name exists
 * because most call sites read better as "this row is clickable" than as
 * "this row press-scales".
 */
@Composable
fun Modifier.ntClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = source,
        indication = NTPressScale,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )
}

/**
 * SwiftUI's `.buttonStyle(.plain)` — tappable, no press feedback at all (29
 * sites in the iOS source). Never falls back to a ripple.
 */
@Composable
fun Modifier.ntPlainClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = source,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )
}

/**
 * Press feedback for something whose gesture is handled elsewhere (a drag, a
 * long press, a `pointerInput`): feed it the same [MutableInteractionSource].
 */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier =
    this.indication(interactionSource, NTPressScale)
