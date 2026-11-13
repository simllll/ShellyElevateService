package com.stretter.shellyelevateservice.dashboard.popup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.RectangleShape
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.cards.CardLayout
import com.stretter.shellyelevateservice.dashboard.cards.RenderCard
import com.stretter.shellyelevateservice.dashboard.html.Html
import com.stretter.shellyelevateservice.dashboard.json.asDouble
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import com.stretter.shellyelevateservice.dashboard.ui.HaButton
import com.stretter.shellyelevateservice.dashboard.ui.HaIconButton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One `browser_mod.popup` call. Keeps what the popup needs beyond composition: its `tag`, whether
 * the user may dismiss it and the `dismiss_action`, which runs whenever the popup closes except
 * through its buttons, icons or timeout (as `closeDialog` in browser_mod does).
 */
internal class BrowserModPopupState(val data: JsonObject) {
    val tag: String = data.string("tag")?.takeIf { it.isNotEmpty() } ?: "standard"
    val dismissable: Boolean = data.bool("dismissable") ?: true
    val timeoutMs: Long? = data["timeout"].asDouble()?.toLong()?.takeIf { it > 0 }

    /** Runs a popup action (set by the composable, which knows the [ActionHandler]). */
    var runActions: ((JsonElement?) -> Unit)? = null
    private var dismissActionDone = false

    /** A button, icon or the timeout closes the popup: no `dismiss_action`. */
    fun suppressDismissAction() {
        dismissActionDone = true
    }

    fun onClosed() {
        if (dismissActionDone) return
        dismissActionDone = true
        data["dismiss_action"]?.let { runActions?.invoke(it) }
    }
}

/**
 * browser_mod popup (`browser-mod-popup` on `ha-wa-dialog`): 24px radius card surface 40px from
 * the top, header with close button (if `dismissable`), `title` and header `icon`/`icons`, the
 * `content` (card config or HTML string, see [Html]), optional `left_button`/`right_button` and,
 * with `timeout` (ms), a 3px progress bar along the top edge that fills until the popup closes.
 * `size`: `normal` (580px), `wide`, `fullscreen`; `classic` behaves like `normal`. On narrow
 * screens ([isNarrowDialogScreen]) every popup is fullscreen, like `ha-dialog`.
 */
@Composable
internal fun PopupScope.BrowserModPopup(state: BrowserModPopupState) {
    val data = state.data
    val actions = ctx.actions
    SideEffect { state.runActions = { actions.runBrowserModActions(it) } }
    val c = HaTheme.colors
    val t = HaTheme.typography
    val shape = RoundedCornerShape(HaTheme.dimens.radius3xl)
    val progress = remember(state) { Animatable(0f) }
    state.timeoutMs?.let { timeout ->
        LaunchedEffect(state) {
            progress.animateTo(1f, tween(timeout.toInt().coerceAtLeast(1), easing = LinearEasing))
            state.suppressDismissAction()
            dismiss()
            actions.runBrowserModActions(data["timeout_action"])
        }
    }
    val hideProgress = data.bool("timeout_hide_progress") == true

    fun closeThen(close: Boolean, action: JsonElement?) {
        state.suppressDismissAction()
        if (close) dismiss()
        actions.runBrowserModActions(action)
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val fullscreen = data.string("size") == "fullscreen" || isNarrowDialogScreen(maxWidth, maxHeight)
        val shape = if (fullscreen) RectangleShape else shape
        val width: Dp = when {
            fullscreen -> maxWidth
            data.string("size") == "wide" -> minOf(1024.dp, maxWidth - 32.dp)
            else -> minOf(580.dp, maxWidth - 32.dp)
        }
        val top = if (fullscreen) 0.dp else 40.dp
        Column(
            Modifier
                .padding(top = top)
                .width(width)
                .then(if (fullscreen) Modifier.height(maxHeight) else Modifier.heightIn(max = maxHeight - top * 2))
                .shadow(16.dp, shape)
                .clip(shape)
                .background(c.cardBackground)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .drawWithContent {
                    drawContent()
                    if (state.timeoutMs != null && !hideProgress) {
                        drawRect(c.primary, size = Size(size.width * progress.value, 3.dp.toPx()))
                    }
                }
        ) {
            val title = data.string("title")
            val icons = headerIcons(data)
            if (title != null || state.dismissable || icons.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.dismissable) HaIconButton(data.string("dismiss_icon") ?: "mdi:close", onClick = { dismiss() })
                    else Spacer(Modifier.width(16.dp))
                    BasicText(
                        title.orEmpty(),
                        style = t.style(t.sizeXl, FontWeight.Medium, t.lineHeightCondensed, color = c.primaryText),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    val ring = data.string("icon_animation") == "ring"
                    icons.forEach { icon ->
                        HaIconButton(icon.string("icon") ?: "mdi:help", onClick = {
                            closeThen(icon.bool("close") ?: true, icon["action"])
                        }, modifier = if (ring) Modifier.ringing() else Modifier)
                    }
                }
            }
            val leftButton = data.string("left_button")
            val rightButton = data.string("right_button")
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (leftButton == null && rightButton == null) 20.dp else 8.dp)
            ) {
                PopupContent(data["content"])
            }
            if (leftButton != null || rightButton != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leftButton != null) {
                        PopupButton(data, "left", leftButton, defaultOutlined = false) {
                            closeThen(data.bool("left_button_close") ?: true, data["left_button_action"])
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (rightButton != null) {
                        PopupButton(data, "right", rightButton, defaultOutlined = true) {
                            closeThen(data.bool("right_button_close") ?: true, data["right_button_action"])
                        }
                    }
                }
            }
        }
    }
}

/**
 * A popup button with browser_mod's `<side>_button_variant` (brand, neutral, danger, warning,
 * success) and `<side>_button_appearance` (accent/filled, outlined, plain). `<side>_button_icon`
 * is a native extension (the web dashboard ignores it).
 */
@Composable
private fun PopupButton(data: JsonObject, side: String, text: String, defaultOutlined: Boolean, onClick: () -> Unit) {
    val c = HaTheme.colors
    val color = when (data.string("${side}_button_variant")) {
        "danger" -> c.error
        "warning" -> c.warning
        "success" -> c.success
        "neutral" -> c.secondaryText
        else -> c.primary
    }
    val appearance = data.string("${side}_button_appearance")
    val filled = appearance in setOf("accent", "filled")
    HaButton(
        text,
        onClick = onClick,
        icon = data.string("${side}_button_icon"),
        filled = filled,
        outlined = !filled && (appearance == "outlined" || (appearance == null && defaultOutlined)),
        color = color,
    )
}

/** Swinging bell (`icon_animation: ring`, native extension): a short shake, then a pause. */
@Composable
private fun Modifier.ringing(): Modifier {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            for (a in listOf(18f, -16f, 14f, -12f, 9f, -6f, 3f, 0f)) angle.animateTo(a, tween(70, easing = LinearEasing))
            delay(600)
        }
    }
    return graphicsLayer {
        rotationZ = angle.value
        transformOrigin = TransformOrigin(0.5f, 0.15f)
    }
}

/** `icon` + `icon_action` or the `icons` list: `{icon, title, action, close}`. */
private fun headerIcons(data: JsonObject): List<JsonObject> {
    val list = data.objects("icons")
    if (list.isNotEmpty()) return list
    val icon = data.string("icon") ?: return emptyList()
    return listOf(jsonOf("icon" to icon, "action" to data["icon_action"], "close" to (data.bool("icon_close") ?: true)))
}

/**
 * `content`: a card config, an HTML string (lit `unsafeHTML`; plain text works too) or a form
 * schema list (not supported natively; a list of card configs is shown as a vertical stack).
 */
@Composable
private fun PopupScope.PopupContent(content: JsonElement?) {
    when (content) {
        is JsonObject -> ctx.withLayout(CardLayout.STACK).RenderCard(content, Modifier.fillMaxWidth())
        is JsonArray -> {
            val cards = content.mapNotNull { it.asObject() }.filter { it.string("type") != null }
            if (cards.isNotEmpty()) {
                val stack = remember(content) { jsonOf("type" to "vertical-stack", "cards" to JsonArray(cards)) }
                ctx.withLayout(CardLayout.STACK).RenderCard(stack, Modifier.fillMaxWidth())
            }
        }
        else -> content.asString()?.let { Html(it, Modifier.fillMaxWidth()) } ?: Box(Modifier.fillMaxWidth())
    }
}
