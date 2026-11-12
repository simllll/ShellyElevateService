package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.ActionTrigger
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.image.RemoteImage
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.double
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * `custom:refreshable-picture-card`: an image from `url` (or an entity's state/`attribute`),
 * reloaded every `refresh_interval` seconds (default 30) while the previous image stays visible.
 * The `<img>` has its own ha-card style border and radius; without `noMargin` it gets a 5 % margin.
 */
class RefreshablePictureCard(private val config: JsonObject) : CardRenderer {
    private val url = config.string("url")?.takeIf { it.isNotBlank() }
    private val entityId = config.string("entity")?.takeIf { it.isNotBlank() }
    private val attribute = config.string("attribute")?.takeIf { it.isNotBlank() }
    private val intervalMs = ((config.double("refresh_interval")?.takeIf { it > 0 } ?: 30.0) * 1000).toLong()
    private val noMargin = config.bool("noMargin") == true
    private val title = config.string("title")
    private val fallback = config.string("fallback_image")?.takeIf { it.isNotBlank() }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        if (url == null && entityId == null) {
            WarningCard("You need to define either a url or an entity").Content(ctx, modifier)
            return
        }
        val entity = ctx.entity(entityId)
        val source = url ?: entity?.let { e -> if (attribute != null) e.attrString(attribute) else e.state } ?: fallback
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(intervalMs) {
            while (true) {
                delay(intervalMs)
                refresh++
            }
        }
        val d = HaTheme.dimens
        val shape = RoundedCornerShape(d.radiusLg)
        val tap = ActionHandler.hasAction(config, ActionTrigger.TAP)
        HaCard(modifier.fillMaxWidth().then(if (tap) Modifier.haActions(ctx, config) else Modifier)) {
            Column(Modifier.fillMaxWidth()) {
                if (!title.isNullOrEmpty()) CardHeader(title)
                RemoteImage(
                    ctx.images,
                    source,
                    Modifier
                        .fillMaxWidth()
                        .then(if (noMargin) Modifier else Modifier.percentMargin(0.05f))
                        .clip(shape)
                        .border(d.cardBorderWidth, HaTheme.colors.divider, shape),
                    contentScale = ContentScale.FillWidth,
                    keepAspectRatio = true,
                    refreshKey = refresh.takeIf { it > 0 },
                )
            }
        }
    }
}

/** CSS `margin: 5%`: every side is a fraction of the containing block's width. */
private fun Modifier.percentMargin(fraction: Float): Modifier = layout { measurable, constraints ->
    val margin = (constraints.maxWidth * fraction).roundToInt()
    val inner = constraints.copy(
        minWidth = (constraints.minWidth - 2 * margin).coerceAtLeast(0),
        maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0),
        minHeight = (constraints.minHeight - 2 * margin).coerceAtLeast(0),
        maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - 2 * margin).coerceAtLeast(0) else constraints.maxHeight,
    )
    val placeable = measurable.measure(inner)
    layout(placeable.width + 2 * margin, placeable.height + 2 * margin) { placeable.place(margin, margin) }
}
