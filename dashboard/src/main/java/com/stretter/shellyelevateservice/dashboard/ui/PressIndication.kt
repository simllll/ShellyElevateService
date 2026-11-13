package com.stretter.shellyelevateservice.dashboard.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * The frontend's `ha-ripple` reduced to its pressed state: a [color] overlay at 12 % opacity
 * (`--ha-ripple-pressed-opacity`) while pressed. Cheaper than a material ripple. Pass [shape]
 * for rounded/circular buttons instead of clipping the parent: a clip makes every button its own
 * clipped layer, which is expensive to draw on the panels' GPUs.
 */
class PressIndication(
    private val color: Color,
    private val alpha: Float = 0.12f,
    private val shape: Shape? = null,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color.copy(alpha = alpha), shape)

    override fun equals(other: Any?) = other is PressIndication && other.color == color && other.alpha == alpha && other.shape == shape
    override fun hashCode() = (color.hashCode() * 31 + alpha.hashCode()) * 31 + shape.hashCode()

    private class Node(private val source: InteractionSource, private val overlay: Color, private val shape: Shape?) : Modifier.Node(), DrawModifierNode {
        private var pressCount = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> pressCount++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressCount = (pressCount - 1).coerceAtLeast(0)
                        else -> return@collect
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressCount == 0) return
            if (shape == null) drawRect(overlay) else drawOutline(shape.createOutline(size, layoutDirection, this), overlay)
        }
    }
}
