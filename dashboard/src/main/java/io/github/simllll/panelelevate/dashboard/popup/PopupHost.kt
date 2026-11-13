package io.github.simllll.panelelevate.dashboard.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import io.github.simllll.panelelevate.dashboard.lovelace.LocalViewActive
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.theme.HaPalette
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import io.github.simllll.panelelevate.dashboard.ui.HaButton
import io.github.simllll.panelelevate.dashboard.ui.HaIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/** Scope of a popup's content: gives the card context and a way to close the popup. */
class PopupScope internal constructor(val ctx: CardContext, private val onDismiss: () -> Unit) {
    fun dismiss() = onDismiss()
}

/**
 * The global popup layer above the dashboard: more-info dialogs, browser_mod popups, bubble-card
 * pop-ups, confirmations and toasts. Popups stack; the top one gets the back button and scrim taps.
 *
 * It also owns the location hash (`#control-covers`), which `navigate` actions set and bubble-card
 * pop-ups react to (see [HashPopup]).
 */
@Stable
class PopupHost {
    private val _hash = MutableStateFlow<String?>(null)
    /** Current location hash including `#`, or null. */
    val hash: StateFlow<String?> = _hash.asStateFlow()

    internal class Entry(
        val id: String,
        val dismissible: Boolean,
        val scrim: Boolean,
        val timeoutMs: Long?,
        val onDismiss: () -> Unit,
        val content: @Composable PopupScope.() -> Unit,
    )

    private val entries = mutableStateListOf<Entry>()
    private val floating = mutableStateMapOf<Any, @Composable () -> Unit>()
    private val ids = AtomicInteger()
    private var toast by mutableStateOf<Notification?>(null)

    val isShowingPopup: Boolean get() = entries.isNotEmpty()

    /** Incremented for every browser_mod popup (observable; [ButtonFeedback] hides on change). */
    var browserModPopupGeneration by mutableStateOf(0)
        private set

    fun setHash(hash: String?) {
        _hash.value = hash?.takeIf { it.length > 1 }
    }

    /**
     * Shows a popup. [content] draws everything including its chrome (use [HaDialog] for the standard
     * dialog look); the layer adds the dimmed scrim when [scrim] is set. Returns the id for [dismiss].
     * @param dismissible scrim taps and back close it.
     * @param timeoutMs closes the popup automatically after this time.
     * @param onDismiss called whenever the popup is removed (by any means).
     */
    fun show(
        id: String = "popup-${ids.incrementAndGet()}",
        dismissible: Boolean = true,
        scrim: Boolean = true,
        timeoutMs: Long? = null,
        onDismiss: () -> Unit = {},
        content: @Composable PopupScope.() -> Unit,
    ): String {
        entries.removeAll { it.id == id }
        entries += Entry(id, dismissible, scrim, timeoutMs, onDismiss, content)
        return id
    }

    fun dismiss(id: String) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        entries.remove(entry)
        entry.onDismiss()
    }

    fun dismissTop() {
        entries.lastOrNull()?.let { dismiss(it.id) }
    }

    fun dismissAll() {
        entries.toList().asReversed().forEach { dismiss(it.id) }
    }

    /** Back button: closes the top popup or clears the hash. Returns false if there was nothing to do. */
    fun handleBack(): Boolean {
        val top = entries.lastOrNull()
        if (top != null) {
            if (top.dismissible) dismiss(top.id)
            return true
        }
        if (_hash.value != null) {
            setHash(null)
            return true
        }
        return false
    }

    fun showMoreInfo(entityId: String) {
        show(id = MORE_INFO_ID) { MoreInfoDialog(entityId) }
    }

    /**
     * `browser_mod.popup` (see [BrowserModPopup]). Popups with the same `tag` replace each other;
     * `dismiss_action` runs whenever the popup goes away other than by its buttons or timeout.
     */
    fun showBrowserModPopup(data: JsonObject) {
        val state = BrowserModPopupState(data)
        browserModPopupGeneration++
        show(
            id = BROWSER_MOD_PREFIX + state.tag,
            dismissible = state.dismissable,
            onDismiss = { state.onClosed() },
        ) { BrowserModPopup(state) }
    }

    /** `browser_mod.close_popup`: closes the popups with [tag], or all browser_mod popups and more-info. */
    fun closeBrowserModPopups(tag: String?) {
        val ids = entries.filter {
            if (tag.isNullOrEmpty()) it.id.startsWith(BROWSER_MOD_PREFIX) || it.id == MORE_INFO_ID
            else it.id == BROWSER_MOD_PREFIX + tag
        }.map { it.id }
        ids.asReversed().forEach { dismiss(it) }
    }

    fun confirm(text: String, title: String? = null, onConfirm: () -> Unit) {
        show(id = "confirm") { ConfirmDialog(title, text, onConfirm) }
    }

    /** Short message at the bottom (`showToast`). */
    fun toast(message: String) = notify(message)

    /**
     * HA's toast (`hass-notification`, used by `browser_mod.notification`): bottom center,
     * [durationMs] null or up to 4 s shows it for 4 s, 0 or less keeps it until dismissed.
     * [actionText] adds a button running [onAction]; [dismissable] adds a close button.
     */
    fun notify(
        message: String,
        durationMs: Long? = null,
        actionText: String? = null,
        onAction: () -> Unit = {},
        dismissable: Boolean = false,
    ) {
        val duration = when {
            durationMs == null || durationMs in 1..4000 -> 4000L
            durationMs <= 0 -> null
            else -> durationMs
        }
        toast = Notification(ids.incrementAndGet(), message, duration, actionText, onAction, dismissable)
    }

    internal class Notification(
        val id: Int,
        val message: String,
        val durationMs: Long?,
        val actionText: String?,
        val onAction: () -> Unit,
        val dismissable: Boolean,
    )

    /**
     * Draws the [Floating] elements of the current view (`position: fixed` in the web, e.g. fab-card)
     * above the view and below popups; call once, before [Layer].
     */
    @Composable
    fun FloatingLayer() {
        floating.values.forEach { it() }
    }

    /**
     * Shows [content] in the [FloatingLayer] (full-screen box) while this composable is composed.
     * Call it from `CardRenderer.Effects`.
     */
    @Composable
    fun Floating(content: @Composable () -> Unit) {
        if (!LocalViewActive.current) return
        val key = remember { Any() }
        val latest by rememberUpdatedState(content)
        DisposableEffect(key) {
            floating[key] = { latest() }
            onDispose { floating.remove(key) }
        }
    }

    /** Renders the layer; call once, above the dashboard content. */
    @Composable
    fun Layer(ctx: CardContext) {
        entries.forEach { entry ->
            key(entry.id) {
                val scope = remember(entry) { PopupScope(ctx) { dismiss(entry.id) } }
                entry.timeoutMs?.let { timeout ->
                    LaunchedEffect(entry) {
                        delay(timeout)
                        if (entries.contains(entry)) dismiss(entry.id)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (entry.scrim) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(HaTheme.colors.scrim)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { if (entry.dismissible) dismiss(entry.id) }
                        )
                    }
                    entry.content(scope)
                }
            }
        }
        Toast()
    }

    @Composable
    private fun Toast() {
        val current = toast ?: return
        current.durationMs?.let { duration ->
            LaunchedEffect(current.id) {
                delay(duration)
                if (toast?.id == current.id) toast = null
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val narrow = isNarrowDialogScreen(maxWidth, maxHeight)
            ToastBox(
                current,
                narrow = narrow,
                modifier = if (narrow) Modifier.width(maxWidth) else Modifier.padding(bottom = 16.dp),
                onAction = { toast = null; current.onAction() },
                onClose = { toast = null },
            )
        }
    }

    companion object {
        const val MORE_INFO_ID = "more-info"
        const val BROWSER_MOD_PREFIX = "browser-mod:"
    }
}

/**
 * `ha-toast`: neutral-10 surface, 4px radius, 350–650px wide, 48px high, 12/16px padding; on
 * [narrow] screens full width with square corners.
 */
@Composable
private fun ToastBox(
    notification: PopupHost.Notification,
    narrow: Boolean,
    modifier: Modifier,
    onAction: () -> Unit,
    onClose: () -> Unit,
) {
    val t = HaTheme.typography
    val c = HaTheme.colors
    val shape = if (narrow) RectangleShape else RoundedCornerShape(HaTheme.dimens.radiusSm)
    Row(
        modifier
            .then(if (narrow) Modifier else Modifier.widthIn(min = 350.dp, max = 650.dp))
            .heightIn(min = 48.dp)
            .shadow(8.dp, shape)
            .background(HaPalette.Neutral10, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            notification.message,
            style = t.style(t.sizeM, color = c.primaryText),
            modifier = Modifier.weight(1f, fill = false).padding(vertical = 6.dp),
        )
        if (notification.actionText != null) {
            HaButton(notification.actionText, onClick = onAction, outlined = false)
        }
        if (notification.dismissable) {
            HaIconButton("mdi:close", onClick = onClose, size = 36.dp, iconSize = 20.dp)
        }
    }
}

/**
 * Shows [content] as a popup while the location hash equals [hash] (bubble-card pop-ups).
 * Dismissing the popup (scrim, back) clears the hash. Call it from `CardRenderer.Effects`, which is
 * composed even when the card itself takes no space.
 */
@Composable
fun PopupHost.HashPopup(
    hash: String,
    dismissible: Boolean = true,
    scrim: Boolean = true,
    content: @Composable PopupScope.() -> Unit,
) {
    val current by this.hash.collectAsState()
    val latest by rememberUpdatedState(content)
    if (current != hash || !LocalViewActive.current) return
    DisposableEffect(hash) {
        val id = show(
            id = "hash:$hash",
            dismissible = dismissible,
            scrim = scrim,
            onDismiss = { if (this@HashPopup.hash.value == hash) setHash(null) },
        ) { latest(this) }
        onDispose { dismiss(id) }
    }
}
