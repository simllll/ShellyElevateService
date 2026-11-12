package com.stretter.shellyelevateservice.dashboard.lovelace

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.stretter.shellyelevateservice.dashboard.actions.Navigator
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * Shows the current view of [views] and, with [swipeNav], switches views by horizontal swipes
 * like hass-swipe-navigation: a swipe of at least `swipe_amount` % of the width goes to the
 * next/previous view. The gesture only starts when no child consumed the movement first
 * (sliders, maps, horizontally scrolling cards keep theirs; vertical scrolling wins when it
 * passes its touch slop first), and once it started it cancels taps on the cards below.
 * `animate: swipe` lets the view follow the finger and slides the new one in, `fade`/`flip` fade
 * the new view in, `none` switches directly.
 */
@Composable
fun ViewSwitcher(
    views: List<JsonObject>,
    swipeNav: SwipeNavConfig?,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    content: @Composable (view: JsonObject) -> Unit,
) {
    val index = SwipeNavConfig.viewIndexOrNull(views, navigator.viewPath) ?: 0
    val currentIndex by rememberUpdatedState(index)
    val offset = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var shownIndex by remember { mutableIntStateOf(index) }
    var width by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(index) {
        if (shownIndex == index) return@LaunchedEffect
        shownIndex = index
        val direction = navigator.transition
        when {
            direction == 0 || swipeNav == null || swipeNav.animate == SwipeNavConfig.Animate.NONE -> {
                offset.snapTo(0f)
                alpha.snapTo(1f)
            }
            swipeNav.animate == SwipeNavConfig.Animate.SWIPE -> {
                alpha.snapTo(1f)
                offset.snapTo(direction * width)
                offset.animateTo(0f, tween(ANIMATION_MS))
            }
            else -> {
                offset.snapTo(0f)
                alpha.snapTo(0f)
                alpha.animateTo(1f, tween(ANIMATION_MS))
            }
        }
    }

    val gestures = if (swipeNav == null) Modifier else Modifier.pointerInput(swipeNav, views) {
        val follow = swipeNav.animate == SwipeNavConfig.Animate.SWIPE
        fun dragTo(dx: Float) {
            if (!follow) return
            // Rubber band at an edge without a target.
            val hasTarget = swipeNav.target(views, currentIndex, if (dx < 0) 1 else -1) != null
            scope.launch { offset.snapTo(if (hasTarget) dx else dx * EDGE_RESISTANCE) }
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            var lastX = start.position.x
            dragTo(lastX - down.position.x)
            val completed = horizontalDrag(start.id) { change ->
                lastX = change.position.x
                dragTo(lastX - down.position.x)
                change.consume()
            }
            val dx = lastX - down.position.x
            val direction = if (dx < 0) 1 else -1
            val target = if (completed && abs(dx) >= swipeNav.swipeAmount / 100f * size.width) {
                swipeNav.target(views, currentIndex, direction)
            } else null
            scope.launch {
                if (target == null) {
                    offset.animateTo(0f, tween(ANIMATION_MS))
                    return@launch
                }
                if (follow) offset.animateTo(-direction * width, tween(ANIMATION_MS))
                navigator.swipeTo(SwipeNavConfig.pathOf(views, target), direction)
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.toFloat() }
            .then(gestures)
            .graphicsLayer {
                translationX = offset.value
                this.alpha = alpha.value
            }
    ) {
        val view = views.getOrNull(index) ?: return@Box
        key(index) { content(view) }
    }
}

private const val ANIMATION_MS = 220
private const val EDGE_RESISTANCE = 0.25f
