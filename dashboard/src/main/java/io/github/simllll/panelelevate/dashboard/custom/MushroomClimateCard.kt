package io.github.simllll.panelelevate.dashboard.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.actions.haActions
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.CardRenderer
import io.github.simllll.panelelevate.dashboard.cards.GridOptions
import io.github.simllll.panelelevate.dashboard.cards.HaCard
import io.github.simllll.panelelevate.dashboard.cards.WarningCard
import io.github.simllll.panelelevate.dashboard.cards.entity
import io.github.simllll.panelelevate.dashboard.cards.now
import io.github.simllll.panelelevate.dashboard.format.StateFormatter
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.isActive
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.json.bool
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.roundToLong

/**
 * `custom:mushroom-climate-card` (Mushroom 5.x, default layout): a 36px shape icon colored by
 * HVAC mode with an `hvac_action` badge, name and "state ⸱ current temperature", and with
 * `show_temperature_control` a full-width `mushroom-input-number` (− target +, 42px, sent after
 * 2 s like Mushroom's debounce; low/high for range thermostats). Tap toggles, hold opens
 * more-info (Mushroom defaults). `hvac_modes` buttons and `collapsible_controls` are not
 * rendered.
 */
class MushroomClimateCard(raw: JsonObject) : CardRenderer {
    private val entityId = raw.string("entity")
    private val name = raw.string("name")
    private val icon = raw.string("icon")
    private val showTemperatureControl = raw.bool("show_temperature_control") == true
    private val config: JsonObject = JsonObject(
        jsonOf("tap_action" to jsonOf("action" to "toggle"), "hold_action" to jsonOf("action" to "more-info")) + raw
    )

    override fun gridOptions() = GridOptions(columns = 6, rows = if (showTemperatureControl) 2 else 1)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        if (entityId == null) {
            WarningCard("Entity not specified").Content(ctx, modifier)
            return
        }
        val entity = ctx.entity(entityId)
        val c = HaTheme.colors
        val t = HaTheme.typography
        HaCard(modifier) {
            Column(Modifier.fillMaxWidth().padding(SPACING), verticalArrangement = Arrangement.spacedBy(SPACING)) {
                val active = entity != null && entity.isActive()
                val color = modeColor(entity?.state)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).haActions(ctx, config, pressColor = color),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SPACING),
                ) {
                    Box {
                        Box(
                            Modifier.size(36.dp).background(if (active) color.copy(alpha = 0.2f) else DISABLED.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            MdiIcon(ctx.icons.icon(entity, icon), if (active) color else DISABLED, size = 24.dp)
                        }
                        ActionBadge(entity, active)
                    }
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            name ?: entity?.friendlyName ?: entityId,
                            style = t.style(t.sizeM, FontWeight.Medium, 20f / 14f, color = c.primaryText),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            entity?.let { secondary(it, ctx.formatter, ctx) } ?: "Entity not available: $entityId",
                            style = t.style(t.sizeS, FontWeight.Normal, 16f / 12f, color = c.secondaryText),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (entity != null && showTemperatureControl) TemperatureControl(ctx, entity, active)
            }
        }
    }

    @Composable
    private fun secondary(entity: EntityState, f: StateFormatter, ctx: CardContext): String {
        val state = f.formatState(entity, ctx.now())
        val current = entity.attrDouble("current_temperature") ?: return state
        return "$state ⸱ ${f.formatNumber(current)} $UNIT"
    }

    /** `mushroom-badge-icon`: 16px circle at the icon's top right with the `hvac_action` icon. */
    @Composable
    private fun ActionBadge(entity: EntityState?, active: Boolean) {
        if (entity == null || !active) return
        val action = entity.attrString("hvac_action")?.takeIf { it != "off" } ?: return
        val badgeIcon = ACTION_ICONS[action] ?: return
        Box(
            Modifier.offset(x = 23.dp, y = (-3).dp).size(16.dp).background(ACTION_COLORS[action] ?: DISABLED, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MdiIcon(badgeIcon, Color.White, size = 12.dp)
        }
    }

    @Composable
    private fun TemperatureControl(ctx: CardContext, entity: EntityState, active: Boolean) {
        val features = entity.supportedFeatures
        val target = entity.attrDouble("temperature")
        val low = entity.attrDouble("target_temp_low")
        val high = entity.attrDouble("target_temp_high")
        val step = entity.attrDouble("target_temp_step") ?: 0.5
        val min = entity.attrDouble("min_temp") ?: 7.0
        val max = entity.attrDouble("max_temp") ?: 35.0
        val single = features and FEATURE_TARGET_TEMPERATURE != 0 && target != null
        val range = !single && features and FEATURE_TARGET_TEMPERATURE_RANGE != 0 && low != null && high != null
        if (!single && !range) return
        val locale = ctx.formatter.locale
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (single) {
                InputNumber(target!!, step, min, max, active, null, locale, Modifier.weight(1f)) { v ->
                    setTemperature(ctx, entity.entityId, jsonOf("temperature" to v))
                }
            } else {
                InputNumber(low!!, step, min, max, active, modeColor("heat"), locale, Modifier.weight(1f)) { v ->
                    setTemperature(ctx, entity.entityId, jsonOf("target_temp_low" to v, "target_temp_high" to entity.attrDouble("target_temp_high")))
                }
                InputNumber(high!!, step, min, max, active, modeColor("cool"), locale, Modifier.weight(1f)) { v ->
                    setTemperature(ctx, entity.entityId, jsonOf("target_temp_low" to entity.attrDouble("target_temp_low"), "target_temp_high" to v))
                }
            }
        }
    }

    private fun setTemperature(ctx: CardContext, entityId: String, data: JsonObject) {
        ctx.actions.callService("climate", "set_temperature", JsonObject(data + jsonOf("entity_id" to entityId)), null)
    }

    /** `mushroom-input-number`: − value + on a 5% tinted 42px bar with 12px radius. */
    @Composable
    private fun InputNumber(
        value: Double,
        step: Double,
        min: Double,
        max: Double,
        enabled: Boolean,
        tint: Color?,
        locale: Locale,
        modifier: Modifier,
        send: (Double) -> Unit,
    ) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val scope = rememberCoroutineScope()
        var pending by remember { mutableStateOf<Double?>(null) }
        var debouncing by remember { mutableStateOf(false) }
        var job by remember { mutableStateOf<Job?>(null) }
        // A new state from HA after sending ends the pending local value.
        LaunchedEffect(value) { if (!debouncing) pending = null }
        val shown = pending ?: value
        val decimals = if (step >= 1.0) 0 else ceil(log10(1 / step)).toInt().coerceAtLeast(1)
        fun change(delta: Double) {
            val next = roundTo((shown + delta).coerceIn(min, max), decimals)
            if (next == shown) return
            pending = next
            debouncing = true
            job?.cancel()
            job = scope.launch {
                delay(DEBOUNCE_MS)
                debouncing = false
                send(next)
                delay(PENDING_TIMEOUT_MS) // no state change came back (e.g. same value)
                pending = null
            }
        }
        val textColor = if (!enabled) DISABLED else tint ?: c.primaryText
        val bg = if (!enabled) DISABLED.copy(alpha = 0.2f) else (tint ?: c.primaryText).copy(alpha = 0.05f)
        Row(
            modifier.height(CONTROL_HEIGHT).clip(RoundedCornerShape(12.dp)).background(bg).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton("mdi:minus", textColor, enabled) { change(-step) }
            BasicText(
                NumberFormat.getNumberInstance(locale).apply {
                    minimumFractionDigits = decimals
                    maximumFractionDigits = decimals
                }.format(shown),
                style = t.style(t.sizeM, FontWeight.Bold, color = textColor).copy(textAlign = TextAlign.Center),
                modifier = Modifier.weight(1f).alpha(if (pending != null) 0.5f else 1f),
            )
            ControlButton("mdi:plus", textColor, enabled) { change(step) }
        }
    }

    @Composable
    private fun ControlButton(icon: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            MdiIcon(icon, color, size = 21.dp)
        }
    }

    companion object {
        private val SPACING = 10.dp
        private val CONTROL_HEIGHT = 42.dp
        private const val DEBOUNCE_MS = 2_000L
        private const val PENDING_TIMEOUT_MS = 10_000L
        private const val UNIT = "°C"
        private const val FEATURE_TARGET_TEMPERATURE = 1
        private const val FEATURE_TARGET_TEMPERATURE_RANGE = 2

        // Mushroom's dark-mode defaults (--default-*).
        private val DISABLED = Color(111, 111, 111)
        private val DEEP_ORANGE = Color(255, 111, 34)
        private val BLUE = Color(33, 150, 243)
        private val GREEN = Color(76, 175, 80)
        private val ORANGE = Color(255, 152, 0)
        private val TEAL = Color(0, 150, 136)

        /** `--rgb-state-climate-<mode>`. */
        fun modeColor(mode: String?): Color = when (mode) {
            "heat" -> DEEP_ORANGE
            "cool" -> BLUE
            "auto", "heat_cool" -> GREEN
            "dry" -> ORANGE
            "fan_only" -> TEAL
            else -> DISABLED
        }

        private val ACTION_ICONS = mapOf(
            "cooling" to "mdi:snowflake", "drying" to "mdi:water-percent", "heating" to "mdi:fire", "idle" to "mdi:clock-outline",
        )
        private val ACTION_COLORS = mapOf(
            "cooling" to BLUE, "drying" to ORANGE, "heating" to DEEP_ORANGE, "idle" to DISABLED,
        )

        private fun roundTo(value: Double, decimals: Int): Double {
            var f = 1.0
            repeat(decimals) { f *= 10 }
            return (value * f).roundToLong() / f
        }
    }
}
