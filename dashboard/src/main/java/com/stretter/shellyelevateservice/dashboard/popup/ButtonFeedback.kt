package com.stretter.shellyelevateservice.dashboard.popup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.theme.HaPalette
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.coroutines.delay

/**
 * Broadcast contract between the background service (main process) and the dashboard: the
 * service sends [ACTION] restricted to our package on every raw key-down ([EVENT_DOWN]) and for
 * every detected hardware button action (single/double/long).
 * The dashboard only registers its receiver while the activity is started, so nothing happens
 * (and no process is started) when the dashboard is not running.
 */
object ButtonFeedbackContract {
    const val ACTION = "com.stretter.shellyelevateservice.dashboard.BUTTON_FEEDBACK"
    /** Button number 1–4. */
    const val EXTRA_BUTTON = "button"
    /** [EVENT_DOWN], or `single`, `double`, `long` (ButtonStateTracker.EventType values). */
    const val EXTRA_EVENT = "event"
    /** The raw key-down, sent at once, before single/double/long is known. */
    const val EVENT_DOWN = "down"
}

/**
 * Immediate local feedback for the hardware buttons: a small pill at the top center. It appears
 * on the raw key-down ("Taste 2") and its label is updated in place when the action is detected
 * ("Taste 2 · 2×", "Taste 2 · lang"). HA automations stay the source of truth (they still show
 * their browser_mod popups); the pill disappears [DURATION_MS] after the last event of the
 * button or as soon as a browser_mod popup arrives.
 */
@Stable
class ButtonFeedback(private val popups: PopupHost) {
    data class Press(val id: Int, val button: Int, val event: String, val popupGeneration: Int) {
        val label: String get() = label(button, event)
        val icon: String get() = icon(event)
    }

    private var ids = 0
    var current by mutableStateOf<Press?>(null)
        private set

    fun show(button: Int, event: String) {
        if (button !in 1..4) return
        val shown = current
        current = if (event != ButtonFeedbackContract.EVENT_DOWN && shown != null && shown.button == button) {
            // The action of the press already shown: relabel it (a popup that arrived meanwhile keeps it hidden).
            Press(++ids, button, event, shown.popupGeneration)
        } else {
            Press(++ids, button, event, popups.browserModPopupGeneration)
        }
    }

    fun dismiss() {
        current = null
    }

    /** Receiver for [ButtonFeedbackContract.ACTION]; register while the activity is started. */
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ButtonFeedbackContract.ACTION) return
            show(intent.getIntExtra(ButtonFeedbackContract.EXTRA_BUTTON, 0), intent.getStringExtra(ButtonFeedbackContract.EXTRA_EVENT) ?: "single")
        }
    }

    fun register(context: Context) {
        try {
            // Not exported: only our own app (the service in the main process) can send it.
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ButtonFeedbackContract.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w(TAG, "could not register button feedback receiver", e)
        }
    }

    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // not registered
        }
        current = null
    }

    /** Renders the pill; call once, above the popup layer. */
    @Composable
    fun Layer() {
        val press = current
        val popupGeneration = popups.browserModPopupGeneration
        val visible = press != null && press.popupGeneration == popupGeneration
        // Keep the last press (plain holder, no state write in composition) while the exit animation runs
        val last = remember { arrayOfNulls<Press>(1) }
        if (press != null) last[0] = press
        if (press != null) {
            LaunchedEffect(press.id) {
                delay(DURATION_MS)
                if (current?.id == press.id) current = null
            }
        }
        Box(Modifier.fillMaxSize().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(
                visible,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                last[0]?.let { Pill(it) }
            }
        }
    }

    companion object {
        private const val TAG = "ButtonFeedback"
        const val DURATION_MS = 1_500L

        fun label(button: Int, event: String): String = when (event) {
            "double" -> "Taste $button · 2×"
            "long" -> "Taste $button · lang"
            else -> "Taste $button"
        }

        fun icon(event: String): String = when (event) {
            "double" -> "mdi:gesture-double-tap"
            "long" -> "mdi:gesture-tap-hold"
            else -> "mdi:gesture-tap"
        }
    }
}

/** Neutral HA toast surface (like `ha-toast`), but as a compact pill. */
@Composable
private fun Pill(press: ButtonFeedback.Press) {
    val t = HaTheme.typography
    val c = HaTheme.colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .shadow(6.dp, shape)
            .background(HaPalette.Neutral10, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MdiIcon(press.icon, c.primary, size = 20.dp)
        BasicText(press.label, style = t.style(t.sizeM, color = c.primaryText))
    }
}
