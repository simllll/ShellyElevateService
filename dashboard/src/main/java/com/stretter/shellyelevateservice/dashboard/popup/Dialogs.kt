package com.stretter.shellyelevateservice.dashboard.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.cards.CardLayout
import com.stretter.shellyelevateservice.dashboard.cards.RenderCard
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import com.stretter.shellyelevateservice.dashboard.ui.HaButton
import com.stretter.shellyelevateservice.dashboard.ui.HaIconButton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Dialog widths of `ha-wa-dialog` (`--ha-dialog-width-sm/md/lg`). */
enum class DialogSize(val width: Dp) { SMALL(320.dp), MEDIUM(580.dp), LARGE(1024.dp), FULLSCREEN(Dp.Infinity) }

/**
 * Standard HA dialog chrome (`ha-wa-dialog`): card-colored surface, 24px radius, header with a
 * close button and title, scrollable body. Taps inside do not reach the scrim.
 */
@Composable
fun PopupScope.HaDialog(
    title: String?,
    size: DialogSize = DialogSize.MEDIUM,
    showClose: Boolean = true,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HaTheme.colors
    val t = HaTheme.typography
    val shape = RoundedCornerShape(HaTheme.dimens.radius3xl)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val width = if (size == DialogSize.FULLSCREEN) maxWidth else minOf(size.width, maxWidth - 32.dp)
        Column(
            Modifier
                .width(width)
                .heightIn(max = maxHeight * 0.9f)
                .shadow(16.dp, shape)
                .clip(shape)
                .background(c.cardBackground)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            if (title != null || showClose) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showClose) HaIconButton("mdi:close", onClick = { dismiss() })
                    else Spacer(Modifier.width(12.dp))
                    BasicText(
                        title.orEmpty(),
                        style = t.style(t.sizeXl, FontWeight.Medium, t.lineHeightCondensed, color = c.primaryText),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = if (actions == null) 24.dp else 8.dp),
                content = content,
            )
            if (actions != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) { actions() }
            }
        }
    }
}

@Composable
internal fun PopupScope.ConfirmDialog(title: String?, text: String, onConfirm: () -> Unit) {
    val t = HaTheme.typography
    HaDialog(
        title = title ?: "Bestätigen",
        size = DialogSize.SMALL,
        showClose = false,
        actions = {
            HaButton("Abbrechen", onClick = { dismiss() }, outlined = false)
            HaButton("OK", onClick = { dismiss(); onConfirm() }, filled = true)
        },
    ) {
        BasicText(text, style = t.style(t.sizeM, color = HaTheme.colors.primaryText))
    }
}

/**
 * browser_mod popup: `content` is a card config, a list of card configs (vertical stack) or text
 * (rendered as markdown). `size`: normal / wide / fullscreen / classic.
 */
@Composable
internal fun PopupScope.BrowserModPopup(data: JsonObject) {
    val size = when (data.string("size")) {
        "wide" -> DialogSize.LARGE
        "fullscreen" -> DialogSize.FULLSCREEN
        else -> DialogSize.MEDIUM
    }
    val content = data["content"]
    val card: JsonObject? = remember(content) {
        when (content) {
            is JsonObject -> content
            is JsonArray -> jsonOf("type" to "vertical-stack", "cards" to content)
            else -> content.asString()?.let { jsonOf("type" to "markdown", "content" to it, "text_only" to true) }
        }
    }
    HaDialog(title = data.string("title"), size = size, showClose = data["dismissable"]?.toString() != "false") {
        if (card != null) ctx.withLayout(CardLayout.STACK).RenderCard(card, Modifier.fillMaxWidth())
        else Box(Modifier.fillMaxWidth())
        data["right_button"].asString()?.let { label ->
            Spacer(Modifier.padding(top = 8.dp))
            HaButton(label, onClick = {
                data["right_button_action"].asObject()?.let { ctx.actions.browserMod(it) }
                dismiss()
            }, filled = true)
        }
    }
}
