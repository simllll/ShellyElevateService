package io.github.simllll.panelelevate.dashboard.cards.bubble

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.CardRenderer
import io.github.simllll.panelelevate.dashboard.cards.entity
import io.github.simllll.panelelevate.dashboard.html.Css
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.json.bool
import io.github.simllll.panelelevate.dashboard.json.double
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.objects
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.lovelace.GridSection
import io.github.simllll.panelelevate.dashboard.popup.HashPopup
import io.github.simllll.panelelevate.dashboard.popup.PopupScope
import io.github.simllll.panelelevate.dashboard.theme.CssColors
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import io.github.simllll.panelelevate.dashboard.ui.PressIndication
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Bubble Card `pop-up`: takes no space in the view; while the location hash equals `hash` it
 * shows a bottom sheet with its `cards` laid out like a grid section (so `grid_options.columns`
 * place cards side by side, as in the web pop-up).
 *
 * Sheet geometry (Bubble's desktop rules, ≥ 768px): `width_desktop` wide (default 540px, `%` of
 * the screen), centered, from `56px + margin_top_desktop` down to the bottom edge, 42px top radius,
 * header with a 56px round close button (`show_header: false` shows only the drag handle).
 * Closes via the close button, back, a tap on the dimmed backdrop (`close_by_clicking_outside`),
 * any tap inside with `close_on_click`, `navigate` to another hash, or `trigger_close`.
 *
 * `trigger_entity` + `trigger_state` open the pop-up whenever the entity changes to that state
 * (also when it already is in that state when the view loads, like Bubble does).
 *
 * Content is composed only while open; template subscriptions are shared and kept for a few
 * seconds, so reopening is instant.
 */
class BubblePopUpCard(private val config: JsonObject) : CardRenderer {
    private val hash = config.string("hash")?.takeIf { it.length > 1 }
    private val section = jsonOf("cards" to JsonArray(config.objects("cards")))

    @Composable
    override fun isVisible(ctx: CardContext): Boolean = false

    @Composable
    override fun Effects(ctx: CardContext) {
        val hash = hash ?: return
        Trigger(ctx, hash)
        ctx.popups.HashPopup(hash, scrim = false) { Sheet() }
    }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) = Unit

    @Composable
    private fun Trigger(ctx: CardContext, hash: String) {
        val entityId = config.string("trigger_entity") ?: return
        val triggerState = config.string("trigger_state") ?: return
        val triggerClose = config.bool("trigger_close") ?: false
        val state = ctx.entity(entityId)?.state ?: return
        var handled by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(state) {
            if (state == handled) return@LaunchedEffect
            handled = state
            val current = ctx.popups.hash.value
            if (state == triggerState) {
                if (current != hash) ctx.popups.setHash(hash)
            } else if (triggerClose && current == hash) {
                ctx.popups.setHash(null)
            }
        }
    }

    @Composable
    private fun PopupScope.Sheet() {
        val closeOutside = config.bool("close_by_clicking_outside") ?: true
        val closeOnClick = config.bool("close_on_click") == true
        val opening = remember { Animatable(0f) }
        LaunchedEffect(Unit) { opening.animateTo(1f, tween(140, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenW = maxWidth.value
            val screenH = maxHeight.value
            val cssCtx = Css.Context(viewportWidth = screenW, viewportHeight = screenH, percentBase = screenW)
            val desktop = screenW >= 768f
            val width = if (screenW < 600f) screenW
            else (Css.length(config.string("width_desktop"), cssCtx) ?: 540f).coerceIn(200f, screenW)
            val marginTop = Css.length(config.string(if (desktop) "margin_top_desktop" else "margin_top_mobile"), cssCtx.copy(percentBase = screenH)) ?: 0f
            val top = (56f + marginTop).coerceIn(0f, (screenH - 120f).coerceAtLeast(0f))
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = opening.value }
                    .background(BubbleColors.backdrop)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (closeOutside) dismiss()
                    }
            )
            val shape = RoundedCornerShape(topStart = 42.dp, topEnd = 42.dp)
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = top.dp)
                    .width(width.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationY = (1f - opening.value) * 14.dp.toPx()
                        alpha = 0.84f + 0.16f * opening.value
                    }
                    .clip(shape)
                    .background(popUpBackground())
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .then(if (closeOnClick) Modifier.closeOnTap { dismiss() } else Modifier)
            ) {
                if (config.bool("show_header") != false) Header(onClose = { dismiss() })
                else DragHandle()
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 18.dp, end = 18.dp, bottom = 36.dp)
                ) {
                    GridSection(section, 1, ctx)
                }
            }
        }
    }

    /**
     * `bg_color` at `bg_opacity` % (default 88). Without backdrop blur on this Android version, a
     * pop-up with `bg_blur` is drawn opaque (the blur would hide the view behind it anyway).
     */
    @Composable
    private fun popUpBackground(): Color {
        val custom = CssColors.computeCssColor(config.string("bg_color")) ?: return BubbleColors.popUp
        val blurred = (config.double("bg_blur") ?: 0.0) > 0.0
        val opacity = if (blurred) 1f else ((config.double("bg_opacity") ?: 88.0) / 100.0).toFloat().coerceIn(0f, 1f)
        return custom.copy(alpha = custom.alpha * opacity)
    }

    /** Header: optional icon + name (a Bubble button without background) and the close button. */
    @Composable
    private fun PopupScope.Header(onClose: () -> Unit) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val name = config.string("name")?.takeIf { it.isNotBlank() && config.bool("show_name") != false }
        val icon = config.string("icon")?.takeIf { it.isNotBlank() && config.bool("show_icon") != false }
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (name != null || icon != null) {
                Row(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(BubbleColors.main),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        BubbleIconContainer(42.dp, Modifier.padding(start = 8.dp)) { MdiIcon(icon, c.primaryText) }
                    }
                    if (name != null) {
                        BasicText(
                            name,
                            style = t.style(13.sp, FontWeight.SemiBold, color = c.primaryText),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(BubbleColors.main)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = remember { PressIndication(c.primaryText) },
                        onClick = onClose,
                    ),
                contentAlignment = Alignment.Center,
            ) { MdiIcon("mdi:close", c.primaryText) }
        }
    }

    @Composable
    private fun DragHandle() {
        Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(40.dp, 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(HaTheme.colors.divider)
            )
        }
    }
}

/** `close_on_click`: any tap (not a scroll) inside the pop-up closes it after the tapped card acted. */
@Composable
private fun Modifier.closeOnTap(onClose: () -> Unit): Modifier {
    val slop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent(PointerEventPass.Initial)
                if (down.type != PointerEventType.Press) continue
                val start = down.changes.first().position
                var moved = false
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val p = e.changes.first().position
                    if ((p - start).getDistance() > slop) moved = true
                    if (e.type == PointerEventType.Release) break
                }
                if (!moved) scope.launch { delay(50); onClose() }
            }
        }
    }
}
