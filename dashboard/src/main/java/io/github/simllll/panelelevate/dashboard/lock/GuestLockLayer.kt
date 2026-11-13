package io.github.simllll.panelelevate.dashboard.lock

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.entity
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import io.github.simllll.panelelevate.dashboard.ui.LoopingVideo
import kotlinx.coroutines.delay

/**
 * The guest lock above the view and floating buttons, below the popup layer (so browser_mod
 * popups such as the alarm keypad stay on top and usable). While locked a transparent shield
 * swallows every touch (taps, swipes, scrolls, long presses) and shows the overlay: the lock
 * video (muted, looped, letterboxed on black) with a lock icon and "Gesperrt". [GuestLock.sleepAfterMs]
 * after the last touch the overlay closes and the service is asked to sleep the display.
 */
@Composable
fun GuestLockLayer(lock: GuestLock, config: GuestLockConfig, ctx: CardContext) {
    val state = ctx.entity(config.entity)?.state
    // Unavailable/unknown (or no connection) keeps the last known lock state.
    LaunchedEffect(config.entity, state) {
        when {
            config.entity == null || state == "off" -> lock.updateLocked(false)
            state == "on" -> lock.updateLocked(true)
        }
    }
    LaunchedEffect(config.sleepAfterS) { lock.sleepAfterMs = config.sleepAfterS.coerceAtLeast(1) * 1000L }
    if (!lock.locked) return

    val context = LocalContext.current
    LaunchedEffect(lock.overlay, lock.lastTouchMs) {
        val due = lock.dueAt() ?: return@LaunchedEffect
        delay((due - System.currentTimeMillis()).coerceAtLeast(0))
        if (lock.onTimer(System.currentTimeMillis())) requestSleep(context)
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(lock) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) lock.onTouch(System.currentTimeMillis())
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        if (lock.overlay) Overlay(config, ctx)
    }
}

@Composable
private fun Overlay(config: GuestLockConfig, ctx: CardContext) {
    val t = HaTheme.typography
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        config.video?.let { url ->
            val request = remember(url) { ctx.hass.request(url) }
            val headers = remember(request) { request.headers.toMap() }
            LoopingVideo(request.url.toString(), headers, Modifier.fillMaxSize())
        }
        Column(
            Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(HaTheme.dimens.radiusLg))
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MdiIcon("mdi:lock", Color.White, size = 48.dp)
            BasicText("Gesperrt", style = t.style(t.size2xl, FontWeight.Medium, color = Color.White))
        }
    }
}

private fun requestSleep(context: Context) {
    try {
        context.sendBroadcast(Intent(GuestLockContract.ACTION_SLEEP).setPackage(context.packageName))
    } catch (e: Exception) {
        Log.w("GuestLock", "sleep request failed: ${e.message}")
    }
}
