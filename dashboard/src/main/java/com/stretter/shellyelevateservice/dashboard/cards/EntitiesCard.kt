package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.hass.TOGGLE_DOMAINS
import com.stretter.shellyelevateservice.dashboard.hass.isActive
import com.stretter.shellyelevateservice.dashboard.hass.turnOnOffService
import com.stretter.shellyelevateservice.dashboard.json.array
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.json.with
import com.stretter.shellyelevateservice.dashboard.rows.RowRegistry
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import com.stretter.shellyelevateservice.dashboard.ui.HaSwitch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `hui-entities-card`: optional header (title, icon, header toggle) and rows with 8px gaps.
 * Rows default to `color: state` (the card's `color`/`state_color` overrides), like the frontend.
 */
class EntitiesCard(private val config: JsonObject) : CardRenderer {
    private val title = config.string("title")
    private val icon = config.string("icon")
    private val color: String = config.string("color") ?: when (config.bool("state_color")) {
        false -> "none"
        else -> "state"
    }
    private val rows: List<JsonObject> = config.array("entities").orEmpty().mapNotNull { entry ->
        val row = RowRegistry.normalize(entry) ?: return@mapNotNull null
        val type = row.string("type")
        // applyDefaultColor: custom rows own their config.
        if (row.containsKey("color") || row.containsKey("state_color") || type?.startsWith("custom:") == true) row
        else row.with("color", JsonPrimitive(color))
    }
    private val toggleEntities: List<String> = rows.mapNotNull { it.string("entity") }.filter { it.substringBefore('.') in TOGGLE_DOMAINS }
    private val showHeaderToggle: Boolean = when {
        config.containsKey("title") && config.string("title") != null && !config.containsKey("show_header_toggle") -> toggleEntities.size >= 2
        else -> config.bool("show_header_toggle") == true
    }

    override fun gridOptions() = GridOptions(columns = 12, minColumns = 3)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val hasHeader = title != null || showHeaderToggle || icon != null
        HaCard(modifier) {
            Column(Modifier.fillMaxWidth()) {
                if (hasHeader) {
                    CardHeader(
                        title.orEmpty(),
                        icon = icon,
                        trailing = if (showHeaderToggle && toggleEntities.isNotEmpty()) ({ HeaderToggle(ctx, toggleEntities) }) else null,
                    )
                }
                // `.card-content` after a header: padding-top 0, margin-top -8px.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (hasHeader) Modifier.offset(y = (-8).dp) else Modifier)
                        .padding(start = 16.dp, end = 16.dp, top = if (hasHeader) 0.dp else 16.dp, bottom = if (hasHeader) 8.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rows.forEach { row ->
                        key(row) {
                            val renderer = remember { ctx.rows.create(row) }
                            if (renderer.isVisible(ctx)) renderer.Content(ctx, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** `hui-entities-toggle`: on if any entity is on; toggling switches all. */
@Composable
private fun HeaderToggle(ctx: CardContext, entityIds: List<String>) {
    val states = entityIds.map { id -> key(id) { ctx.entity(id) } }
    val anyOn = states.any { it?.isActive() == true }
    HaSwitch(anyOn, { turnOn ->
        entityIds.forEach { id ->
            val (domain, service) = turnOnOffService(id, turnOn)
            ctx.actions.callService(domain, service, jsonOf("entity_id" to id), null)
        }
    }, Modifier.padding(start = HaTheme.dimens.space2))
}
