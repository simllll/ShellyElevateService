package io.github.simllll.panelelevate.dashboard.lovelace

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.actions.Navigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.sign

/**
 * Shows the current view of [views] and, with [swipeNav], works like a pager between views
 * (hass-swipe-navigation): with `animate: swipe` the current view and its neighbour follow the
 * finger, and on release the pager settles on the neighbour when the swipe covered
 * `swipe_amount` % of the width or was a fling in that direction, else it springs back. `wrap`
 * continues at the other end; without a target the drag is damped. `fade`/`flip` fade the new
 * view in, `none` switches directly (both without following the finger).
 *
 * Only the neighbours a swipe can reach are kept composed (off-screen, not drawn, not touchable,
 * [LocalViewActive] false), so the next view is already laid out when the drag starts. The
 * offset is read in the draw phase only: dragging recomposes nothing.
 *
 * The gesture only starts when no child consumed the movement first (sliders, maps, horizontally
 * scrolling cards keep theirs; vertical scrolling wins when it passes its touch slop first), and
 * once it started it cancels taps on the cards below.
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
    val follow = swipeNav?.animate == SwipeNavConfig.Animate.SWIPE
    val next = if (follow) swipeNav?.target(views, index, 1) else null
    val prev = if (follow) swipeNav?.target(views, index, -1) else null
    val scope = rememberCoroutineScope()
    // Drag/settle offset in px; read only in graphicsLayer (draw phase).
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val alpha = remember { Animatable(1f) }
    var shownIndex by remember { mutableIntStateOf(index) }
    val flingVelocity = with(LocalDensity.current) { FLING_DP_PER_S.dp.toPx() }

    // Views switched by buttons/back/return home: no slide; fade for `fade`/`flip`.
    LaunchedEffect(index) {
        if (shownIndex == index) return@LaunchedEffect
        shownIndex = index
        if (navigator.transition != 0 && swipeNav?.animate == SwipeNavConfig.Animate.FADE) {
            alpha.snapTo(0f)
            alpha.animateTo(1f, tween(ANIMATION_MS))
        } else alpha.snapTo(1f)
    }

    val gestures = if (swipeNav == null) Modifier else Modifier.pointerInput(swipeNav, views) {
        fun target(direction: Int) = swipeNav.target(views, currentIndex, direction)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (settling?.isActive == true) return@awaitEachGesture
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            fun dragTo(dx: Float) {
                if (!follow) return
                // Damped at an edge without a target.
                offset = if (target(if (dx < 0) 1 else -1) != null) dx else dx * EDGE_RESISTANCE
            }
            var lastX = start.position.x
            tracker.addPosition(start.uptimeMillis, start.position)
            dragTo(lastX - down.position.x)
            val completed = horizontalDrag(start.id) { change ->
                lastX = change.position.x
                tracker.addPosition(change.uptimeMillis, change.position)
                dragTo(lastX - down.position.x)
                change.consume()
            }
            val dx = lastX - down.position.x
            val velocity = tracker.calculateVelocity().x
            val direction = if (dx < 0) 1 else -1
            val far = abs(dx) >= swipeNav.swipeAmount / 100f * size.width
            val fling = abs(velocity) >= flingVelocity && sign(velocity) == sign(dx)
            val target = if (completed && (far || fling)) target(direction) else null
            settling = scope.launch {
                if (target == null) {
                    animate(offset, 0f, animationSpec = tween(ANIMATION_MS)) { v, _ -> offset = v }
                    return@launch
                }
                if (follow) animate(offset, -direction * width, animationSpec = tween(ANIMATION_MS)) { v, _ -> offset = v }
                // One snapshot: the neighbour becomes the current view at offset 0 in the same frame.
                Snapshot.withMutableSnapshot {
                    navigator.swipeTo(SwipeNavConfig.pathOf(views, target), direction)
                    offset = 0f
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.toFloat() }
            .then(gestures)
            .graphicsLayer { this.alpha = alpha.value }
    ) {
        // Stable keys: after a swipe the neighbour's composition becomes the current view as is.
        val pages = listOfNotNull(prev, next, index).distinct()
        for (page in pages) {
            val view = views.getOrNull(page) ?: continue
            key(page) {
                CompositionLocalProvider(LocalViewActive provides (page == index)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // With two views, the other one is both neighbours: follow the drag side.
                                val position = when {
                                    page == index -> 0
                                    page == next && page == prev -> if (offset > 0) -1 else 1
                                    page == next -> 1
                                    else -> -1
                                }
                                val x = offset + position * width
                                translationX = x
                                // Off-screen pages are neither drawn nor hit.
                                this.alpha = if (abs(x) >= width && position != 0) 0f else 1f
                            }
                    ) {
                        content(view)
                    }
                }
            }
        }
    }
}

private const val ANIMATION_MS = 220
private const val EDGE_RESISTANCE = 0.25f
private const val FLING_DP_PER_S = 400f
