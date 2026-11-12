package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.isActive
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.image.RemoteImage
import com.stretter.shellyelevateservice.dashboard.json.array
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asInt
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.asStringList
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonObject

/**
 * `hui-tile-card` without features: 36px icon disc tinted with the state color (20 % background),
 * name (14px medium) and state (12px). `vertical` stacks them centered.
 * Tap defaults to more-info; `icon_tap_action` is honoured when configured.
 */
class TileCard(private val config: JsonObject) : CardRenderer {
    private val entityId = config.string("entity")
    private val vertical = config.bool("vertical") == true
    private val stateContent: List<String> = config["state_content"].asStringList()

    override fun gridOptions(): GridOptions {
        val featureRows = config.array("features")?.size ?: 0
        var rows = 1 + featureRows
        var minColumns = 6
        if (vertical) {
            rows++
            minColumns = 3
        }
        return GridOptions(columns = 6, rows = rows, minColumns = minColumns, minRows = rows)
    }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(entityId)
        val c = HaTheme.colors
        val t = HaTheme.typography
        val active = entity?.isActive() == true
        val tileColor = entity?.let { tileColor(it, config.string("color")) } ?: if (active) c.stateIcon else c.stateInactive
        val name = config.string("name") ?: entity?.friendlyName ?: entityId.orEmpty()
        val stateText = if (config.bool("hide_state") == true || entity == null) null else stateDisplay(ctx, entity)
        val iconAction = config.obj("icon_tap_action")
        HaCard(modifier.fillMaxSize()) {
            val content = Modifier
                .fillMaxSize()
                .haActions(ctx, config, pressColor = tileColor)
            val icon: @Composable () -> Unit = {
                TileIcon(
                    ctx, entity, config.string("icon"), tileColor,
                    if (config.bool("show_entity_picture") == true) entity?.entityPicture else null,
                    Modifier.then(
                        if (iconAction != null && iconAction.string("action") != "none") {
                            Modifier.haActions(ctx, jsonOf("tap_action" to iconAction, "entity" to entityId), pressColor = tileColor)
                        } else Modifier
                    ),
                )
            }
            if (vertical) {
                Column(
                    content.padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                ) {
                    icon()
                    TileInfo(name, stateText, centered = true)
                }
            } else {
                Row(
                    content.heightIn(min = 56.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    icon()
                    TileInfo(name, stateText, centered = false, Modifier.weight(1f))
                }
            }
        }
    }

    /** `state_content`: state, last_changed, last_updated or attribute names, joined by " · ". */
    @Composable
    private fun stateDisplay(ctx: CardContext, entity: EntityState): String {
        val f = ctx.formatter
        val now = ctx.now()
        if (stateContent.isEmpty()) return f.formatState(entity, now)
        return stateContent.mapNotNull { item ->
            when (item) {
                "state" -> f.formatState(entity, now)
                "last_changed", "last-changed" -> f.relativeTime(entity.lastChanged, now, capitalize = true)
                "last_updated", "last-updated" -> f.relativeTime(entity.lastUpdated, now, capitalize = true)
                else -> entity.attr(item)?.let { it.asString() ?: it.toString() }
            }
        }.joinToString(" · ")
    }

    companion object {
        /** `_computeStateColor` of the tile card. */
        fun tileColor(entity: EntityState, color: String?): Color? {
            val active = entity.isActive()
            if (color != null) return if (active) CssColors.computeCssColor(color) else null
            if (entity.domain == "person" || entity.domain == "device_tracker") return null
            if (entity.domain == "light") {
                val rgb = entity.attr("rgb_color").asArray()?.mapNotNull { it.asInt() }
                if (rgb != null && rgb.size == 3) return lightTileColor(rgb[0], rgb[1], rgb[2])
            }
            return CssColors.stateColor(entity) ?: if (active) CssColors.variables["state-icon-color"] else null
        }

        /** Light colors get a minimum saturation for contrast (very light colors become pale). */
        private fun lightTileColor(r: Int, g: Int, b: Int): Color {
            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            if (hsv[1] < 0.4f) {
                if (hsv[1] < 0.1f) hsv[2] = 225f / 255f else hsv[1] = 0.4f
            }
            return Color(android.graphics.Color.HSVToColor(hsv))
        }
    }
}

/** `ha-tile-icon`: 36px disc, background = tile color at 20 %, 24px icon (or picture). */
@Composable
fun TileIcon(ctx: CardContext, entity: EntityState?, icon: String?, color: Color, imageUrl: String? = null, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(if (imageUrl == null) Modifier.background(color.copy(alpha = 0.2f)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) RemoteImage(ctx.images, imageUrl, Modifier.fillMaxSize())
        else MdiIcon(ctx.icons.icon(entity, icon), color)
    }
}

/** `ha-tile-info`: primary 14px/500/1.6 (0.1px), secondary 12px/400/1.2 (0.4px). */
@Composable
fun TileInfo(primary: String, secondary: String?, centered: Boolean, modifier: Modifier = Modifier) {
    val t = HaTheme.typography
    val c = HaTheme.colors
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(modifier.then(if (centered) Modifier.fillMaxWidth() else Modifier), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        BasicText(
            primary,
            style = t.style(t.sizeM, FontWeight.Medium, t.lineHeightNormal, 0.1.sp, c.primaryText).copy(textAlign = align),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (secondary != null) {
            BasicText(
                secondary,
                style = t.style(t.sizeS, FontWeight.Normal, t.lineHeightCondensed, 0.4.sp, c.primaryText).copy(textAlign = align),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

