package io.github.simllll.panelelevate.dashboard.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.theme.HaTheme

/**
 * `ha-switch` (48×24 track, 18px thumb, 1px border) in the dark default theme.
 * The whole 40px touch target toggles.
 */
@Composable
fun HaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = HaTheme.colors
    val thumbOffset by animateDpAsState(if (checked) 27.dp else 3.dp, tween(150), label = "thumb")
    val alpha = if (enabled) 1f else 0.38f
    Box(
        modifier
            .size(width = 48.dp, height = 40.dp)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background((if (checked) c.switchCheckedTrack else c.switchTrack).copy(alpha = alpha), CircleShape)
                .border(1.dp, (if (checked) c.switchCheckedBorder else c.switchBorder).copy(alpha = alpha), CircleShape)
        )
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(18.dp)
                .shadow(1.dp, CircleShape)
                .background((if (checked) c.switchCheckedThumb else c.switchThumb).copy(alpha = alpha), CircleShape)
        )
    }
}

/** Round icon button (`ha-icon-button`, 48px target, 24px icon). */
@Composable
fun HaIconButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HaTheme.colors.primaryText,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier
            .size(size)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = remember(tint) { PressIndication(tint, shape = CircleShape) },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MdiIcon(icon, if (enabled) tint else HaTheme.colors.disabledText, size = iconSize)
    }
}

/** Text button (`ha-button`): [filled] = primary background, otherwise outlined/plain. */
@Composable
fun HaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    filled: Boolean = false,
    outlined: Boolean = !filled,
    color: Color = HaTheme.colors.primary,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val shape = RoundedCornerShape(HaTheme.dimens.radiusMd * 2)
    val t = HaTheme.typography
    val fg = if (filled) HaTheme.colors.textPrimaryColor else HaTheme.colors.primaryText
    Row(
        modifier
            .height(40.dp)
            .then(if (filled) Modifier.background(color, shape) else Modifier)
            .then(if (outlined) Modifier.border(1.dp, HaTheme.colors.outlineHover, shape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = remember(fg) { PressIndication(fg, shape = shape) },
                onClick = onClick,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            MdiIcon(icon, fg, size = 18.dp)
            Box(Modifier.width(8.dp))
        }
        BasicText(
            text,
            style = t.style(t.sizeM, FontWeight.Medium, t.lineHeightCondensed, color = fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Horizontal slider in the style of `ha-control-slider` (rounded track filled up to the value).
 * [onValueChangeFinished] fires once when the finger lifts — use it for service calls.
 */
@Composable
fun HaSlider(
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    color: Color = HaTheme.colors.primary,
    height: Dp = 48.dp,
) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shown = dragValue ?: value
    val fraction = ((shown - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val latestFinish by rememberUpdatedState(onValueChangeFinished)
    fun valueAt(x: Float) = range.start + (x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start)
    val shape = RoundedCornerShape(HaTheme.dimens.radiusLg)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .clip(shape)
            .background(color.copy(alpha = 0.2f))
            .pointerInput(range) {
                detectTapGestures { latestFinish(valueAt(it.x)) }
            }
            .pointerInput(range) {
                detectDragGestures(
                    onDragStart = { dragValue = valueAt(it.x) },
                    onDragEnd = { dragValue?.let { latestFinish(it) }; dragValue = null },
                    onDragCancel = { dragValue = null },
                ) { change, _ -> dragValue = valueAt(change.position.x) }
            }
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(color)
        )
    }
}
